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

package com.android.server.oemlock;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Environment;
import android.os.SystemProperties;
import android.util.Slog;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * CubeOS: OEM unlocking needs an unlock code from the CubeOS project. The carrier gate of the
 * OEM lock (Android's "allowed by carrier", which CubeOS Settings, stock Settings and every other
 * app go through) is closed at every boot, and opens only with a code for this phone: an
 * SHA256withRSA signature of {@link #message()} made with the CubeOS platform key, checked here,
 * in system_server, against the certificate of the "android" package (the image's platform key),
 * so no app can get around it. The accepted code is kept in /data/system until the data is wiped
 * (a bootloader unlock or a factory reset), after which a new code is needed.
 *
 * Codes are made by the key holder with device/cube/ygf_f35/tools/oem-unlock-code.sh.
 */
final class CubeOemUnlock {
    private static final String TAG = "OemLock";
    private static final String PREFIX = "CubeOS-OEM-unlock-v1:";

    private CubeOemUnlock() {}

    /** What a code signs: this phone's serial number. */
    static byte[] message() {
        return (PREFIX + SystemProperties.get("ro.serialno", "")).getBytes(StandardCharsets.UTF_8);
    }

    private static File stored() {
        return new File(Environment.getDataSystemDirectory(), "cube_oem_unlock.code");
    }

    /** True when [signature] is this phone's code, signed with the image's platform key. */
    static boolean verify(Context context, @Nullable byte[] signature) {
        if (signature == null || signature.length == 0) return false;
        if (SystemProperties.get("ro.serialno", "").isEmpty()) return false;
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo("android",
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (pi.signingInfo == null) return false;
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (Signature s : pi.signingInfo.getApkContentsSigners()) {
                X509Certificate cert = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(s.toByteArray()));
                java.security.Signature v = java.security.Signature.getInstance("SHA256withRSA");
                v.initVerify(cert.getPublicKey());
                v.update(message());
                if (v.verify(signature)) return true;
            }
        } catch (Exception e) {
            Slog.w(TAG, "CubeOS unlock code check failed", e);
        }
        return false;
    }

    /** Keep an accepted code (so the next boot leaves OEM unlocking available). */
    static void store(byte[] signature) {
        try {
            Files.write(stored().toPath(), signature);
        } catch (Exception e) {
            Slog.w(TAG, "could not keep the CubeOS unlock code", e);
        }
    }

    static void forget() {
        stored().delete();
    }

    /** A code accepted earlier on this data (and still valid for this phone and image). */
    static boolean accepted(Context context) {
        File f = stored();
        if (!f.exists()) return false;
        try {
            return verify(context, Files.readAllBytes(f.toPath()));
        } catch (Exception e) {
            return false;
        }
    }
}