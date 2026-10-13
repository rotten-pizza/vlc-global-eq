package app.vlcglobaleq.audio

import app.vlcglobaleq.data.BandSet
import app.vlcglobaleq.data.BuiltinPresets
import app.vlcglobaleq.data.Preset
import app.vlcglobaleq.data.VlcImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveMapperTest {
    private fun preset(vararg bands: Double, preamp: Double = 0.0) =
        Preset("t", "t", preamp, bands.toList(), BandSet.ISO10)

    private val fixture = VlcImporter.parse(javaClass.classLoader!!.getResource("vlc_club_copy.json")!!.readText())
        .presets.single()

    @Test
    fun `flat in gives all bands at 0 dB`() {
        val bands = CurveMapper.dpBands(preset(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(CurveMapper.DP_BAND_COUNT, bands.size)
        bands.forEach { assertEquals(0.0, it.gainDb, 1e-12) }
    }

    @Test
    fun `cutoffs are strictly increasing and the last covers Nyquist`() {
        val bands = CurveMapper.dpBands(fixture)
        bands.zipWithNext().forEach { (a, b) -> assertTrue(b.cutoffHz > a.cutoffHz) }
        assertTrue(bands.last().cutoffHz >= 24_000.0)
        assertTrue(bands.first().cutoffHz > CurveMapper.MIN_HZ)
    }

    @Test
    fun `no overshoot beyond input min and max`() {
        for (p in BuiltinPresets.all + fixture) {
            val g = CurveMapper.curve(p)
            val lo = p.bandsDb.min(); val hi = p.bandsDb.max()
            var f = 10.0
            while (f < 24_000) {
                val v = g(f)
                assertTrue("${p.name} @ $f: $v", v >= lo - 1e-9 && v <= hi + 1e-9)
                f *= 1.02
            }
        }
    }

    @Test
    fun `curve passes through VLC points and holds outside`() {
        val g = CurveMapper.curve(fixture)
        BandSet.ISO10.frequencies.forEachIndexed { i, f -> assertEquals(fixture.bandsDb[i], g(f), 1e-9) }
        assertEquals(fixture.bandsDb[0], g(20.0), 1e-9)
        assertEquals(fixture.bandsDb[9], g(20_000.0), 1e-9)
    }

    @Test
    fun `monotone segments stay monotone`() {
        val g = CurveMapper.curve(preset(-10.0, -8.0, -6.0, -4.0, -2.0, 0.0, 2.0, 4.0, 6.0, 8.0))
        var prev = g(20.0); var f = 21.0
        while (f < 20_000) { val v = g(f); assertTrue(v >= prev - 1e-12); prev = v; f *= 1.05 }
    }

    @Test
    fun `preamp includes VLC internal -12 dB and auto preamp prevents boost`() {
        val flat = preset(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, preamp = 12.0)
        assertEquals(-0.04, CurveMapper.inputGainDb(flat, CurveMapper.dpBands(flat), false), 0.01)
        val bands = CurveMapper.dpBands(fixture)
        assertEquals(-2.0 - 12.04, CurveMapper.inputGainDb(fixture, bands, false), 0.01)
        val auto = CurveMapper.inputGainDb(fixture, bands, true)
        assertEquals(-bands.maxOf { it.gainDb }, auto, 1e-12)
        assertTrue(auto < -13)
    }
}
