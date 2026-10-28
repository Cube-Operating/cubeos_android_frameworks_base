/*
 * Copyright (C) 2026 CubeOS
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

package com.android.systemui.biometrics.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.android.systemui.res.R
import kotlin.math.max
import kotlin.math.min

/**
 * CubeOS: the passcode dots of BiometricPrompt's PIN sheet, as on the iOS passcode screen - one
 * filled dot per digit typed, a single hollow dot while empty (the PIN length is not known here).
 *
 * It only ever learns the entry's length (from PinPadViewBinder's TextWatcher on the real
 * R.id.lockPassword field), never the digits. Drawing is a handful of circles; the only animation
 * is a short shake when a wrong PIN clears the entry.
 */
class CubePinDotsView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val dotSize = resources.getDimension(R.dimen.cube_auth_pin_dot_size)
    private val dotGap = resources.getDimension(R.dimen.cube_auth_pin_dot_gap)
    private val ring = resources.getDimension(R.dimen.cube_auth_pin_dot_ring)

    private val fill =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = context.getColor(R.color.cube_auth_label)
        }
    private val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ring
            color = context.getColor(R.color.cube_auth_label)
            alpha = 230
        }

    private var count = 0
    private var shake: ObjectAnimator? = null

    /** Updates the number of digits entered; [rejected] shakes the row (a wrong PIN). */
    fun setCount(newCount: Int, rejected: Boolean = false) {
        if (rejected) shake()
        if (newCount == count) return
        count = newCount
        invalidate()
    }

    private fun shake() {
        val d = resources.displayMetrics.density * 18f
        shake?.cancel()
        shake =
            ObjectAnimator.ofFloat(this, TRANSLATION_X, 0f, d, -d, 0.7f * d, -0.7f * d, 0.35f * d, 0f)
                .setDuration(SHAKE_MS)
                .also { it.start() }
    }

    override fun onDetachedFromWindow() {
        shake?.cancel()
        translationX = 0f
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = dotSize / 2f
        val step = dotSize + dotGap
        // Never draw past the edges, however long the PIN.
        val fits = max(1, ((width - paddingLeft - paddingRight + dotGap) / step).toInt())
        val slots = min(max(count, 1), fits)
        val rowWidth = slots * dotSize + (slots - 1) * dotGap
        var cx = (width - rowWidth) / 2f + radius
        val cy = height / 2f
        for (i in 0 until slots) {
            if (i < count) {
                canvas.drawCircle(cx, cy, radius, fill)
            } else {
                canvas.drawCircle(cx, cy, radius - ring / 2f, stroke)
            }
            cx += step
        }
    }

    private companion object {
        const val SHAKE_MS = 330L
    }
}
