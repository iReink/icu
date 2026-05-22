package com.example.icu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class ReticleDimOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(122, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val path = Path()
    private var holeCenterX: Float? = null
    private var holeCenterY: Float? = null

    fun setHoleCenter(x: Float?, y: Float?) {
        holeCenterX = x
        holeCenterY = y
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = holeCenterX ?: width / 2f
        val centerY = holeCenterY ?: height / 2f
        val density = resources.displayMetrics.density
        val holeRadius = 18f * density

        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addCircle(centerX, centerY, holeRadius, Path.Direction.CCW)
        canvas.drawPath(path, paint)
    }
}
