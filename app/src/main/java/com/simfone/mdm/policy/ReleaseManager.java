/*
 * SimFone MDM
 * Licensed under the Apache License, Version 2.0.
 */

package com.simfone.mdm.policy;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.work.WorkManager;

import com.simfone.mdm.Const;
import com.simfone.mdm.R;
import com.simfone.mdm.service.LocationService;
import com.simfone.mdm.service.PushLongPollingService;
import com.simfone.mdm.service.SmsFilterManager;
import com.simfone.mdm.util.LegacyUtils;
import com.simfone.mdm.util.PushNotificationMqttWrapper;
import com.simfone.mdm.util.RemoteLogger;

import org.eclipse.paho.android.service.MqttService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hands the phone back without a factory reset (plan section 10).
 *
 * Single code path for every trigger (server push, offline code, signed fallback manifest):
 *  {@link #release} - reverse everything, clear Device Owner, go inert, prompt for uninstall
 *  {@link #park}    - reverse everything but KEEP Device Owner and go dormant (no network)
 *  {@link #reactivate} - leave park mode with a (possibly new) server
 *
 * Apps are NEVER uninstalled here (that would delete user data).
 */
public class ReleaseManager {
    private static final String TAG = Const.LOG_TAG;

    /** Sent to companions (launcher, KyCall, dpadsms) after release/park. Guarded by a signature permission. */
    public static final String ACTION_RELEASED = "com.simfone.mdm.RELEASED";
    public static final String ACTION_PARKED = "com.simfone.mdm.PARKED";
    public static final String PERMISSION_RELEASE = "com.simfone.mdm.permission.RELEASE_BROADCAST";

    public static final String STATE_ACTIVE = "active";
    public static final String STATE_PARKED = "parked";
    public static final String STATE_RELEASED = "released";

    private static final String PREFS = "simfone_lifecycle";
    private static final String KEY_STATE = "state";
    private static final String NOTIFICATION_CHANNEL = "simfone_release";

    public static synchronized String getState(Context context) {
        return prefs(context).getString(KEY_STATE, STATE_ACTIVE);
    }

    public static boolean isParkedOrReleased(Context context) {
        return !STATE_ACTIVE.equals(getState(context));
    }

    /** Called when SimFone becomes Device Owner again (re-provisioning). */
    public static synchronized void markActive(Context context) {
        prefs(context).edit().putString(KEY_STATE, STATE_ACTIVE).commit();
    }

    // ---------------------------------------------------------------------------------------

    public static synchronized void release(Context context, String reason) {
        Context app = context.getApplicationContext();
        RemoteLogger.log(app, Const.LOG_WARN, "RELEASE started: " + reason);
        // Persist first: if we crash halfway, boot will NOT re-apply policies, and the
        // release sequence below is idempotent so it can simply be re-run.
        prefs(app).edit().putString(KEY_STATE, STATE_RELEASED).commit();

        stopBackgroundWork(app);
        reverseAllPolicies(app);
        broadcast(app, ACTION_RELEASED);
        reportBestEffort(app, "released: " + reason);

        DevicePolicyManager dpm = PolicyEnforcer.dpm(app);
        try {
            if (dpm.isDeviceOwnerApp(app.getPackageName())) {
                //noinspection deprecation - still the only way for the DO to remove itself
                dpm.clearDeviceOwnerApp(app.getPackageName());
            }
        } catch (Exception e) {
            Log.e(TAG, "clearDeviceOwnerApp failed", e);
        }
        try {
            ComponentName admin = LegacyUtils.getAdminComponentName(app);
            if (dpm.isAdminActive(admin)) {
                dpm.removeActiveAdmin(admin);
            }
        } catch (Exception e) {
            Log.w(TAG, "removeActiveAdmin failed", e);
        }

        PolicyLedger.get(app).wipe();
        disableOwnComponents(app);
        promptUninstall(app);
    }

    public static synchronized void park(Context context, String reason) {
        Context app = context.getApplicationContext();
        RemoteLogger.log(app, Const.LOG_WARN, "PARK started: " + reason);
        reportBestEffort(app, "parked: " + reason);
        prefs(app).edit().putString(KEY_STATE, STATE_PARKED).commit();
        stopBackgroundWork(app);
        reverseAllPolicies(app);
        PolicyLedger.get(app).wipe();
        broadcast(app, ACTION_PARKED);
    }

    /** Leaves park mode. The caller sets the new server URL first if needed. */
    public static synchronized void reactivate(Context context) {
        markActive(context);
        RemoteLogger.log(context, Const.LOG_INFO, "Reactivated from park mode");
    }

    // ---------------------------------------------------------------------------------------
    // Steps
    // ---------------------------------------------------------------------------------------

    private static void stopBackgroundWork(Context app) {
        try {
            PushNotificationMqttWrapper.getInstance().disconnect(app);
        } catch (Exception e) {
            Log.w(TAG, "MQTT disconnect", e);
        }
        for (Class<?> c : new Class<?>[]{PushLongPollingService.class, MqttService.class,
                LocationService.class}) {
            try {
                app.stopService(new Intent(app, c));
            } catch (Exception ignored) {
            }
        }
        try {
            WorkManager.getInstance(app).cancelAllWork();
        } catch (Exception e) {
            Log.w(TAG, "cancelAllWork", e);
        }
    }

    /** Ledger first, then a sweep of the whole device for anything the ledger missed. */
    @SuppressLint("WrongConstant")
    static void reverseAllPolicies(Context app) {
        DevicePolicyManager dpm = PolicyEnforcer.dpm(app);
        if (!dpm.isDeviceOwnerApp(app.getPackageName())) {
            Log.w(TAG, "Not Device Owner, nothing to reverse");
            return;
        }
        ComponentName admin = LegacyUtils.getAdminComponentName(app);
        PolicyLedger ledger = PolicyLedger.get(app);
        PackageManager pm = app.getPackageManager();

        // --- Packages: un-hide (MUST happen while we are still DO), un-suspend, un-block uninstall
        List<PackageInfo> all;
        try {
            all = pm.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES
                    | PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.GET_PERMISSIONS);
        } catch (Exception e) {
            all = new ArrayList<>();
        }
        Set<String> pkgs = new HashSet<>();
        for (PackageInfo p : all) pkgs.add(p.packageName);
        pkgs.addAll(ledger.getSet(PolicyLedger.HIDDEN));
        pkgs.addAll(ledger.getSet(PolicyLedger.SUSPENDED));
        pkgs.addAll(ledger.getSet(PolicyLedger.UNINSTALL_BLOCKED));
        pkgs.addAll(ledger.getSet(PolicyLedger.PREFERRED_ACTIVITIES));

        List<String> toUnsuspend = new ArrayList<>();
        for (String pkg : pkgs) {
            try {
                if (dpm.isApplicationHidden(admin, pkg)) {
                    dpm.setApplicationHidden(admin, pkg, false);
                }
            } catch (Exception ignored) {
            }
            try {
                if (dpm.isPackageSuspended(admin, pkg)) toUnsuspend.add(pkg);
            } catch (Exception ignored) {
            }
            try {
                if (dpm.isUninstallBlocked(admin, pkg)) {
                    dpm.setUninstallBlocked(admin, pkg, false);
                }
            } catch (Exception ignored) {
            }
            try {
                dpm.clearPackagePersistentPreferredActivities(admin, pkg);
            } catch (Exception ignored) {
            }
        }
        if (!toUnsuspend.isEmpty()) {
            try {
                dpm.setPackagesSuspended(admin, toUnsuspend.toArray(new String[0]), false);
            } catch (Exception e) {
                Log.w(TAG, "unsuspend", e);
            }
        }

        // --- Permission grant states -> DEFAULT (user decides again)
        for (PackageInfo p : all) {
            if (p.requestedPermissions == null) continue;
            for (String perm : p.requestedPermissions) {
                if (!isDangerous(pm, perm)) continue;
                try {
                    if (dpm.getPermissionGrantState(admin, p.packageName, perm)
                            != DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT) {
                        dpm.setPermissionGrantState(admin, p.packageName, perm,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // --- User restrictions: everything this admin set (API 24+), plus the ledger
        Set<String> restrictions = new HashSet<>(ledger.getSet(PolicyLedger.RESTRICTIONS));
        try {
            Bundle b = dpm.getUserRestrictions(admin);
            for (String k : b.keySet()) {
                if (b.getBoolean(k)) restrictions.add(k);
            }
        } catch (Exception ignored) {
        }
        for (String r : restrictions) {
            try {
                dpm.clearUserRestriction(admin, r);
            } catch (Exception ignored) {
            }
        }

        // --- APN overrides
        ApnPolicy.clear(app, ledger);

        // --- Device-wide flags
        safe(() -> dpm.setSystemUpdatePolicy(admin, null));
        safe(() -> dpm.setKeyguardDisabledFeatures(admin, DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE));
        safe(() -> dpm.setKeyguardDisabled(admin, false));
        safe(() -> dpm.setStatusBarDisabled(admin, false));
        safe(() -> dpm.setRecommendedGlobalProxy(admin, null));
        safe(() -> dpm.setScreenCaptureDisabled(admin, false));
        safe(() -> dpm.setAutoTimeRequired(admin, false));
        safe(() -> dpm.setPasswordQuality(admin, DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED));
        safe(() -> dpm.setPasswordMinimumLength(admin, 0));
        safe(() -> dpm.setLockTaskPackages(admin, new String[0]));
        safe(() -> dpm.setShortSupportMessage(admin, null));
        safe(() -> dpm.setCameraDisabled(admin, false));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            safe(() -> dpm.clearResetPasswordToken(admin));
        }

        // --- Managed configurations SimFone pushed to companions
        try {
            SmsFilterManager.getInstance(app).clearRestrictions();
        } catch (Exception ignored) {
        }
        for (PackageInfo p : all) {
            try {
                if (!dpm.getApplicationRestrictions(admin, p.packageName).isEmpty()) {
                    dpm.setApplicationRestrictions(admin, p.packageName, new Bundle());
                }
            } catch (Exception ignored) {
            }
        }
        RemoteLogger.log(app, Const.LOG_INFO, "All policies reversed");
    }

    private static boolean isDangerous(PackageManager pm, String perm) {
        try {
            PermissionInfo pi = pm.getPermissionInfo(perm, 0);
            //noinspection deprecation
            return (pi.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS;
        } catch (Exception e) {
            return false;
        }
    }

    private static void broadcast(Context app, String action) {
        Intent i = new Intent(action);
        i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        app.sendBroadcast(i, PERMISSION_RELEASE);
        // Explicit broadcasts as well (implicit broadcasts don't reach manifest receivers on API 26+)
        // Companions are listed in the com.simfone.mdm setting "companion_packages" (CSV);
        // the protected (uninstall-blocked) packages are always included.
        Set<String> companions = new HashSet<>();
        companions.add(SmsFilterManager.MESSAGING_PACKAGE);
        com.simfone.mdm.helper.SettingsHelper sh = com.simfone.mdm.helper.SettingsHelper.getInstance(app);
        companions.addAll(PolicyEnforcer.csv(sh.getAppPreference(app.getPackageName(), "companion_packages")));
        companions.addAll(PolicyEnforcer.csv(sh.getAppPreference(app.getPackageName(), PolicyEnforcer.SETTING_PROTECTED)));
        for (String pkg : companions) {
            try {
                app.sendBroadcast(new Intent(action).setPackage(pkg)
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES), PERMISSION_RELEASE);
            } catch (Exception ignored) {
            }
        }
    }

    private static void reportBestEffort(Context app, String message) {
        try {
            RemoteLogger.log(app, Const.LOG_WARN, message);
            RemoteLogger.sendLogsToServer(app);
        } catch (Exception ignored) {
        }
    }

    /** After release SimFone is inert: no receivers, services or providers stay enabled. */
    private static void disableOwnComponents(Context app) {
        PackageManager pm = app.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(app.getPackageName(), PackageManager.GET_RECEIVERS
                    | PackageManager.GET_SERVICES | PackageManager.GET_ACTIVITIES
                    | PackageManager.MATCH_DISABLED_COMPONENTS);
            List<ComponentInfo> comps = new ArrayList<>();
            if (info.receivers != null) for (ComponentInfo c : info.receivers) comps.add(c);
            if (info.services != null) for (ComponentInfo c : info.services) comps.add(c);
            if (info.activities != null) for (ComponentInfo c : info.activities) comps.add(c);
            for (ComponentInfo c : comps) {
                if (c.name.endsWith(".ui.ReleasedActivity")) continue;  // the uninstall prompt
                try {
                    pm.setComponentEnabledSetting(new ComponentName(app, c.name),
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "disableOwnComponents", e);
        }
    }

    /** One tap to uninstall. A fully silent self-uninstall is not possible once DO is cleared. */
    @SuppressLint("MissingPermission")
    private static void promptUninstall(Context app) {
        Intent delete = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(NOTIFICATION_CHANNEL,
                        app.getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT));
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getActivity(app, 0, delete, flags);
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(app, NOTIFICATION_CHANNEL) : new Notification.Builder(app);
            nm.notify(4242, b.setSmallIcon(R.drawable.ic_mqtt_service)
                    .setContentTitle(app.getString(R.string.released_title))
                    .setContentText(app.getString(R.string.released_text))
                    .setContentIntent(pi).setAutoCancel(true).build());
        } catch (Exception e) {
            Log.w(TAG, "release notification", e);
        }
        try {
            app.startActivity(delete);
        } catch (Exception e) {
            Log.w(TAG, "uninstall prompt", e);
        }
    }

    private interface Op { void run() throws Exception; }

    private static void safe(Op op) {
        try {
            op.run();
        } catch (Throwable ignored) {
        }
    }

    private static SharedPreferences prefs(Context context) {
        Context c = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            c = c.createDeviceProtectedStorageContext();
        }
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
