package com.v2plus.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

class SaturationValueView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var hue: Float = 210f
        set(value) {
            field = value
            satShader = null
            invalidate()
        }

    var saturation: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var value: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var onChanged: ((saturation: Float, value: Float, committed: Boolean) -> Unit)? = null

    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val clipPath = Path()
    private val bounds = RectF()
    private var satShader: LinearGradient? = null
    private var valShader: LinearGradient? = null
    private val corner = 16f * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(bounds, corner, corner, Path.Direction.CW)
        valShader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.TRANSPARENT, Color.BLACK,
            Shader.TileMode.CLAMP
        )
        satShader = null
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        if (satShader == null) {
            satShader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                Color.WHITE, hueColor,
                Shader.TileMode.CLAMP
            )
        }
        satPaint.shader = satShader
        valPaint.shader = valShader
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(bounds, satPaint)
        canvas.drawRect(bounds, valPaint)
        canvas.restore()

        val cx = saturation * width
        val cy = (1f - value) * height
        val r = 8f * resources.displayMetrics.density
        thumbStroke.strokeWidth = 2.5f * resources.displayMetrics.density
        thumbFill.color = Color.HSVToColor(floatArrayOf(hue, saturation, value))
        canvas.drawCircle(cx, cy, r, thumbFill)
        canvas.drawCircle(cx, cy, r, thumbStroke)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_MOVE ||
            event.actionMasked == MotionEvent.ACTION_UP
        ) {
            val w = max(1, width)
            val h = max(1, height)
            saturation = (event.x / w).coerceIn(0f, 1f)
            value = 1f - (event.y / h).coerceIn(0f, 1f)
            onChanged?.invoke(saturation, value, event.actionMasked == MotionEvent.ACTION_UP)
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        return super.onTouchEvent(event)
    }
}

class HueBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var hue: Float = 210f
        set(value) {
            field = value.coerceIn(0f, 360f)
            invalidate()
        }

    var onHueChanged: ((hue: Float, committed: Boolean) -> Unit)? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val rainbow = intArrayOf(
        0xFFFF0000.toInt(),
        0xFFFFFF00.toInt(),
        0xFF00FF00.toInt(),
        0xFF00FFFF.toInt(),
        0xFF0000FF.toInt(),
        0xFFFF00FF.toInt(),
        0xFFFF0000.toInt()
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            rainbow, null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val radius = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, barPaint)
        val cx = (hue / 360f) * width
        val cy = height / 2f
        val r = radius - 1f
        thumbFill.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        thumbStroke.strokeWidth = 2.5f * resources.displayMetrics.density
        canvas.drawCircle(cx, cy, r, thumbFill)
        canvas.drawCircle(cx, cy, r, thumbStroke)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_MOVE ||
            event.actionMasked == MotionEvent.ACTION_UP
        ) {
            val w = max(1, width)
            hue = (event.x / w).coerceIn(0f, 1f) * 360f
            onHueChanged?.invoke(hue, event.actionMasked == MotionEvent.ACTION_UP)
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        return super.onTouchEvent(event)
    }
}
