package app.vlcglobaleq.data

/** Centre frequencies of VLC's 10 equalizer bands (from libVLC equalizer_presets.h). */
enum class BandSet(val frequencies: DoubleArray) {
    /** libVLC / VLC for Android (`equalizer-vlcfreqs` = false). */
    ISO10(doubleArrayOf(31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)),

    /** Desktop VLC default (`equalizer-vlcfreqs` = true). */
    VLC10(doubleArrayOf(60.0, 170.0, 310.0, 600.0, 1000.0, 3000.0, 6000.0, 12000.0, 14000.0, 16000.0)),
}

enum class PresetSource { BUILTIN, IMPORTED, USER }

data class Preset(
    val id: String,
    val name: String,
    /** VLC preamp in dB, exactly as VLC stores it (−20…+20). */
    val preampDb: Double,
    /** 10 band gains in dB (−20…+20), index 0 = lowest band. */
    val bandsDb: List<Double>,
    val bandSet: BandSet = BandSet.ISO10,
    val source: PresetSource = PresetSource.USER,
    /** VLC's `isDisabled` UI flag, kept only so exports round-trip. */
    val vlcDisabled: Boolean = false,
    /** VLC's internal `equalizerEntry.id`, kept only so exports round-trip. */
    val vlcId: Int? = null,
    /** VLC's `presetIndex`, kept only so exports round-trip. */
    val vlcPresetIndex: Int? = null,
) {
    init {
        require(bandsDb.size == BAND_COUNT) { "A preset needs exactly $BAND_COUNT bands" }
    }

    val isBuiltin get() = source == PresetSource.BUILTIN

    companion object {
        const val BAND_COUNT = 10
        const val MIN_DB = -20.0
        const val MAX_DB = 20.0

        fun clampDb(v: Double) = v.coerceIn(MIN_DB, MAX_DB)
    }
}
