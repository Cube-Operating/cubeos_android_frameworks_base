/*
 * Copyright (C) 2026 The CubeOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.volume.cube

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

/**
 * The CubeOS volume pill. Sizes (dp) and springs follow the measured behaviour of the iOS 26
 * volume HUD (SpringBoard's elastic HUD, read with REA from the iOS 26.3 shared cache):
 *
 *  - appears at 47 x 148, 10 from the screen edge beside the volume buttons, sliding in
 *    from off screen (response 0.478 s, critically damped);
 *  - swells to 58 x 170 while touched (response 0.408 s);
 *  - stretches 18.2 % past the top or bottom on a key press there, 10.5 % while dragging,
 *    and springs back;
 *  - after 1 s it deflates into a 7 x 134 line (damping 1.22, slow), then leaves 0.7 s later;
 *  - corners are 0.32 of the width.
 *
 * Original code; nothing of Apple's is used. The glyphs are drawn here.
 */
@SuppressLint("ViewConstructor")
class CubeVolumeHudView(context: Context) : View(context) {

    companion object {
        const val DISMISS_MS = 1000L
        const val POST_DEFLATE_MS = 700L

        private const val BASE_W = 47f
        private const val BASE_H = 148f
        private const val TOUCH_W = 58f
        private const val TOUCH_H = 170f
        private const val THIN_W = 7f
        private const val THIN_H = 134f
        private const val EDGE = 10f
        private const val KEY_STRETCH = 0.182f
        private const val DRAG_STRETCH = 0.105f
        private const val CORNER = 0.32f
        /** Where the volume buttons are, as a fraction of the screen height (Cube Phone: left, upper third). */
        private const val BUTTONS_Y = 0.30f

        /** iOS-style spring (response in seconds, damping ratio) as an Android spring. */
        fun spring(response: Float, damping: Float): SpringForce =
            SpringForce().setStiffness(((2 * PI / response) * (2 * PI / response)).toFloat()).setDampingRatio(damping)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    // ---------- animated values ----------

    private var x0 = -dp(BASE_W + EDGE)   // pill's left edge
    private var w = dp(BASE_W)
    private var h = dp(BASE_H)
    private var level = 0f                // 0..1, drawn
    private var stretchAmt = 0f           // -1..1: + stretched up, - down
    private var glyphAlpha = 1f

    private fun prop(get: () -> Float, set: (Float) -> Unit) = object : FloatPropertyCompat<CubeVolumeHudView>("v") {
        override fun getValue(o: CubeVolumeHudView) = get()
        override fun setValue(o: CubeVolumeHudView, v: Float) { set(v); invalidatePill() }
    }

    private val xAnim = SpringAnimation(this, prop({ x0 }, { x0 = it }))
    private val wAnim = SpringAnimation(this, prop({ w }, { w = it }))
    private val hAnim = SpringAnimation(this, prop({ h }, { h = it }))
    private val levelAnim = SpringAnimation(this, prop({ level }, { level = it }))
        .setSpring(spring(0.30f, 1f))
    private val stretchAnim = SpringAnimation(this, prop({ stretchAmt }, { stretchAmt = it }))
    private val glyphAnim = SpringAnimation(this, prop({ glyphAlpha }, { glyphAlpha = it }))
        .setSpring(spring(0.30f, 1f))

    private fun to(a: SpringAnimation, value: Float, response: Float, damping: Float) {
        a.spring = spring(response, damping).setFinalPosition(value)
        a.start()
    }

    // ---------- drawing ----------

    private var blur: BackgroundBlurDrawable? = null
    private val pill = RectF()
    private val pillPath = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 38, 38, 42) }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private var ringer = false
    private var muted = false

    fun windowWidthPx() = dp(EDGE + TOUCH_W + 24f).toInt()
    fun windowHeightPx() = dp(TOUCH_H * (1 + KEY_STRETCH) + 48f).toInt()
    fun windowTopPx() = (resources.displayMetrics.heightPixels * BUTTONS_Y - windowHeightPx() / 2f).toInt()

    fun pillBounds(): Rect? = if (pill.isEmpty) null else Rect(
        pill.left.toInt(), pill.top.toInt(), pill.right.toInt(), pill.bottom.toInt())

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        blur = rootView.viewRootImpl?.createBackgroundBlurDrawable()?.apply {
            setBlurRadius(dp(30f).toInt())
            setColor(Color.TRANSPARENT)
        }
    }

    override fun onDetachedFromWindow() {
        blur = null
        super.onDetachedFromWindow()
    }

    fun onConfigurationChanged() {
        requestLayout()
        invalidatePill()
    }

    private fun invalidatePill() = invalidate()

    override fun onDraw(canvas: Canvas) {
        // Stretch keeps the far end in place and grows towards the press, a little narrower.
        val s = stretchAmt
        val sh = h * (1 + abs(s))
        val sw = w * (1 - abs(s) * 0.25f)
        val cy = height / 2f
        val top = if (s >= 0) cy + h / 2 - sh else cy - h / 2
        pill.set(x0, top, x0 + sw, top + sh)
        if (pill.right <= 0f) { pill.setEmpty(); return }
        val radius = min(sw * CORNER, sw / 2)

        blur?.let {
            it.setBounds(pill.left.toInt(), pill.top.toInt(), pill.right.toInt(), pill.bottom.toInt())
            it.setCornerRadius(radius)
            it.draw(canvas)
        }
        pillPath.reset()
        pillPath.addRoundRect(pill, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(pillPath)
        canvas.drawRect(pill, tintPaint)
        val fillTop = pill.bottom - pill.height() * level.coerceIn(0f, 1f)
        canvas.drawRect(pill.left, fillTop, pill.right, pill.bottom, fillPaint)
        canvas.restore()

        // The glyph at the foot, dark where the fill covers it; it fades as the pill thins.
        val a = (glyphAlpha * ((sw - dp(THIN_W)) / dp(BASE_W - THIN_W)).coerceIn(0f, 1f))
        if (a > 0.02f) {
            val gs = min(sw * 0.42f, dp(20f))
            val gcx = pill.centerX()
            val gcy = pill.bottom - dp(22f)
            val covered = fillTop < gcy - gs / 2
            val c = if (covered) Color.argb((a * 230).toInt(), 28, 28, 30) else Color.argb((a * 255).toInt(), 255, 255, 255)
            if (ringer) drawBell(canvas, gcx, gcy, gs, c) else drawSpeaker(canvas, gcx, gcy, gs, c)
        }
    }

    /** A speaker with sound waves for the level (none, and a slash, when muted). */
    private fun drawSpeaker(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        glyphPaint.color = color
        glyphStroke.color = color
        glyphStroke.strokeWidth = size * 0.11f
        val u = size / 10f
        val body = Path().apply {
            moveTo(cx - 4.6f * u, cy - 1.7f * u)
            lineTo(cx - 2.6f * u, cy - 1.7f * u)
            lineTo(cx + 0.2f * u, cy - 4.3f * u)
            lineTo(cx + 0.2f * u, cy + 4.3f * u)
            lineTo(cx - 2.6f * u, cy + 1.7f * u)
            lineTo(cx - 4.6f * u, cy + 1.7f * u)
            close()
        }
        canvas.drawPath(body, glyphPaint)
        if (muted) {
            canvas.drawLine(cx + 1.8f * u, cy - 2.2f * u, cx + 4.8f * u, cy + 2.2f * u, glyphStroke)
            canvas.drawLine(cx + 4.8f * u, cy - 2.2f * u, cx + 1.8f * u, cy + 2.2f * u, glyphStroke)
            return
        }
        val waves = when { level > 0.66f -> 3; level > 0.33f -> 2; else -> 1 }
        for (i in 1..waves) {
            val rr = (1.6f + 1.7f * i) * u
            val oval = RectF(cx + 0.2f * u - rr, cy - rr, cx + 0.2f * u + rr, cy + rr)
            canvas.drawArc(oval, -40f, 80f, false, glyphStroke)
        }
    }

    /** A bell (the ringer), crossed out when silent. */
    private fun drawBell(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        glyphPaint.color = color
        glyphStroke.color = color
        glyphStroke.strokeWidth = size * 0.11f
        val u = size / 10f
        val bell = Path().apply {
            moveTo(cx - 3.6f * u, cy + 2.6f * u)
            cubicTo(cx - 3.6f * u, cy - 4.6f * u, cx + 3.6f * u, cy - 4.6f * u, cx + 3.6f * u, cy + 2.6f * u)
            lineTo(cx + 4.6f * u, cy + 3.6f * u)
            lineTo(cx - 4.6f * u, cy + 3.6f * u)
            close()
        }
        canvas.drawPath(bell, glyphPaint)
        canvas.drawCircle(cx, cy + 4.6f * u, 1.1f * u, glyphPaint)
        if (muted) canvas.drawLine(cx - 4.6f * u, cy - 4.4f * u, cx + 4.6f * u, cy + 4.8f * u, glyphStroke)
    }

    // ---------- states ----------

    fun appear() {
        stretchAnim.cancel(); stretchAmt = 0f
        x0 = -dp(BASE_W + EDGE)
        w = dp(BASE_W); h = dp(BASE_H)
        to(xAnim, dp(EDGE), 0.478f, 1f)
        glyphAnim.animateToFinalPosition(1f)
    }

    /** Pressed again while it's showing (and possibly thinning): back to full size. */
    fun reinflate() {
        to(wAnim, dp(BASE_W), 0.544f, 1f)
        to(hAnim, dp(BASE_H), 0.544f, 1f)
        to(xAnim, dp(EDGE), 0.478f, 1f)
    }

    fun deflate() {
        to(wAnim, dp(THIN_W), 2.75f * 0.18f, 1.22f)
        to(hAnim, dp(THIN_H), 2.75f * 0.18f, 1.22f)
    }

    fun disappear() {
        to(xAnim, -dp(BASE_W + EDGE + 20f), 0.408f, 1f)
        xAnim.addEndListener(object : androidx.dynamicanimation.animation.DynamicAnimation.OnAnimationEndListener {
            override fun onAnimationEnd(a: androidx.dynamicanimation.animation.DynamicAnimation<*>?, canceled: Boolean, v: Float, vel: Float) {
                xAnim.removeEndListener(this)
                if (!canceled) { w = dp(BASE_W); h = dp(BASE_H); invalidate() }
            }
        })
    }

    fun setLevel(value: Float, ringer: Boolean, muted: Boolean, animate: Boolean) {
        this.ringer = ringer
        this.muted = muted
        contentDescription = "Volume ${Math.round(value * 100)}%"
        if (animate) levelAnim.animateToFinalPosition(value) else { levelAnim.cancel(); level = value; invalidate() }
    }

    /** Pressed past the top ([up]) or the bottom: stretch that way and spring back. */
    fun stretch(up: Boolean) {
        val target = if (up) KEY_STRETCH else -KEY_STRETCH
        stretchAnim.spring = spring(0.18f, 1f).setFinalPosition(target)
        stretchAnim.start()
        stretchAnim.addEndListener(object : androidx.dynamicanimation.animation.DynamicAnimation.OnAnimationEndListener {
            override fun onAnimationEnd(a: androidx.dynamicanimation.animation.DynamicAnimation<*>?, canceled: Boolean, v: Float, vel: Float) {
                stretchAnim.removeEndListener(this)
                to(stretchAnim, 0f, 0.408f, 0.62f)
            }
        })
    }

    // ---------- touch ----------

    var onUserLevel: (Float) -> Unit = {}
    var onInteracting: (Boolean) -> Unit = {}
    private var lastStep = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (pill.isEmpty || !pill.contains(e.x, e.y)) return false
                onInteracting(true)
                to(wAnim, dp(TOUCH_W), 0.408f, 1f)
                to(hAnim, dp(TOUCH_H), 0.408f, 1f)
                follow(e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> { follow(e.y); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                to(stretchAnim, 0f, 0.408f, 0.62f)
                to(wAnim, dp(BASE_W), 0.544f, 1f)
                to(hAnim, dp(BASE_H), 0.544f, 1f)
                onInteracting(false)
                return true
            }
        }
        return false
    }

    /** The finger sets the level; past either end the pill stretches after it, damped. */
    private fun follow(y: Float) {
        val cy = height / 2f
        val bottom = cy + h / 2
        val raw = (bottom - y) / h
        val v = raw.coerceIn(0f, 1f)
        val over = when { raw > 1f -> raw - 1f; raw < 0f -> raw; else -> 0f }
        stretchAnim.cancel()
        stretchAmt = (over / (1 + abs(over))) * DRAG_STRETCH * 2.5f
        stretchAmt = stretchAmt.coerceIn(-DRAG_STRETCH, DRAG_STRETCH)
        levelAnim.cancel(); level = v
        val step = Math.round(v * 100)
        if ((v == 0f || v == 1f) && step != lastStep) performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
        lastStep = step
        onUserLevel(v)
        invalidate()
    }
}