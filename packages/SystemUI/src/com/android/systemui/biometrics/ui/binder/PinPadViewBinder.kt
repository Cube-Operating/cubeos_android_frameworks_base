/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.biometrics.ui.binder

import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.ImeAwareEditText
import com.android.internal.widget.LockscreenCredential
import com.android.systemui.res.R
import com.android.systemui.biometrics.ui.CredentialPasswordView
import com.android.systemui.biometrics.ui.CubePinDotsView
import com.android.systemui.biometrics.ui.CubePinPadView
import com.android.systemui.biometrics.ui.IPinPad
import com.android.systemui.biometrics.ui.PinPadClickListener

/** Binder for IPinPad */
object PinPadViewBinder {
    /** Implements a PinPadClickListener inside a pin pad */
    @JvmStatic
    fun bind(view: IPinPad, credentialPasswordView: CredentialPasswordView) {
        val passwordField: ImeAwareEditText =
            credentialPasswordView.requireViewById(R.id.lockPassword)
        view.setPinPadClickListener(
            object : PinPadClickListener {

                override fun onDigitKeyClick(digit: String?) {
                    passwordField.append(digit)
                }

                override fun onBackspaceClick() {
                    val pin = LockscreenCredential.createPinOrNone(passwordField.text)
                    if (pin.size() > 0) {
                        passwordField.text.delete(
                            passwordField.selectionEnd - 1,
                            passwordField.selectionEnd
                        )
                    }
                    pin.zeroize()
                }

                override fun onEnterKeyClick() {
                    passwordField.dispatchKeyEvent(
                        KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)
                    )
                    passwordField.dispatchKeyEvent(
                        KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)
                    )
                }
            }
        )
        bindCubeDots(passwordField, credentialPasswordView, view as? CubePinPadView)
    }

    /**
     * CubeOS: mirrors the PIN field's length (never its digits) onto the passcode dots and the
     * pad's Delete/OK keys. A jump from 2+ digits straight to none is the binder clearing a wrong
     * PIN (backspace removes one at a time), so the dots shake.
     */
    private fun bindCubeDots(
        passwordField: ImeAwareEditText,
        credentialPasswordView: CredentialPasswordView,
        pad: CubePinPadView?,
    ) {
        val dots: CubePinDotsView? = credentialPasswordView.findViewById(R.id.cube_pin_dots)
        if (dots == null && pad == null) return
        (passwordField.getTag(R.id.cube_pin_dots) as? TextWatcher)?.let {
            passwordField.removeTextChangedListener(it)
        }
        val watcher =
            object : TextWatcher {
                private var before = 0

                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                    before = s.length
                }

                override fun onTextChanged(s: CharSequence, start: Int, b: Int, count: Int) {}

                override fun afterTextChanged(s: Editable) {
                    val length = s.length
                    dots?.setCount(length, rejected = before >= 2 && length == 0)
                    pad?.setEntryLength(length)
                }
            }
        passwordField.addTextChangedListener(watcher)
        passwordField.setTag(R.id.cube_pin_dots, watcher)
        val length = passwordField.text?.length ?: 0
        dots?.setCount(length)
        pad?.setEntryLength(length)
    }
}
