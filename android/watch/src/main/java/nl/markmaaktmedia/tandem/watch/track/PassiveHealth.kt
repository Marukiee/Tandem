package nl.markmaaktmedia.tandem.watch.track

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.clearPassiveListenerService
import androidx.health.services.client.data.DataPointAccuracy
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.HeartRateAccuracy
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.setPassiveListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nl.markmaaktmedia.tandem.watch.sleep.PassiveHeart
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Steps and heart rate of the day from Health Services: the watch measures them for everyone, and this app is told. Registered with the
 * system, which starts [PassiveHealthService] when there is something, so nothing here runs or wakes anything by itself.
 *
 * What is not there (no Health Services on this watch, no permission, a type it does not hand over) is simply not registered, and
 * what is registered but stays silent is caught by [HeartTracker] (our own measurement when nothing good came lately) and
 * [StepTracker] (the step counter, when it moves while Health Services says nothing for hours).
 */
object PassiveHealth {
    private const val TAG = "TandemWatchPassive"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var registeredAtMs = 0L

    /** A restart of Health Services (an update of it, or a crash) can take the registration with it, and then nothing says so. */
    private const val REREGISTER_MS = 30 * 60_000L

    fun start(context: Context) = refresh(context)

    /** Again when a permission was given or all-day heart rate was switched: registering replaces what was registered. */
    @Synchronized
    fun refresh(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        registeredAtMs = SystemClock.elapsedRealtime()
        job = scope.launch { register(app) }
    }

    /** For whoever notices that nothing comes from the system: registers again, but not more than once in half an hour. */
    @Synchronized
    fun reregisterIfDue(context: Context) {
        if (SystemClock.elapsedRealtime() - registeredAtMs >= REREGISTER_MS) {
            Log.i(TAG, "Nothing came from the system for a while: registering again")
            refresh(context)
        }
    }

    private suspend fun register(app: Context) {
        val steps = StepTracker.shared(app)
        val client = attempt("Health Services") { HealthServices.getClient(app).passiveMonitoringClient }
        val capabilities = client?.let { attempt("Capabilities") { it.getCapabilities() } }
        if (client == null || capabilities == null) {
            steps.healthServices(false)
            return
        }
        val supported = capabilities.supportedDataTypesPassiveMonitoring
        // The sleep states are only looked at here: whether the system can tell a night is the thing to know before the movement of the
        // wrist could ever be dropped for it.
        Log.i(TAG, "Passive: ${supported.map { it.name }}, sleep states: ${capabilities.supportedUserActivityStates.map { it.name }}")
        val wantSteps = DataType.STEPS_DAILY in supported && has(app, Manifest.permission.ACTIVITY_RECOGNITION)
        val wantHeart = DataType.HEART_RATE_BPM in supported && TrackingStore(app).heartAllDay && has(app, HEART_PERMISSION)
        Log.i(
            TAG,
            "Wanted: steps $wantSteps, heart $wantHeart. Allowed: activity ${has(app, Manifest.permission.ACTIVITY_RECOGNITION)}, " +
                "heart ${has(app, HEART_PERMISSION)}, heart in the background ${has(app, BACKGROUND_PERMISSION)}",
        )
        val types = buildSet<DataType<*, *>> {
            if (wantSteps) add(DataType.STEPS_DAILY)
            if (wantHeart) add(DataType.HEART_RATE_BPM)
        }
        if (types.isEmpty()) {
            attempt("Clearing") {
                client.clearPassiveListenerService()
                true
            }
            steps.healthServices(false)
            return
        }
        val config = PassiveListenerConfig.builder().setDataTypes(types).build()
        // The call has nothing to return, so what comes back is whether it went through.
        val registered = attempt("Registering") {
            client.setPassiveListenerService(PassiveHealthService::class.java, config)
            true
        } == true
        if (registered) Log.i(TAG, "Registered: ${types.map { it.name }}")
        steps.healthServices(registered && wantSteps)
    }

    /** What Health Services handed over, from [PassiveHealthService]. This may be the call that started the process. */
    fun onData(context: Context, container: DataPointContainer) {
        val app = context.applicationContext
        HeartTracker.start(app)
        val bootInstant = Instant.now().minusMillis(SystemClock.elapsedRealtime())
        val zone = ZoneId.systemDefault()
        val steps = container.getData(DataType.STEPS_DAILY)
        if (steps.isNotEmpty()) {
            Log.i(TAG, "Steps: ${steps.size} totals, first from ${steps.first().getStartInstant(bootInstant)} to ${steps.first().getEndInstant(bootInstant)}: ${steps.first().value}")
            StepTracker.shared(app).onDaily(
                steps.map { point ->
                    val end = point.getEndInstant(bootInstant).atZone(zone)
                    StepTracker.DailyTotal(point.value, end.toLocalDate().toString(), end.hour, point.endDurationFromBoot.toMillis())
                },
            )
        }
        val hearts = container.getData(DataType.HEART_RATE_BPM)
        if (hearts.isNotEmpty()) {
            // What the sensor says about itself, so a watch that never gets a value past the filter shows why in the log.
            Log.i(TAG, "Heart: ${hearts.size} samples, by status: " + hearts.groupingBy { (it.accuracy as? HeartRateAccuracy)?.sensorStatus?.name ?: "none" }.eachCount())
            HeartTracker.onPassive(
                hearts.map { PassiveHeart(it.getTimeInstant(bootInstant).toEpochMilli(), it.value.roundToInt(), reliable(it.accuracy)) },
                System.currentTimeMillis(),
            )
        }
    }

    fun onPermissionLost(context: Context) {
        Log.i(TAG, "A permission was taken back: the step counter and our own measurement count again")
        StepTracker.shared(context.applicationContext).healthServices(false)
    }

    /** What our own measurement also lets through (a sensor accuracy of low or better): no skin, or no idea, does not count. */
    private fun reliable(accuracy: DataPointAccuracy?): Boolean {
        val status = (accuracy as? HeartRateAccuracy)?.sensorStatus
        return status == HeartRateAccuracy.SensorStatus.ACCURACY_LOW ||
            status == HeartRateAccuracy.SensorStatus.ACCURACY_MEDIUM ||
            status == HeartRateAccuracy.SensorStatus.ACCURACY_HIGH
    }

    private fun has(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Null when it could not be done, with the reason in the log. Cancelling is not a failure. */
    private inline fun <T> attempt(what: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i(TAG, "$what: ${e.message}")
        null
    }

    private val HEART_PERMISSION =
        if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS

    /** From Android 16 on the heart rate of the day needs this one as well; before it, the body sensor one for the background. */
    private val BACKGROUND_PERMISSION =
        if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" else "android.permission.BODY_SENSORS_BACKGROUND"
}
