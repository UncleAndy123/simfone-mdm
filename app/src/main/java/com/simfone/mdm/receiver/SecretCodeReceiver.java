/*
 * SimFone MDM
 * Licensed under the Apache License, Version 2.0.
 */

package com.simfone.mdm.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.simfone.mdm.ui.SettingsActivity;

/**
 * Opens the hidden settings when the dialer broadcasts android.provider.Telephony.SECRET_CODE
 * for *#*#7463#*#* (the host is set in the manifest). The dialer (KyCall or the stock dialer)
 * must send this broadcast. Fallback over ADB:
 *   adb shell am start -n com.simfone.mdm/.ui.SettingsActivity
 */
public class SecretCodeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent i = new Intent(context, SettingsActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(i);
    }
}
