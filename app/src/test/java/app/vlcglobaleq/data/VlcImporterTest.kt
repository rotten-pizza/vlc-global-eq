package app.vlcglobaleq.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcImporterTest {
    private val fixture = javaClass.classLoader!!.getResource("vlc_club_copy.json")!!.readText()
    private val fixtureBands = listOf(14.315821, 13.80448, 13.68421, 5.8, 0.5, -0.5, 0.4, 0.0, 9.2, 8.7)

    @Test
    fun `imports VLC export with identical values`() {
        val r = VlcImporter.parse(fixture)
        assertEquals(emptyList<String>(), r.errors)
        val p = r.presets.single()
        assertEquals("Club (copy)_", p.name)
        assertEquals(-2.0, p.preampDb, 0.0)
        assertEquals(fixtureBands, p.bandsDb)
        assertEquals(BandSet.ISO10, p.bandSet)
        assertEquals(PresetSource.IMPORTED, p.source)
        assertFalse(p.vlcDisabled)
        assertEquals(23, p.vlcId)
        assertEquals(-1, p.vlcPresetIndex)
    }

    @Test
    fun `export round-trips back to the same JSON`() {
        val p = VlcImporter.parse(fixture).presets.single()
        val exported = JSONObject(VlcExporter.export(listOf(p)))
        assertTrue(jsonEquals(JSONObject(fixture), exported))
        assertEquals(p, VlcImporter.parse(exported.toString()).presets.single())
    }

    /** Structural JSON equality (Android's org.json has no `similar`); numbers compare by value. */
    private fun jsonEquals(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject ->
            a.length() == b.length() && a.keys().asSequence().all { b.has(it) && jsonEquals(a.opt(it), b.opt(it)) }
        a is JSONArray && b is JSONArray ->
            a.length() == b.length() && (0 until a.length()).all { jsonEquals(a.opt(it), b.opt(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    @Test
    fun `imports top-level array`() {
        val arr = JSONArray().put(JSONObject(fixture)).put(JSONObject(fixture))
        val r = VlcImporter.parse(arr.toString())
        assertEquals(2, r.presets.size)
        assertTrue(r.errors.isEmpty())
    }

    @Test
    fun `imports object wrapping an array`() {
        val wrapped = JSONObject().put("version", 1)
            .put("data", JSONObject().put("equalizers", JSONArray().put(JSONObject(fixture))))
        val r = VlcImporter.parse(wrapped.toString())
        assertEquals("Club (copy)_", r.presets.single().name)
    }

    @Test
    fun `rejects nine bands but keeps importing the rest`() {
        val bad = JSONObject(fixture).apply { getJSONArray("bands").remove(9) }
        bad.getJSONObject("equalizerEntry").put("name", "Broken")
        val r = VlcImporter.parse(JSONArray().put(bad).put(JSONObject(fixture)).toString())
        assertEquals(1, r.presets.size)
        assertEquals(1, r.errors.size)
        assertTrue(r.errors[0], r.errors[0].startsWith("Broken: expected 10 bands, found 9"))
    }

    @Test
    fun `rejects duplicate or out of range indexes`() {
        val dup = JSONObject(fixture).apply { getJSONArray("bands").getJSONObject(9).put("index", 3) }
        assertTrue(VlcImporter.parse(dup.toString()).presets.isEmpty())
        val oob = JSONObject(fixture).apply { getJSONArray("bands").getJSONObject(9).put("index", 10) }
        assertTrue(VlcImporter.parse(oob.toString()).presets.isEmpty())
    }

    @Test
    fun `clamps out of range values`() {
        val o = JSONObject(fixture)
        o.getJSONArray("bands").getJSONObject(0).put("bandValue", 35.0)
        o.getJSONArray("bands").getJSONObject(1).put("bandValue", -99.0)
        o.getJSONObject("equalizerEntry").put("preamp", 25.0)
        val p = VlcImporter.parse(o.toString()).presets.single()
        assertEquals(20.0, p.bandsDb[0], 0.0)
        assertEquals(-20.0, p.bandsDb[1], 0.0)
        assertEquals(20.0, p.preampDb, 0.0)
    }

    @Test
    fun `sorts by index, not array order`() {
        val o = JSONObject(fixture)
        val bands = o.getJSONArray("bands")
        val shuffled = JSONArray()
        listOf(7, 2, 9, 0, 4, 1, 8, 3, 6, 5).forEach { shuffled.put(bands.getJSONObject(it)) }
        o.put("bands", shuffled)
        assertEquals(fixtureBands, VlcImporter.parse(o.toString()).presets.single().bandsDb)
    }

    @Test
    fun `malformed json does not throw`() {
        for (text in listOf("", "{", "[1,2,3]", "\"hi\"", "{\"a\":1}", "[]", "{\"bands\":5,\"equalizerEntry\":{}}")) {
            val r = VlcImporter.parse(text)
            assertTrue(text, r.presets.isEmpty())
            assertTrue(text, r.errors.isNotEmpty())
        }
    }

    @Test
    fun `accepts a leading byte-order mark`() {
        assertEquals(1, VlcImporter.parse("\uFEFF" + fixture).presets.size)
    }

    @Test
    fun `de-duplicates names`() {
        assertEquals("Rock", VlcImporter.uniqueName("Rock", listOf("Pop")))
        assertEquals("Rock (2)", VlcImporter.uniqueName("Rock", listOf("Rock")))
        assertEquals("Rock (3)", VlcImporter.uniqueName("Rock", listOf("Rock", "Rock (2)")))
    }

    @Test
    fun `built-in presets match libVLC`() {
        val all = BuiltinPresets.all
        assertEquals(18, all.size)
        assertEquals("Flat", all[0].name)
        val club = all.single { it.id == "builtin:club" }
        assertEquals(6.0, club.preampDb, 0.0)
        assertEquals(listOf(0.0, 0.0, 8.0, 5.6, 5.6, 5.6, 3.2, 0.0, 0.0, 0.0), club.bandsDb)
    }
}
