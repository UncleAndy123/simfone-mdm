/*
 * SimFone MDM
 * Licensed under the Apache License, Version 2.0.
 */

package com.simfone.mdm.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.simfone.mdm.security.OfflineCode;

/**
 * Installs the per-device offline release key (K_dev) from the provisioning computer:
 *
 *   adb shell am broadcast -a com.simfone.mdm.SET_RELEASE_KEY \
 *       -n com.simfone.mdm/.receiver.ReleaseKeyReceiver --es key <hex K_dev>
 *
 * The receiver is protected by android.permission.DUMP, which only the shell and the system
 * hold, so no installed app can send it. Trust on first use: an existing key is never replaced.
 */
public class ReleaseKeyReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.simfone.mdm.SET_RELEASE_KEY";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        boolean ok = OfflineCode.storeKey(context, intent.getStringExtra("key"), false);
        setResultCode(ok ? 1 : 0);
        setResultData(ok ? "release key installed" : "release key NOT installed (already set or malformed)");
    }
}
