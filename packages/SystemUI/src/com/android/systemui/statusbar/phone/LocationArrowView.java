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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;

import java.util.ArrayList;

/**
 * CubeOS: the location arrow beside the clock, shown while an app uses location (like iOS).
 * {@link PhoneStatusBarViewController} shows and hides it and registers it for the status bar
 * tint.
 */
public class LocationArrowView extends ImageView implements DarkReceiver {

    public LocationArrowView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        setImageTintList(ColorStateList.valueOf(DarkIconDispatcher.getTint(areas, this, tint)));
    }
}
