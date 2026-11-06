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

object PlayerRules {
    const val MAX_PLAYERS = 3

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

    /** A choice lasts until that player is gone, or until something else starts playing: then that is what the person is doing now. */
    fun keptPick(picked: String?, before: List<MediaPlayer>, now: List<MediaPlayer>): String? {
        if (picked == null || now.none { it.id == picked }) return null
        val started = now.any { player -> player.playing && player.id != picked && before.none { it.id == player.id && it.playing } }
        return picked.takeUnless { started }
    }
}
