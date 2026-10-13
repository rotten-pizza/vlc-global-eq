package app.vlcglobaleq.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import app.vlcglobaleq.audio.CurveMapper
import app.vlcglobaleq.data.Preset
import kotlin.math.ln
import kotlin.math.roundToInt

internal object Palette {
    val BG = Color.parseColor("#121212")
    val SURFACE = Color.parseColor("#1E1E1E")
    val SELECTED = Color.parseColor("#3A2A14")
    val ACCENT = Color.parseColor("#FF8800")
    val TEXT = Color.parseColor("#EEEEEE")
    val TEXT2 = Color.parseColor("#9E9E9E")
    val GRID = Color.parseColor("#333333")
}

internal fun Context.dp(v: Float) = v * resources.displayMetrics.density
internal fun View.dp(v: Float) = context.dp(v)

internal fun formatDb(v: Double): String {
    val r = (v * 10).roundToInt() / 10.0
    return (if (r > 0) "+" else "") + if (r == 0.0) "0" else "%.1f".format(r)
}

/** Vertical −20…+20 dB slider that draws its own value and frequency label. */
@SuppressLint("ViewConstructor")
class VerticalSlider(context: Context, private val label: String) : View(context) {
    var value = 0.0
        set(v) {
            field = v.coerceIn(Preset.MIN_DB, Preset.MAX_DB); invalidate()
        }
    var onChange: ((Double) -> Unit)? = null
    var onRelease: (() -> Unit)? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.GRID; strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT; strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT; textSize = dp(11f); textAlign = Paint.Align.CENTER }
    private val text2 = Paint(text).apply { color = Palette.TEXT2 }
    private val pad = dp(24f)

    init {
        contentDescription = "$label Hz"
        isFocusable = true
    }

    private fun yFor(v: Double) = pad + (height - 2 * pad) * ((Preset.MAX_DB - v) / (Preset.MAX_DB - Preset.MIN_DB)).toFloat()

    override fun onDraw(c: Canvas) {
        val x = width / 2f
        c.drawLine(x, pad, x, height - pad, track)
        c.drawLine(x, yFor(0.0), x, yFor(value), fill)
        c.drawCircle(x, yFor(value), dp(8f), thumb)
        c.drawText(formatDb(value), x, dp(14f), text)
        c.drawText(label, x, height - dp(6f), text2)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val frac = ((e.y - pad) / (height - 2 * pad)).coerceIn(0f, 1f)
                val v = ((Preset.MAX_DB - frac * (Preset.MAX_DB - Preset.MIN_DB)) * 10).roundToInt() / 10.0
                if (v != value) {
                    value = v
                    onChange?.invoke(v)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onRelease?.invoke()
        }
        return true
    }
}

/**
 * Plots a preset: the DynamicsProcessing step curve actually applied (solid) over the target
 * curve (faint) and VLC's 10 points. In [thumbnail] mode only the smooth curve is drawn.
 */
class CurveView(context: Context, private val thumbnail: Boolean = false) : View(context) {
    private var preset: Preset? = null
    private var exact = false

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Palette.ACCENT; strokeWidth = dp(if (thumbnail) 1.5f else 2f)
    }
    private val targetPaint = Paint(curvePaint).apply { alpha = 90; strokeWidth = dp(1.5f) }
    private val gridPaint = Paint().apply { color = Palette.GRID; strokeWidth = dp(1f) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT }
    private val path = Path()

    fun set(preset: Preset, vlcExact: Boolean) {
        this.preset = preset
        this.exact = vlcExact
        invalidate()
    }

    private val lnMin = ln(CurveMapper.MIN_HZ)
    private val lnMax = ln(CurveMapper.MAX_HZ)
    private fun xFor(f: Double) = (width * (ln(f) - lnMin) / (lnMax - lnMin)).toFloat()
    private fun yFor(db: Double): Float {
        val p = if (thumbnail) dp(2f) else dp(4f)
        return p + (height - 2 * p) * ((Preset.MAX_DB - db.coerceIn(Preset.MIN_DB, Preset.MAX_DB)) / 40.0).toFloat()
    }

    override fun onDraw(c: Canvas) {
        val p = preset ?: return
        val g = CurveMapper.curve(p, exact)
        if (!thumbnail) {
            for (db in listOf(-10.0, 0.0, 10.0)) c.drawLine(0f, yFor(db), width.toFloat(), yFor(db), gridPaint)
        }
        val steps = if (thumbnail) 48 else 160
        path.reset()
        for (i in 0..steps) {
            val f = CurveMapper.MIN_HZ * Math.pow(CurveMapper.MAX_HZ / CurveMapper.MIN_HZ, i / steps.toDouble())
            val x = xFor(f); val y = yFor(g(f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (thumbnail) {
            c.drawPath(path, curvePaint)
            return
        }
        c.drawPath(path, targetPaint)

        // The stair-step curve DynamicsProcessing actually applies.
        path.reset()
        var left = 0f
        CurveMapper.dpBands(p, exact).forEachIndexed { i, b ->
            val right = if (i == CurveMapper.DP_BAND_COUNT - 1) width.toFloat() else xFor(b.cutoffHz)
            val y = yFor(b.gainDb)
            if (i == 0) path.moveTo(left, y) else path.lineTo(left, y)
            path.lineTo(right, y)
            left = right
        }
        c.drawPath(path, curvePaint)

        p.bandSet.frequencies.forEachIndexed { i, f -> c.drawCircle(xFor(f), yFor(p.bandsDb[i]), dp(3f), dotPaint) }
    }
}
