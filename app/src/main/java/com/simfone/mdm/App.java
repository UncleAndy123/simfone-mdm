/*
 * SimFone MDM
 * Based on Headwind MDM (https://h-mdm.com), Copyright (C) 2019 Headwind Solutions LLC.
 * Licensed under the Apache License, Version 2.0.
 */

package com.simfone.mdm;

import android.app.Application;

import com.simfone.mdm.policy.PolicyEnforcer;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Last line of defence: any crash still leaves the lockdown in place
        // (DPM policies are persisted by Android), and the next start re-applies the cached config.
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                PolicyEnforcer.applyCached(this);
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(t, e);
        });
    }
}
