# GPS Emulator

> [!WARNING]
> **Strictly for authorized use only.** GPS Emulator is a developer and security-testing
> tool. Use it only on devices and applications you own or have explicit permission to
> test. Do not use it to deceive others, evade restrictions, or perform unauthorized activities.

A small native Android app for **authorized GPS mock-location testing**.
Search for a destination, choose a point on the interactive map, or enter latitude and longitude,
save frequently used pins, and simulate a fixed location (or move it with a 360° joystick)
with a visible foreground session and a Stop action.

Android explicitly marks the locations as mocked. GPS Emulator does not hide that flag,
root the device, bypass app protections, or change IP/cellular location.

## Features

- Decimal-degree latitude/longitude validation, including negative values and decimal commas.
- Real-world OpenStreetMap data rendered with English-first street/place labels, pan, pinch/double-tap zoom,
  tap-to-place and long-press-to-drag pins, a 360° movement joystick, a live coordinate preview,
  and copy-to-clipboard.
- Search cities, addresses, and landmarks; choose from up to five results to preview
  a destination. Tiny, linked map attribution overlays the map without a box.
- GPS and network test providers refreshed every second with 10 m reported accuracy, plus
  speed and bearing while the joystick moves a session.
- Apply different coordinates while a session is running.
- Named saved-pin chips stored privately on the device; tap to load, long-press or use
  **Manage** to load, rename, or delete.
- Foreground notification with a Stop action; the session continues when the screen is off
  or you switch apps, subject to Android/OEM process and battery restrictions.
- Compact, map-first light/dark layout: spoofing status appears only in the sticky bottom bar,
  alongside preview coordinates, Save/Copy and Start/Apply/Stop, side-by-side coordinate fields,
  and a setup checklist that only appears until the device is ready. Privacy details and usage
  limitations are in **About & privacy** (ⓘ). Accessible labels, keyboard adjustments, and
  system/keyboard insets are supported.
- No accounts, API keys, ads, analytics, or cloud backups.
- Offline mode hides map/search and their online-only controls and disclosures. Coordinate entry,
  saved pins, joystick movement, copy, and Start/Apply/Stop remain available.
  Online features return automatically when Android validates the internet connection.
- Sessions never automatically resume after reboot or process termination.

## Device setup and use

Requires **Android 8.0 or newer**. No root is needed.

1. Enable **Developer options**: in Settings, find About phone / Software information
   and tap **Build number** seven times. OEM menus vary.
2. Open **Developer options → Select mock location app → GPS Emulator**.
3. Enable the device's **Location** setting.
4. Choose coordinates in GPS Emulator and tap **Start spoofing**. Allow the location permission
   required by Android's location foreground-service rules. Approximate permission is sufficient;
   GPS Emulator does not read your real location.
5. Allow notifications to keep the Stop action visible. If denied, stop through GPS Emulator
   or Android's Active apps controls.
6. To end a session, use **Stop** in the app or notification. The test providers
   are removed so Android can return to normal location fixes.

Start typing a city, address, or landmark in the search box: after three characters and a
short pause, matching places appear in a dropdown under the box. Tap a suggestion to fill the
box, move the preview pin, and zoom to the area. The magnifier at the end of the box (or the
keyboard Search key) searches immediately and selects a single match directly. The × that
appears while the box has text clears it for a new search; the preview pin stays put. Search uses
Android's device-provided `Geocoder`, not the public Nominatim API. English results
are requested regardless of the phone's language (the provider controls available translations). The device's
geocoding provider receives what you type for suggestions; avoid sensitive information.
Availability, connectivity requirements, and search coverage depend on the device/provider.
Missing service, connection failures, and empty results are shown explicitly; manual
coordinates and map selection remain available. No extra API key is required.

Pan/zoom the real-world map and tap a destination to select a six-decimal preview;
long-press the pin to drag it. Typed and saved coordinates keep their precision.
Select a fixed speed from the dropdown to the left of the joystick: Walk (5 km/h), Run
(10 km/h), Cycle (20 km/h), or Drive (50 km/h). The selection is remembered. Drag the joystick
knob in any direction (360°; north is up, like the map) to steer; pushing further does not
change speed. Changing the dropdown while moving takes effect immediately. The readout shows the
speed, compass heading, and pace (walking, running, cycling, or driving); releasing stops.
Distances are true metres along the heading at the current latitude (WGS 84). The map
follows the pin, and starting to move zooms a far-out map to street level (16); search
results zoom to level 13. Arrow keys on the focused map or joystick, and the joystick's accessibility
actions, move 5 m north/south/east/west. Longitude wraps at the date line; latitude stops at
the poles; east/west is undefined at a pole.

Before a session starts, the joystick moves the preview pin. During a session every joystick
drag or step moves the **live** location directly, with no Apply needed, starting from wherever the
session is: fixes keep their 1-second cadence and report the joystick's speed and bearing,
the status shows the current position, and releasing stops in place. While the preview matches
the live location, the bottom bar says so and Apply stays disabled. If GPS Emulator stops sending
joystick updates (for example, the app is closed mid-drag), the session halts within 2 seconds.
Typing, dragging the pin, searching/selecting a result, or loading a saved pin only changes the
preview, even during an active session: tap **Apply** explicitly to move the injected location there.

Maps require internet and use [OpenFreeMap](https://openfreemap.org/quick_start/)
vector tiles through the device's Android WebView and locally bundled MapLibre GL JS,
without an API key or billing account. Only the map uses WebView; the dashboard, search,
saved pins, and mock-location service remain native. A current Android System WebView
with WebGL 2 support is required for the map. Renderer failures show a retry action and
leave manual coordinates available. Executable map scripts are bundled in the APK,
not downloaded from a CDN; map content cannot navigate to other sites or access local files.
Place-name labels prefer English (`name:en` / `name_en`), then Latin-script or
transliterated names (`name:latin`); local-script-only labels are omitted rather
than silently switching languages. Road numbers are retained. The map data does
not contain an English translation for every street or business.
The map provider receives your IP address and requested map areas;
GPS Emulator does not read or upload your device's real location or saved-pin names.
Transparent 9sp attribution links to [OpenStreetMap's licence](https://www.openstreetmap.org/copyright),
[OpenMapTiles](https://openmaptiles.org/), and [OpenFreeMap](https://openfreemap.org/).
WebView manages its normal private HTTP cache; there is no bulk download,
prefetch, or offline-map download feature. Map and search are hidden without a validated
internet connection, including Wi-Fi captive portals, and return after reconnecting.
Your draft, selected pin, camera, and active session are retained. Tile availability is
best-effort, and load failures show a retry action. Coordinate entry, saved pins,
and spoofing work without internet. Web Mercator maps cover approximately
85.0511°S to 85.0511°N; coordinates outside this coverage remain editable/applicable
without silently changing their latitude, but their pin cannot be shown on the map.

Use only on devices and applications you are authorized to test. Apps can detect or ignore
mock locations. Google Play services fused-location behavior and OEM implementations vary:
not every app uses the GPS/network providers. Movement is manual (joystick); there is no route playback.
If Android kills or force-stops the process, cleanup callbacks are not guaranteed; if fixes
remain stuck, deselect the mock-location app in Developer options and toggle Location off/on.

## Project and local builds

The **entire single-module Gradle project is inside `app/`**, with only repository
metadata and GitHub Actions outside it.

```text
GpsEmulator/
  .github/
    workflows/build-and-release.yml
    scripts/release_version.py
    scripts/test_release_version.py
  app/
    build.gradle.kts, settings.gradle.kts, gradle.properties
    gradlew, gradlew.bat, gradle/wrapper/
    src/main/AndroidManifest.xml
    src/main/java/in/bipul/gpsemulator/
    src/main/res/
    src/test/java/in/bipul/gpsemulator/
```

Stack: **Kotlin + Android Views + Android WebView + MapLibre GL JS 6.16.0** (BSD-3-Clause), minSdk 26 /
compileSdk and targetSdk 37, Android Gradle Plugin 9.4.1, Gradle 9.8.2, JDK 17+
(Java 21 in CI). JVM tests use JUnit and Robolectric.

The pinned renderer's distributable modules/CSS and upstream licence notices are in
`app/src/main/assets/map/vendor/`, copied from the `maplibre-gl@6.16.0` npm package.
No native map libraries or architecture-specific APKs are required.

Open `app/` in Android Studio, or configure `JAVA_HOME` and `ANDROID_HOME`:

```powershell
cd app
.\gradlew.bat testDebugUnitTest lintRelease assembleDebug
.\gradlew.bat installDebug
```

Run the map-label regression tests from the repository root with Node.js 22+:
`node --test app\src\test\js\labels.test.mjs`.

`installDebug` requires a connected device/emulator. Local release builds use the local
debug signing key unless `GPS_EMULATOR_KEYSTORE` is configured:

```powershell
.\gradlew.bat assembleRelease '-PversionName=2.3.4' '-PversionCode=20304'
```

Output: `app\build\outputs\apk\release\GpsEmulator-release.apk`. Local debug-signed builds
cannot update installs signed with your permanent release key.

## Release-only APK pipeline

[Build & Release APK](.github/workflows/build-and-release.yml) runs **only when a GitHub
Release is published**, including a published prerelease with a valid plain version tag.
It does not run on pushes, pull requests, tag pushes alone, draft releases, or manual dispatch.

Publish a release with a plain **`major.minor.patch`** tag, for example **`2.3.4`**.
No `v` prefix, leading zeros, or prerelease/build suffixes are accepted. Minor and patch
numbers must be below 100; the derived Android code must be positive and at most 2100000000.

- `versionName = tag`
- `versionCode = major * 10000 + minor * 100 + patch` (`2.3.4` becomes `20304`)

The workflow validates the tag, runs tests and Android Lint, builds a minified release APK,
verifies its signing certificate against the permanent keystore, and uploads
**`GpsEmulator-<version>.apk`** plus **`GpsEmulator-<version>.apk.sha256`** to that same release.
Install the APK from the release assets, allowing installation from that source in Android.
Higher versions with the same application ID and signing key update existing installations
and preserve saved pins.

### One-time permanent signing-key setup

CI uses the repository secret **`DEBUG_KEYSTORE_BASE64`** with alias `androiddebugkey`
and Android's default keystore/key passwords (`android`). Keep a secure private backup
of the **permanent GPS Emulator signing key**. If the secret is already configured, keep
that exact key; do not generate or upload a replacement.

For an initial setup without a signing key, generate it locally with JDK `keytool` and
send the base64 directly to GitHub's encrypted repository secret. Run from the repository
checkout; this procedure **never prints private signing-key material into public logs**:

```powershell
$keystore = Join-Path $env:TEMP "gps-emulator-signing.keystore"
if (Test-Path $keystore) { throw "Keystore already exists; do not replace your signing key." }
keytool -genkeypair -noprompt -keystore $keystore -storetype JKS `
  -storepass android -keypass android -alias androiddebugkey `
  -keyalg RSA -keysize 3072 -validity 36500 -dname "CN=GpsEmulator,O=GpsEmulator,C=US"
if ($LASTEXITCODE -ne 0) { throw "Key generation failed." }
[Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore)) |
  gh secret set DEBUG_KEYSTORE_BASE64
if ($LASTEXITCODE -ne 0) { throw "Secret upload failed; keep the generated key and retry upload." }
```

Back up that exact keystore securely, then remove the temporary copy. Never commit it,
print it, or replace the secret after distributing APKs: changing keys breaks in-place updates.
The default password is not the security boundary; protect the private keystore and repository
access. The pipeline fails rather than shipping with a fallback key when the secret is missing.

Local signing overrides: `GPS_EMULATOR_KEYSTORE`, `GPS_EMULATOR_KEYSTORE_PASSWORD`,
`GPS_EMULATOR_KEY_ALIAS`, and `GPS_EMULATOR_KEY_PASSWORD`. CI expects the alias and passwords above.
The application ID is `in.bipul.gpsemulator`; keep it unchanged for future upgrades.
