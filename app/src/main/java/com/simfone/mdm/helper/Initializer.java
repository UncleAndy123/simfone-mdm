package com.simfone.mdm.helper;

import static android.content.Context.MODE_PRIVATE;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.simfone.mdm.BuildConfig;
import com.simfone.mdm.Const;
import com.simfone.mdm.json.Application;
import com.simfone.mdm.json.DeviceInfo;
import com.simfone.mdm.json.RemoteFile;
import com.simfone.mdm.json.ServerConfig;
import com.simfone.mdm.pro.ProUtils;
import com.simfone.mdm.pro.worker.DetailedInfoWorker;
import com.simfone.mdm.service.PushLongPollingService;
import com.simfone.mdm.task.SendDeviceInfoTask;
import com.simfone.mdm.util.ConnectionWaiter;
import com.simfone.mdm.util.DeviceInfoProvider;
import com.simfone.mdm.util.InstallUtils;
import com.simfone.mdm.util.RemoteLogger;
import com.simfone.mdm.util.Utils;
import com.simfone.mdm.worker.PushNotificationWorker;
import com.simfone.mdm.worker.ScheduledAppUpdateWorker;
import com.simfone.mdm.worker.SendDeviceInfoWorker;

import org.eclipse.paho.android.service.MqttAndroidClient;

import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Shared initialization code which should run either by MainActivity (in foreground mode)
// or by InitialSetupActivity (in background mode)
public class Initializer {
    private static ExecutorService executor = Executors.newSingleThreadExecutor();
    private static Handler uiHandler = new Handler(Looper.getMainLooper());

    public static void init(Context context, Runnable completion) {
        // Background work
        executor.execute(() -> {
            // Crashlytics is not included in the open-source version
            ProUtils.initCrashlytics(context);

            if (BuildConfig.TRUST_ANY_CERTIFICATE) {
                InstallUtils.initUnsafeTrustManager();
            }

            // DISALLOW_SAFE_BOOT is part of PolicyEnforcer's baseline (applied with the ledger)
            Utils.initPasswordReset(context);

            RemoteLogger.log(context, Const.LOG_INFO, "MDM Launcher " + BuildConfig.VERSION_NAME + "-" + Utils.getLauncherVariant() + " started");

            InstallUtils.clearTempFiles(context);

            // Install the certificates (repeat the action from InitialSetupActivity because
            // the customer may wish to install new certificates without re-enrolling the device
            CertInstaller.installCertificatesFromAssets(context);

            ConnectionWaiter.waitForConnect(context, () -> {
                DetailedInfoWorker.schedule(context);
                PushNotificationWorker.schedule(context);
                ScheduledAppUpdateWorker.schedule(context);

                // Run completion in the UI thread
                uiHandler.post(completion);
            });
        });
    }

    public static void startServicesAndLoadConfig(Context context) {
        // Start Push service
        String pushOptions = null;
        int keepaliveTime = Const.DEFAULT_PUSH_ALARM_KEEPALIVE_TIME_SEC;
        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        if (settingsHelper != null && settingsHelper.getConfig() != null) {
            pushOptions = settingsHelper.getConfig().getPushOptions();
            Integer newKeepaliveTime = settingsHelper.getConfig().getKeepaliveTime();
            if (newKeepaliveTime != null && newKeepaliveTime >= 30) {
                keepaliveTime = newKeepaliveTime;
            }
        }
        if (BuildConfig.MQTT_SERVICE_FOREGROUND && pushOptions != null) {
            if (pushOptions.equals(ServerConfig.PUSH_OPTIONS_MQTT_WORKER)
                    || pushOptions.equals(ServerConfig.PUSH_OPTIONS_MQTT_ALARM)) {
                try {
                    URL url = new URL(settingsHelper.getBaseUrl());
                    // Broadcast receivers are not allowed to bind to services
                    // Therefore we start a service, and it binds to itself using
                    // PushNotificationMqttWrapper.getInstance().connect()
                    Intent serviceStartIntent = new Intent();
                    serviceStartIntent.setClassName(context, MqttAndroidClient.SERVICE_NAME);
                    serviceStartIntent.putExtra(MqttAndroidClient.EXTRA_START_AT_BOOT, true);
                    serviceStartIntent.putExtra(MqttAndroidClient.EXTRA_DOMAIN, url.getHost());
                    serviceStartIntent.putExtra(MqttAndroidClient.EXTRA_KEEPALIVE_TIME, keepaliveTime);
                    serviceStartIntent.putExtra(MqttAndroidClient.EXTRA_PUSH_OPTIONS, pushOptions);
                    serviceStartIntent.putExtra(MqttAndroidClient.EXTRA_DEVICE_ID, settingsHelper.getDeviceId());
                    Object service = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                            context.startForegroundService(serviceStartIntent) :
                            context.startService(serviceStartIntent);
                    Log.i(Const.LOG_TAG, "Starting Push service from BootReceiver");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if (pushOptions.equals(ServerConfig.PUSH_OPTIONS_POLLING)) {
                try {
                    Intent serviceStartIntent = new Intent(context, PushLongPollingService.class);
                    serviceStartIntent.putExtra(Const.EXTRA_ENABLED, true);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceStartIntent);
                    } else {
                        context.startService(serviceStartIntent);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }


        // Send pending logs to server
        RemoteLogger.sendLogsToServer(context);

        final ConfigUpdater.UINotifier uiNotifier = new ConfigUpdater.UINotifier() {
            @Override
            public void onConfigUpdateStart() {
            }

            @Override
            public void onConfigUpdateServerError(String errorText) {
            }

            @Override
            public void onConfigUpdateNetworkError(String errorText) {
            }

            @Override
            public void onConfigLoaded() {
            }

            @Override
            public void onPoliciesUpdated() {
            }

            @Override
            public void onFileDownloading(RemoteFile remoteFile) {
            }

            @Override
            public void onDownloadProgress(int progress, long total, long current) {
            }

            @Override
            public void onFileDownloadError(RemoteFile remoteFile) {
            }

            @Override
            public void onFileInstallError(RemoteFile remoteFile) {
            }

            @Override
            public void onAppUpdateStart() {
            }

            @Override
            public void onAppRemoving(Application application) {
            }

            @Override
            public void onAppDownloading(Application application) {
            }

            @Override
            public void onAppInstalling(Application application) {
            }

            @Override
            public void onAppDownloadError(Application application) {
            }

            @Override
            public void onAppInstallError(String packageName) {
            }

            @Override
            public void onAppInstallComplete(String packageName) {
            }

            @Override
            public void onConfigUpdateComplete() {
                // In background mode, we need to send the information to the server once update is complete
                SendDeviceInfoTask sendDeviceInfoTask = new SendDeviceInfoTask(context);
                DeviceInfo deviceInfo = DeviceInfoProvider.getDeviceInfo(context, true, true);
                sendDeviceInfoTask.execute(deviceInfo);
                SendDeviceInfoWorker.scheduleDeviceInfoSending(context);
            }

            @Override
            public void onAllAppInstallComplete() {
            }
        };
        ConfigUpdater.forceConfigUpdate(context, uiNotifier, false);
    }

    // Used by InitialSetupActivity
    @SuppressLint("MissingPermission")
    public static void applyEarlyNonInteractivePolicies(Context context, ServerConfig config) {
        if (config.getSystemUpdateType() != null &&
                config.getSystemUpdateType() != ServerConfig.SYSTEM_UPDATE_DEFAULT &&
                Utils.isDeviceOwner(context)) {
            Utils.setSystemUpdatePolicy(context, config.getSystemUpdateType(), config.getSystemUpdateFrom(), config.getSystemUpdateTo());
        }

        if (config.getBluetooth() != null) {
            try {
                BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                if (bluetoothAdapter != null) {
                    boolean enabled = bluetoothAdapter.isEnabled();
                    if (config.getBluetooth() && !enabled) {
                        bluetoothAdapter.enable();
                    } else if (!config.getBluetooth() && enabled) {
                        bluetoothAdapter.disable();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (config.getTimeZone() != null) {
            Utils.setTimeZone(config.getTimeZone(), context);
        }

        if (config.getUsbStorage() != null) {
            Utils.lockUsbStorage(config.getUsbStorage(), context);
        }

        // Null value is processed here, it means unlock brightness
        Utils.setBrightnessPolicy(config.getAutoBrightness(), config.getBrightness(), context);
        Utils.setScreenTimeoutPolicy(config.getManageTimeout(), config.getTimeout(), context);

        if (config.getManageVolume() != null && config.getManageVolume() && config.getVolume() != null) {
            Utils.lockVolume(false, context);
            if (!Utils.setVolume(config.getVolume(), context)) {
                RemoteLogger.log(context, Const.LOG_WARN, "Failed to set the device volume");
            }
        }

        if (config.getLockVolume() != null) {
            Utils.lockVolume(config.getLockVolume(), context);
        }

        Utils.disableScreenshots(config.isDisableScreenshots(), context);
    }

}
