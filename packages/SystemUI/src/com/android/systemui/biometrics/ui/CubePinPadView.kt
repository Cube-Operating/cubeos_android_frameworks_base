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

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.android.systemui.res.R
import kotlin.math.min

/**
 * CubeOS: the numeric keypad of BiometricPrompt's PIN sheet - the lock-screen bouncer's round
 * frosted keys (NumPadKey / NumPadButton) in a 4x3 grid, with OK bottom left and Delete bottom
 * right, as on the iOS passcode pad.
 *
 * This is the [IPinPad] hook AOSP left for a custom pad: CredentialPasswordViewBinder finds it by
 * R.id.pin_pad and PinPadViewBinder routes its keys into the existing R.id.lockPassword field, so
 * the PIN is held, checked and cleared exactly as before. The keys only report clicks.
 *
 * Keys are 78dp like the bouncer's and shrink (with their gaps) when the height or width is short,
 * e.g. in landscape.
 */
class CubePinPadView(context: Context, attrs: AttributeSet?) : ViewGroup(context, attrs), IPinPad {

    private val keySize = resources.getDimensionPixelSize(R.dimen.cube_auth_pin_key_size)
    private val colGap = resources.getDimensionPixelSize(R.dimen.cube_auth_pin_key_gap)
    private val rowGap = resources.getDimensionPixelSize(R.dimen.cube_auth_pin_row_gap)

    /** Grid order: 1-9, then OK, 0, Delete. */
    private lateinit var cells: List<View>
    private lateinit var digitKeys: List<Pair<View, String>>
    private lateinit var okKey: View
    private lateinit var deleteKey: View

    private var cell = keySize
    private var hGap = colGap
    private var vGap = rowGap

    override fun onFinishInflate() {
        super.onFinishInflate()
        val digitIds =
            intArrayOf(
                R.id.key0,
                R.id.key1,
                R.id.key2,
                R.id.key3,
                R.id.key4,
                R.id.key5,
                R.id.key6,
                R.id.key7,
                R.id.key8,
                R.id.key9,
            )
        digitKeys = digitIds.mapIndexed { digit, id -> requireViewById<View>(id) to "$digit" }
        okKey = requireViewById(R.id.key_enter)
        deleteKey = requireViewById(R.id.delete_button)
        cells = digitKeys.subList(1, 10).map { it.first } + listOf(okKey, digitKeys[0].first, deleteKey)

        // The bouncer tints its Delete glyph from the day/night palette; this sheet is always dark.
        (deleteKey as? ImageView)?.imageTintList =
            ColorStateList.valueOf(context.getColor(R.color.cube_auth_label))
    }

    override fun setPinPadClickListener(pinPadClickListener: PinPadClickListener) {
        digitKeys.forEach { (key, digit) ->
            key.setOnClickListener { pinPadClickListener.onDigitKeyClick(digit) }
        }
        deleteKey.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            pinPadClickListener.onBackspaceClick()
        }
        okKey.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            pinPadClickListener.onEnterKeyClick()
        }
    }

    /**
     * Shows Delete once a digit is in, and OK once the entry could be a PIN (4+ digits, the
     * shortest PIN Android allows), as iOS does on a custom-length numeric code. A hardware Enter
     * still submits through the field whatever the length.
     */
    fun setEntryLength(length: Int) {
        deleteKey.visibility = if (length > 0) VISIBLE else INVISIBLE
        okKey.visibility = if (length >= MIN_PIN_LENGTH) VISIBLE else INVISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fullW = 3 * keySize + 2 * colGap
        val fullH = 4 * keySize + 3 * rowGap
        var scale = 1f
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val w = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            scale = min(scale, w.toFloat() / fullW)
        }
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val h = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
            scale = min(scale, h.toFloat() / fullH)
        }
        scale = scale.coerceIn(0f, 1f)
        cell = (keySize * scale).toInt()
        hGap = (colGap * scale).toInt()
        vGap = (rowGap * scale).toInt()

        val spec = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY)
        cells.forEach { it.measure(spec, spec) }

        val w = 3 * cell + 2 * hGap + paddingLeft + paddingRight
        val h = 4 * cell + 3 * vGap + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val gridW = 3 * cell + 2 * hGap
        val gridH = 4 * cell + 3 * vGap
        val left0 = paddingLeft + (width - paddingLeft - paddingRight - gridW) / 2
        val top0 = paddingTop + (height - paddingTop - paddingBottom - gridH) / 2
        // A keypad keeps its order in RTL locales too, as the bouncer's does.
        cells.forEachIndexed { i, child ->
            val col = i % 3
            val row = i / 3
            val cellLeft = left0 + col * (cell + hGap)
            val cellTop = top0 + row * (cell + vGap)
            // NumPadButton (Delete) measures shorter than wide; centre every key in its cell.
            val x = cellLeft + (cell - child.measuredWidth) / 2
            val y = cellTop + (cell - child.measuredHeight) / 2
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    override fun shouldDelayChildPressedState() = false

    private companion object {
        const val MIN_PIN_LENGTH = 4
    }
}
