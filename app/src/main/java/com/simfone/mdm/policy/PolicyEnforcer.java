/*
 * SimFone MDM
 * Based on Headwind MDM (https://h-mdm.com), Copyright (C) 2019 Headwind Solutions LLC.
 * Licensed under the Apache License, Version 2.0.
 */

package com.simfone.mdm.policy;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.simfone.mdm.Const;
import com.simfone.mdm.helper.SettingsHelper;
import com.simfone.mdm.json.ServerConfig;
import com.simfone.mdm.service.SmsFilterManager;
import com.simfone.mdm.util.LegacyUtils;
import com.simfone.mdm.util.RemoteLogger;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Applies the policy part of the (cached or fresh) configuration through DevicePolicyManager.
 *
 * Fail-closed rules (plan section 11):
 *  - Works fully offline from the last-good cached config. Called on boot, after an app update,
 *    after every config sync and by the relock watchdog.
 *  - A policy is only reversed when it disappears from a config that passed {@link ConfigSanity}
 *    (i.e. an explicit server change), or by {@link ReleaseManager}. Never on errors.
 *  - When there is no cached config, nothing is relaxed: the ledger is simply re-applied.
 *
 * Package lists come from the application settings of com.simfone.mdm in the Headwind panel:
 *   hidden_packages     CSV  setApplicationHidden (gone from every launcher)
 *   suspended_packages  CSV  setPackagesSuspended (visible but greyed out, data kept)
 *   locked_packages     CSV  both of the above (upstream Headwind compatibility)
 *   unlocked_packages   CSV  explicitly never hidden/suspended (wins over the lists above)
 *   protected_packages  CSV  setUninstallBlocked (launcher, KyCall, dpadsms...)
 *   auto_time_required  bool setAutoTimeRequired (default true; offline codes depend on the clock)
 * User restrictions come from the configuration "Restrictions" field.
 */
public class PolicyEnforcer {
    private static final String TAG = Const.LOG_TAG;

    /** Restrictions that are always applied as long as SimFone controls the device. */
    private static final Set<String> BASELINE_RESTRICTIONS =
            Collections.unmodifiableSet(new HashSet<>(Collections.singletonList(UserManager.DISALLOW_SAFE_BOOT)));

    /** Restrictions that block SimFone's own install/uninstall work. */
    private static final Set<String> INSTALL_RESTRICTIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            UserManager.DISALLOW_INSTALL_APPS, UserManager.DISALLOW_UNINSTALL_APPS)));

    private static final String STATE_PREFS = "simfone_policy_state";
    private static final String KEY_INSTALL_WINDOW_UNTIL = "install_window_until";
    private static final String KEY_TEMP_UNLOCK_UNTIL = "temp_unlock_until";
    private static final long INSTALL_WINDOW_MS = TimeUnit.MINUTES.toMillis(20);
    private static final String RELOCK_WORK = "simfone_relock";

    public static final String SETTING_HIDDEN = "hidden_packages";
    public static final String SETTING_SUSPENDED = "suspended_packages";
    public static final String SETTING_LOCKED = "locked_packages";
    public static final String SETTING_UNLOCKED = "unlocked_packages";
    public static final String SETTING_PROTECTED = "protected_packages";
    public static final String SETTING_AUTO_TIME = "auto_time_required";
    public static final String SETTING_SETUP_ICON = "settings_icon";

    private static final Object lock = new Object();

    // ---------------------------------------------------------------------------------------
    // Entry points
    // ---------------------------------------------------------------------------------------

    /**
     * Re-applies the full policy set from the cached last-good config. No network needed.
     * Safe to call any time; idempotent.
     */
    public static void applyCached(Context context) {
        if (ReleaseManager.isParkedOrReleased(context)) {
            return;
        }
        ServerConfig config = SettingsHelper.getInstance(context).getConfig();
        apply(context, config, false);
    }

    /**
     * Applies a config that was just received and accepted.
     * @param keepInstallWindow true while silent installs started by this sync are still pending
     */
    public static void apply(Context context, ServerConfig config, boolean keepInstallWindow) {
        if (!isDeviceOwner(context) || ReleaseManager.isParkedOrReleased(context)) {
            return;
        }
        synchronized (lock) {
            PolicyLedger ledger = PolicyLedger.get(context);
            if (config == null) {
                // No config cached (not enrolled yet, or storage lost): re-assert whatever we recorded.
                reassertLedger(context, ledger);
                return;
            }
            applyRestrictions(context, ledger, config, keepInstallWindow);
            applyPackages(context, ledger);
            applyAutoTime(context, ledger);
            applySetupIcon(context);
            ApnPolicy.apply(context, ledger);
            try {
                SmsFilterManager.getInstance(context).pushIfNeeded();
            } catch (Exception e) {
                Log.w(TAG, "SMS filter push failed", e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Install window: lift ONLY no_install_apps / no_uninstall_apps for our own operation
    // ---------------------------------------------------------------------------------------

    /** Lifts the install/uninstall restrictions (only those) for SimFone's own work. */
    public static void beginInstallWindow(Context context) {
        if (!isDeviceOwner(context)) return;
        synchronized (lock) {
            state(context).edit().putLong(KEY_INSTALL_WINDOW_UNTIL,
                    System.currentTimeMillis() + INSTALL_WINDOW_MS).commit();
            DevicePolicyManager dpm = dpm(context);
            ComponentName admin = LegacyUtils.getAdminComponentName(context);
            for (String r : INSTALL_RESTRICTIONS) {
                try {
                    dpm.clearUserRestriction(admin, r);
                } catch (Exception e) {
                    Log.w(TAG, "clearUserRestriction " + r, e);
                }
            }
        }
        // Watchdog: if the process dies mid-install, re-lock anyway
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(RELOCK_WORK, ExistingWorkPolicy.REPLACE,
                    new OneTimeWorkRequest.Builder(RelockWorker.class)
                            .setInitialDelay(INSTALL_WINDOW_MS + 60_000, TimeUnit.MILLISECONDS).build());
        } catch (Exception e) {
            Log.w(TAG, "Failed to schedule relock watchdog", e);
        }
    }

    /** Re-applies the install/uninstall restrictions if the config asks for them. */
    public static void endInstallWindow(Context context) {
        state(context).edit().remove(KEY_INSTALL_WINDOW_UNTIL).commit();
        applyCached(context);
    }

    /** Runs a single install/uninstall with the install restrictions lifted, then re-locks. */
    public static void withInstallAllowed(Context context, Runnable operation) {
        beginInstallWindow(context);
        try {
            operation.run();
        } finally {
            endInstallWindow(context);
        }
    }

    private static boolean installWindowOpen(Context context) {
        return System.currentTimeMillis() < state(context).getLong(KEY_INSTALL_WINDOW_UNTIL, 0);
    }

    // ---------------------------------------------------------------------------------------
    // Temporary admin unlock (hidden settings, PIN or signed code)
    // ---------------------------------------------------------------------------------------

    /** Lifts all user restrictions for the given time. Boot and the watchdog re-lock afterwards. */
    public static void beginTemporaryUnlock(Context context, long durationMs) {
        if (!isDeviceOwner(context)) return;
        RemoteLogger.log(context, Const.LOG_WARN, "Temporary admin unlock for " + (durationMs / 60000) + " min");
        synchronized (lock) {
            state(context).edit().putLong(KEY_TEMP_UNLOCK_UNTIL, System.currentTimeMillis() + durationMs).commit();
            DevicePolicyManager dpm = dpm(context);
            ComponentName admin = LegacyUtils.getAdminComponentName(context);
            for (String r : PolicyLedger.get(context).getSet(PolicyLedger.RESTRICTIONS)) {
                try {
                    dpm.clearUserRestriction(admin, r);
                } catch (Exception e) {
                    Log.w(TAG, "clearUserRestriction " + r, e);
                }
            }
        }
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(RELOCK_WORK + "_temp", ExistingWorkPolicy.REPLACE,
                    new OneTimeWorkRequest.Builder(RelockWorker.class)
                            .setInitialDelay(durationMs + 30_000, TimeUnit.MILLISECONDS).build());
        } catch (Exception e) {
            Log.w(TAG, "Failed to schedule relock watchdog", e);
        }
    }

    public static void endTemporaryUnlock(Context context) {
        state(context).edit().remove(KEY_TEMP_UNLOCK_UNTIL).commit();
        applyCached(context);
    }

    public static long temporaryUnlockRemainingMs(Context context) {
        long until = state(context).getLong(KEY_TEMP_UNLOCK_UNTIL, 0);
        return Math.max(0, until - System.currentTimeMillis());
    }

    /** Called on boot: a reboot always ends temporary unlocks and install windows. */
    public static void resetTransientWindows(Context context) {
        state(context).edit().remove(KEY_TEMP_UNLOCK_UNTIL).remove(KEY_INSTALL_WINDOW_UNTIL).commit();
    }

    public static class RelockWorker extends Worker {
        public RelockWorker(Context context, WorkerParameters params) {
            super(context, params);
        }

        @Override
        public Result doWork() {
            applyCached(getApplicationContext());
            return Result.success();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Restrictions
    // ---------------------------------------------------------------------------------------

    private static void applyRestrictions(Context context, PolicyLedger ledger, ServerConfig config,
                                          boolean keepInstallWindow) {
        if (temporaryUnlockRemainingMs(context) > 0) {
            Log.i(TAG, "Temporary unlock active, restrictions not re-applied");
            return;
        }
        Set<String> desired = new LinkedHashSet<>(BASELINE_RESTRICTIONS);
        desired.addAll(csv(config.getRestrictions()));
        boolean skipInstall = keepInstallWindow || installWindowOpen(context);

        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = LegacyUtils.getAdminComponentName(context);
        for (String r : desired) {
            if (skipInstall && INSTALL_RESTRICTIONS.contains(r)) {
                ledger.add(PolicyLedger.RESTRICTIONS, r);  // recorded, applied when the window closes
                continue;
            }
            try {
                dpm.addUserRestriction(admin, r);
                ledger.add(PolicyLedger.RESTRICTIONS, r);
            } catch (Exception e) {
                Log.w(TAG, "addUserRestriction " + r + " failed: " + e.getMessage());
            }
        }
        for (String r : ledger.getSet(PolicyLedger.RESTRICTIONS)) {
            if (!desired.contains(r)) {
                try {
                    dpm.clearUserRestriction(admin, r);
                    ledger.remove(PolicyLedger.RESTRICTIONS, r);
                    RemoteLogger.log(context, Const.LOG_INFO, "Restriction removed by config: " + r);
                } catch (Exception e) {
                    Log.w(TAG, "clearUserRestriction " + r + " failed: " + e.getMessage());
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Packages: hidden / suspended / uninstall-blocked
    // ---------------------------------------------------------------------------------------

    private static void applyPackages(Context context, PolicyLedger ledger) {
        SettingsHelper settings = SettingsHelper.getInstance(context);
        String self = context.getPackageName();
        Set<String> locked = csv(settings.getAppPreference(self, SETTING_LOCKED));
        Set<String> unlocked = csv(settings.getAppPreference(self, SETTING_UNLOCKED));

        Set<String> hidden = csv(settings.getAppPreference(self, SETTING_HIDDEN));
        hidden.addAll(locked);
        hidden.removeAll(unlocked);
        hidden.remove(self);

        Set<String> suspended = csv(settings.getAppPreference(self, SETTING_SUSPENDED));
        suspended.addAll(locked);
        suspended.removeAll(unlocked);
        suspended.remove(self);

        Set<String> protectedPkgs = csv(settings.getAppPreference(self, SETTING_PROTECTED));
        protectedPkgs.remove(self);  // a Device Owner cannot be uninstalled anyway

        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = LegacyUtils.getAdminComponentName(context);

        // Hidden
        for (String pkg : hidden) {
            try {
                if (dpm.setApplicationHidden(admin, pkg, true) || dpm.isApplicationHidden(admin, pkg)) {
                    ledger.add(PolicyLedger.HIDDEN, pkg);
                }
            } catch (Exception e) {
                Log.w(TAG, "hide " + pkg + ": " + e.getMessage());
            }
        }
        for (String pkg : ledger.getSet(PolicyLedger.HIDDEN)) {
            if (!hidden.contains(pkg)) {
                try {
                    dpm.setApplicationHidden(admin, pkg, false);
                    ledger.remove(PolicyLedger.HIDDEN, pkg);
                } catch (Exception e) {
                    Log.w(TAG, "unhide " + pkg + ": " + e.getMessage());
                }
            }
        }

        // Suspended (API 24+)
        if (!suspended.isEmpty()) {
            try {
                String[] failed = dpm.setPackagesSuspended(admin, suspended.toArray(new String[0]), true);
                Set<String> failedSet = new HashSet<>(Arrays.asList(failed));
                for (String pkg : suspended) {
                    if (!failedSet.contains(pkg)) ledger.add(PolicyLedger.SUSPENDED, pkg);
                }
            } catch (Exception e) {
                Log.w(TAG, "suspend: " + e.getMessage());
            }
        }
        Set<String> toUnsuspend = new HashSet<>();
        for (String pkg : ledger.getSet(PolicyLedger.SUSPENDED)) {
            if (!suspended.contains(pkg)) toUnsuspend.add(pkg);
        }
        if (!toUnsuspend.isEmpty()) {
            try {
                dpm.setPackagesSuspended(admin, toUnsuspend.toArray(new String[0]), false);
                for (String pkg : toUnsuspend) ledger.remove(PolicyLedger.SUSPENDED, pkg);
            } catch (Exception e) {
                Log.w(TAG, "unsuspend: " + e.getMessage());
            }
        }

        // Uninstall blocked
        for (String pkg : protectedPkgs) {
            try {
                dpm.setUninstallBlocked(admin, pkg, true);
                ledger.add(PolicyLedger.UNINSTALL_BLOCKED, pkg);
            } catch (Exception e) {
                Log.w(TAG, "block uninstall " + pkg + ": " + e.getMessage());
            }
        }
        for (String pkg : ledger.getSet(PolicyLedger.UNINSTALL_BLOCKED)) {
            if (!protectedPkgs.contains(pkg)) {
                try {
                    dpm.setUninstallBlocked(admin, pkg, false);
                    ledger.remove(PolicyLedger.UNINSTALL_BLOCKED, pkg);
                } catch (Exception e) {
                    Log.w(TAG, "unblock uninstall " + pkg + ": " + e.getMessage());
                }
            }
        }
    }

    private static void applyAutoTime(Context context, PolicyLedger ledger) {
        String v = SettingsHelper.getInstance(context).getAppPreference(context.getPackageName(), SETTING_AUTO_TIME);
        boolean required = v == null || !"false".equalsIgnoreCase(v.trim());
        try {
            dpm(context).setAutoTimeRequired(LegacyUtils.getAdminComponentName(context), required);
            ledger.setFlag(PolicyLedger.FLAG_AUTO_TIME_REQUIRED, required);
        } catch (Exception e) {
            Log.w(TAG, "setAutoTimeRequired: " + e.getMessage());
        }
    }

    /**
     * Shows or hides the "SimFone Setup" app-drawer icon. Setting settings_icon = "hide" hides it;
     * anything else (or no setting) shows it. The dialer code works either way.
     */
    private static void applySetupIcon(Context context) {
        String v = SettingsHelper.getInstance(context).getAppPreference(context.getPackageName(), SETTING_SETUP_ICON);
        boolean show = v == null || !"hide".equalsIgnoreCase(v.trim());
        try {
            context.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(context, "com.simfone.mdm.ui.SetupIcon"),
                    show ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                         : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.w(TAG, "setup icon: " + e.getMessage());
        }
    }

    /** Without a config we never relax anything: just re-assert what was recorded. */
    private static void reassertLedger(Context context, PolicyLedger ledger) {
        DevicePolicyManager dpm = dpm(context);
        ComponentName admin = LegacyUtils.getAdminComponentName(context);
        boolean skipInstall = installWindowOpen(context);
        if (temporaryUnlockRemainingMs(context) == 0) {
            Set<String> restrictions = new LinkedHashSet<>(BASELINE_RESTRICTIONS);
            restrictions.addAll(ledger.getSet(PolicyLedger.RESTRICTIONS));
            for (String r : restrictions) {
                if (skipInstall && INSTALL_RESTRICTIONS.contains(r)) continue;
                try {
                    dpm.addUserRestriction(admin, r);
                } catch (Exception ignored) {
                }
            }
        }
        for (String pkg : ledger.getSet(PolicyLedger.HIDDEN)) {
            try {
                dpm.setApplicationHidden(admin, pkg, true);
            } catch (Exception ignored) {
            }
        }
        Set<String> suspended = ledger.getSet(PolicyLedger.SUSPENDED);
        if (!suspended.isEmpty()) {
            try {
                dpm.setPackagesSuspended(admin, suspended.toArray(new String[0]), true);
            } catch (Exception ignored) {
            }
        }
        for (String pkg : ledger.getSet(PolicyLedger.UNINSTALL_BLOCKED)) {
            try {
                dpm.setUninstallBlocked(admin, pkg, true);
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    public static Set<String> csv(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value == null || value.isEmpty()) return result;
        for (String s : value.split("[,;\\s]+")) {
            String t = s.trim();
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }

    static DevicePolicyManager dpm(Context context) {
        return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    static boolean isDeviceOwner(Context context) {
        DevicePolicyManager dpm = dpm(context);
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    private static SharedPreferences state(Context context) {
        Context c = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            c = c.createDeviceProtectedStorageContext();
        }
        return c.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
    }
}
