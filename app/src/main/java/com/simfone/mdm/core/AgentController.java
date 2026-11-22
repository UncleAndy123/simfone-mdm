/*
 * SimFone MDM
 * Based on Headwind MDM (https://h-mdm.com), Copyright (C) 2019 Headwind Solutions LLC.
 * Licensed under the Apache License, Version 2.0.
 */

package com.simfone.mdm.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.simfone.mdm.BuildConfig;
import com.simfone.mdm.Const;
import com.simfone.mdm.helper.Initializer;
import com.simfone.mdm.helper.SettingsHelper;
import com.simfone.mdm.policy.PolicyEnforcer;
import com.simfone.mdm.policy.ReleaseManager;
import com.simfone.mdm.security.FallbackManifest;
import com.simfone.mdm.util.DeviceInfoProvider;
import com.simfone.mdm.util.RemoteLogger;
import com.simfone.mdm.util.SystemUtils;
import com.simfone.mdm.util.Utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The headless agent's single entry point. There is no launcher UI: everything starts from
 * BootReceiver, the package-replaced receiver, provisioning completion, push and WorkManager.
 *
 * Order matters (fail-closed, plan section 11b):
 *  1. Re-apply the cached last-good policies - no network, no server needed.
 *  2. Only then try to reach the server (enroll if needed, sync config, start push).
 */
public class AgentController {
    private static final String TAG = Const.LOG_TAG;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean servicesStarted = false;

    public static void start(Context context, String reason) {
        final Context app = context.getApplicationContext();
        Log.i(TAG, "AgentController.start(" + reason + ")");
        executor.execute(() -> {
            String state = ReleaseManager.getState(app);
            if (ReleaseManager.STATE_RELEASED.equals(state)) {
                Log.i(TAG, "Released: staying inert");
                return;
            }

            // 1. Offline lockdown first
            try {
                PolicyEnforcer.applyCached(app);
            } catch (Exception e) {
                Log.e(TAG, "applyCached failed", e);
            }

            if (ReleaseManager.STATE_PARKED.equals(state)) {
                Log.i(TAG, "Parked: no network activity");
                return;
            }

            SettingsHelper settings = SettingsHelper.getInstance(app);
            if (!Utils.isDeviceOwner(app) && settings.getConfig() == null) {
                // Installed but not provisioned yet: never enroll an unmanaged phone
                Log.i(TAG, "Not Device Owner and not enrolled: waiting for provisioning");
                return;
            }
            settings.setAppStartTime(System.currentTimeMillis());
            if (!settings.isBaseUrlSet()) {
                // ADB-provisioned devices have no QR extras: pin the compiled-in server
                settings.setBaseUrl(BuildConfig.BASE_URL);
                settings.setSecondaryBaseUrl(BuildConfig.SECONDARY_BASE_URL);
                settings.setServerProject(BuildConfig.SERVER_PROJECT);
            }
            boolean deviceOwner = Utils.isDeviceOwner(app);
            if (deviceOwner && "".equals(settings.getImei())) {
                settings.setImei(DeviceInfoProvider.getImei(app, 0));
            }
            if (settings.getDeviceId() == null || settings.getDeviceId().isEmpty()) {
                if (deviceOwner) {
                    Utils.autoGrantPhonePermission(app);
                }
                if (!SystemUtils.autoSetDeviceId(app)) {
                    RemoteLogger.log(app, Const.LOG_ERROR,
                            "Device ID could not be determined (" + BuildConfig.DEVICE_ID_CHOICE + "), set it in hidden settings");
                    return;
                }
            }

            FallbackManifest.schedule(app);

            // 2. Network part (upstream Headwind background flow)
            main.post(() -> Initializer.init(app, () -> {
                servicesStarted = true;
                Initializer.startServicesAndLoadConfig(app);
            }));
        });
    }

    public static boolean isStarted() {
        return servicesStarted;
    }
}
