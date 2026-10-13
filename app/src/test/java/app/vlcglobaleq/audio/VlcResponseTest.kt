package app.vlcglobaleq.audio

import app.vlcglobaleq.data.BandSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcResponseTest {
    private val flat = List(10) { 0.0 }

    @Test
    fun `flat preset is 0 dB everywhere`() {
        var f = 20.0
        while (f < 20_000) {
            assertEquals(0.0, VlcResponse.magnitudeDb(flat, BandSet.ISO10, f), 1e-9)
            f *= 1.1
        }
    }

    @Test
    fun `single band boost peaks at its centre frequency`() {
        val bands = flat.toMutableList().also { it[5] = 6.0 }
        assertEquals(6.0, VlcResponse.magnitudeDb(bands, BandSet.ISO10, 1000.0), 0.05)
        assertTrue(VlcResponse.magnitudeDb(bands, BandSet.ISO10, 100.0) < 1.0)
        assertTrue(VlcResponse.magnitudeDb(bands, BandSet.ISO10, 10_000.0) < 1.0)
    }

    @Test
    fun `exact mode feeds the mapper`() {
        val bands = flat.toMutableList().also { it[0] = 10.0 }
        val p = app.vlcglobaleq.data.Preset("t", "t", 0.0, bands)
        val dp = CurveMapper.dpBands(p, vlcExact = true)
        assertTrue(dp.first().gainDb > 3)
        assertEquals(0.0, dp.last().gainDb, 0.5)
    }
}
