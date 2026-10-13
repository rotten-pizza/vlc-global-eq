package app.vlcglobaleq.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class EqMode { GLOBAL, SESSIONS, BOTH }

/**
 * User presets live in `filesDir/presets.json`; settings (enabled flag, active preset,
 * mode, toggles) in SharedPreferences. Built-in presets are never written to disk.
 */
class PresetStore private constructor(context: Context) {
    private val file = File(context.filesDir, "presets.json")
    private val prefs: SharedPreferences = context.getSharedPreferences("eq", Context.MODE_PRIVATE)
    private var userPresets: MutableList<Preset> = load()

    val presets: List<Preset> get() = BuiltinPresets.all + userPresets

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    var mode: EqMode
        get() = runCatching { EqMode.valueOf(prefs.getString("mode", null)!!) }.getOrDefault(EqMode.GLOBAL)
        set(v) = prefs.edit().putString("mode", v.name).apply()

    var autoPreamp: Boolean
        get() = prefs.getBoolean("autoPreamp", false)
        set(v) = prefs.edit().putBoolean("autoPreamp", v).apply()

    /** v2: use VLC's exact IIR magnitude response instead of the interpolated curve. */
    var vlcExactCurve: Boolean
        get() = prefs.getBoolean("vlcExact", false)
        set(v) = prefs.edit().putBoolean("vlcExact", v).apply()

    var askedBatteryOptimization: Boolean
        get() = prefs.getBoolean("askedBattery", false)
        set(v) = prefs.edit().putBoolean("askedBattery", v).apply()

    var activeId: String
        get() = prefs.getString("active", null) ?: BuiltinPresets.all.first().id
        set(v) = prefs.edit().putString("active", v).apply()

    val active: Preset get() = find(activeId) ?: BuiltinPresets.all.first()

    fun find(id: String) = presets.firstOrNull { it.id == id }

    fun uniqueName(name: String) = VlcImporter.uniqueName(name, presets.map { it.name })

    /** Adds parsed presets with fresh ids and de-duplicated names; returns the stored copies. */
    fun addAll(parsed: List<Preset>): List<Preset> {
        val added = parsed.map { it.copy(id = UUID.randomUUID().toString(), name = uniqueName(it.name)) }
            .onEach { userPresets.add(it) }
        save()
        return added
    }

    fun add(preset: Preset): Preset = addAll(listOf(preset)).first()

    /** Updates a user preset. Built-ins are immutable; edit a copy instead. */
    fun update(preset: Preset, persist: Boolean = true) {
        val i = userPresets.indexOfFirst { it.id == preset.id }
        if (i < 0) return
        userPresets[i] = preset
        if (persist) save()
    }

    fun delete(id: String) {
        if (userPresets.removeAll { it.id == id }) {
            if (activeId == id) activeId = BuiltinPresets.all.first().id
            save()
        }
    }

    fun next(): Preset {
        val list = presets
        val i = list.indexOfFirst { it.id == activeId }
        return list[(i + 1).mod(list.size)].also { activeId = it.id }
    }

    fun save() {
        val arr = JSONArray()
        userPresets.forEach { arr.put(toJson(it)) }
        val tmp = File(file.parentFile, "presets.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    private fun load(): MutableList<Preset> = try {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull() }
            .toMutableList()
    } catch (_: Exception) {
        mutableListOf()
    }

    private fun toJson(p: Preset) = JSONObject()
        .put("id", p.id).put("name", p.name).put("preamp", p.preampDb)
        .put("bands", JSONArray().apply { p.bandsDb.forEach { put(it) } })
        .put("bandSet", p.bandSet.name).put("source", p.source.name)
        .put("vlcDisabled", p.vlcDisabled)
        .apply {
            p.vlcId?.let { put("vlcId", it) }
            p.vlcPresetIndex?.let { put("vlcPresetIndex", it) }
        }

    private fun fromJson(o: JSONObject): Preset {
        val b = o.getJSONArray("bands")
        return Preset(
            id = o.getString("id"),
            name = o.getString("name"),
            preampDb = o.getDouble("preamp"),
            bandsDb = (0 until b.length()).map { b.getDouble(it) },
            bandSet = runCatching { BandSet.valueOf(o.getString("bandSet")) }.getOrDefault(BandSet.ISO10),
            source = runCatching { PresetSource.valueOf(o.getString("source")) }.getOrDefault(PresetSource.USER),
            vlcDisabled = o.optBoolean("vlcDisabled"),
            vlcId = if (o.has("vlcId")) o.getInt("vlcId") else null,
            vlcPresetIndex = if (o.has("vlcPresetIndex")) o.getInt("vlcPresetIndex") else null,
        )
    }

    companion object {
        @Volatile private var instance: PresetStore? = null

        fun get(context: Context): PresetStore =
            instance ?: synchronized(this) {
                instance ?: PresetStore(context.applicationContext).also { instance = it }
            }
    }
}
