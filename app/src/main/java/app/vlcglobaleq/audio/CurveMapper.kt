package app.vlcglobaleq.audio

import app.vlcglobaleq.data.Preset
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Maps VLC's 10 band gains onto DynamicsProcessing pre-EQ bands.
 *
 * DP bands are flat steps (each covers previous cutoff → own cutoff at one gain), so the
 * 10 VLC points are first turned into a smooth curve G(f) — monotone cubic (PCHIP) in
 * log-frequency, held flat outside the band range — and then sampled at the geometric
 * centre of [DP_BAND_COUNT] log-spaced bands between 20 Hz and 20 kHz.
 */
object CurveMapper {
    const val DP_BAND_COUNT = 28
    const val MIN_HZ = 20.0
    const val MAX_HZ = 20_000.0
    /** The last band's cutoff; must cover Nyquist of the output mix. */
    const val LAST_CUTOFF_HZ = 24_000.0

    /**
     * VLC's filter output is `preamp · (0.25·x + Σ…)`, so a flat curve at preamp P plays at
     * P − 12.04 dB (which is why VLC's built-in presets use preamps around +12 dB).
     * Adding this to the VLC preamp gives the same loudness VLC produces.
     */
    val VLC_INTERNAL_GAIN_DB = 20 * log10(0.25)

    data class DpBand(val cutoffHz: Double, val gainDb: Double)

    /** Upper edges of the DP bands. */
    val cutoffs: DoubleArray = DoubleArray(DP_BAND_COUNT) { k ->
        if (k == DP_BAND_COUNT - 1) LAST_CUTOFF_HZ else edge(k + 1)
    }

    /** Frequencies the curve is sampled at (geometric band centres). */
    val centers: DoubleArray = DoubleArray(DP_BAND_COUNT) { k -> sqrt(edge(k) * edge(k + 1)) }

    private fun edge(i: Int) = MIN_HZ * (MAX_HZ / MIN_HZ).pow(i.toDouble() / DP_BAND_COUNT)

    /** Target curve G(f) in dB (band gains only, preamp excluded). */
    fun curve(preset: Preset, vlcExact: Boolean = false): (Double) -> Double =
        if (vlcExact) { f -> VlcResponse.magnitudeDb(preset.bandsDb, preset.bandSet, f) }
        else pchip(preset.bandSet.frequencies.map { log2(it) }.toDoubleArray(), preset.bandsDb.toDoubleArray())
            .let { p -> { f: Double -> p(log2(f)) } }

    fun dpBands(preset: Preset, vlcExact: Boolean = false): List<DpBand> {
        val g = curve(preset, vlcExact)
        return List(DP_BAND_COUNT) { DpBand(cutoffs[it], g(centers[it])) }
    }

    /** DP input gain: VLC preamp corrected for VLC's internal −12 dB, or auto (no boost above 0 dB). */
    fun inputGainDb(preset: Preset, bands: List<DpBand>, autoPreamp: Boolean): Double =
        if (autoPreamp) -max(0.0, bands.maxOf { it.gainDb })
        else preset.preampDb + VLC_INTERNAL_GAIN_DB

    private fun log2(f: Double) = ln(f) / ln(2.0)

    /**
     * Monotone piecewise-cubic Hermite interpolation (Fritsch–Carlson). Never overshoots the
     * input points; values outside [xs] are held at the end values.
     */
    fun pchip(xs: DoubleArray, ys: DoubleArray): (Double) -> Double {
        val n = xs.size
        require(n == ys.size && n >= 2)
        val h = DoubleArray(n - 1) { xs[it + 1] - xs[it] }
        val delta = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / h[it] }
        val d = DoubleArray(n)
        for (k in 1 until n - 1) {
            d[k] = if (delta[k - 1] * delta[k] <= 0) 0.0 else {
                val w1 = 2 * h[k] + h[k - 1]
                val w2 = h[k] + 2 * h[k - 1]
                (w1 + w2) / (w1 / delta[k - 1] + w2 / delta[k])
            }
        }
        d[0] = endSlope(h[0], h.getOrElse(1) { h[0] }, delta[0], delta.getOrElse(1) { delta[0] })
        d[n - 1] = endSlope(h[n - 2], h.getOrElse(n - 3) { h[n - 2] }, delta[n - 2], delta.getOrElse(n - 3) { delta[n - 2] })

        return f@{ x ->
            if (x <= xs[0]) return@f ys[0]
            if (x >= xs[n - 1]) return@f ys[n - 1]
            var k = 0
            while (x > xs[k + 1]) k++
            val t = (x - xs[k]) / h[k]
            val t2 = t * t
            val t3 = t2 * t
            (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h[k] * d[k] +
                (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h[k] * d[k + 1]
        }
    }

    private fun endSlope(h0: Double, h1: Double, d0: Double, d1: Double): Double {
        var s = ((2 * h0 + h1) * d0 - h0 * d1) / (h0 + h1)
        if (s * d0 <= 0) s = 0.0
        else if (d0 * d1 < 0 && abs(s) > abs(3 * d0)) s = 3 * d0
        return s
    }
}
