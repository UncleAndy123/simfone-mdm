/*
 * SimFone MDM
 * Licensed under the Apache License, Version 2.0.
 */

package com.simfone.mdm.policy;

import android.annotation.TargetApi;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.telephony.data.ApnSetting;
import android.text.TextUtils;
import android.util.Log;

import com.simfone.mdm.Const;
import com.simfone.mdm.helper.SettingsHelper;
import com.simfone.mdm.util.LegacyUtils;
import com.simfone.mdm.util.RemoteLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * APN override (Android 9+, API 28).
 *
 * Driven by the com.simfone.mdm application setting "apn_override": a JSON object or array, e.g.
 * <pre>
 * {"name":"SimFone","apn":"simfone.apn","mccmnc":"310260","type":"default,supl",
 *  "protocol":"IPV4V6","user":"","password":"","auth":"none"}
 * </pre>
 * An empty value or "0" removes SimFone's overrides. On Android 7/8 the setting is ignored.
 */
public class ApnPolicy {
    public static final String SETTING = "apn_override";

    static void apply(Context context, PolicyLedger ledger) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        String value = SettingsHelper.getInstance(context).getAppPreference(context.getPackageName(), SETTING);
        if (value == null) {
            // Not managed by this config: keep whatever is there (fail-closed, no change)
            return;
        }
        applyP(context, ledger, value.trim());
    }

    @TargetApi(Build.VERSION_CODES.P)
    private static void applyP(Context context, PolicyLedger ledger, String value) {
        DevicePolicyManager dpm = PolicyEnforcer.dpm(context);
        ComponentName admin = LegacyUtils.getAdminComponentName(context);
        try {
            // Rebuild from scratch each time: remove our previous overrides, then add the desired ones
            String sig = Integer.toHexString(value.hashCode());
            if (ledger.getSet(PolicyLedger.APN_IDS).contains("sig:" + sig)) {
                return;  // unchanged since last apply
            }
            clear(context, ledger);
            if (value.isEmpty() || "0".equals(value)) {
                return;
            }
            JSONArray arr = value.startsWith("[") ? new JSONArray(value) : new JSONArray().put(new JSONObject(value));
            for (int i = 0; i < arr.length(); i++) {
                ApnSetting apn = build(arr.getJSONObject(i));
                int id = dpm.addOverrideApn(admin, apn);
                if (id >= 0) {
                    ledger.add(PolicyLedger.APN_IDS, String.valueOf(id));
                } else {
                    RemoteLogger.log(context, Const.LOG_WARN, "addOverrideApn failed for " + apn.getApnName());
                }
            }
            dpm.setOverrideApnsEnabled(admin, true);
            ledger.add(PolicyLedger.APN_IDS, "sig:" + sig);
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "APN override failed: " + e.getMessage());
        }
    }

    /** Removes every override APN SimFone added and disables overrides. Used by release. */
    public static void clear(Context context, PolicyLedger ledger) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        clearP(context, ledger);
    }

    @TargetApi(Build.VERSION_CODES.P)
    private static void clearP(Context context, PolicyLedger ledger) {
        DevicePolicyManager dpm = PolicyEnforcer.dpm(context);
        ComponentName admin = LegacyUtils.getAdminComponentName(context);
        try {
            List<ApnSetting> existing = dpm.getOverrideApns(admin);
            if (existing != null) {
                for (ApnSetting a : existing) {
                    dpm.removeOverrideApn(admin, a.getId());
                }
            }
            dpm.setOverrideApnsEnabled(admin, false);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "APN clear failed: " + e.getMessage());
        }
        ledger.clear(PolicyLedger.APN_IDS);
    }

    @TargetApi(Build.VERSION_CODES.P)
    private static ApnSetting build(JSONObject o) {
        ApnSetting.Builder b = new ApnSetting.Builder()
                .setEntryName(o.optString("name", "SimFone"))
                .setApnName(o.optString("apn"))
                .setApnTypeBitmask(typeMask(o.optString("type", "default,supl")))
                .setCarrierEnabled(true);
        String mccmnc = o.optString("mccmnc", "");
        if (mccmnc.length() >= 5) {
            b.setOperatorNumeric(mccmnc);
        }
        String user = o.optString("user", "");
        if (!TextUtils.isEmpty(user)) b.setUser(user);
        String password = o.optString("password", "");
        if (!TextUtils.isEmpty(password)) b.setPassword(password);
        String protocol = o.optString("protocol", "IPV4V6");
        int p = protocol(protocol);
        b.setProtocol(p);
        b.setRoamingProtocol(p);
        b.setAuthType(auth(o.optString("auth", "none")));
        return b.build();
    }

    private static int typeMask(String types) {
        int mask = 0;
        for (String t : types.toLowerCase().split("[,\\s]+")) {
            switch (t) {
                case "default": mask |= ApnSetting.TYPE_DEFAULT; break;
                case "mms": mask |= ApnSetting.TYPE_MMS; break;
                case "supl": mask |= ApnSetting.TYPE_SUPL; break;
                case "dun": mask |= ApnSetting.TYPE_DUN; break;
                case "hipri": mask |= ApnSetting.TYPE_HIPRI; break;
                case "fota": mask |= ApnSetting.TYPE_FOTA; break;
                case "ims": mask |= ApnSetting.TYPE_IMS; break;
                case "cbs": mask |= ApnSetting.TYPE_CBS; break;
                case "ia": mask |= ApnSetting.TYPE_IA; break;
                case "emergency": mask |= ApnSetting.TYPE_EMERGENCY; break;
                default: break;
            }
        }
        return mask == 0 ? ApnSetting.TYPE_DEFAULT : mask;
    }

    private static int protocol(String p) {
        switch (p.toUpperCase()) {
            case "IP": case "IPV4": return ApnSetting.PROTOCOL_IP;
            case "IPV6": return ApnSetting.PROTOCOL_IPV6;
            default: return ApnSetting.PROTOCOL_IPV4V6;
        }
    }

    private static int auth(String a) {
        switch (a.toLowerCase()) {
            case "pap": return ApnSetting.AUTH_TYPE_PAP;
            case "chap": return ApnSetting.AUTH_TYPE_CHAP;
            case "pap_or_chap": return ApnSetting.AUTH_TYPE_PAP_OR_CHAP;
            default: return ApnSetting.AUTH_TYPE_NONE;
        }
    }
}
