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

package com.android.systemui.util;

import android.view.animation.Interpolator;

/**
 * CubeOS: a Cube Motion spring as a time interpolator, so animators that run for a set duration
 * (and keep their listeners, such as jank tracking) still move on the same springs as Cube Home
 * and CubeLine. The curve is the exact damped-spring solution for a spring set by its response
 * (seconds for one swing) and damping ratio, mass 1; it ends where the motion is within 1 % of
 * rest and is scaled to land exactly on 1. Keep the presets in step with
 * {@code org.cubeapp.ui.Motion} in CubeOSApps/cubeui.
 */
public final class CubeSpringInterpolator implements Interpolator {

    /** Duration that fits {@link #settle()}: the spring is within 1 % of rest by then. */
    public static final long SETTLE_DURATION_MS = 400;
    /** Duration that fits {@link #dismiss()}. */
    public static final long DISMISS_DURATION_MS = 300;

    /** Panels and sheets arriving: response 0.38 s, damping 0.85. */
    public static CubeSpringInterpolator settle() {
        return new CubeSpringInterpolator(0.38, 0.85, SETTLE_DURATION_MS);
    }

    /** Leaving, no bounce: response 0.28 s, damping 1. */
    public static CubeSpringInterpolator dismiss() {
        return new CubeSpringInterpolator(0.28, 1.0, DISMISS_DURATION_MS);
    }

    private final double mOmega;
    private final double mZeta;
    private final double mSeconds;
    private final double mEnd;

    public CubeSpringInterpolator(double response, double dampingRatio, long durationMs) {
        mOmega = 2 * Math.PI / response;
        mZeta = dampingRatio;
        mSeconds = durationMs / 1000.0;
        mEnd = position(mSeconds);
    }

    @Override
    public float getInterpolation(float fraction) {
        if (fraction >= 1f) return 1f;
        if (fraction <= 0f) return 0f;
        return (float) (position(fraction * mSeconds) / mEnd);
    }

    /** How far a spring released from 0 towards 1 has travelled after {@code t} seconds. */
    private double position(double t) {
        double decay = Math.exp(-mZeta * mOmega * t);
        if (mZeta < 1) {
            double damped = mOmega * Math.sqrt(1 - mZeta * mZeta);
            return 1 - decay * (Math.cos(damped * t) + mZeta * mOmega / damped * Math.sin(damped * t));
        }
        return 1 - decay * (1 + mOmega * t);
    }
}