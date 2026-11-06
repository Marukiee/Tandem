package nl.markmaaktmedia.tandem.watch.protocol

const val PROTOCOL_VERSION = 1

/** Largest message after reassembly. A phone must trim its snapshot to stay under this. */
const val MAX_MESSAGE_BYTES = 64 * 1024

/*
 * Field names deliberately match Tandem's crates/tandem-core/src/proto.rs (NotificationMsg, CallMsg,
 * MediaPlayer), so the phone side can move into Tandem without a translation layer.
 */

/** One stretch of a sleep stage, in the order of the night. */
data class StageRun(val stage: Int, val seconds: Int) {
    companion object {
        const val WAKE = 0
        const val LIGHT = 1
        const val DEEP = 2
        const val REM = 3
    }
}

/** [id] is what comes back in [Message.NotificationAction]: a label is not stable and can repeat. */
data class NotifButton(val id: String, val title: String, val isReply: Boolean)

data class NotificationMsg(
    val key: String,
    val appId: String,
    val appName: String,
    val title: String,
    val text: String,
    val subText: String?,
    val ts: Long,
    val ongoing: Boolean,
    /** Show without buzzing: anything already alerted about, and everything a resync brings except the newest. */
    val silent: Boolean,
    val buttons: List<NotifButton>,
    /** A one-time code. Off by default: a watch can be read on someone else's wrist, so the phone masks codes. */
    val otp: String?,
    /** 0-100, or null when the notification has no progress bar. */
    val progress: Int?,
)

enum class CallStatus { RINGING, DIALING, ACTIVE, ENDED, MISSED }

/** Same set as Tandem's CallActionKind. Call audio stays on the phone: these only control the call. */
enum class CallActionKind { ANSWER, REJECT, SILENCE, HANGUP, REJECT_WITH_MESSAGE, MUTE_MIC }

data class CallMsg(
    val id: String,
    val state: CallStatus,
    val incoming: Boolean,
    val number: String?,
    val name: String?,
    val ts: Long,
    val muted: Boolean,
)

data class MediaPlayer(
    val id: String,
    val app: String,
    val title: String,
    val artist: String,
    val album: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** Which [Message.MediaArt] belongs to this player, empty without one. */
    val artKey: String = "",
)

enum class MediaCommandKind { PLAY_PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN }

/** A computer in the circle. [allowsInput] is that computer's own switch for playing back input from elsewhere. */
data class PointerTarget(val id: String, val name: String, val online: Boolean, val allowsInput: Boolean)

/** A device in the circle with its battery, for the overview on the watch. */
data class DeviceBattery(val id: String, val name: String, val level: Int, val charging: Boolean)

/**
 * Everything that travels over the encrypted link.
 *
 * State is synced rather than streamed: after each (re)connect the watch sends [SyncRequest] and the
 * phone answers with [Call], a [NotificationSnapshot] followed by each [Notification], and [MediaPlayers].
 * A lost connection therefore never loses a notification, and because a resync is many small messages,
 * a ringing phone can overtake it.
 *
 * Forward compatibility: fields are only ever appended, readers ignore trailing bytes, and an unknown
 * type decodes to [Unknown] so an older peer keeps working.
 */
sealed interface Message {
    /** Watch to phone: send me the complete current state. */
    data object SyncRequest : Message

    /** Either side: this pairing is over, forget the key. Sealed, so nobody else can break a pairing this way. */
    data object Unpair : Message

    data class Ping(val id: Long) : Message
    data class Pong(val id: Long) : Message

    /** Phone to watch: anything the watch shows that is not in [keys] is gone from the phone. */
    data class NotificationSnapshot(val keys: List<String>) : Message
    data class Notification(val notification: NotificationMsg) : Message
    data class NotificationRemoved(val key: String) : Message

    /**
     * Watch to phone. [button] is the id of the pressed button. An empty [button] means "open" or,
     * with [dismiss] set, "dismiss". Same meaning as Tandem's NotificationAction.
     */
    data class NotificationAction(val key: String, val button: String, val reply: String?, val dismiss: Boolean) : Message

    /** Phone to watch: the icon of an app, ahead of its [Notification]. The watch keeps it, so it only crosses the link once per connection. */
    data class AppIcon(val appId: String, val image: ByteArray) : Message {
        override fun equals(other: Any?) = other is AppIcon && appId == other.appId && image.contentEquals(other.image)

        override fun hashCode() = 31 * appId.hashCode() + image.contentHashCode()

        companion object {
            const val MAX_BYTES = 6 * 1024
        }
    }

    /** Phone to watch. A null [call] means no call is in progress. */
    data class Call(val call: CallMsg?) : Message
    data class CallAction(val id: String, val kind: CallActionKind) : Message

    data class MediaPlayers(val players: List<MediaPlayer>) : Message
    data class MediaCommand(val playerId: String, val kind: MediaCommandKind) : Message

    /**
     * Phone to watch: a cover, as a small image. Only when it changes, because over Bluetooth this takes a second or two.
     * [playerId] says whose it is, so that a player's newer cover replaces only that player's in the queue.
     */
    data class MediaArt(val key: String, val image: ByteArray, val playerId: String = "") : Message {
        override fun equals(other: Any?) = other is MediaArt && key == other.key && playerId == other.playerId && image.contentEquals(other.image)

        override fun hashCode() = 31 * (31 * key.hashCode() + playerId.hashCode()) + image.contentHashCode()

        companion object {
            const val MAX_BYTES = 6 * 1024
        }
    }

    /**
     * Phone to watch: the computers this watch may steer. [enabled] is the switch on the phone, off until the
     * user turns it on: steering a computer is more than a watch gets by being paired.
     */
    data class PointerTargets(val enabled: Boolean, val targets: List<PointerTarget>) : Message

    /** Watch to phone: steer this computer from now on, or nothing when null. */
    data class PointerSession(val targetId: String?) : Message
    data class PointerMove(val dx: Int, val dy: Int) : Message
    data class PointerScroll(val dx: Int, val dy: Int) : Message
    data class PointerClick(val button: Int, val count: Int) : Message

    /** A key pressed and let go on the steered computer, as the key codes Tandem's input already uses. */
    data class PointerKey(val code: Int, val mods: Int) : Message

    /** Either side: make the other device ring so it can be found, or stop. A command; [Ringing] is the report. */
    data class FindDevice(val ring: Boolean) : Message

    /** Either side: this device started or stopped ringing, also when someone stopped it by hand. */
    data class Ringing(val on: Boolean) : Message

    /** Phone to watch: the batteries of the devices in the circle, this phone included. */
    data class DeviceBatteries(val devices: List<DeviceBattery>) : Message

    /** Watch to phone: so Tandem can show this watch next to the other devices. */
    data class WatchStatus(val level: Int, val charging: Boolean, val stepsToday: Long = 0, val activeMinutesToday: Int = 0) : Message

    /** Watch to phone: the switch for steering a computer was changed on the wrist. */
    data class MouseEnabled(val on: Boolean) : Message

    /** Phone to watch: the next alarm on the phone, 0 when there is none or when the person does not want waking early. */
    data class SmartWakeAlarm(val atMs: Long) : Message

    /** Phone to watch: how many steps a day the person aims for. */
    data class StepGoal(val steps: Int) : Message

    /**
     * Watch to phone: the wearer sleeps or not. Sent again after each reconnect, so the receiver must treat it as the
     * state it is and not as a command to change something.
     */
    data class SleepMode(val on: Boolean) : Message

    /** Watch to phone: a finished night or workout. Sent again after each reconnect until [HealthAck] comes back. */
    data class HealthRecord(
        val id: String,
        val workout: Boolean,
        val startMs: Long,
        val endMs: Long,
        val sleepMinutes: Int,
        val awakeMinutes: Int,
        val awakenings: Int,
        val efficiencyPercent: Int,
        /** Null when the heart rate was missing too much to tell the stages apart. */
        val lightMinutes: Int?,
        val deepMinutes: Int?,
        val remMinutes: Int?,
        val stages: List<StageRun>,
        val heartRateMean: Int?,
        val heartRateLow: Int?,
        val heartRateHigh: Int?,
        val steps: Long,
        /** A whole day of steps rather than a night or a workout. */
        val day: Boolean = false,
        val activeMinutes: Int = 0,
    ) : Message

    /** Phone to watch: this record is safe on the phone, the watch can let go of it. */
    data class HealthAck(val id: String) : Message

    /** A message from a newer peer. Ignored, never an error. Types up to 0x7F are reserved for pointer input. */
    data class Unknown(val type: Int, val payload: ByteArray) : Message {
        override fun equals(other: Any?) = other is Unknown && type == other.type && payload.contentEquals(other.payload)
        override fun hashCode() = 31 * type + payload.contentHashCode()
    }
}

object MessageCodec {
    private const val SYNC_REQUEST = 0x01
    private const val UNPAIR = 0x04
    private const val PING = 0x02
    private const val PONG = 0x03
    private const val NOTIF_SNAPSHOT = 0x10
    private const val NOTIF = 0x11
    private const val NOTIF_REMOVED = 0x12
    private const val NOTIF_ACTION = 0x13
    private const val APP_ICON = 0x14
    private const val CALL = 0x20
    private const val CALL_ACTION = 0x21
    private const val MEDIA_PLAYERS = 0x30
    private const val MEDIA_COMMAND = 0x31
    private const val MEDIA_ART = 0x32
    private const val FIND_DEVICE = 0x40
    private const val DEVICE_BATTERIES = 0x41
    private const val WATCH_STATUS = 0x42
    private const val SLEEP_MODE = 0x43
    private const val RINGING = 0x44
    private const val STEP_GOAL = 0x45
    private const val SMART_WAKE_ALARM = 0x46
    private const val HEALTH_RECORD = 0x50
    private const val HEALTH_ACK = 0x51
    private const val POINTER_TARGETS = 0x70
    private const val POINTER_SESSION = 0x71
    private const val POINTER_MOVE = 0x72
    private const val POINTER_SCROLL = 0x73
    private const val POINTER_CLICK = 0x74
    private const val POINTER_KEY = 0x75
    private const val MOUSE_ENABLED = 0x76

    fun encode(message: Message): ByteArray {
        val w = ByteWriter()
        when (message) {
            Message.SyncRequest -> w.u8(SYNC_REQUEST)
            Message.Unpair -> w.u8(UNPAIR)
            is Message.Ping -> w.u8(PING).varint(message.id)
            is Message.Pong -> w.u8(PONG).varint(message.id)
            is Message.NotificationSnapshot -> {
                w.u8(NOTIF_SNAPSHOT).varint(message.keys.size.toLong())
                message.keys.forEach { w.str(it) }
            }
            is Message.Notification -> w.u8(NOTIF).bytes(encodeNotification(message.notification))
            is Message.NotificationRemoved -> w.u8(NOTIF_REMOVED).str(message.key)
            is Message.NotificationAction -> w.u8(NOTIF_ACTION)
                .str(message.key)
                .str(message.button)
                .optStr(message.reply)
                .bool(message.dismiss)
            is Message.AppIcon -> w.u8(APP_ICON).str(message.appId).bytes(message.image)
            is Message.Call -> {
                w.u8(CALL).bool(message.call != null)
                message.call?.let { w.bytes(encodeCall(it)) }
            }
            is Message.CallAction -> w.u8(CALL_ACTION).str(message.id).u8(message.kind.ordinal)
            is Message.MediaPlayers -> {
                w.u8(MEDIA_PLAYERS).varint(message.players.size.toLong())
                message.players.forEach { w.bytes(encodePlayer(it)) }
            }
            is Message.MediaCommand -> w.u8(MEDIA_COMMAND).str(message.playerId).u8(message.kind.ordinal)
            is Message.MediaArt -> w.u8(MEDIA_ART).str(message.key).bytes(message.image).str(message.playerId)
            is Message.PointerTargets -> {
                w.u8(POINTER_TARGETS).bool(message.enabled).varint(message.targets.size.toLong())
                message.targets.forEach { w.bytes(encodeTarget(it)) }
            }
            is Message.PointerSession -> w.u8(POINTER_SESSION).optStr(message.targetId)
            is Message.PointerMove -> w.u8(POINTER_MOVE).svarint(message.dx).svarint(message.dy)
            is Message.PointerScroll -> w.u8(POINTER_SCROLL).svarint(message.dx).svarint(message.dy)
            is Message.PointerClick -> w.u8(POINTER_CLICK).u8(message.button).u8(message.count)
            is Message.PointerKey -> w.u8(POINTER_KEY).varint(message.code.toLong()).u8(message.mods)
            is Message.FindDevice -> w.u8(FIND_DEVICE).bool(message.ring)
            is Message.Ringing -> w.u8(RINGING).bool(message.on)
            is Message.DeviceBatteries -> {
                w.u8(DEVICE_BATTERIES).varint(message.devices.size.toLong())
                message.devices.forEach { w.bytes(ByteWriter().str(it.id).str(it.name).u8(it.level).bool(it.charging).toByteArray()) }
            }
            is Message.WatchStatus -> w.u8(WATCH_STATUS).u8(message.level).bool(message.charging).varint(message.stepsToday).varint(message.activeMinutesToday.toLong())
            is Message.StepGoal -> w.u8(STEP_GOAL).varint(message.steps.toLong())
            is Message.MouseEnabled -> w.u8(MOUSE_ENABLED).bool(message.on)
            is Message.SmartWakeAlarm -> w.u8(SMART_WAKE_ALARM).varint(message.atMs)
            is Message.SleepMode -> w.u8(SLEEP_MODE).bool(message.on)
            is Message.HealthRecord -> {
                w.u8(HEALTH_RECORD).str(message.id).bool(message.workout).varint(message.startMs).varint(message.endMs)
                    .varint(message.sleepMinutes.toLong()).varint(message.awakeMinutes.toLong()).varint(message.awakenings.toLong())
                    .varint(message.efficiencyPercent.toLong())
                    .optional(message.lightMinutes).optional(message.deepMinutes).optional(message.remMinutes)
                    .varint(message.stages.size.toLong())
                message.stages.forEach { w.u8(it.stage).varint(it.seconds.toLong()) }
                w.optional(message.heartRateMean).optional(message.heartRateLow).optional(message.heartRateHigh).varint(message.steps)
                    .bool(message.day).varint(message.activeMinutes.toLong())
            }
            is Message.HealthAck -> w.u8(HEALTH_ACK).str(message.id)
            is Message.Unknown -> w.u8(message.type).raw(message.payload)
        }
        return w.toByteArray()
    }

    fun decode(data: ByteArray): Message {
        val r = ByteReader(data)
        return when (val type = r.u8()) {
            SYNC_REQUEST -> Message.SyncRequest
            UNPAIR -> Message.Unpair
            PING -> Message.Ping(r.varint())
            PONG -> Message.Pong(r.varint())
            NOTIF_SNAPSHOT -> Message.NotificationSnapshot(r.list { it.str() })
            NOTIF -> Message.Notification(decodeNotification(r.bytes()))
            NOTIF_REMOVED -> Message.NotificationRemoved(r.str())
            NOTIF_ACTION -> Message.NotificationAction(
                key = r.str(),
                button = r.str(),
                reply = r.optStr(),
                dismiss = r.bool(),
            )
            APP_ICON -> Message.AppIcon(r.str(), r.bytes())
            CALL -> Message.Call(if (r.bool()) decodeCall(r.bytes()) else null)
            CALL_ACTION -> Message.CallAction(r.str(), enumAt<CallActionKind>(r.u8()))
            MEDIA_PLAYERS -> Message.MediaPlayers(r.list { decodePlayer(it.bytes()) })
            MEDIA_COMMAND -> Message.MediaCommand(r.str(), enumAt<MediaCommandKind>(r.u8()))
            // The player was added later: a phone that has not been updated yet sends nothing here.
            MEDIA_ART -> Message.MediaArt(r.str(), r.bytes(), if (r.remaining > 0) r.str() else "")
            POINTER_TARGETS -> Message.PointerTargets(r.bool(), r.list { decodeTarget(it.bytes()) })
            POINTER_SESSION -> Message.PointerSession(r.optStr())
            POINTER_MOVE -> Message.PointerMove(r.svarint(), r.svarint())
            POINTER_SCROLL -> Message.PointerScroll(r.svarint(), r.svarint())
            POINTER_CLICK -> Message.PointerClick(r.u8(), r.u8())
            POINTER_KEY -> Message.PointerKey(r.varint().toInt(), r.u8())
            FIND_DEVICE -> Message.FindDevice(r.bool())
            RINGING -> Message.Ringing(r.bool())
            DEVICE_BATTERIES -> Message.DeviceBatteries(
                r.list {
                    val d = ByteReader(it.bytes())
                    DeviceBattery(d.str(), d.str(), d.u8(), d.bool())
                },
            )
            WATCH_STATUS -> Message.WatchStatus(r.u8(), r.bool(), if (r.remaining > 0) r.varint() else 0, if (r.remaining > 0) r.varint().toInt() else 0)
            STEP_GOAL -> Message.StepGoal(r.varint().toInt())
            MOUSE_ENABLED -> Message.MouseEnabled(r.bool())
            SMART_WAKE_ALARM -> Message.SmartWakeAlarm(r.varint())
            SLEEP_MODE -> Message.SleepMode(r.bool())
            HEALTH_RECORD -> Message.HealthRecord(
                id = r.str(),
                workout = r.bool(),
                startMs = r.varint(),
                endMs = r.varint(),
                sleepMinutes = r.varint().toInt(),
                awakeMinutes = r.varint().toInt(),
                awakenings = r.varint().toInt(),
                efficiencyPercent = r.varint().toInt(),
                lightMinutes = r.optional(),
                deepMinutes = r.optional(),
                remMinutes = r.optional(),
                stages = List(r.varint().toInt()) { StageRun(r.u8(), r.varint().toInt()) },
                heartRateMean = r.optional(),
                heartRateLow = r.optional(),
                heartRateHigh = r.optional(),
                steps = r.varint(),
                day = r.remaining > 0 && r.bool(),
                activeMinutes = if (r.remaining > 0) r.varint().toInt() else 0,
            )
            HEALTH_ACK -> Message.HealthAck(r.str())
            else -> Message.Unknown(type, r.rest())
        }
    }

    private fun ByteWriter.optional(value: Int?) = varint(if (value == null) 0 else value + 1L)

    private fun ByteReader.optional(): Int? = varint().toInt().let { if (it == 0) null else it - 1 }

    private fun encodeTarget(t: PointerTarget): ByteArray =
        ByteWriter().str(t.id).str(t.name).bool(t.online).bool(t.allowsInput).toByteArray()

    private fun decodeTarget(data: ByteArray): PointerTarget {
        val r = ByteReader(data)
        return PointerTarget(id = r.str(), name = r.str(), online = r.bool(), allowsInput = r.bool())
    }

    private fun encodeNotification(n: NotificationMsg): ByteArray {
        val w = ByteWriter()
            .str(n.key)
            .str(n.appId)
            .str(n.appName)
            .str(n.title)
            .str(n.text)
            .optStr(n.subText)
            .varint(n.ts)
            .bool(n.ongoing)
            .bool(n.silent)
            .varint(n.buttons.size.toLong())
        // Length-prefixed so a newer peer can append fields to a button without breaking the list.
        n.buttons.forEach { b -> w.bytes(ByteWriter().str(b.id).str(b.title).bool(b.isReply).toByteArray()) }
        return w.optStr(n.otp).bool(n.progress != null).varint((n.progress ?: 0).toLong()).toByteArray()
    }

    private fun decodeNotification(data: ByteArray): NotificationMsg {
        val r = ByteReader(data)
        return NotificationMsg(
            key = r.str(),
            appId = r.str(),
            appName = r.str(),
            title = r.str(),
            text = r.str(),
            subText = r.optStr(),
            ts = r.varint(),
            ongoing = r.bool(),
            silent = r.bool(),
            buttons = r.list { outer ->
                val b = ByteReader(outer.bytes())
                NotifButton(b.str(), b.str(), b.bool())
            },
            otp = r.optStr(),
            progress = run {
                val present = r.bool()
                val value = r.varint().toInt()
                value.takeIf { present }
            },
        )
    }

    private fun encodeCall(c: CallMsg): ByteArray = ByteWriter()
        .str(c.id)
        .u8(c.state.ordinal)
        .bool(c.incoming)
        .optStr(c.number)
        .optStr(c.name)
        .varint(c.ts)
        .bool(c.muted)
        .toByteArray()

    private fun decodeCall(data: ByteArray): CallMsg {
        val r = ByteReader(data)
        return CallMsg(
            id = r.str(),
            state = enumAt<CallStatus>(r.u8()),
            incoming = r.bool(),
            number = r.optStr(),
            name = r.optStr(),
            ts = r.varint(),
            muted = r.bool(),
        )
    }

    private fun encodePlayer(p: MediaPlayer): ByteArray = ByteWriter()
        .str(p.id)
        .str(p.app)
        .str(p.title)
        .str(p.artist)
        .str(p.album)
        .bool(p.playing)
        .varint(p.positionMs)
        .varint(p.durationMs)
        .str(p.artKey)
        .toByteArray()

    private fun decodePlayer(data: ByteArray): MediaPlayer {
        val r = ByteReader(data)
        return MediaPlayer(
            id = r.str(),
            app = r.str(),
            title = r.str(),
            artist = r.str(),
            album = r.str(),
            playing = r.bool(),
            positionMs = r.varint(),
            durationMs = r.varint(),
            // Added later: a phone that has not been updated yet sends nothing here.
            artKey = if (r.remaining > 0) r.str() else "",
        )
    }

    private fun ByteWriter.optStr(value: String?) = apply {
        bool(value != null)
        str(value.orEmpty())
    }

    private fun ByteReader.optStr(): String? {
        val present = bool()
        val value = str()
        return value.takeIf { present }
    }

    private inline fun <T> ByteReader.list(read: (ByteReader) -> T): List<T> {
        val count = varint()
        // Every entry takes at least one byte, so this bound stops a forged count from allocating memory.
        if (count > remaining) throw ProtocolException("list count too large")
        return List(count.toInt()) { read(this) }
    }

    private inline fun <reified E : Enum<E>> enumAt(ordinal: Int): E =
        enumValues<E>().getOrNull(ordinal) ?: throw ProtocolException("unknown ${E::class.simpleName} $ordinal")
}
