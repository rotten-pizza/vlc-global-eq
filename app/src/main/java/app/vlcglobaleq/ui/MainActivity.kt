package app.vlcglobaleq.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.vlcglobaleq.audio.EqStatus
import app.vlcglobaleq.data.EqMode
import app.vlcglobaleq.data.Preset
import app.vlcglobaleq.data.PresetSource
import app.vlcglobaleq.data.PresetStore
import app.vlcglobaleq.data.VlcExporter
import app.vlcglobaleq.data.VlcImporter
import app.vlcglobaleq.service.EqController
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var store: PresetStore
    private lateinit var master: Switch
    private lateinit var statusText: TextView
    private lateinit var presetList: LinearLayout
    private lateinit var editorTitle: TextView
    private lateinit var curve: CurveView
    private lateinit var preampSeek: SeekBar
    private lateinit var preampLabel: TextView
    private val sliders = ArrayList<VerticalSlider>()

    /** The preset shown in the editor (always the active one). */
    private lateinit var editing: Preset
    private var pendingExportId: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val pushLive = Runnable { EqController.applyLive(this, editing) }
    private val statusListener: (EqStatus) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PresetStore.get(this)
        editing = store.active
        setContentView(buildUi())
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onStart() {
        super.onStart()
        EqController.addListener(statusListener)
    }

    override fun onStop() {
        EqController.removeListener(statusListener)
        handler.removeCallbacks(pushLive)
        store.save()
        super.onStop()
    }

    // ---------------------------------------------------------------- UI construction

    @Suppress("DEPRECATION") // systemWindowInset* on API 28–29
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            val p = dp(16f).toInt()
            setPadding(p, p, p, p)
        }

        // Header: title, master switch, overflow menu.
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("VLC Global EQ", 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        master = Switch(this).apply {
            setOnClickListener { onMasterToggled(isChecked) }
            contentDescription = "Equalizer on/off"
        }
        header.addView(master)
        header.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_more)
            setBackgroundColor(0)
            contentDescription = "More options"
            setOnClickListener { showMenu(it) }
        })
        root.addView(header)
        statusText = text("", 14f, color = Palette.TEXT2)
        root.addView(statusText)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(content); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Editor
        editorTitle = text("", 16f, bold = true).apply { setPadding(0, dp(16f).toInt(), 0, dp(8f).toInt()) }
        content.addView(editorTitle)
        curve = CurveView(this)
        content.addView(curve, LinearLayout.LayoutParams(-1, dp(120f).toInt()))

        val preampRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8f).toInt(), 0, 0) }
        preampRow.addView(text("Preamp", 14f), LinearLayout.LayoutParams(dp(64f).toInt(), -2))
        preampSeek = SeekBar(this).apply {
            max = 400
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) edit { it.copy(preampDb = (progress - 200) / 10.0) }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) = store.save()
            })
        }
        preampRow.addView(preampSeek, LinearLayout.LayoutParams(0, -2, 1f))
        preampLabel = text("", 13f).apply { gravity = Gravity.END }
        preampRow.addView(preampLabel, LinearLayout.LayoutParams(dp(56f).toInt(), -2))
        content.addView(preampRow)

        val sliderRow = LinearLayout(this)
        listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k").forEachIndexed { i, label ->
            val s = VerticalSlider(this, label)
            s.onChange = { v -> edit { p -> p.copy(bandsDb = p.bandsDb.toMutableList().also { it[i] = v }) } }
            s.onRelease = { store.save() }
            sliders += s
            sliderRow.addView(s, LinearLayout.LayoutParams(0, dp(240f).toInt(), 1f))
        }
        content.addView(sliderRow)

        val buttons = LinearLayout(this).apply { setPadding(0, dp(8f).toInt(), 0, 0) }
        buttons.addView(button("Import") { pickImport() }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(button("Export") { export(editing) }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(buttons)

        content.addView(text("Presets", 16f, bold = true).apply { setPadding(0, dp(16f).toInt(), 0, dp(8f).toInt()) })
        presetList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(presetList)

        // targetSdk 35+ draws edge-to-edge; keep content clear of the system bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val p = dp(16f).toInt()
            val (l, t, r, b) = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars()).let { listOf(it.left, it.top, it.right, it.bottom) }
            } else listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            v.setPadding(p + l, p + t, p + r, p + b)
            insets
        }
        render()
        return root
    }

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Palette.TEXT) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------- Rendering

    private fun render() {
        val active = store.active
        if (active.id != editing.id) editing = active
        master.isChecked = store.enabled
        statusText.text = when {
            !store.enabled -> "Off"
            EqController.status == EqStatus.Off -> "Starting…"
            else -> EqController.status.text
        }
        renderEditor()
        renderPresetList()
    }

    private fun renderEditor() {
        val p = editing
        editorTitle.text = p.name + if (p.isBuiltin) "  (built-in — edits make a copy)" else ""
        curve.set(p, store.vlcExactCurve)
        preampSeek.progress = (p.preampDb * 10).roundToInt() + 200
        preampLabel.text = formatDb(p.preampDb) + " dB"
        p.bandsDb.forEachIndexed { i, v -> sliders[i].value = v }
    }

    private fun renderPresetList() {
        presetList.removeAllViews()
        store.presets.forEach { p ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                val pad = dp(8f).toInt()
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(if (p.id == editing.id) Palette.SELECTED else Palette.SURFACE)
                setOnClickListener { select(p) }
                setOnLongClickListener { presetActions(p); true }
            }
            row.addView(CurveView(this, thumbnail = true).apply { set(p, store.vlcExactCurve) },
                LinearLayout.LayoutParams(dp(64f).toInt(), dp(32f).toInt()))
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f).toInt(), 0, 0, 0)
            }
            labels.addView(text(p.name, 15f))
            labels.addView(text(when (p.source) {
                PresetSource.BUILTIN -> "Built-in"
                PresetSource.IMPORTED -> "Imported from VLC"
                PresetSource.USER -> "Custom"
            }, 12f, color = Palette.TEXT2))
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            presetList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4f).toInt() })
        }
    }

    // ---------------------------------------------------------------- Editing

    private fun select(p: Preset) {
        store.activeId = p.id
        editing = p
        EqController.applyLive(this, p)
        EqController.refresh(this) // notification + tile show the new name
        render()
    }

    /** Applies an edit to the active preset; built-ins are copied first. Pushes to the effect debounced. */
    private fun edit(change: (Preset) -> Preset) {
        if (editing.isBuiltin) {
            val copy = store.add(editing.copy(name = "${editing.name} (custom)", source = PresetSource.USER))
            store.activeId = copy.id
            editing = copy
            renderPresetList()
        }
        editing = change(editing)
        store.update(editing, persist = false)
        curve.set(editing, store.vlcExactCurve)
        preampLabel.text = formatDb(editing.preampDb) + " dB"
        editorTitle.text = editing.name
        handler.removeCallbacks(pushLive)
        handler.postDelayed(pushLive, 30)
    }

    private fun presetActions(p: Preset) {
        val actions = buildList {
            if (!p.isBuiltin) add("Rename")
            add("Duplicate")
            add("Export")
            if (!p.isBuiltin) add("Delete")
        }
        AlertDialog.Builder(this).setTitle(p.name).setItems(actions.toTypedArray()) { _, which ->
            val p = store.find(p.id) ?: return@setItems // the row may be stale after edits
            when (actions[which]) {
                "Rename" -> rename(p)
                "Duplicate" -> select(store.add(p.copy(source = PresetSource.USER)))
                "Export" -> export(p)
                "Delete" -> {
                    store.delete(p.id)
                    select(store.active)
                }
            }
        }.show()
    }

    private fun rename(p: Preset) {
        val input = EditText(this).apply { setText(p.name); setSelection(p.name.length) }
        AlertDialog.Builder(this).setTitle("Rename").setView(input)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != p.name) {
                    store.update(p.copy(name = store.uniqueName(name)))
                    if (p.id == editing.id) editing = store.active
                    render()
                    EqController.refresh(this)
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------------------------------------------------------------- Master switch, menu

    private fun onMasterToggled(on: Boolean) {
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            if (!store.askedBatteryOptimization) {
                store.askedBatteryOptimization = true
                requestIgnoreBatteryOptimizations()
            }
        }
        if (!EqController.setEnabled(this, on)) Toast.makeText(this, "Android refused to start the equalizer service", Toast.LENGTH_LONG).show()
        render()
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
        }
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val m = menu.menu
        val modes = listOf(EqMode.GLOBAL to "Global (recommended)", EqMode.SESSIONS to "Per-app sessions", EqMode.BOTH to "Both")
        modes.forEachIndexed { i, (mode, label) ->
            m.add(1, 100 + i, i, label).isChecked = store.mode == mode
        }
        m.setGroupCheckable(1, true, true)
        m.add(2, 200, 10, "Auto preamp (never boost above 0 dB)").apply { isCheckable = true; isChecked = store.autoPreamp }
        m.add(2, 201, 11, "VLC-exact curve (experimental)").apply { isCheckable = true; isChecked = store.vlcExactCurve }
        m.add(3, 300, 20, "Battery optimisation…")
        m.add(3, 301, 21, "Help")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                in 100..102 -> {
                    store.mode = modes[item.itemId - 100].first
                    if (store.mode != EqMode.GLOBAL) {
                        Toast.makeText(this, "Per-app mode only covers players that announce their audio session", Toast.LENGTH_LONG).show()
                    }
                }
                200 -> store.autoPreamp = !store.autoPreamp
                201 -> store.vlcExactCurve = !store.vlcExactCurve
                300 -> openBatterySettings()
                301 -> showHelp()
            }
            EqController.refresh(this)
            EqController.applyLive(this, editing)
            render()
            true
        }
        menu.show()
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            requestIgnoreBatteryOptimizations()
        }
    }

    private fun showHelp() {
        val tv = text(HELP, 14f).apply {
            val p = dp(20f).toInt()
            setPadding(p, p, p, p)
            Linkify.addLinks(this, Linkify.WEB_URLS)
            movementMethod = LinkMovementMethod.getInstance()
        }
        AlertDialog.Builder(this).setTitle("Help")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("OK", null).show()
    }

    // ---------------------------------------------------------------- Import / export

    private fun pickImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // Some file managers label .json as octet-stream or text, hence the */* fallback.
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "*/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    private fun export(p: Preset) {
        pendingExportId = p.id
        val safe = p.name.replace(Regex("[^A-Za-z0-9._ ()-]"), "_")
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "vlc_$safe.json")
        }
        startActivityForResult(intent, REQ_EXPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_IMPORT -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
                if (uris.isEmpty()) data.data?.let { uris += it }
                importUris(uris)
            }
            REQ_EXPORT -> {
                val p = pendingExportId?.let { store.find(it) } ?: return
                val uri = data.data ?: return
                try {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(VlcExporter.export(listOf(p)).toByteArray()) }
                    Toast.makeText(this, "Exported “${p.name}”", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Handles VIEW / SEND / SEND_MULTIPLE from VLC's share button or a file manager. */
    @Suppress("DEPRECATION")
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uris += it }
            Intent.ACTION_SEND -> {
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris += it }
                if (uris.isEmpty()) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { importTexts(listOf("Shared text" to it)); return }
            }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            else -> return
        }
        importUris(uris)
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val texts = uris.map { uri ->
            val name = uri.lastPathSegment ?: uri.toString()
            name to try {
                contentResolver.openInputStream(uri)!!.use { s ->
                    val bytes = s.readBytes()
                    if (bytes.size > MAX_IMPORT_BYTES) null else String(bytes)
                }
            } catch (e: Exception) {
                null
            }
        }
        importTexts(texts)
    }

    private fun importTexts(texts: List<Pair<String, String?>>) {
        val parsed = ArrayList<Preset>()
        val errors = ArrayList<String>()
        texts.forEach { (name, text) ->
            if (text == null) {
                errors += "$name: could not read the file"
                return@forEach
            }
            val r = VlcImporter.parse(text)
            parsed += r.presets
            errors += r.errors.map { if (texts.size > 1) "$name — $it" else it }
        }
        val added = store.addAll(parsed)
        added.firstOrNull()?.let { select(it) } ?: render()

        val msg = buildString {
            append(when (added.size) {
                0 -> "No presets imported."
                1 -> "Imported “${added[0].name}”."
                else -> "Imported ${added.size} presets."
            })
            if (errors.isNotEmpty()) append("\n\nSkipped:\n• ").append(errors.joinToString("\n• "))
            if (added.isNotEmpty()) append("\n\nTip: set VLC's own equalizer to Flat or off, otherwise both EQs stack.")
        }
        AlertDialog.Builder(this).setTitle("Import").setMessage(msg).setPositiveButton("OK", null).show()
    }

    companion object {
        private const val REQ_IMPORT = 1
        private const val REQ_EXPORT = 2
        private const val MAX_IMPORT_BYTES = 2 * 1024 * 1024

        private val HELP = """
            VLC Global EQ applies your preset to all audio on the phone using Android's built-in DynamicsProcessing effect on the global output mix. The sound is processed inside Android's audio server, so there is no added latency and this app uses almost no CPU.

            Importing: in VLC open the equalizer, export or SHARE a preset, and pick this app or the exported vlc_*.json file. Export writes the same format, so presets go back into VLC unchanged.

            Set VLC's own equalizer to Flat or turn it off, otherwise both equalizers stack.

            Known limits:
            • Audio sent to hardware offload or a direct output (some music players, bit-perfect USB DAC modes, some games) bypasses Android's effects. This can't be fixed without root.
            • Only one app can control the global mix at a time. Wavelet, RootlessJamesDSP, Dolby and other OEM sound effects may take it over; the status line then says "Lost control".
            • Per-app sessions mode only covers players that announce their audio session.

            If the equalizer stops after a while, your phone is killing background apps. Allow this app to ignore battery optimisation and see https://dontkillmyapp.com for your phone brand.
        """.trimIndent()
    }
}
