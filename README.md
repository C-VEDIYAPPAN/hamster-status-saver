# Status Vault

View, save and share WhatsApp and WhatsApp Business statuses on Android.
Plain Java on the Android framework — **no libraries**, so the APK is under 100 KB.

## Install

1. Copy `StatusVault-v1.7.apk` to your phone and open it.
2. Allow "Install unknown apps" for your file manager or browser when Android asks.
3. Open **Status Vault** → tap **Grant access** → on the folder screen tap **Use this folder → Allow**.

Requires Android 8.0+. Statuses only appear after you've viewed them in WhatsApp,
and WhatsApp deletes them after 24 hours.

## Features

**Browse**
- **WhatsApp / Business / Saved** sections with All · Photos · Videos filters, counts and total size
- A colourful, slowly **rotating ring** around statuses you haven't opened yet; tiles press in when touched
- **Countdown timer** on each tile — a small ring that drains as WhatsApp's 24 hours run out (red under 3h)
- Video length on every video tile; one-tap **Save all** for unsaved statuses
- Sort by newest, oldest or largest; 2–5 column grid

**Act**
- **Multi-select** — long-press, then save, share, repost or delete many at once
- **Repost to WhatsApp** — opens WhatsApp ready to post as your own status
- **Grab a photo from a video** — pause anywhere and save that exact frame
- Full-screen viewer: swipe between statuses, videos loop, tap to pause
- **Delete** statuses or saved items, from the viewer or in bulk (always confirms first)
- Compact floating glass tab bar that slides between sections and turns into an action bar while selecting

**Automate & protect**
- **Auto-save** every new status when you open the app
- **Background check** about once an hour, with a notification
- **App lock** with fingerprint, face or screen lock
- No internet permission — nothing leaves the phone

**Settings** — live preview card, 6 accent colours, Light / Dark / System theme,
grid slider, sort, badges, auto-save, app lock, storage clean-up, folder reset.

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
├── MainActivity.java       Sections, filters, grid, multi-select, auto-save
├── SettingsActivity.java   Interactive settings with live preview
├── LockActivity.java       App lock (BiometricPrompt / screen lock)
├── AutoSaveJob.java        Hourly background auto-save + notification
├── StatusVaultApp.java     Locks the app when it returns from background
├── Prefs.java, Palette.java, BaseActivity.java   Settings, accent colours, theme override
├── ViewerActivity.java     Swipe viewer: save, repost, share, frame grab, delete
├── StatusRepository.java   SAF reading, MediaStore save/delete, frames, share copies
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
