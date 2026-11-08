# Status Vault

View, save and share WhatsApp and WhatsApp Business statuses on Android.
Plain Java on the Android framework — **no libraries**, so the APK is about 60 KB.

## Install

1. Copy `StatusVault-v1.0.apk` to your phone and open it.
2. Allow "Install unknown apps" for your file manager or browser when Android asks.
3. Open **Status Vault** → tap **Grant access** → on the folder screen tap **Use this folder → Allow**.

Requires Android 8.0+. Statuses only appear after you've viewed them in WhatsApp,
and WhatsApp deletes them after 24 hours.

## Features

- **WhatsApp / Business / Saved** sections, with All · Photos · Videos filters and counts
- One-tap save to `Pictures/StatusVault` or `Movies/StatusVault` (appears in Gallery)
- Full-screen viewer: swipe between statuses, videos loop, tap to pause, Save and Share buttons
- Auto-refresh when you come back from WhatsApp
- Light and dark theme, works edge-to-edge on Android 15

## Build

**Android Studio:** open this folder, let Gradle sync, then *Build → Build APK(s)*.
No dependencies to download besides the Android Gradle plugin.

**Command line without Gradle** (how the included APK was built):

```bash
ANDROID_JAR=~/Android/Sdk/platforms/android-35/android.jar ./build.sh
```

Needs `aapt2`, `javac`, `d8`/`dx`, `zipalign` and `apksigner` on your PATH.

## How it works

Android 11+ blocks apps from reading another app's files. WhatsApp keeps statuses in
`Android/media/com.whatsapp/WhatsApp/Media/.Statuses` (Business: `com.whatsapp.w4b/WhatsApp Business/…`).
The app opens the system folder picker directly inside that folder, the user approves it once,
and the app keeps a persistent read grant (Storage Access Framework). Saving copies the file into
MediaStore, which needs no storage permission on Android 10+.

```
app/src/main/java/com/statusvault/app/
├── MainActivity.java       Sections, filters, grid, access prompt, saving
├── ViewerActivity.java     Full-screen swipe viewer, video playback, share
├── StatusRepository.java   SAF reading, MediaStore saving, share copies
├── ThumbnailLoader.java    Background image/video-frame thumbnails + memory cache
├── ShareProvider.java      Serves shared files to other apps
├── StatusSource.java       WhatsApp / Business folder locations
├── StatusItem.java, AspectFrameLayout.java, SwipeFrameLayout.java
```

## Signing key

`signing/statusvault.jks` (password `statusvault`) signed this APK. Keep it: Android only
installs updates signed with the same key. For a public release, make your own key and
never share it.

## Logo

`art/logo.svg` and `art/play-store-icon-512.png`. The launcher icon is an adaptive vector,
including a monochrome version for Android 13+ themed icons.

## Publishing

Don't put "WhatsApp" in the app name, icon or store title — Google removes apps that do.
"Works with WhatsApp" in the description is the usual approach.
