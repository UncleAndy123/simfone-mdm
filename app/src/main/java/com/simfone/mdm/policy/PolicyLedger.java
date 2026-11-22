/*
 * SimFone MDM
 * Based on Headwind MDM (https://h-mdm.com), Copyright (C) 2019 Headwind Solutions LLC.
 * Licensed under the Apache License, Version 2.0.
 */

package com.simfone.mdm.policy;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Local record of every device policy SimFone has applied (plan section 10a).
 *
 * The ledger is the basis for:
 *  - diffing: a policy that disappears from the server config is reversed;
 *  - release: {@link ReleaseManager} reverses exactly what is recorded here
 *    (plus a sweep of the device for anything the ledger missed).
 *
 * Stored in device-protected storage so it can be read before the first unlock
 * (BootReceiver may run before the user unlocks on file-based-encryption devices).
 */
public class PolicyLedger {
    private static final String PREFS = "simfone_policy_ledger";

    public static final String HIDDEN = "hidden";               // setApplicationHidden
    public static final String SUSPENDED = "suspended";         // setPackagesSuspended
    public static final String UNINSTALL_BLOCKED = "uninstall_blocked"; // setUninstallBlocked
    public static final String RESTRICTIONS = "restrictions";   // addUserRestriction
    public static final String APN_IDS = "apn_ids";             // addOverrideApn (int ids as strings)
    public static final String PERMISSION_GRANTS = "permission_grants"; // "pkg|permission"
    public static final String PREFERRED_ACTIVITIES = "preferred_activities"; // packages with persistent preferred activities
    public static final String SYSTEM_APPS_ENABLED = "system_apps_enabled"; // enableSystemApp (informational)

    // Scalar flags
    public static final String FLAG_SYSTEM_UPDATE_POLICY = "system_update_policy";
    public static final String FLAG_GLOBAL_PROXY = "global_proxy";
    public static final String FLAG_AUTO_TIME_REQUIRED = "auto_time_required";
    public static final String FLAG_SCREEN_CAPTURE_DISABLED = "screen_capture_disabled";
    public static final String FLAG_KEYGUARD_FEATURES = "keyguard_features";
    public static final String FLAG_PASSWORD_QUALITY = "password_quality";
    public static final String FLAG_STATUS_BAR_DISABLED = "status_bar_disabled";

    private final SharedPreferences prefs;

    private static PolicyLedger instance;

    public static synchronized PolicyLedger get(Context context) {
        if (instance == null) {
            instance = new PolicyLedger(context);
        }
        return instance;
    }

    private PolicyLedger(Context context) {
        Context storage = context.getApplicationContext();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            Context dps = storage.createDeviceProtectedStorageContext();
            dps.moveSharedPreferencesFrom(storage, PREFS);
            storage = dps;
        }
        prefs = storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized Set<String> getSet(String name) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(prefs.getStringSet(name, new HashSet<>())));
    }

    public synchronized void add(String name, String value) {
        if (TextUtils.isEmpty(value)) return;
        Set<String> s = new HashSet<>(prefs.getStringSet(name, new HashSet<>()));
        if (s.add(value)) {
            prefs.edit().putStringSet(name, s).commit();
        }
    }

    public synchronized void remove(String name, String value) {
        Set<String> s = new HashSet<>(prefs.getStringSet(name, new HashSet<>()));
        if (s.remove(value)) {
            prefs.edit().putStringSet(name, s).commit();
        }
    }

    public synchronized void clear(String name) {
        prefs.edit().remove(name).commit();
    }

    public synchronized void setFlag(String name, boolean value) {
        prefs.edit().putBoolean(name, value).commit();
    }

    public synchronized boolean getFlag(String name) {
        return prefs.getBoolean(name, false);
    }

    /** Wipes the whole ledger. Only called by ReleaseManager after everything was reversed. */
    public synchronized void wipe() {
        prefs.edit().clear().commit();
    }

    /** Human-readable summary for the hidden settings status screen and logs. */
    public synchronized String summary() {
        return "hidden=" + getSet(HIDDEN).size()
                + " suspended=" + getSet(SUSPENDED).size()
                + " blocked=" + getSet(UNINSTALL_BLOCKED).size()
                + " restrictions=" + getSet(RESTRICTIONS).size()
                + " apns=" + getSet(APN_IDS).size()
                + " grants=" + getSet(PERMISSION_GRANTS).size();
    }
}
