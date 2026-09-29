package jp.gpsaltimeter.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** 直近10分（1秒1点）の GPS 高度と気圧高度を描画するシンプルなグラフ */
class AltitudeChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val capacity = 600
    private val gps = ArrayList<Double?>()
    private val baro = ArrayList<Double?>()

    private val density = resources.displayMetrics.density
    private val gpsPaint = linePaint(0xFF4FC3F7.toInt())
    private val baroPaint = linePaint(0xFFFFB74D.toInt())
    private val gridPaint = Paint().apply {
        color = 0x33FFFFFF; strokeWidth = 1f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(154, 160, 166); textSize = 11f * density
    }

    private fun linePaint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; style = Paint.Style.STROKE; strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
    }

    fun add(gpsAlt: Double?, baroAlt: Double?) {
        gps.add(gpsAlt); baro.add(baroAlt)
        if (gps.size > capacity) { gps.removeAt(0); baro.removeAt(0) }
        invalidate()
    }

    fun clear() { gps.clear(); baro.clear(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 48f * density
        val right = width - 12f * density
        val top = 26f * density
        val bottom = height - 22f * density

        // 凡例
        canvas.drawText("Altitude [m]", left, 16f * density, textPaint)
        val lx = right - 110f * density
        canvas.drawLine(lx, 12f * density, lx + 14f * density, 12f * density, gpsPaint)
        canvas.drawText("GPS", lx + 18f * density, 16f * density, textPaint)
        canvas.drawLine(lx + 50f * density, 12f * density, lx + 64f * density, 12f * density, baroPaint)
        canvas.drawText("Baro", lx + 68f * density, 16f * density, textPaint)

        val all = (gps + baro).filterNotNull()
        if (all.isEmpty()) {
            canvas.drawText("Waiting for data…", left, (top + bottom) / 2, textPaint)
            return
        }
        var lo = all.min()
        var hi = all.max()
        val span = max(hi - lo, 10.0)
        val mid = (hi + lo) / 2
        lo = floor((mid - span * 0.6) / 5) * 5
        hi = ceil((mid + span * 0.6) / 5) * 5

        fun yOf(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
        fun xOf(i: Int) = left + (right - left) * i / (capacity - 1).toFloat()

        // グリッドと目盛
        for (k in 0..4) {
            val v = lo + (hi - lo) * k / 4
            val yy = yOf(v)
            canvas.drawLine(left, yy, right, yy, gridPaint)
            canvas.drawText(String.format(Locale.US, "%.0f", v), 4f * density, yy + 4f * density, textPaint)
        }
        canvas.drawText("-10 min", left, height - 6f * density, textPaint)
        canvas.drawText("now", right - 24f * density, height - 6f * density, textPaint)

        // データは右詰め（最新が右端）
        val offset = capacity - gps.size
        drawSeries(canvas, gps, offset, ::xOf, ::yOf, gpsPaint)
        drawSeries(canvas, baro, offset, ::xOf, ::yOf, baroPaint)
    }

    private fun drawSeries(
        c: Canvas, data: List<Double?>, offset: Int,
        fx: (Int) -> Float, fy: (Double) -> Float, p: Paint
    ) {
        val path = Path()
        var pen = false
        data.forEachIndexed { i, v ->
            if (v == null) { pen = false; return@forEachIndexed }
            val px = fx(i + offset); val py = fy(v)
            if (pen) path.lineTo(px, py) else path.moveTo(px, py)
            pen = true
        }
        c.drawPath(path, p)
    }
}
