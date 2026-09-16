package com.example.audiostreamer.diagnostics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Custom Android View for rendering a real-time, rolling ~60-second graph of the
 * Estimated Receiver Playout Latency.
 *
 * Latency is an estimate derived from receiver jitter-buffer occupancy plus queued
 * AudioTrack playback frames. It does not measure network one-way latency, wall-clock
 * end-to-end latency, or physical speaker acoustic emission latency.
 *
 * Performance characteristics:
 * - Pre-allocated Paint and Path instances (zero heap allocations inside onDraw).
 * - Adaptive Y-scale with horizontal baseline guides (e.g., 50ms, 100ms, 200ms).
 * - Gracefully handles empty data, stream stoppage, and small mobile displays without clipping.
 */
class LatencyGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Preallocated Paint objects
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A38")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val targetLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A4A62")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#808096")
        textSize = dpToPx(10f)
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6") // Blue accent
        strokeWidth = dpToPx(2.2f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60A5FA")
        style = Paint.Style.FILL
    }

    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D4ED8")
        strokeWidth = dpToPx(2f)
        style = Paint.Style.STROKE
    }

    // Preallocated Paths
    private val linePath = Path()
    private val areaPath = Path()

    // Data buffer
    private var sampleData = FloatArray(0)
    private var sampleCount = 0
    private var currentLatencyMs = 0f
    private var targetWatermarkMs = 0f
    private var isIdle = true

    // Dimensions
    private var graphLeft = 0f
    private var graphTop = 0f
    private var graphRight = 0f
    private var graphBottom = 0f
    private var graphWidth = 0f
    private var graphHeight = 0f

    private var areaGradient: LinearGradient? = null

    init {
        setWillNotDraw(false)
    }

    /**
     * Updates the graph with a snapshot of chronological latency samples.
     * Thread-safe; triggers a view invalidation.
     */
    fun updateData(
        samples: FloatArray,
        currentMs: Float,
        targetMs: Float = 0f,
        idle: Boolean = false
    ) {
        if (sampleData.size < samples.size) {
            sampleData = FloatArray(samples.size)
        }
        System.arraycopy(samples, 0, sampleData, 0, samples.size)
        sampleCount = samples.size
        currentLatencyMs = currentMs
        targetWatermarkMs = targetMs
        isIdle = idle
        postInvalidate()
    }

    fun setIdleState(idle: Boolean) {
        isIdle = idle
        postInvalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padL = paddingLeft.toFloat() + dpToPx(4f)
        val padR = paddingRight.toFloat() + dpToPx(40f) // Space for right Y-axis labels
        val padT = paddingTop.toFloat() + dpToPx(8f)
        val padB = paddingBottom.toFloat() + dpToPx(20f) // Space for bottom X-axis labels

        graphLeft = padL
        graphTop = padT
        graphRight = w - padR
        graphBottom = h - padB
        graphWidth = (graphRight - graphLeft).coerceAtLeast(1f)
        graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)

        areaGradient = LinearGradient(
            0f, graphTop,
            0f, graphBottom,
            intArrayOf(Color.argb(70, 59, 130, 246), Color.argb(0, 59, 130, 246)),
            null,
            Shader.TileMode.CLAMP
        )
        areaPaint.shader = areaGradient
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (graphWidth <= 0f || graphHeight <= 0f) return

        // 1. Determine dynamic Y-axis maximum scale
        var maxVal = 80f
        if (!isIdle && sampleCount > 0) {
            for (i in 0 until sampleCount) {
                if (sampleData[i] > maxVal) {
                    maxVal = sampleData[i]
                }
            }
            if (targetWatermarkMs > maxVal) {
                maxVal = targetWatermarkMs
            }
        }
        // Round up max to nearest clean interval (50ms, 100ms, 150ms, 200ms, etc.)
        val maxY = (kotlin.math.ceil((maxVal * 1.15f) / 50f) * 50f).coerceIn(60f, 600f)

        // 2. Draw horizontal grid guide lines & Y-axis labels
        val stepY = if (maxY <= 100f) 25f else if (maxY <= 250f) 50f else 100f
        var gridYVal = stepY
        while (gridYVal <= maxY) {
            val y = graphBottom - (gridYVal / maxY) * graphHeight
            canvas.drawLine(graphLeft, y, graphRight, y, gridPaint)
            val label = "${gridYVal.toInt()}ms"
            canvas.drawText(label, graphRight + dpToPx(4f), y + dpToPx(3.5f), labelPaint)
            gridYVal += stepY
        }

        // Bottom baseline
        canvas.drawLine(graphLeft, graphBottom, graphRight, graphBottom, gridPaint)
        canvas.drawText("0ms", graphRight + dpToPx(4f), graphBottom + dpToPx(3.5f), labelPaint)

        // 3. Draw X-axis timeline markers (-60s, -30s, Now)
        canvas.drawText("-60s", graphLeft, height - dpToPx(4f), labelPaint)
        canvas.drawText("-30s", graphLeft + (graphWidth * 0.5f) - dpToPx(8f), height - dpToPx(4f), labelPaint)
        canvas.drawText("Now", graphRight - dpToPx(18f), height - dpToPx(4f), labelPaint)

        // 4. Draw Target Watermark guide line if available
        if (targetWatermarkMs in 1.0f..maxY) {
            val targetY = graphBottom - (targetWatermarkMs / maxY) * graphHeight
            canvas.drawLine(graphLeft, targetY, graphRight, targetY, targetLinePaint)
            canvas.drawText(
                "tgt ${targetWatermarkMs.toInt()}ms",
                graphLeft + dpToPx(4f),
                targetY - dpToPx(3f),
                labelPaint
            )
        }

        // 5. Draw Curve & Fill if active
        if (isIdle || sampleCount < 2) {
            // Draw empty / idle state text
            val msg = if (isIdle) "Receiver idle — start stream to monitor latency" else "Collecting initial samples..."
            val textWidth = labelPaint.measureText(msg)
            val textX = (graphLeft + graphRight - textWidth) / 2f
            val textY = (graphTop + graphBottom) / 2f
            canvas.drawText(msg, textX, textY, labelPaint)
            return
        }

        linePath.reset()
        areaPath.reset()

        val maxSlots = LatencyHistory.DEFAULT_CAPACITY // 120 slots
        val xStep = graphWidth / (maxSlots - 1).toFloat()
        // Align newest sample at the far right edge
        val startXOffset = graphRight - ((sampleCount - 1) * xStep)

        var lastX = 0f
        var lastY = 0f

        for (i in 0 until sampleCount) {
            val v = sampleData[i].coerceIn(0f, maxY)
            val x = startXOffset + (i * xStep)
            val y = graphBottom - (v / maxY) * graphHeight

            if (i == 0) {
                linePath.moveTo(x, y)
                areaPath.moveTo(x, graphBottom)
                areaPath.lineTo(x, y)
            } else {
                // Smooth cubic bezier segment
                val prevX = startXOffset + ((i - 1) * xStep)
                val prevV = sampleData[i - 1].coerceIn(0f, maxY)
                val prevY = graphBottom - (prevV / maxY) * graphHeight
                val cX = (prevX + x) / 2f
                linePath.cubicTo(cX, prevY, cX, y, x, y)
                areaPath.cubicTo(cX, prevY, cX, y, x, y)
            }

            if (i == sampleCount - 1) {
                lastX = x
                lastY = y
                areaPath.lineTo(x, graphBottom)
                areaPath.close()
            }
        }

        // Draw area fill & line stroke
        canvas.drawPath(areaPath, areaPaint)
        canvas.drawPath(linePath, linePaint)

        // Draw newest point marker at the right edge
        if (lastX > 0f) {
            canvas.drawCircle(lastX, lastY, dpToPx(5f), markerRingPaint)
            canvas.drawCircle(lastX, lastY, dpToPx(3f), markerPaint)
        }
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
}
