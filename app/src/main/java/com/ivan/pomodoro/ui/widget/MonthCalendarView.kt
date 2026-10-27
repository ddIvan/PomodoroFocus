package com.ivan.pomodoro.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.ivan.pomodoro.R
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月历视图：周一开始，每格显示日号与当日番茄数。
 */
class MonthCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val cellHeight = 52f * density
    private val rows = 6

    private var month: YearMonth = YearMonth.now()
    private var counts: Map<LocalDate, Int> = emptyMap()
    private var selected: LocalDate? = LocalDate.now()
    private val today = LocalDate.now()

    var onDayClick: ((LocalDate) -> Unit)? = null

    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15f * density
        color = ContextCompat.getColor(context, R.color.pomo_text)
    }
    private val dayTypefaceNormal = Typeface.create("sans-serif", Typeface.NORMAL)
    private val dayTypefaceBold = Typeface.create("sans-serif-medium", Typeface.BOLD)
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
        color = ContextCompat.getColor(context, R.color.pomo_primary)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.pomo_primary_light)
    }
    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = ContextCompat.getColor(context, R.color.pomo_primary)
    }
    private val rectF = RectF()
    private val colorPrimary = ContextCompat.getColor(context, R.color.pomo_primary)
    private val colorMuted = ContextCompat.getColor(context, R.color.pomo_text_second)

    fun submit(newMonth: YearMonth, data: Map<LocalDate, Int>, selectedDate: LocalDate?) {
        month = newMonth
        counts = data
        selected = selectedDate
        invalidate()
    }

    fun setSelected(date: LocalDate?) {
        selected = date
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (cellHeight * rows).toInt()
        setMeasuredDimension(width, height)
    }

    private fun columns(): Int = 7

    private fun cellWidth(): Float = width / 7f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val firstDay = month.atDay(1)
        val offset = firstDay.dayOfWeek.value - 1
        val daysInMonth = month.lengthOfMonth()
        val cellW = cellWidth()

        for (day in 1..daysInMonth) {
            val index = offset + day - 1
            val row = index / columns()
            val col = index % columns()
            if (row >= rows) continue
            val date = month.atDay(day)
            val left = col * cellW
            val top = row * cellHeight
            rectF.set(left + 4f * density, top + 4f * density, left + cellW - 4f * density, top + cellHeight - 4f * density)

            if (selected == date) {
                canvas.drawRoundRect(rectF, 12f * density, 12f * density, selectedPaint)
            } else if (date == today) {
                canvas.drawRoundRect(rectF, 12f * density, 12f * density, todayPaint)
            }

            val isSelected = selected == date
            dayPaint.color = when {
                isSelected -> colorPrimary
                date == today -> colorPrimary
                else -> colorMuted
            }
            dayPaint.typeface = if (isSelected || date == today) dayTypefaceBold else dayTypefaceNormal
            val cx = left + cellW / 2f
            val baseline = top + cellHeight / 2f + 3f * density
            canvas.drawText(day.toString(), cx, baseline, dayPaint)

            val count = counts[date] ?: 0
            if (count > 0) {
                canvas.drawText(getTomatoLabel(count), cx, top + cellHeight / 2f + 17f * density, countPaint)
            }
        }
    }

    private fun getTomatoLabel(count: Int): String = if (count > 99) "99+" else "·$count"

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val col = (event.x / cellWidth()).toInt().coerceIn(0, 6)
            val row = (event.y / cellHeight).toInt().coerceIn(0, rows - 1)
            val offset = month.atDay(1).dayOfWeek.value - 1
            val day = row * 7 + col - offset + 1
            if (day in 1..month.lengthOfMonth()) {
                val date = month.atDay(day)
                selected = date
                invalidate()
                onDayClick?.invoke(date)
            }
            return true
        }
        return true
    }
}
