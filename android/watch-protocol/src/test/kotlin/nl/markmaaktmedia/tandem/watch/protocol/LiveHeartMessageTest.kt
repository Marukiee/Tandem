package nl.markmaaktmedia.tandem.watch.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveHeartMessageTest {
    @Test
    fun askingAndTheHeartRateNowSurviveTheWire() {
        for (message in listOf(Message.HeartLive(true), Message.HeartLive(false), Message.HeartNow(72), Message.HeartNow(0))) {
            assertEquals(message, MessageCodec.decode(MessageCodec.encode(message)))
        }
    }

    @Test
    fun aHeartRateOutOfRangeIsKeptToOneByte() {
        assertEquals(Message.HeartNow(255), MessageCodec.decode(MessageCodec.encode(Message.HeartNow(400))))
    }
}
