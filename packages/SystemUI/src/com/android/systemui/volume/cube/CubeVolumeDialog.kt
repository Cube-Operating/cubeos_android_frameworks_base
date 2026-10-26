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

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Region
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.WindowManager
import com.android.systemui.plugins.VolumeDialog
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.volume.SafetyWarningDialog

/**
 * CubeOS volume: one slim elastic pill beside the volume buttons (left edge, upper third),
 * in place of Android's volume panel. It appears on a key press, fills to the level, stretches
 * when pressed past the top or bottom, swells and follows the finger when dragged, and after a
 * second melts into a thin line and slides away.
 *
 * Original code. Its sizes and springs follow the measurements in [CubeVolumeHudView].
 */
class CubeVolumeDialog(
    private val context: Context,
    private val controller: VolumeDialogController,
) : VolumeDialog {

    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(WindowManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var hud: CubeVolumeHudView? = null
    private var state: VolumeDialogController.State? = null
    private var stream = AudioManager.STREAM_MUSIC
    private var shown = false
    /** Level when the last key press arrived, to tell a press at the top or bottom (no change). */
    private var levelAtPress = -1
    private var safetyWarning: SafetyWarningDialog? = null
    private val touchable = Region()

    override fun init(windowType: Int, callback: VolumeDialog.Callback?) {
        val view = CubeVolumeHudView(context).apply {
            onUserLevel = { level -> setLevelFromUser(level) }
            onInteracting = { interacting ->
                controller.userActivity()
                if (interacting) cancelTimers() else scheduleDeflate()
            }
        }
        hud = view
        val lp = WindowManager.LayoutParams(
            view.windowWidthPx(),
            view.windowHeightPx(),
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = view.windowTopPx()
            title = "CubeVolume"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setTrustedOverlay()
        }
        // Touches go to the pill only; everywhere else passes through to the window below.
        view.viewTreeObserver.addOnComputeInternalInsetsListener { info ->
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
            touchable.setEmpty()
            if (shown) view.pillBounds()?.let { touchable.set(it) }
            info.touchableRegion.set(touchable)
        }
        wm.addView(view, lp)
        controller.addCallback(callbacks, main)
        controller.getState()
    }

    override fun destroy() {
        controller.removeCallback(callbacks)
        cancelTimers()
        hud?.let { runCatching { wm.removeViewImmediate(it) } }
        hud = null
    }

    // ---------- showing ----------

    private fun show() {
        val view = hud ?: return
        cancelTimers()
        if (!shown) {
            shown = true
            controller.notifyVisible(true)
            view.appear()
        } else {
            view.reinflate()
        }
        scheduleDeflate()
    }

    private fun dismiss() {
        cancelTimers()
        if (!shown) return
        shown = false
        hud?.disappear()
        controller.notifyVisible(false)
    }

    private val deflate = Runnable { hud?.deflate(); main.postDelayed(hide, CubeVolumeHudView.POST_DEFLATE_MS) }
    private val hide = Runnable { dismiss() }

    private fun scheduleDeflate() {
        cancelTimers()
        main.postDelayed(deflate, CubeVolumeHudView.DISMISS_MS)
    }

    private fun cancelTimers() {
        main.removeCallbacks(deflate)
        main.removeCallbacks(hide)
    }

    // ---------- level ----------

    private fun streamState() = state?.states?.get(stream)

    private fun applyState(animate: Boolean) {
        val s = state ?: return
        stream = when {
            s.activeStream != VolumeDialogController.State.NO_ACTIVE_STREAM && s.states.get(s.activeStream) != null -> s.activeStream
            else -> AudioManager.STREAM_MUSIC
        }
        val ss = streamState() ?: return
        val span = (ss.levelMax - ss.levelMin).coerceAtLeast(1)
        val level = if (ss.muted) 0f else (ss.level - ss.levelMin).toFloat() / span
        hud?.setLevel(level, ringer = stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION,
            muted = ss.muted || ss.level == ss.levelMin, animate = animate)
    }

    private fun setLevelFromUser(fraction: Float) {
        val ss = streamState() ?: return
        val level = ss.levelMin + Math.round(fraction * (ss.levelMax - ss.levelMin))
        if (level != ss.level) controller.setStreamVolume(stream, level)
    }

    /** A key press that didn't move the level: it was pressed past the top or the bottom. */
    private val checkLimit = Runnable {
        val ss = streamState() ?: return@Runnable
        if (ss.level != levelAtPress) return@Runnable
        when (ss.level) {
            ss.levelMax -> hud?.stretch(up = true)
            ss.levelMin -> hud?.stretch(up = false)
        }
    }

    private val callbacks = object : VolumeDialogController.Callbacks {
        override fun onShowRequested(reason: Int, keyguardLocked: Boolean, lockTaskModeState: Int) {
            levelAtPress = streamState()?.level ?: -1
            show()
            main.removeCallbacks(checkLimit)
            main.postDelayed(checkLimit, 60)
        }

        override fun onDismissRequested(reason: Int) = dismiss()

        override fun onStateChanged(newState: VolumeDialogController.State) {
            state = newState
            applyState(animate = shown)
        }

        override fun onShowSafetyWarning(flags: Int) {
            if (safetyWarning != null) return
            safetyWarning = object : SafetyWarningDialog(context, audio) {
                override fun cleanUp() { safetyWarning = null }
            }.also { it.show() }
        }

        override fun onScreenOff() = dismiss()
        override fun onConfigurationChanged() { hud?.onConfigurationChanged() }
        override fun onLayoutDirectionChanged(layoutDirection: Int) {}
        override fun onShowVibrateHint() {}
        override fun onShowSilentHint() {}
        override fun onAccessibilityModeChanged(showA11yStream: Boolean?) {}
        override fun onCaptionComponentStateChanged(isComponentEnabled: Boolean?, fromTooltip: Boolean?) {}
        override fun onCaptionEnabledStateChanged(isEnabled: Boolean?, checkBeforeSwitch: Boolean?) {}
        override fun onShowCsdWarning(csdWarning: Int, durationMs: Int) {
            Log.i(TAG, "sound dose warning $csdWarning")
        }
        override fun onVolumeChangedFromKey() {}
    }

    private companion object {
        const val TAG = "CubeVolume"
    }
}
