package dev.dfanso.lkrp2p.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import dev.dfanso.lkrp2p.core.ChartWindow
import dev.dfanso.lkrp2p.core.SeriesPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The palette of the reference card: near-black ground, emerald glow, mint line. */
object Palette {
    const val BASE = 0xFF131313.toInt()
    const val GLOW = 0xFF13804A.toInt()
    const val GLOW_MID = 0x8C0E5233.toInt()
    const val UP = 0xFF3BE29A.toInt()
    const val DOWN = 0xFFFF6B6B.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT_DIM = 0x99FFFFFF.toInt()
    const val GRID = 0x26FFFFFF
    const val AXIS = 0x1FFFFFFF
    const val STALE = 0xFFFFB547.toInt()
}

/**
 * Plain android.graphics drawing, so the widget (which can only show bitmaps)
 * and the app (which draws into a Compose canvas) render identically.
 */
object BackgroundPainter {

    fun draw(canvas: Canvas, width: Float, height: Float, cornerPx: Float, theme: ThemeColors) {
        val rect = RectF(0f, 0f, width, height)
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.base }
        canvas.drawRoundRect(rect, cornerPx, cornerPx, base)

        // The glow sits above the top edge, a third of the way across, and is
        // sized off the width so a tall widget keeps a dark lower half for the chart.
        val radius = max(width * 0.8f, min(height, width) * 0.9f)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.32f, -height * 0.04f, radius,
                intArrayOf(theme.glow, theme.glowMid, theme.glowMid and 0x00FFFFFF),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(rect, cornerPx, cornerPx, glow)
    }

    /**
     * The gradient is smooth, so it is rendered at reduced [scale] and stretched:
     * a full-resolution widget background would cost megabytes per update.
     */
    fun bitmap(widthPx: Int, heightPx: Int, cornerPx: Float, theme: ThemeColors, scale: Float = 0.5f): Bitmap {
        val w = max(1, (widthPx * scale).toInt())
        val h = max(1, (heightPx * scale).toInt())
        return createBitmap(w, h).also {
            draw(Canvas(it), w.toFloat(), h.toFloat(), cornerPx * scale, theme)
        }
    }
}

object ChartPainter {

    data class Spec(
        val points: List<SeriesPoint>,
        val window: ChartWindow,
        val nowSec: Long,
        /** A wider spacing than this between samples is drawn as a break. */
        val gapSec: Long,
        val lineColor: Int,
        val density: Float,
        val showLabels: Boolean,
        val emptyMessage: String = "Collecting data…",
        val zone: TimeZone = TimeZone.getDefault(),
        /** A point the user is touching, drawn with a crosshair. */
        val highlight: SeriesPoint? = null,
        /** The ground the chart sits on, for the highlight ring's fill. */
        val baseColor: Int = Palette.BASE,
        /** Window-independent tick labels for daily data: "d MMM" instead of "HH:mm". */
        val daily: Boolean = false,
        /** How far back the x axis reaches; defaults to the window's length. */
        val durationSec: Long = window.durationSec,
    )

    /** Where the plot sits and how time and price map onto it. */
    class Geometry(
        val left: Float, val right: Float, val top: Float, val bottom: Float,
        val startSec: Long, val endSec: Long, val yMin: Double, val yMax: Double,
    ) {
        fun x(ts: Long) = left + (right - left) * ((ts - startSec).toFloat() / (endSec - startSec))
        fun y(price: Double) = (bottom - (bottom - top) * ((price - yMin) / (yMax - yMin))).toFloat()

        /** The sample closest in time to a touch at [x]. */
        fun nearest(points: List<SeriesPoint>, x: Float): SeriesPoint? {
            val ts = startSec + ((x - left) / (right - left) * (endSec - startSec)).toLong()
            return points.minByOrNull { kotlin.math.abs(it.timestampSec - ts) }
        }
    }

    private fun labelPaint(density: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT_DIM
        textSize = 11.5f * density
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    fun geometry(width: Float, height: Float, spec: Spec): Geometry {
        val d = spec.density
        val labelBand = if (spec.showLabels) labelPaint(d).textSize + 10f * d else 0f
        val points = spec.points
        val endSec = spec.nowSec
        val windowStart = spec.nowSec - spec.durationSec
        val first = points.firstOrNull()?.timestampSec ?: windowStart
        // Span only the history that exists, but never less than an hour, so a
        // new install does not squeeze its first samples into a corner.
        val startSec = min(max(windowStart, first), endSec - 3_600)
        val lo = points.minOfOrNull { it.price } ?: 0.0
        val hi = points.maxOfOrNull { it.price } ?: 1.0
        val pad = if (hi - lo < 0.01) 0.5 else (hi - lo) * 0.18
        return Geometry(6f * d, width - 6f * d, 6f * d, height - labelBand, startSec, endSec, lo - pad, hi + pad)
    }

    private val tickSteps = longArrayOf(
        300, 600, 900, 1_800, 3_600, 7_200, 10_800, 14_400, 21_600, 43_200,
        86_400, 172_800, 259_200, 604_800, 1_209_600, 2_592_000,
    )

    fun draw(canvas: Canvas, width: Float, height: Float, spec: Spec) {
        val d = spec.density
        val labelPaint = labelPaint(d)
        val g = geometry(width, height, spec)
        val plotTop = g.top
        val plotBottom = g.bottom
        val plotLeft = g.left
        val plotRight = g.right
        if (plotBottom - plotTop < 8f * d || plotRight - plotLeft < 8f * d) return

        val points = spec.points
        val startSec = g.startSec
        val endSec = g.endSec
        fun x(ts: Long) = g.x(ts)

        // Dashed verticals at each tick, then the baseline.
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.GRID
            strokeWidth = max(1f, 0.8f * d)
            pathEffect = DashPathEffect(floatArrayOf(4f * d, 4f * d), 0f)
        }
        val labelWidth = labelPaint.measureText(if (spec.window == ChartWindow.MONTH || spec.daily) "30 Sep" else "00:00")
        val maxTicks = max(2, floor((plotRight - plotLeft) / (labelWidth * 1.45f)).toInt())
        val step = tickSteps.firstOrNull { (endSec - startSec) / it <= maxTicks && (!spec.daily || it >= 86_400) }
            ?: tickSteps.last()
        val format = SimpleDateFormat(
            when {
                step < 86_400 -> "HH:mm"
                spec.window == ChartWindow.WEEK && step == 86_400L -> "EEE"
                else -> "d MMM"
            },
            Locale.getDefault(),
        ).apply { timeZone = spec.zone }

        for (tick in ticks(startSec, endSec, step, spec.zone)) {
            val tx = x(tick)
            if (tx < plotLeft + labelWidth * 0.4f || tx > plotRight - labelWidth * 0.4f) continue
            canvas.drawLine(tx, plotTop, tx, plotBottom, gridPaint)
            if (spec.showLabels) {
                canvas.drawText(format.format(Date(tick * 1000)), tx, height - 3f * d, labelPaint)
            }
        }
        canvas.drawLine(0f, plotBottom, width, plotBottom, Paint().apply {
            color = Palette.AXIS
            strokeWidth = max(1f, 0.8f * d)
        })

        if (points.size < 2) {
            labelPaint.textSize = 12.5f * d
            canvas.drawText(spec.emptyMessage, width / 2, (plotTop + plotBottom) / 2, labelPaint)
            return
        }

        fun y(price: Double) = g.y(price)

        // One path per unbroken run; a gap stays a gap.
        val runs = mutableListOf<MutableList<SeriesPoint>>()
        for (point in points) {
            val last = runs.lastOrNull()?.lastOrNull()
            if (last == null || point.timestampSec - last.timestampSec > spec.gapSec) runs += mutableListOf(point)
            else runs.last() += point
        }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, plotTop, 0f, plotBottom,
                (spec.lineColor and 0x00FFFFFF) or 0x2E000000, spec.lineColor and 0x00FFFFFF,
                Shader.TileMode.CLAMP,
            )
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.lineColor
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * d
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        // Bridge each gap with a faint dashed line: the price is unknown there,
        // but the chart should still read as one series rather than scraps.
        val bridge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (spec.lineColor and 0x00FFFFFF) or 0x73000000
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * d
            strokeCap = Paint.Cap.ROUND
            pathEffect = DashPathEffect(floatArrayOf(2f * d, 4f * d), 0f)
        }
        runs.zipWithNext { a, b ->
            val from = a.last()
            val to = b.first()
            canvas.drawLine(x(from.timestampSec), y(from.price), x(to.timestampSec), y(to.price), bridge)
        }

        for (run in runs) {
            val line = Path()
            run.forEachIndexed { i, p ->
                if (i == 0) line.moveTo(x(p.timestampSec), y(p.price)) else line.lineTo(x(p.timestampSec), y(p.price))
            }
            if (run.size > 1) {
                val area = Path(line).apply {
                    lineTo(x(run.last().timestampSec), plotBottom)
                    lineTo(x(run.first().timestampSec), plotBottom)
                    close()
                }
                canvas.drawPath(area, fill)
                canvas.drawPath(line, stroke)
            } else {
                canvas.drawCircle(x(run[0].timestampSec), y(run[0].price), 1.6f * d, stroke)
            }
        }

        // The latest observation, haloed.
        val last = points.last()
        val lx = x(last.timestampSec)
        val ly = y(last.price)
        canvas.drawCircle(lx, ly, 5.5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (spec.lineColor and 0x00FFFFFF) or 0x40000000
        })
        canvas.drawCircle(lx, ly, 2.8f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spec.lineColor })

        spec.highlight?.let { h ->
            val hx = x(h.timestampSec)
            val hy = y(h.price)
            canvas.drawLine(hx, plotTop, hx, plotBottom, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x66FFFFFF
                strokeWidth = max(1f, d)
            })
            canvas.drawCircle(hx, hy, 6f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spec.baseColor or 0xFF000000.toInt() })
            canvas.drawCircle(hx, hy, 6f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = spec.lineColor
                style = Paint.Style.STROKE
                strokeWidth = 2.2f * d
            })
        }
    }

    /** Tick instants aligned to local wall-clock boundaries. */
    private fun ticks(startSec: Long, endSec: Long, step: Long, zone: TimeZone): List<Long> {
        val offset = zone.getOffset(startSec * 1000) / 1000
        var tick = ceil((startSec + offset).toDouble() / step).toLong() * step - offset
        return buildList {
            while (tick <= endSec) {
                add(tick)
                tick += step
            }
        }
    }

    /**
     * Renders at the widget's true pixel size so text is crisp, but caps the
     * pixel count: RemoteViews bitmaps share a per-update memory budget.
     */
    fun bitmap(widthPx: Int, heightPx: Int, spec: Spec, maxPixels: Int = 900_000): Bitmap {
        val scale = min(1f, kotlin.math.sqrt(maxPixels.toFloat() / max(1, widthPx * heightPx)))
        val w = max(1, (widthPx * scale).toInt())
        val h = max(1, (heightPx * scale).toInt())
        return createBitmap(w, h).also {
            draw(Canvas(it), w.toFloat(), h.toFloat(), spec.copy(density = spec.density * scale))
        }
    }
}
