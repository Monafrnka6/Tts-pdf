package com.example.ttspdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class HighlightOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = 0x66FFCC00.toInt()
    }
    private var rects: List<RectF> = emptyList()

    fun setHighlightRects(newRects: List<RectF>) { rects = newRects; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (r in rects) canvas.drawRoundRect(r, 8f, 8f, paint)
    }
}