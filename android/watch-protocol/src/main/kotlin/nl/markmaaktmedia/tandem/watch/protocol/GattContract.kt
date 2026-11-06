package nl.markmaaktmedia.tandem.watch.protocol

import java.util.UUID

/** Own service, separate from Tandem's hotspot and Mac BLE routes. */
object GattContract {
    val SERVICE: UUID = UUID.fromString("7b1e4a50-9c3d-4f6e-8a21-5d0c6e7f1a00")
    val WATCH_TO_PHONE: UUID = UUID.fromString("7b1e4a50-9c3d-4f6e-8a21-5d0c6e7f1a01")
    val PHONE_TO_WATCH: UUID = UUID.fromString("7b1e4a50-9c3d-4f6e-8a21-5d0c6e7f1a02")
    val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val REQUEST_MTU = 247

    fun chunkSize(mtu: Int): Int = (mtu - 3).coerceIn(Chunker.MIN_CHUNK, Chunker.MAX_CHUNK)
}
