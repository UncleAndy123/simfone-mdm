/*
 * SimFone MDM
 * Licensed under the Apache License, Version 2.0.
 */

package com.simfone.mdm.policy;

import com.simfone.mdm.json.ApplicationSetting;
import com.simfone.mdm.json.ServerConfig;

import java.util.HashSet;
import java.util.Set;

/**
 * Sanity guard for configs received from the server (plan section 11b).
 *
 * A config is rejected (the device keeps its current state and retries later) when:
 *  - it is structurally empty (no restrictions AND no applications AND no settings), or
 *  - it would drop most of the current lockdown at once (at least half of the user restrictions,
 *    or at least half of the hidden/suspended/locked packages)
 * unless the com.simfone.mdm application setting "intentional_relax" is "true".
 *
 * This protects against a server bug, a bad edit, or a device moved to the wrong configuration.
 */
public class ConfigSanity {
    public static final String SETTING_INTENTIONAL_RELAX = "intentional_relax";

    /** @return null if the config is acceptable, otherwise the reason it was rejected */
    public static String check(String selfPackage, ServerConfig current, ServerConfig next) {
        if (next == null) {
            return "empty config";
        }
        boolean emptyRestrictions = isBlank(next.getRestrictions());
        boolean emptyApps = next.getApplications() == null || next.getApplications().isEmpty();
        boolean emptySettings = next.getApplicationSettings() == null || next.getApplicationSettings().isEmpty();
        if (emptyRestrictions && emptyApps && emptySettings) {
            return "config has no restrictions, applications or settings";
        }
        if (current == null) {
            return null;  // first enrollment: nothing to compare against
        }
        if ("true".equalsIgnoreCase(setting(next, selfPackage, SETTING_INTENTIONAL_RELAX))) {
            return null;
        }

        Set<String> curR = PolicyEnforcer.csv(current.getRestrictions());
        Set<String> nextR = PolicyEnforcer.csv(next.getRestrictions());
        if (droppedMost(curR, nextR)) {
            return "would remove " + dropped(curR, nextR) + " of " + curR.size()
                    + " restrictions without intentional_relax";
        }

        Set<String> curP = lockedPackages(current, selfPackage);
        Set<String> nextP = lockedPackages(next, selfPackage);
        if (droppedMost(curP, nextP)) {
            return "would unlock " + dropped(curP, nextP) + " of " + curP.size()
                    + " locked packages without intentional_relax";
        }
        return null;
    }

    private static Set<String> lockedPackages(ServerConfig c, String self) {
        Set<String> s = new HashSet<>();
        s.addAll(PolicyEnforcer.csv(setting(c, self, PolicyEnforcer.SETTING_HIDDEN)));
        s.addAll(PolicyEnforcer.csv(setting(c, self, PolicyEnforcer.SETTING_SUSPENDED)));
        s.addAll(PolicyEnforcer.csv(setting(c, self, PolicyEnforcer.SETTING_LOCKED)));
        s.removeAll(PolicyEnforcer.csv(setting(c, self, PolicyEnforcer.SETTING_UNLOCKED)));
        return s;
    }

    private static boolean droppedMost(Set<String> current, Set<String> next) {
        if (current.size() < 2) return false;
        return dropped(current, next) * 2 >= current.size();
    }

    private static int dropped(Set<String> current, Set<String> next) {
        int n = 0;
        for (String s : current) if (!next.contains(s)) n++;
        return n;
    }

    private static String setting(ServerConfig c, String pkg, String name) {
        if (c.getApplicationSettings() == null) return null;
        for (ApplicationSetting s : c.getApplicationSettings()) {
            if (name.equals(s.getName()) && (s.getPackageId() == null || pkg.equals(s.getPackageId()))) {
                return s.getValue();
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
