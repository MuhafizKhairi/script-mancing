package com.autofish.wibu;

import android.content.Context;
import android.provider.Settings;

import java.security.MessageDigest;

/**
 * The dev build (riwayat + REC) only runs on Hae's own phones. Each phone has a "device code": a hash of its
 * ANDROID_ID, which Android scopes to this app's signing key, so the code is stable across reinstalls on that phone
 * but different on every other phone. Allowed codes come from dev-devices.txt at build time (not in git); with no
 * codes the dev build runs nowhere. The public build is never locked.
 */
final class DevLock {
    private DevLock() {
    }

    private static String code;

    /** 16 hex chars identifying this phone for the dev build. */
    static synchronized String deviceCode(Context c) {
        if (code == null) {
            String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(("automancing-dev:" + id).getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i] & 255));
                code = sb.toString();
            } catch (Exception e) {
                code = "0000000000000000";
            }
        }
        return code;
    }

    static boolean allowed(Context c) {
        if (Variant.PUBLIK) return true;
        String me = deviceCode(c);
        for (String s : Variant.DEV_IDS) if (s.equals(me)) return true;
        return false;
    }
}
