package app.vlcglobaleq.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Parsed presets have an empty id until [PresetStore.addAll] assigns one. */
data class ImportResult(val presets: List<Preset>, val errors: List<String>)

/**
 * Parses VLC for Android equalizer exports:
 *  1. a single `{ "bands": [...], "equalizerEntry": {...} }` object,
 *  2. a top-level array of those objects,
 *  3. any object that wraps such an array somewhere inside it.
 *
 * Invalid presets are skipped with a message; valid ones are still returned.
 */
object VlcImporter {

    fun parse(text: String): ImportResult {
        val root = try {
            JSONTokener(text.trim().removePrefix("﻿")).nextValue()
        } catch (e: JSONException) {
            return ImportResult(emptyList(), listOf("Not valid JSON: ${e.message}"))
        }
        val items: List<Any?> = when {
            root is JSONObject && looksLikePreset(root) -> listOf(root)
            root is JSONArray -> root.toList()
            root is JSONObject -> findPresetArray(root)?.toList()
                ?: return ImportResult(emptyList(), listOf("No VLC equalizer presets found in this file"))
            else -> return ImportResult(emptyList(), listOf("Not a VLC equalizer export"))
        }

        val presets = ArrayList<Preset>()
        val errors = ArrayList<String>()
        items.forEachIndexed { i, item ->
            val label = (item as? JSONObject)?.optJSONObject("equalizerEntry")?.optString("name")
                ?.takeIf { it.isNotBlank() } ?: "Preset #${i + 1}"
            try {
                if (item !is JSONObject) throw InvalidPreset("not a JSON object")
                presets += parsePreset(item)
            } catch (e: InvalidPreset) {
                errors += "$label: ${e.message}"
            } catch (e: JSONException) {
                errors += "$label: ${e.message}"
            }
        }
        if (items.isEmpty()) errors += "The file contains no presets"
        return ImportResult(presets, errors)
    }

    private class InvalidPreset(msg: String) : Exception(msg)

    private fun parsePreset(o: JSONObject): Preset {
        val bands = o.optJSONArray("bands") ?: throw InvalidPreset("missing \"bands\"")
        val entry = o.optJSONObject("equalizerEntry") ?: throw InvalidPreset("missing \"equalizerEntry\"")
        if (bands.length() != Preset.BAND_COUNT) {
            throw InvalidPreset("expected ${Preset.BAND_COUNT} bands, found ${bands.length()}")
        }
        val gains = arrayOfNulls<Double>(Preset.BAND_COUNT)
        for (i in 0 until bands.length()) {
            val b = bands.optJSONObject(i) ?: throw InvalidPreset("band #$i is not an object")
            if (!b.has("index")) throw InvalidPreset("band #$i has no index")
            val index = b.getInt("index")
            if (index !in 0 until Preset.BAND_COUNT) throw InvalidPreset("band index $index is outside 0–9")
            if (gains[index] != null) throw InvalidPreset("band index $index appears twice")
            val v = b.optDouble("bandValue", Double.NaN)
            if (!v.isFinite()) throw InvalidPreset("band $index has no numeric bandValue")
            gains[index] = Preset.clampDb(v)
        }
        val preamp = entry.optDouble("preamp", 0.0).let { if (it.isFinite()) Preset.clampDb(it) else 0.0 }
        return Preset(
            id = "",
            name = entry.optString("name").trim().ifEmpty { "Imported preset" },
            preampDb = preamp,
            bandsDb = gains.map { it!! },
            bandSet = BandSet.ISO10,
            source = PresetSource.IMPORTED,
            vlcDisabled = entry.optBoolean("isDisabled", false),
            vlcId = if (entry.has("id")) entry.optInt("id") else null,
            vlcPresetIndex = if (entry.has("presetIndex")) entry.optInt("presetIndex") else null,
        )
    }

    private fun looksLikePreset(o: JSONObject) = o.has("bands") && o.has("equalizerEntry")

    /** Depth-first search for the first array whose items look like VLC presets. */
    private fun findPresetArray(node: Any?): JSONArray? {
        when (node) {
            is JSONArray -> {
                val first = (0 until node.length()).map { node.opt(it) }
                if (first.isNotEmpty() && first.all { it is JSONObject && looksLikePreset(it) }) return node
                first.forEach { child -> findPresetArray(child)?.let { return it } }
            }
            is JSONObject -> node.keys().forEach { key -> findPresetArray(node.opt(key))?.let { return it } }
        }
        return null
    }

    private fun JSONArray.toList(): List<Any?> = (0 until length()).map { opt(it) }

    /** Returns [name], or "name (2)", "name (3)"… if it is already taken. */
    fun uniqueName(name: String, taken: Collection<String>): String {
        if (name !in taken) return name
        var n = 2
        while ("$name ($n)" in taken) n++
        return "$name ($n)"
    }
}

/** Writes presets back in VLC for Android's export schema so they re-import into VLC. */
object VlcExporter {

    fun toJson(preset: Preset): JSONObject {
        val entryId = preset.vlcId ?: 0
        val bands = JSONArray()
        preset.bandsDb.forEachIndexed { i, v ->
            bands.put(JSONObject().put("bandValue", v).put("equalizerEntry", entryId).put("index", i))
        }
        val entry = JSONObject()
            .put("id", entryId)
            .put("isDisabled", preset.vlcDisabled)
            .put("name", preset.name)
            .put("preamp", preset.preampDb)
            .put("presetIndex", preset.vlcPresetIndex ?: -1)
        return JSONObject().put("bands", bands).put("equalizerEntry", entry)
    }

    /** One preset → single object (VLC's own format); several → a JSON array. */
    fun export(presets: List<Preset>): String =
        if (presets.size == 1) toJson(presets[0]).toString(2)
        else JSONArray().apply { presets.forEach { put(toJson(it)) } }.toString(2)
}
