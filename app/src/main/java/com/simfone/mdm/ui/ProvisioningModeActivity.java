/*
 * SimFone MDM
 * Licensed under the Apache License, Version 2.0.
 */

package com.simfone.mdm.ui;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;

import com.simfone.mdm.AdminReceiver;

/**
 * Android 10+ admin-integrated provisioning (QR code): GET_PROVISIONING_MODE.
 * Headless: always answers "fully managed device" without showing anything.
 */
public class ProvisioningModeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        PersistableBundle extras = in.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE);
        AdminReceiver.updateSettings(this, extras);
        Intent result = new Intent();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE);
        }
        if (extras != null) {
            result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, extras);
        }
        setResult(RESULT_OK, result);
        finish();
    }
}
