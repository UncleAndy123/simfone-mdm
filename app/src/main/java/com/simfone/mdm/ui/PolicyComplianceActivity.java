/*
 * SimFone MDM
 * Licensed under the Apache License, Version 2.0.
 */

package com.simfone.mdm.ui;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.os.Bundle;
import android.os.PersistableBundle;

import com.simfone.mdm.AdminReceiver;
import com.simfone.mdm.core.AgentController;

/**
 * Android 10+ admin-integrated provisioning: ADMIN_POLICY_COMPLIANCE.
 * Headless: store the QR extras, start the agent in the background and return at once.
 * Enrollment, app installs and policies continue after the setup wizard.
 */
public class PolicyComplianceActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PersistableBundle extras = getIntent().getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE);
        if (extras != null) {
            AdminReceiver.updateSettings(this, extras);
        }
        AgentController.start(getApplicationContext(), "provisioning compliance");
        setResult(RESULT_OK);
        finish();
    }
}
