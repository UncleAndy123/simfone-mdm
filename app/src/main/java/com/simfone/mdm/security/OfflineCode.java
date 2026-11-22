/*
 * SimFone MDM
 * Licensed under the Apache License, Version 2.0.
 */

package com.simfone.mdm.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.util.Base64;
import android.util.Log;

import com.simfone.mdm.Const;
import com.simfone.mdm.util.RemoteLogger;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Offline challenge/response codes (plan section 10d), usable with no server at all.
 *
 *  K_dev    = HMAC-SHA256(master, deviceId)   computed by the admin tool, delivered at enrollment
 *  challenge = random 6 digits shown in hidden settings (single use, 15 min)
 *  response  = first 8 bytes of HMAC-SHA256(K_dev, challenge + "|" + action), as unsigned, mod 10^10,
 *              zero-padded to 10 digits
 *
 * Actions: "release", "park", "unlock" (temporary admin unlock), "reactivate", "settings".
 * The master key never touches the device. K_dev is imported into the Android Keystore
 * (non-exportable) when possible.
 *
 * Lockout: after 5 wrong answers, a growing delay (5, 10, 20 ... min, capped at 24 h).
 * The failure counter survives reboots; auto time is enforced by policy so the clock can't be wound.
 */
public class OfflineCode {
    private static final String TAG = Const.LOG_TAG;
    private static final String PREFS = "simfone_offline_code";
    private static final String KEYSTORE_ALIAS = "simfone_release_key";
    private static final String KEY_FALLBACK = "k_dev";
    private static final String KEY_IN_KEYSTORE = "k_in_keystore";
    private static final String KEY_CHALLENGE = "challenge";
    private static final String KEY_CHALLENGE_TIME = "challenge_time";
    private static final String KEY_FAILURES = "failures";
    private static final String KEY_LOCKED_UNTIL = "locked_until";

    public static final String ACTION_RELEASE = "release";
    public static final String ACTION_PARK = "park";
    public static final String ACTION_UNLOCK = "unlock";
    public static final String ACTION_REACTIVATE = "reactivate";
    public static final String ACTION_SETTINGS = "settings";

    private static final int FREE_ATTEMPTS = 5;
    private static final long CHALLENGE_TTL = TimeUnit.MINUTES.toMillis(15);

    public static boolean hasKey(Context context) {
        SharedPreferences p = prefs(context);
        return p.getBoolean(KEY_IN_KEYSTORE, false) || p.getString(KEY_FALLBACK, null) != null;
    }

    /**
     * Stores K_dev. Trust on first use: an existing key is never replaced unless {@code replace}
     * is true (only used after a fully authenticated re-enrollment).
     * @param keyText base64 or hex encoded 32-byte key
     */
    public static synchronized boolean storeKey(Context context, String keyText, boolean replace) {
        if (keyText == null || keyText.trim().isEmpty()) return false;
        if (hasKey(context) && !replace) {
            Log.w(TAG, "Release key already set, ignoring");
            return false;
        }
        byte[] key = decode(keyText.trim());
        if (key == null || key.length < 16) {
            Log.w(TAG, "Release key malformed");
            return false;
        }
        SharedPreferences.Editor e = prefs(context).edit();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.setEntry(KEYSTORE_ALIAS,
                    new KeyStore.SecretKeyEntry(new SecretKeySpec(key, "HmacSHA256")),
                    new KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).build());
            e.putBoolean(KEY_IN_KEYSTORE, true).remove(KEY_FALLBACK);
        } catch (Exception ex) {
            Log.w(TAG, "Keystore import failed, using private storage", ex);
            e.putBoolean(KEY_IN_KEYSTORE, false)
                    .putString(KEY_FALLBACK, Base64.encodeToString(key, Base64.NO_WRAP));
        }
        e.commit();
        RemoteLogger.log(context, Const.LOG_INFO, "Offline release key installed");
        return true;
    }

    /** Returns the current challenge, creating a new one if none is valid. */
    public static synchronized String getChallenge(Context context) {
        SharedPreferences p = prefs(context);
        String c = p.getString(KEY_CHALLENGE, null);
        long t = p.getLong(KEY_CHALLENGE_TIME, 0);
        long now = System.currentTimeMillis();
        if (c == null || now - t > CHALLENGE_TTL || now < t) {
            c = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1_000_000));
            p.edit().putString(KEY_CHALLENGE, c).putLong(KEY_CHALLENGE_TIME, now).commit();
        }
        return c;
    }

    /** Milliseconds until another attempt is allowed (0 = allowed now). */
    public static long lockoutRemainingMs(Context context) {
        return Math.max(0, prefs(context).getLong(KEY_LOCKED_UNTIL, 0) - System.currentTimeMillis());
    }

    public static int failures(Context context) {
        return prefs(context).getInt(KEY_FAILURES, 0);
    }

    /**
     * Verifies a 10-digit response for the current challenge and action.
     * A correct answer consumes the challenge; a wrong one counts towards the lockout.
     */
    public static synchronized boolean verify(Context context, String action, String response) {
        if (!hasKey(context) || lockoutRemainingMs(context) > 0) {
            return false;
        }
        String challenge = getChallenge(context);
        String expected = compute(context, challenge, action);
        String given = response == null ? "" : response.replaceAll("[^0-9]", "");
        boolean ok = expected != null && MessageDigest.isEqual(expected.getBytes(), given.getBytes());
        SharedPreferences p = prefs(context);
        if (ok) {
            p.edit().remove(KEY_CHALLENGE).remove(KEY_CHALLENGE_TIME)
                    .putInt(KEY_FAILURES, 0).remove(KEY_LOCKED_UNTIL).commit();
            RemoteLogger.log(context, Const.LOG_WARN, "Offline code accepted for action " + action);
        } else {
            int f = p.getInt(KEY_FAILURES, 0) + 1;
            SharedPreferences.Editor e = p.edit().putInt(KEY_FAILURES, f);
            if (f >= FREE_ATTEMPTS) {
                long delay = TimeUnit.MINUTES.toMillis(5) << Math.min(f - FREE_ATTEMPTS, 8);
                delay = Math.min(delay, TimeUnit.HOURS.toMillis(24));
                e.putLong(KEY_LOCKED_UNTIL, System.currentTimeMillis() + delay);
                // New challenge after a lockout so answers can't be brute-forced across one challenge
                e.remove(KEY_CHALLENGE).remove(KEY_CHALLENGE_TIME);
            }
            e.commit();
            RemoteLogger.log(context, Const.LOG_WARN, "Wrong offline code (" + f + " failures)");
        }
        return ok;
    }

    /** Same algorithm as tools/simfone_admin.py "response". */
    static String compute(Context context, String challenge, String action) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SharedPreferences p = prefs(context);
            if (p.getBoolean(KEY_IN_KEYSTORE, false)) {
                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                mac.init((SecretKey) ks.getKey(KEYSTORE_ALIAS, null));
            } else {
                byte[] key = Base64.decode(p.getString(KEY_FALLBACK, ""), Base64.NO_WRAP);
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
            }
            return formatResponse(mac.doFinal((challenge + "|" + action).getBytes("UTF-8")));
        } catch (Exception e) {
            Log.e(TAG, "Offline code computation failed", e);
            return null;
        }
    }

    static String formatResponse(byte[] hmac) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (hmac[i] & 0xff);
        }
        v &= Long.MAX_VALUE;
        return String.format(Locale.US, "%010d", v % 10_000_000_000L);
    }

    static byte[] decode(String s) {
        try {
            if (s.matches("^[0-9a-fA-F]+$") && s.length() % 2 == 0) {
                byte[] out = new byte[s.length() / 2];
                for (int i = 0; i < out.length; i++) {
                    out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
                }
                return out;
            }
            return Base64.decode(s, Base64.DEFAULT);
        } catch (Exception e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
