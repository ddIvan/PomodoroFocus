package com.ivan.pomodoro.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ivan.pomodoro.R

/**
 * 专注进度圆环。progress 表示已完成比例（0..1）。
 */
class ProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val ringWidth = 14f * density
    private val trackColor = ContextCompat.getColor(context, R.color.pomo_track)
    private val ringColor = ContextCompat.getColor(context, R.color.pomo_primary)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = ringWidth
    }
    private val rectF = RectF()
    private val startAngle = -90f

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var warnMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) - ringWidth) / 2f
        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)

        paint.color = trackColor
        canvas.drawCircle(cx, cy, radius, paint)

        paint.color = if (warnMode) ContextCompat.getColor(context, R.color.pomo_amber) else ringColor
        if (progress > 0f) {
            canvas.drawArc(rectF, startAngle, 360f * progress, false, paint)
        }
    }
}
