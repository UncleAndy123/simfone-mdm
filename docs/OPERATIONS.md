# SimFone MDM – operations

## 1. Keys to create once (offline admin computer)

```bash
python3 tools/simfone_admin.py new-master master.key
```

```bash
python3 tools/simfone_admin.py new-manifest-key manifest_private.pem
```

The second command prints `SIMFONE_MANIFEST_PUBKEY=...` for the build. Keep `master.key`,
`manifest_private.pem` and the signing keystore offline, each in 2+ places.

## 2. Per-device release key

`K_dev = HMAC-SHA256(master, deviceId)`. It is never in the APK.

```bash
python3 tools/simfone_admin.py device-key master.key <DEVICE_ID>
```

Deliver it either in a per-device QR (`com.simfone.RELEASE_KEY`) or over ADB during install
(see README). The phone imports it into the Android Keystore and never accepts a second key.

## 3. Releasing or parking one phone

| Situation | How |
|---|---|
| Server up | Push `releaseDevice` / `parkDevice` with `{"deviceId":"<id>"}` |
| Server lost, phone in hand | `*#*#7463#*#*` → Release (or Park) → read the 6-digit challenge → `python3 tools/simfone_admin.py response master.key <DEVICE_ID> <CHALLENGE> release` → enter the 10 digits |
| Server lost, whole fleet | Signed fallback manifest (section 4) |

5 wrong codes lock the screen for 5 min, then 10, 20 … up to 24 h. The counter survives reboots.

**Release** (`ReleaseManager.release`):
1. Marks the state `released` (boot will not re-apply anything).
2. Stops push, WorkManager jobs and location.
3. Un-hides, un-suspends and un-blocks every package (ledger + full device sweep), clears persistent
   preferred activities.
4. Clears every user restriction this admin set (`getUserRestrictions`), APN overrides, permission
   grant states (→ default), system update policy, keyguard / status bar flags, global proxy,
   screen-capture, auto-time, password quality, lock-task packages, managed app configurations.
5. Broadcasts `com.simfone.mdm.RELEASED` (signature permission
   `com.simfone.mdm.permission.RELEASE_BROADCAST`).
6. Sends logs to the server if reachable.
7. `clearDeviceOwnerApp`, disables its own components, shows a one-tap Uninstall prompt
   (notification + `ACTION_DELETE`).

No app is ever uninstalled during release.

**Park** = steps 2–5, keeps Device Owner, no network. Reactivate from hidden settings with a
`reactivate` code and (optionally) a new server URL – no reset needed. Works on Android 7.

## 4. Fallback manifest (server lost, whole fleet)

```bash
python3 tools/simfone_admin.py sign-manifest manifest_private.pem release "*" 14 > manifest.json
```

```bash
python3 tools/simfone_admin.py sign-manifest manifest_private.pem newServerUrl "*" 14 https://new-server.example > manifest.json
```

Publish `manifest.json` at `SIMFONE_MANIFEST_URL` (a different provider from the server, e.g.
GitHub Pages). Phones check it once a day, **only** after the primary server has been unreachable
for `FALLBACK_MANIFEST_AFTER_DAYS` (default 3) while they have internet. Each manifest has an
expiry and a nonce; a used nonce is never accepted again. `targets` can list device IDs instead of
`"*"`. Use `newServerUrl` to point the fleet at a rebuilt server instead of removing SimFone.

The server being unreachable is never a trigger by itself; only a valid signature is.

## 5. Fail-closed behaviour (what keeps the phone locked)

- DPM policies are stored by Android itself; SimFone need not run for them to hold.
- The last-good config is cached and re-applied on boot, after self-update
  (`MY_PACKAGE_REPLACED`), after crashes and by a relock watchdog – no network needed.
- Sync errors (HTTP errors, DNS, timeouts, unsigned/invalid responses) keep the current state.
- A config that is empty, or would remove ≥50 % of restrictions or locked apps at once, is
  rejected unless it carries `intentional_relax=true`.
- Upstream behaviours removed: releasing all restrictions at the start of every sync, the
  `12345678` default admin password, the kiosk "permissive mode", reading `hmdm_init.json`
  from public Downloads, accepting an unsigned enroll response when `CHECK_SIGNATURE` is on.
- Temporary admin unlock (15 min, code-gated) and install windows (20 min max) end on reboot.

## 6. Migrating the Pure-speech fleet (`com.hmdm.launcher` → `com.simfone.mdm`)

Android 9+ only (`transferOwnership`). Android 7/8 phones already enrolled need a reset;
new E4610s are provisioned straight onto SimFone.

SimFone side is done: `<support-transfer-ownership/>` is declared and
`AdminReceiver.onTransferOwnershipComplete` imports the server URL / device ID from the bundle and
starts the agent.

Pure-speech side (new push type, ship before the migration) – add to
`PushNotificationProcessor.process()`:

```java
} else if (message.getMessageType().equals("transferOwnership")) {
    executor.execute(() -> {
        try {
            JSONObject p = message.getPayloadJSON();
            SettingsHelper s = SettingsHelper.getInstance(context);
            if (p == null || !s.getDeviceId().equals(p.optString("deviceId"))) return;
            PersistableBundle b = new PersistableBundle();
            b.putString("com.hmdm.BASE_URL", s.getBaseUrl());
            b.putString("com.hmdm.SECONDARY_BASE_URL", s.getSecondaryBaseUrl());
            b.putString("com.hmdm.SERVER_PROJECT", s.getServerProject());
            b.putString("com.hmdm.DEVICE_ID", s.getDeviceId());
            // Un-hide / un-suspend everything first: hidden apps stay hidden forever otherwise
            // (same release steps Pure-speech needs in removeDeviceOwner).
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            dpm.transferOwnership(LegacyUtils.getAdminComponentName(context),
                    new ComponentName("com.simfone.mdm", "com.simfone.mdm.AdminReceiver"), b);
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_ERROR, "transferOwnership failed: " + e.getMessage());
        }
    });
    return;
}
```

Steps per phone:
1. Add `com.simfone.mdm` to the Pure-speech configuration so it installs silently.
2. Send push `transferOwnership` with `{"deviceId":"<id>"}`.
3. Check in SimFone hidden settings → Status that Device Owner = true and the sync worked.
4. Move the device to the SimFone configuration (main app `com.simfone.mdm`) and remove
   `com.hmdm.launcher` from it (uninstall).

**Pilot on one test phone first:** a failed transfer can leave the phone with no Device Owner.

## 7. Tests still to run on hardware

Not possible in the build container (no devices, no KVM). On the E4610 (7.1), Android 9 and 13:

- [ ] `adb shell getprop ro.build.version.release` on the E4610 (expect 7.1.1+ for Let's Encrypt;
      ISRG Root X1 is bundled anyway)
- [ ] QR provisioning and `dpm set-device-owner`; enrollment with serial as device ID
- [ ] Silent install/update/uninstall with `no_install_apps,no_uninstall_apps` set
- [ ] Hidden/suspended/protected lists; removing one from the config reverses it
- [ ] `*#*#7463#*#*` from KyCall and from the stock dialer; D-pad navigation
- [ ] Release → all apps visible, restrictions gone, SimFone uninstallable, nothing wiped
- [ ] Park → DO kept, no network traffic (`dumpsys netstats`), reactivation works
- [ ] Wrong codes lock out; expired / tampered / replayed manifest rejected
- [ ] Pull-the-plug: server off 7+ days (clock), reboots, airplane mode, no SIM, wrong URL,
      server returning 404/500/empty config → policies unchanged
- [ ] Safe mode, ADB `pm unhide`, clock change, Settings force-stop → blocked
- [ ] Kill SimFone mid-sync (`am force-stop` via root/emulator) → still locked after reboot
- [ ] `dumpsys meminfo com.simfone.mdm`, `batterystats`, APK size vs Pure-speech
- [ ] `transferOwnership` pilot from Pure-speech
