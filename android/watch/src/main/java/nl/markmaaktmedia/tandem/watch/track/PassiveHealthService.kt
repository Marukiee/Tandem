package nl.markmaaktmedia.tandem.watch.track

import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer

/** What Health Services binds to when it has steps or a heart rate for this app. Everything is handled in [PassiveHealth]. */
class PassiveHealthService : PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) = PassiveHealth.onData(this, dataPoints)

    override fun onPermissionLost() = PassiveHealth.onPermissionLost(this)
}
