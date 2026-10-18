/*
 * Copyright (C) 2026 Egoras / CubeOS
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

package com.android.server.wm;

import android.os.Handler;
import android.os.HwBinder;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

/**
 * CubeOS: touch boost on MediaTek phones.
 *
 * MediaTek vendors ignore the AOSP power HAL's INTERACTION boost; the stock firmware boosts from
 * MediaTek's own framework code, which a GSI doesn't have, so the CPU stays at low clocks while
 * you scroll. This sends MediaTek's perf hint instead, through
 * vendor.mediatek.hardware.mtkpower@1.1::IMtkPerf::perfCusLockHint(hint, durationMs) (the
 * interface is labelled hal_power_hwservice, so system_server may already call it).
 *
 * APP_ROTATE (24) raises the big cores' minimum clock for the duration; APP_TOUCH (25) only
 * nudges the scheduler. Measured on the CubePhone (MT6877) home screen: median frame 17 -> 10 ms
 * with APP_ROTATE, no change with APP_TOUCH. persist.sys.cube.touch_boost = rotate (default),
 * touch or off. Phones without the interface: one lookup, then nothing.
 */
final class MtkPowerBoost {
    private static final String TAG = "MtkPowerBoost";
    private static final String DESCRIPTOR = "vendor.mediatek.hardware.mtkpower@1.1::IMtkPerf";
    // IMtkPerf@1.0: perfLockAcquire = 1, perfLockRelease = 2; @1.1: perfCusLockHint = 3.
    private static final int TRANSACTION_PERF_CUS_LOCK_HINT = 3;
    private static final int HINT_APP_ROTATE = 24;
    private static final int HINT_APP_TOUCH = 25;
    private static final int TOUCH_MS = 1000;
    /** A new hint while the last one still holds is pointless; drag events would flood the HAL. */
    private static final long MIN_INTERVAL_MS = 500;

    private static MtkPowerBoost sInstance;

    private final Handler mHandler = BackgroundThread.getHandler();
    private IHwBinder mPerf;
    private boolean mLookedUp;
    private long mLastHintAt;

    static synchronized MtkPowerBoost get() {
        if (sInstance == null) sInstance = new MtkPowerBoost();
        return sInstance;
    }

    /** A touch began or a fling started; [durationMs] <= 0 means the default touch length. */
    void boost(int durationMs) {
        final long now = SystemClock.uptimeMillis();
        synchronized (this) {
            if (mLookedUp && mPerf == null) return;
            if (now - mLastHintAt < MIN_INTERVAL_MS) return;
            mLastHintAt = now;
        }
        final int ms = Math.max(durationMs, TOUCH_MS);
        mHandler.post(() -> send(ms));
    }

    private void send(int durationMs) {
        final String mode = SystemProperties.get("persist.sys.cube.touch_boost", "rotate");
        if ("off".equals(mode)) return;
        final int hint = "touch".equals(mode) ? HINT_APP_TOUCH : HINT_APP_ROTATE;
        final IHwBinder perf = service();
        if (perf == null) return;
        final HwParcel request = new HwParcel();
        final HwParcel reply = new HwParcel();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeInt32(hint);
            request.writeInt32(durationMs);
            perf.transact(TRANSACTION_PERF_CUS_LOCK_HINT, request, reply, 0 /* flags */);
            reply.verifySuccess();
            request.releaseTemporaryStorage();
        } catch (Exception e) {
            // The HAL restarted (or went away): look it up again next time.
            Slog.w(TAG, "perfCusLockHint failed", e);
            synchronized (this) {
                mPerf = null;
                mLookedUp = false;
            }
        } finally {
            reply.release();
        }
    }

    private IHwBinder service() {
        synchronized (this) {
            if (mLookedUp) return mPerf;
            mLookedUp = true;
        }
        IHwBinder perf = null;
        try {
            perf = HwBinder.getService(DESCRIPTOR, "default", false /* retry */);
        } catch (Exception e) {
            // Not a MediaTek vendor (or too old for 1.1): stay quiet from now on.
        }
        if (perf != null) Slog.i(TAG, "MediaTek touch boost enabled");
        synchronized (this) {
            mPerf = perf;
            return perf;
        }
    }
}
