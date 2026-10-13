package app.vlcglobaleq.audio

import app.vlcglobaleq.data.BandSet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * (v2) Magnitude response of libVLC's equalizer (modules/audio_filter/equalizer.c), single pass.
 *
 * Each band is a 2nd-order band-pass `y = α(x − x[n−2]) + γ·y[n−1] − β·y[n−2]` and the output
 * is `0.25·x + Σ amp_j·y_j` with `amp_j = 0.25·(10^(dB/20) − 1)`. The result is normalised
 * by the 0.25 factor so a flat preset is 0 dB (the −12 dB is applied via the preamp instead,
 * see [CurveMapper.VLC_INTERNAL_GAIN_DB]).
 */
object VlcResponse {
    private const val IN_FACTOR = 0.25

    private class Coeffs(val alpha: Double, val beta: Double, val gamma: Double)

    private fun coeffs(freq: Double, sampleRate: Double): Coeffs {
        if (freq > sampleRate / 2) return Coeffs(0.0, 0.0, 0.0)
        val octave = 2.0.pow(0.5) // f_octave_percent = 1.0
        val f1 = 0.5 * (octave + 1)
        val f2 = 0.5 * (octave - 1)
        val theta1 = 2 * PI * freq / sampleRate
        val theta2 = theta1 / octave
        val s = sin(theta2)
        val sinPrd = sin(theta2 * f1) * sin(theta2 * f2)
        val sinHlf = s * 0.5
        val den = sinHlf + sinPrd
        return Coeffs(sinPrd / den, (sinHlf - sinPrd) / den, s * cos(theta1) / den)
    }

    fun magnitudeDb(bandsDb: List<Double>, bandSet: BandSet, f: Double, sampleRate: Double = 48_000.0): Double {
        val w = 2 * PI * f.coerceIn(1.0, sampleRate / 2) / sampleRate
        // z^-1 and z^-2 on the unit circle
        val c1 = cos(w); val s1 = -sin(w)
        val c2 = cos(2 * w); val s2 = -sin(2 * w)
        var re = IN_FACTOR
        var im = 0.0
        for (j in bandsDb.indices) {
            val db = bandsDb[j].coerceIn(-20.0, 20.0)
            val amp = IN_FACTOR * (10.0.pow(db / 20) - 1)
            if (amp == 0.0) continue
            val k = coeffs(bandSet.frequencies[j], sampleRate)
            if (k.alpha == 0.0) continue
            // H = α(1 − z⁻²) / (1 − γz⁻¹ + βz⁻²)
            val nr = k.alpha * (1 - c2); val ni = k.alpha * (-s2)
            val dr = 1 - k.gamma * c1 + k.beta * c2; val di = -k.gamma * s1 + k.beta * s2
            val dd = dr * dr + di * di
            re += amp * (nr * dr + ni * di) / dd
            im += amp * (ni * dr - nr * di) / dd
        }
        return 20 * log10(max(sqrt(re * re + im * im), 1e-9) / IN_FACTOR)
    }
}
