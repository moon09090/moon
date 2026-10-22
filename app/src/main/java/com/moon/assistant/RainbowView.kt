package com.moon.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/** Animated, transparent rainbow glow that fades out toward the top. */
class RainbowView(context: Context) : View(context) {
    private val colors = intArrayOf(
        0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFE600.toInt(),
        0xFF34C759.toInt(), 0xFF00C7FF.toInt(), 0xFF5856D6.toInt(),
        0xFFBF5AF2.toInt(), 0xFFFF3B30.toInt()
    )
    private val rainbowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 210 }
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val shaderMatrix = Matrix()
    private var rainbow: LinearGradient? = null
    private var shift = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3500
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { shift = it.animatedValue as Float; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        rainbow = LinearGradient(0f, 0f, w.toFloat(), 0f, colors, null, Shader.TileMode.REPEAT)
        maskPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x00000000, 0xB0000000.toInt(), 0xF0000000.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        shaderMatrix.setTranslate(shift * w, 0f)
        rainbow?.setLocalMatrix(shaderMatrix)
        rainbowPaint.shader = rainbow
        canvas.drawRect(0f, 0f, w, h, rainbowPaint)
        canvas.drawRect(0f, 0f, w, h, maskPaint)
        canvas.restoreToCount(layer)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
}
