package nl.markmaaktmedia.tandem.watch.protocol

/** What the order of the players needs to know of one media session on the phone. */
data class SessionView(
    val id: String,
    /** Title and artist, empty when it has neither: two sessions with one song are a player and the speaker it plays on. */
    val song: String,
    val playing: Boolean,
    /** False for a session with nothing to offer: stopped, in error, or without a song, like an app that only keeps one open. */
    val usable: Boolean = true,
    /** When it last started or stopped playing, in a clock that only goes up. 0 when it was never seen doing either. */
    val lastPlayingAt: Long = 0,
)

/** What a press on play or pause should lead to, shown until the phone has caught up. */
data class ExpectedPlay(val playerId: String, val playing: Boolean, val positionMs: Long, val untilMs: Long)

object PlayerRules {
    const val MAX_PLAYERS = 3

    private const val COMPUTER = "computer:"

    /** A player on a computer has an id that says where it is, so a press knows where to go. The watch itself sees no difference. */
    fun computerPlayerId(device: String, player: String): String = "$COMPUTER$device:$player"

    /** The device and the player of an id made by [computerPlayerId], or null for a player on the phone. */
    fun computerPlayer(id: String): Pair<String, String>? {
        if (!id.startsWith(COMPUTER)) return null
        val rest = id.removePrefix(COMPUTER)
        val split = rest.indexOf(':')
        return if (split <= 0) null else rest.take(split) to rest.drop(split + 1)
    }

    /** What plays comes first wherever it plays. Among the rest the phone goes first: that is the one on the wrist of its owner. */
    fun withComputers(phone: List<MediaPlayer>, computers: List<MediaPlayer>): List<MediaPlayer> =
        (phone + computers).sortedByDescending { it.playing }.take(MAX_PLAYERS)

    /** Long enough for an app that answers slowly, short enough that a press that got lost does not keep showing the wrong thing. */
    const val EXPECT_MS = 4_000L

    /**
     * The sessions to show, the one in front first: what plays, then what played last. So a player that gets paused stays where it
     * is, and whatever the system lists next does not take over. On a tie the system's own order counts. A session with nothing to
     * offer that was never seen playing is a leftover from earlier and stays out; one that was stays, because it was stopped from here.
     */
    fun order(sessions: List<SessionView>): List<String> = sessions.withIndex()
        .filter { (_, session) -> session.playing || session.usable || session.lastPlayingAt > 0 }
        .sortedWith(
            compareByDescending<IndexedValue<SessionView>> { it.value.playing }
                .thenByDescending { it.value.lastPlayingAt }
                .thenBy { it.index },
        )
        .distinctBy { (index, session) -> session.song.ifBlank { "#$index" } }
        .map { it.value.id }
        .distinct()
        .take(MAX_PLAYERS)

    /** The player on the screen: the one chosen on the watch for as long as it is there, else the one in front. */
    fun shown(players: List<MediaPlayer>, picked: String?): MediaPlayer? =
        players.firstOrNull { it.id == picked } ?: players.firstOrNull()

    /**
     * The answer of the phone takes a moment over Bluetooth, and a button that waits for it feels broken, so a press counts at once.
     * [sinceShownMs] is how long the players have been on the screen, which is how far a playing one has come.
     */
    fun pressed(players: List<MediaPlayer>, id: String, sinceShownMs: Long, nowMs: Long): ExpectedPlay? {
        val player = players.firstOrNull { it.id == id } ?: return null
        val position = player.positionMs + if (player.playing) sinceShownMs else 0
        return ExpectedPlay(id, !player.playing, if (player.durationMs > 0) minOf(position, player.durationMs) else position, nowMs + EXPECT_MS)
    }

    fun withExpected(players: List<MediaPlayer>, expected: ExpectedPlay?): List<MediaPlayer> = players.map { player ->
        if (player.id == expected?.playerId) player.copy(playing = expected.playing, positionMs = expected.positionMs) else player
    }

    /** An update sent just before the press still says the old thing, and that must not flip the button back. */
    fun keptExpected(expected: ExpectedPlay?, fromPhone: List<MediaPlayer>, nowMs: Long): ExpectedPlay? {
        if (expected == null || nowMs >= expected.untilMs) return null
        val player = fromPhone.firstOrNull { it.id == expected.playerId } ?: return null
        return expected.takeIf { player.playing != expected.playing }
    }

    /** A choice lasts until that player is gone, or until something else starts playing: then that is what the person is doing now. */
    fun keptPick(picked: String?, before: List<MediaPlayer>, now: List<MediaPlayer>): String? {
        if (picked == null || now.none { it.id == picked }) return null
        val started = now.any { player -> player.playing && player.id != picked && before.none { it.id == player.id && it.playing } }
        return picked.takeUnless { started }
    }
}
