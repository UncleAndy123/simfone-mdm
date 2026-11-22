# SimFone MDM

A headless Android Device Owner agent for SimFone phones (Android 7.0+ / API 24), built on
[Headwind MDM](https://github.com/h-mdm/hmdm-android) (Apache-2.0, see `LICENSE` and `NOTICE`).

- **No home screen.** The separate SimFone launcher is a normal HOME app. SimFone MDM has no
  launcher icon and no resident UI.
- Silent install / update / remove, hide, suspend and protect apps, user restrictions, APN override.
- **Fail-closed:** losing the server never relaxes anything.
- **Removable without a factory reset**, even if the server is gone (offline code or signed manifest).
- Hidden D-pad settings: dial `*#*#7463#*#*`.

The Headwind server is unchanged; only its configurations are updated (see "Server setup").

---

## Build

Needs JDK 17+ and the Android SDK (platform 34).

```bash
./gradlew :app:assembleRelease :app:testDebugUnitTest
```

Release secrets go in `~/.gradle/gradle.properties` (never commit them):

```properties
SIMFONE_KEYSTORE=/secure/simfone-release.jks
SIMFONE_KEYSTORE_PASSWORD=...
SIMFONE_KEY_ALIAS=simfone
SIMFONE_KEY_PASSWORD=...
SIMFONE_REQUEST_SIGNATURE=...      # must equal the server's hash.secret
SIMFONE_LIBRARY_API_KEY=...        # must match the launcher / KyCall / dpadsms builds
SIMFONE_MANIFEST_PUBKEY=...        # from tools/simfone_admin.py new-manifest-key
SIMFONE_MANIFEST_URL=https://<second-provider>/simfone/manifest.json
```

Also set `BASE_URL` / `SECONDARY_BASE_URL` in `app/build.gradle` to your server.

> **Back up the signing keystore and the offline master key in 2+ places.**
> Losing the keystore means no more updates for the fleet. Losing the master key means no offline
> release codes for any device.

Release build: R8 + resource shrinking, about 1.8 MB.

### hmdm-lib (companion apps)

`lib/` is the AIDL client library used by the launcher, KyCall and dpadsms. It now targets
`com.simfone.mdm` (`lib/src/main/java/com/hmdm/Const.java`). **Rebuild those apps with this lib.**

---

## Provisioning

### QR code (Android 7+; on 10+ the headless provisioning activities answer automatically)

```json
{
 "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.simfone.mdm/com.simfone.mdm.AdminReceiver",
 "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "https://<server>/files/simfone-mdm.apk",
 "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": "<see below>",
 "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true,
 "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
   "com.hmdm.BASE_URL": "https://<server>",
   "com.hmdm.CONFIG": "<configuration name>",
   "com.hmdm.DEVICE_ID_USE": "serial",
   "com.simfone.RELEASE_KEY": "<per-device K_dev hex, optional>"
 }
}
```

Signature checksum (URL-safe base64 of the signing certificate's SHA-256):

```bash
keytool -exportcert -alias simfone -keystore simfone-release.jks | openssl dgst -binary -sha256 | openssl base64 | tr '+/' '-_' | tr -d '='
```

### ADB / TurboADB installer

```bash
adb install simfone-mdm.apk
```

```bash
adb shell dpm set-device-owner com.simfone.mdm/.AdminReceiver
```

```bash
adb shell am broadcast -a com.simfone.mdm.SET_RELEASE_KEY -n com.simfone.mdm/.receiver.ReleaseKeyReceiver --es key "$(python3 tools/simfone_admin.py device-key master.key SERIAL)"
```

The device ID defaults to the serial number (`DEVICE_ID_CHOICE`). The release key can only be set
once (trust on first use), and only from the shell (the receiver requires `android.permission.DUMP`).

---

## Server setup (Headwind panel)

Configuration → **Main app**: `com.simfone.mdm`.

Configuration → **Restrictions** is the source of truth for user restrictions, for example:

```
no_debugging_features,no_install_unknown_sources,no_install_apps,no_uninstall_apps,no_factory_reset,no_safe_boot,no_apps_control,no_config_date_time,no_add_user
```

Leave `no_debugging_features` out of test-device configurations (scrcpy/ADB). `no_safe_boot` is
always applied while SimFone manages the phone. SimFone lifts `no_install_apps` /
`no_uninstall_apps` only around its own installs and uninstalls, then re-applies them.

Configuration → **Application settings** for package `com.simfone.mdm`:

| Setting | Value | Effect |
|---|---|---|
| `hidden_packages` | CSV | `setApplicationHidden` – gone from every launcher |
| `suspended_packages` | CSV | `setPackagesSuspended` – visible but blocked, data kept |
| `locked_packages` | CSV | hidden + suspended (upstream compatibility) |
| `unlocked_packages` | CSV | never hidden/suspended (wins over the lists above) |
| `protected_packages` | CSV | `setUninstallBlocked` – e.g. launcher, KyCall, dpadsms |
| `companion_packages` | CSV | also receive the `RELEASED`/`PARKED` broadcast |
| `auto_time_required` | `true`/`false` | `setAutoTimeRequired` (default `true`) |
| `apn_override` | JSON | Android 9+ APN override, e.g. `{"name":"SimFone","apn":"simfone.apn","mccmnc":"310260","type":"default,supl"}`; `0` removes it |
| `intentional_relax` | `true` | allows a config that drops ≥50 % of restrictions/locked apps at once |
| `sms_filter_mode`, `sms_allowed_numbers`, `sms_blocked_numbers` | | pushed to dpadsms (may also be registered under `com.dpadsms`) |
| `proxy`, `certificates`, `client-certificates` | | as in upstream Headwind |

A package/restriction removed from the config is reversed (the policy ledger remembers what SimFone
applied). Removing most of them at once needs `intentional_relax=true` (sanity guard).

### Push messages (custom push in the panel or plugin)

| Type | Payload | Effect |
|---|---|---|
| `uninstallApp` | `{"pkg":"..."}` | silent uninstall |
| `releaseDevice` | `{"deviceId":"<id>"}` | full release (section 10b) |
| `parkDevice` | `{"deviceId":"<id>"}` | park: keep Device Owner, drop all control |
| `transferOwnership` | `{"deviceId":"<id>","pkg":"...","receiver":"..."}` | hand DO to a successor (Android 9+) |

`deviceId` must match the phone, so a mis-targeted push can never release a fleet.
Upstream `permissiveMode`, `exitKiosk` and `adminPanel` pushes are ignored.

---

## Hidden settings (`*#*#7463#*#*`)

The dialer must broadcast `android.provider.Telephony.SECRET_CODE` (KyCall must implement this;
check the Kyocera stock dialer). Fallback:

```bash
adb shell am start -n com.simfone.mdm/.ui.SettingsActivity
```

D-pad only: one focusable column, OK selects, BACK goes back. Opens with the config's admin
password (there is **no** default password), or an offline code. Status · Sync now ·
Server / Device ID · Wi-Fi · Send logs · Temporary admin unlock (15 min) · Check fallback manifest ·
Park · Release. Release, park, reactivate, unlock and server changes always need an offline code for
that action.

## Removal without a factory reset

See `docs/OPERATIONS.md`. In short: server push, offline challenge/response code
(`tools/simfone_admin.py response master.key DEVICE_ID CHALLENGE release`), or a signed fallback
manifest for the whole fleet. Release reverses every policy, clears Device Owner, disables SimFone's
components and shows a one-tap uninstall prompt. No app or user data is removed.

## Layout

| Path | What |
|---|---|
| `core/AgentController` | headless entry point (boot, update, provisioning) |
| `policy/PolicyEnforcer` | applies restrictions / packages / APN / auto-time from the cached config, install window, temporary unlock |
| `policy/PolicyLedger` | record of everything applied |
| `policy/ConfigSanity` | rejects empty configs and mass relaxes |
| `policy/ReleaseManager` | release / park / reactivate |
| `security/OfflineCode` | challenge/response codes, Keystore-backed K_dev |
| `security/FallbackManifest` | ECDSA P-256 signed manifest |
| `ui/SettingsActivity` | hidden D-pad settings |
| `tools/simfone_admin.py` | offline admin tool |

## Upstream

`upstream` remote = `h-mdm/hmdm-android`. The Java package was renamed to `com.simfone.mdm`, so
cherry-picks need `git cherry-pick -X find-renames`.
