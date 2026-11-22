/*
 * SimFone MDM
 * Based on Headwind MDM (https://h-mdm.com), Copyright (C) 2019 Headwind Solutions LLC.
 * Licensed under the Apache License, Version 2.0.
 */

package com.simfone.mdm.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.simfone.mdm.Const;
import com.simfone.mdm.core.AgentController;
import com.simfone.mdm.policy.PolicyEnforcer;

/**
 * Boot and self-update entry point. Re-applies the cached lockdown without network
 * (a reboot always ends temporary unlocks and install windows), then starts the agent.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(Const.LOG_TAG, "BootReceiver: " + action);
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AgentController.start(context, "package replaced");
            return;
        }
        PolicyEnforcer.resetTransientWindows(context);
        AgentController.start(context, "boot");
    }
}
