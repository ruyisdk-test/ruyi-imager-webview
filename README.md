# Ruyi Imager for Android

The app opens the deployed RuyiSDK graphical imager. The Android menu also opens `ruyi device provision` and keeps the current website language. Board selection, package recipes, Starlark, archive extraction, fastboot commands and Bianbu compression use the website implementation.

## Running the app

Use Android 10 or later, an up-to-date Android System WebView and a phone or tablet with USB host support. Choose a writable folder in the Android system picker. Downloaded archives and extracted images are stored under `RuyiImages/<package>/<version>/` in that folder. The app also needs temporary local space for the file being saved and for a verified copy of a whole-disk image during a disk write.

For fastboot, connect the board through USB OTG and choose it in the website's connection card. Android may prompt for USB permission again when the board re-enumerates. The website handles confirmation of the new target.

For `dd-v1`, use an external USB Mass Storage Bulk-Only Transport reader or drive with a writable SCSI direct-access medium. If Android owns the interface, eject the volume using Android's storage controls before refreshing the disk list. The app requires an exclusive interface claim and reports an error if the system driver retains ownership. Phone internal storage, built-in SD slots and UAS-only drives are outside this writer's scope. Select the image directory on storage that remains accessible while the target is ejected.

The app checks the selected medium, capacity and write protection, hashes a private copy of the source, writes, flushes the device cache, and verifies the written byte range by reading it back. The foreground service stays active through verification. Its connected-device permission comes from the user's USB device grant.

## Build and test

Use JDK 17 and an Android SDK with platform 35 and Build Tools 35.0.0. Set `ANDROID_HOME` or configure `sdk.dir` in `local.properties`.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The JVM tests exercise the origin and path rules and a simulated SCSI medium, including a partial final sector, write protection, capacity checks, malformed status and read-back corruption. Actual device enumeration, Android directory providers, USB permission prompts and board flashing require hardware testing.
