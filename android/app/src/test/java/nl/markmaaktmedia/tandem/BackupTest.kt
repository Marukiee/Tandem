package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.data.Backup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupTest {
    private val mac = """{"id":"MACID","name":"MacBook","kind":"mac","platform":"mac","model":"Mac15,7","settings":{"showInDock":false}}"""
    private val laptop = """{"id":"PC","name":"Laptop","kind":"desktop","platform":"windows","settings":{"closeToTray":true}}"""

    private fun phone(id: String, name: String, model: String) =
        Backup.entry(id, name, model, "nl", JSONObject().put("theme_mode", "DARK"))

    @Test
    fun a_reset_phone_finds_itself_by_its_model_though_its_id_is_new() {
        val file = Backup.file(phone("OLD", "Xperia", "Sony XQ-EC54"), listOf(mac, laptop, "not json"), "2026-11-08")
        val devices = Backup.devices(file)!!
        assertEquals(listOf("Xperia", "MacBook", "Laptop"), devices.map { it.name })
        assertEquals(0, Backup.recognise(devices, "NEW", "Sony XQ-EC54", "Pixel"))
        assertEquals("nl", devices[0].language)
    }

    @Test
    fun two_phones_of_one_model_are_told_apart_by_name_or_left_to_the_person() {
        val file = Backup.file(phone("A", "Work", "Sony XQ-EC54"), listOf(phone("B", "Home", "Sony XQ-EC54").toString()), "now")
        val devices = Backup.devices(file)!!
        assertEquals(1, Backup.recognise(devices, "NEW", "Sony XQ-EC54", "Home"))
        assertNull(Backup.recognise(devices, "NEW", "Sony XQ-EC54", "Xperia"))
        assertEquals(0, Backup.recognise(devices, "A", "Other", "Other"))
    }

    @Test
    fun a_file_of_an_older_version_is_one_device_of_its_own_kind() {
        val android = Backup.devices("""{"app":"tandem","format":1,"language":"en","settings":{"device_name":"Xperia"}}""")!!
        assertEquals("android" to "Xperia", android.single().kind to android.single().name)
        assertEquals("mac", Backup.devices("""{"app":"Tandem","format":2,"settings":{"deviceName":"Mac"}}""")!!.single().kind)
        assertEquals("desktop", Backup.devices("""{"closeToTray":true,"copyCodes":false}""")!!.single().kind)
        assertNull(Backup.devices("""{"hello":1}"""))
        assertNull(Backup.recognise(Backup.devices(mac.let { """{"app":"Tandem","format":3,"devices":[$it]}""" })!!, "X", "Y", "Z"))
    }
}
