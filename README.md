# FlipPin

A small native Android app for **authorized GPS mock-location testing**.
Search for a destination, choose a point on the interactive map, or enter latitude and longitude,
save frequently used pins, and simulate a fixed location
with a visible foreground session and a Stop action.

Android explicitly marks the locations as mocked. FlipPin does not hide that flag,
root the device, bypass app protections, or change IP/cellular location.

## Features

- Decimal-degree latitude/longitude validation, including negative values and decimal commas.
- Real-world OpenStreetMap data rendered with English-first street/place labels, pan, pinch/double-tap zoom,
  tap-to-place and long-press-to-drag pins, World/Area/Fine zoom presets,
  directional adjustments, a live coordinate preview, and copy-to-clipboard.
- Search cities, addresses, and landmarks; choose from up to five results to preview
  a destination. Tiny, linked map attribution overlays the map without a box.
- GPS and network test providers refreshed every second with 10 m reported accuracy.
- Apply different coordinates while a session is running.
- Named saved-pin cards stored privately on the device; tap to load, use the menu
  to rename or delete, or long-press to delete.
- Foreground notification with a Stop action; the session continues when the screen is off
  or you switch apps, subject to Android/OEM process and battery restrictions.
- Polished light/dark dashboard, collapsible setup checklist, accessible field labels,
  keyboard-controlled coordinate adjustments, and Android system/keyboard insets.
- No accounts, API keys, ads, analytics, or cloud backups.
- Sessions never automatically resume after reboot or process termination.

## Device setup and use

Requires **Android 8.0 or newer**. No root is needed.

1. Enable **Developer options**: in Settings, find About phone / Software information
   and tap **Build number** seven times. OEM menus vary.
2. Open **Developer options → Select mock location app → FlipPin**.
3. Enable the device's **Location** setting.
4. Choose coordinates in FlipPin and tap **Start spoofing**. Allow the location permission
   required by Android's location foreground-service rules. Approximate permission is sufficient;
   FlipPin does not read your real location.
5. Allow notifications to keep the Stop action visible. If denied, stop through FlipPin
   or Android's Active apps controls.
6. To end a session, use **Stop spoofing** in the app or notification. The test providers
   are removed so Android can return to normal location fixes.

Type a city, address, or landmark in **Find a destination**, tap **Search location**
(or the keyboard Search key), then choose a result to move the preview pin and zoom
to the area. Search uses Android's device-provided `Geocoder`, not the public
Nominatim API, and only runs when submitted, never while typing. English results
are requested regardless of the phone's language (the provider controls available translations). The device's
geocoding provider receives the entered search text; avoid sensitive information.
Availability, connectivity requirements, and search coverage depend on the device/provider.
Missing service, connection failures, and empty results are shown explicitly; manual
coordinates and map selection remain available. No extra API key is required.

Pan/zoom the real-world map and tap a destination to select a six-decimal preview;
long-press the pin to drag it. Typed and saved coordinates keep their precision.
World, Area, and Fine use zoom levels 2, 13, and 18 around the selected point.
Directional buttons
adjust by 1°, 0.001°, or 0.00001° for World, Area, and Fine respectively. Longitude
wraps at the date line; latitude stops at the poles. Typing, dragging, nudging, or
searching/selecting a result, or loading a saved pin only changes the preview, even during an active session:
tap **Apply coordinates** explicitly to update the injected location.

Maps require internet and use [OpenFreeMap](https://openfreemap.org/quick_start/)
vector tiles through MapLibre Native, without an API key or billing account.
Place-name labels prefer English (`name:en` / `name_en`), then Latin-script or
transliterated names (`name:latin`); local-script-only labels are omitted rather
than silently switching languages. Road numbers are retained. The map data does
not contain an English translation for every street or business.
The map provider receives your IP address and requested map areas;
FlipPin does not read or upload your device's real location or saved-pin names.
Transparent 9sp attribution links to [OpenStreetMap's licence](https://www.openstreetmap.org/copyright),
[OpenMapTiles](https://openmaptiles.org/), and [OpenFreeMap](https://openfreemap.org/).
MapLibre manages the private on-device map cache; there is no bulk download,
prefetch, or offline-map download feature. Tile availability is
best-effort, and load failures show a retry action. Coordinate entry, saved pins,
and spoofing work without internet. Web Mercator maps cover approximately
85.0511°S to 85.0511°N; coordinates outside this coverage remain editable/applicable
without silently changing their latitude, but their pin cannot be shown on the map.

Use only on devices and applications you are authorized to test. Apps can detect or ignore
mock locations. Google Play services fused-location behavior and OEM implementations vary:
not every app uses the GPS/network providers. This is fixed-point simulation, not route playback.
If Android kills or force-stops the process, cleanup callbacks are not guaranteed; if fixes
remain stuck, deselect the mock-location app in Developer options and toggle Location off/on.

## Project and local builds

The layout follows `D:\GitHub\EbookReader`: the **entire single-module Gradle project
is inside `app/`**, with only repository metadata and GitHub Actions outside it.

```text
FlipPin/
  .github/
    workflows/build-and-release.yml
    scripts/release_version.py
    scripts/test_release_version.py
  app/
    build.gradle.kts, settings.gradle.kts, gradle.properties
    gradlew, gradlew.bat, gradle/wrapper/
    src/main/AndroidManifest.xml
    src/main/java/in/bipul/flippin/
    src/main/res/
    src/test/java/in/bipul/flippin/
```

Stack: **Kotlin + Android Views + MapLibre Native 13.0.2** (BSD-2-Clause), minSdk 26 /
compileSdk and targetSdk 37, Android Gradle Plugin 9.4.1, Gradle 9.8.2, JDK 17+
(Java 21 in CI). JVM tests use JUnit and Robolectric.

Open `app/` in Android Studio, or configure `JAVA_HOME` and `ANDROID_HOME`:

```powershell
cd app
.\gradlew.bat testDebugUnitTest lintRelease assembleDebug
.\gradlew.bat installDebug
```

`installDebug` requires a connected device/emulator. Local release builds use the local
debug signing key unless `FLIPPIN_KEYSTORE` is configured:

```powershell
.\gradlew.bat assembleRelease '-PversionName=1.0.0' '-PversionCode=10000'
```

Output: `app\build\outputs\apk\release\FlipPin-release.apk`. Local debug-signed builds
cannot update installs signed with your permanent release key.

## Release-only APK pipeline

[Build & Release APK](.github/workflows/build-and-release.yml) runs **only when a GitHub
Release is published**, including a published prerelease with a valid plain version tag.
It does not run on pushes, pull requests, tag pushes alone, draft releases, or manual dispatch.

Publish a release with a tag such as **`1.0.0`**, **`1.0.1`**, or **`1.2.3`**.
No `v` prefix, leading zeros, or prerelease/build suffixes are accepted. Minor and patch
numbers must be below 100; the derived Android code must be positive and at most 2100000000.

- `versionName = tag`
- `versionCode = major * 10000 + minor * 100 + patch` (`1.0.0` becomes `10000`)

The workflow validates the tag, runs tests and Android Lint, builds a minified release APK,
verifies its signing certificate against the permanent keystore, and uploads
**`FlipPin-1.0.0.apk`** plus **`FlipPin-1.0.0.apk.sha256`** to that same release.
Install the APK from the release assets, allowing installation from that source in Android.
Higher versions signed with the same key update existing installations and preserve saved pins.

### One-time permanent signing-key setup

Like EbookReader, CI uses the repository secret **`DEBUG_KEYSTORE_BASE64`** with alias
`androiddebugkey` and Android's default keystore/key passwords (`android`). Generate a
**separate permanent key for FlipPin** and keep a secure private backup.

Unlike the reference's setup workflow, this procedure **never prints private signing-key
material into public GitHub Actions logs**. Generate it locally with JDK `keytool` and
send the base64 directly to GitHub's encrypted repository secret:

```powershell
$keystore = Join-Path $env:TEMP "flippin-signing.keystore"
if (Test-Path $keystore) { throw "Keystore already exists; do not replace your signing key." }
keytool -genkeypair -noprompt -keystore $keystore -storetype JKS `
  -storepass android -keypass android -alias androiddebugkey `
  -keyalg RSA -keysize 3072 -validity 36500 -dname "CN=FlipPin,O=FlipPin,C=US"
if ($LASTEXITCODE -ne 0) { throw "Key generation failed." }
[Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore)) |
  gh secret set DEBUG_KEYSTORE_BASE64 --repo BipulRaman/FlipPin
if ($LASTEXITCODE -ne 0) { throw "Secret upload failed; keep the generated key and retry upload." }
```

Back up that exact keystore securely, then remove the temporary copy. Never commit it,
print it, or replace the secret after distributing APKs: changing keys breaks in-place updates.
The default password is not the security boundary; protect the private keystore and repository
access. The pipeline fails rather than shipping with a fallback key when the secret is missing.

Local signing overrides: `FLIPPIN_KEYSTORE`, `FLIPPIN_KEYSTORE_PASSWORD`,
`FLIPPIN_KEY_ALIAS`, and `FLIPPIN_KEY_PASSWORD`. CI expects the alias and passwords above.
The application ID `in.bipul.flippin` must stay unchanged for upgrades.
