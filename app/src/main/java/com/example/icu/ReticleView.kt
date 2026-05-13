package com.example.icu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class ReticleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val density = resources.displayMetrics.density
        val radius = 22f * density
        val gap = 6f * density

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (0.5f * density).coerceAtLeast(1f)
        paint.color = Color.argb(190, 214, 38, 42)
        canvas.drawCircle(centerX, centerY, radius, paint)
        canvas.drawLine(centerX - radius, centerY, centerX - gap, centerY, paint)
        canvas.drawLine(centerX + gap, centerY, centerX + radius, centerY, paint)
        canvas.drawLine(centerX, centerY - radius, centerX, centerY - gap, paint)
        canvas.drawLine(centerX, centerY + gap, centerX, centerY + radius, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(214, 38, 42)
        canvas.drawCircle(centerX, centerY, 2.5f * density, paint)
    }
}
