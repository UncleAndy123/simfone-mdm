/*
 * SimFone MDM
 * Licensed under the Apache License, Version 2.0.
 */

package com.simfone.mdm.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.simfone.mdm.BuildConfig;
import com.simfone.mdm.Const;
import com.simfone.mdm.helper.ConfigUpdater;
import com.simfone.mdm.helper.SettingsHelper;
import com.simfone.mdm.policy.ReleaseManager;
import com.simfone.mdm.server.ServerServiceKeeper;
import com.simfone.mdm.util.RemoteLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Signed fallback manifest (plan section 10d) for when the primary server is lost.
 *
 * File format (static JSON on a second provider, e.g. GitHub Pages):
 * <pre>
 * {"payload": "&lt;base64 of the payload JSON bytes&gt;",
 *  "sig":     "&lt;base64 DER ECDSA P-256 / SHA-256 signature over the payload bytes&gt;"}
 * payload = {"v":1, "targets": ["dev1","dev2"] | "*", "action": "release"|"park"|"newServerUrl",
 *            "newServerUrl": "https://...", "notBefore": epochSec, "expires": epochSec, "nonce": "..."}
 * </pre>
 * The public key is compiled into the APK (FALLBACK_MANIFEST_PUBLIC_KEY). P-256 works on API 24.
 *
 * Checked at most once a day, and ONLY after the primary server has been unreachable for
 * FALLBACK_MANIFEST_AFTER_DAYS. An unreachable server is never a trigger by itself: only a valid,
 * unexpired, targeted, unused signed manifest acts.
 */
public class FallbackManifest {
    private static final String TAG = Const.LOG_TAG;
    private static final String PREFS = "simfone_server_contact";
    private static final String KEY_LAST_SUCCESS = "last_success";
    private static final String KEY_USED_NONCES = "used_nonces";
    private static final String WORK_NAME = "simfone_fallback_manifest";

    /** Called after every successful contact with the primary server. */
    public static void markServerContact(Context context) {
        prefs(context).edit().putLong(KEY_LAST_SUCCESS, System.currentTimeMillis()).apply();
    }

    public static long lastServerContact(Context context) {
        return prefs(context).getLong(KEY_LAST_SUCCESS, 0);
    }

    public static boolean isConfigured() {
        return !BuildConfig.FALLBACK_MANIFEST_URL.isEmpty() && !BuildConfig.FALLBACK_MANIFEST_PUBLIC_KEY.isEmpty();
    }

    public static void schedule(Context context) {
        if (!isConfigured()) return;
        Constraints c = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(CheckWorker.class, 24, TimeUnit.HOURS).setConstraints(c).build());
    }

    public static class CheckWorker extends Worker {
        public CheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            try {
                checkNow(getApplicationContext(), false);
            } catch (Exception e) {
                Log.w(TAG, "Fallback manifest check failed", e);
            }
            return Result.success();
        }
    }

    /**
     * @param force skip the "server unreachable for N days" gate (hidden settings, admin only)
     * @return a short human-readable result
     */
    public static String checkNow(Context context, boolean force) throws Exception {
        if (!isConfigured()) return "not configured";
        if (ReleaseManager.isParkedOrReleased(context)) return "inactive";
        long last = lastServerContact(context);
        if (!force) {
            if (last == 0) return "never enrolled";
            long offlineMs = System.currentTimeMillis() - last;
            if (offlineMs < TimeUnit.DAYS.toMillis(BuildConfig.FALLBACK_MANIFEST_AFTER_DAYS)) {
                return "server reachable recently";
            }
        }
        String body = fetch(BuildConfig.FALLBACK_MANIFEST_URL);
        JSONObject payload = verify(body, BuildConfig.FALLBACK_MANIFEST_PUBLIC_KEY);
        if (payload == null) {
            RemoteLogger.log(context, Const.LOG_WARN, "Fallback manifest rejected: bad signature or format");
            return "rejected: signature";
        }
        String deviceId = SettingsHelper.getInstance(context).getDeviceId();
        String reason = checkPayload(payload, deviceId, System.currentTimeMillis() / 1000, usedNonces(context));
        if (reason != null) {
            return "ignored: " + reason;
        }
        markNonceUsed(context, payload.getString("nonce"));
        String action = payload.getString("action");
        RemoteLogger.log(context, Const.LOG_WARN, "Fallback manifest action: " + action);
        switch (action) {
            case "release":
                ReleaseManager.release(context, "signed fallback manifest");
                break;
            case "park":
                ReleaseManager.park(context, "signed fallback manifest");
                break;
            case "newServerUrl":
                String url = payload.getString("newServerUrl").trim();
                SettingsHelper sh = SettingsHelper.getInstance(context);
                sh.setBaseUrl(url);
                sh.setSecondaryBaseUrl(url);
                ServerServiceKeeper.resetServices();
                ConfigUpdater.forceConfigUpdate(context);
                break;
            default:
                return "ignored: unknown action";
        }
        return "applied: " + action;
    }

    /** @return the payload if the envelope's signature is valid, otherwise null */
    public static JSONObject verify(String envelope, String publicKeyB64) {
        try {
            JSONObject env = new JSONObject(envelope);
            byte[] payloadBytes = Base64.decode(env.getString("payload"), Base64.DEFAULT);
            byte[] sig = Base64.decode(env.getString("sig"), Base64.DEFAULT);
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(Base64.decode(publicKeyB64, Base64.DEFAULT)));
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(key);
            s.update(payloadBytes);
            if (!s.verify(sig)) return null;
            return new JSONObject(new String(payloadBytes, "UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "Manifest verify error: " + e.getMessage());
            return null;
        }
    }

    /** @return null if the payload applies to this device now, otherwise the reason it doesn't */
    static String checkPayload(JSONObject p, String deviceId, long nowSec, Set<String> usedNonces) {
        if (p.optInt("v", 1) != 1) return "unsupported version";
        String nonce = p.optString("nonce", "");
        if (nonce.length() < 8) return "missing nonce";
        if (usedNonces.contains(nonce)) return "nonce already used";
        long notBefore = p.optLong("notBefore", 0);
        long expires = p.optLong("expires", 0);
        if (expires == 0 || nowSec > expires) return "expired";
        if (nowSec < notBefore) return "not yet valid";
        Object targets = p.opt("targets");
        boolean targeted = false;
        if ("*".equals(targets)) {
            targeted = true;
        } else if (targets instanceof JSONArray) {
            JSONArray a = (JSONArray) targets;
            for (int i = 0; i < a.length(); i++) {
                if (deviceId != null && deviceId.equals(a.optString(i))) targeted = true;
            }
        }
        if (!targeted) return "not targeted";
        String action = p.optString("action", "");
        if ("newServerUrl".equals(action) && !p.optString("newServerUrl", "").startsWith("https://")) {
            return "newServerUrl must be https";
        }
        return null;
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(20000);
        c.setUseCaches(false);
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 256 * 1024) throw new Exception("manifest too large");
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static Set<String> usedNonces(Context context) {
        return new HashSet<>(prefs(context).getStringSet(KEY_USED_NONCES, new HashSet<>()));
    }

    private static void markNonceUsed(Context context, String nonce) {
        Set<String> s = usedNonces(context);
        s.add(nonce);
        prefs(context).edit().putStringSet(KEY_USED_NONCES, s).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
