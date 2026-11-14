package nl.markmaaktmedia.tandem.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * The question before sending files to a device that has less free space than they take (P5): cancel, or send anyway.
 * A screen that can show it registers with [attach]. With none on the screen nothing is asked and the files go, because the other
 * side refuses what does not fit by itself.
 */
class SpaceAsker(private val scope: CoroutineScope) {
    class Question(val devices: List<String>, internal val answer: CompletableDeferred<Boolean>)

    private val _pending = MutableStateFlow<Question?>(null)
    val pending: StateFlow<Question?> = _pending

    private val viewers = AtomicInteger(0)
    private val one = Mutex()

    fun attach() {
        viewers.incrementAndGet()
    }

    /** A turn of the phone makes the screen again: a question is only dropped when no screen comes back for a while. */
    fun detach() {
        if (viewers.decrementAndGet() > 0) return
        scope.launch {
            delay(DROP_AFTER_MS)
            if (viewers.get() == 0) _pending.value?.let { reply(it, false) }
        }
    }

    /** True to send. [devices] are the names of the devices that are short of space. */
    suspend fun ask(devices: List<String>): Boolean {
        if (viewers.get() == 0) return true
        return one.withLock {
            val question = Question(devices, CompletableDeferred())
            _pending.value = question
            question.answer.await()
        }
    }

    fun reply(question: Question, send: Boolean) {
        if (_pending.compareAndSet(question, null)) question.answer.complete(send)
    }

    private companion object {
        const val DROP_AFTER_MS = 4_000L
    }
}
