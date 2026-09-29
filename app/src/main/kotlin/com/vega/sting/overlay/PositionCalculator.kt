package com.vega.sting.overlay

import android.graphics.Paint
import android.graphics.PointF

object PositionCalculator {
    fun calculate(
        position: OverlayPosition,
        text: String,
        paint: Paint,
        width: Int,
        height: Int,
        margin: Float
    ): PointF {
        val textWidth = paint.measureText(text)
        val metrics = paint.fontMetrics
        val topBaseline = margin - metrics.ascent
        val bottomBaseline = height - margin - metrics.descent

        val x = when (position) {
            OverlayPosition.TOP_LEFT,
            OverlayPosition.BOTTOM_LEFT -> margin
            OverlayPosition.TOP_CENTER,
            OverlayPosition.BOTTOM_CENTER -> (width - textWidth) / 2f
            OverlayPosition.TOP_RIGHT,
            OverlayPosition.BOTTOM_RIGHT -> width - textWidth - margin
        }

        val y = when (position) {
            OverlayPosition.TOP_LEFT,
            OverlayPosition.TOP_CENTER,
            OverlayPosition.TOP_RIGHT -> topBaseline
            OverlayPosition.BOTTOM_LEFT,
            OverlayPosition.BOTTOM_CENTER,
            OverlayPosition.BOTTOM_RIGHT -> bottomBaseline
        }

        return PointF(x.coerceAtLeast(margin), y)
    }
}
