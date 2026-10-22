# Security and privacy

This is a personal app for one person. These are its fixed rules. You can check every one of them in the code.

## No internet

The app does **not** have the Internet permission (see `AndroidManifest.xml`). Android blocks it from connecting to anything. It cannot send your data anywhere.

Your data only leaves the phone when **you** tap **Save file** or **Share** on the Monthly screen and choose where the datapack goes.

## Permissions (the complete list)

| Permission | Why |
|---|---|
| Notifications | Meal, water and monthly datapack reminders |
| Alarms & reminders (`USE_EXACT_ALARM`) | So reminders fire at the right time |
| Run at startup (`RECEIVE_BOOT_COMPLETED`) | So reminders come back after the phone restarts |

The app does **not** have: internet, camera, microphone, location, contacts, photos/files, SMS, phone calls, accessibility, or "display over other apps".

- **Voice:** the app opens Samsung's voice input. The app only receives the text.
- **Files:** saving a datapack or importing an advice file uses Android's file picker, so the app only touches the one file you pick.

Check on the phone: Settings › Apps › Kidney Diary › Permissions.

## Where data is stored

- One file per month in the app's private folder on the phone (`files/months/YYYY-MM.json`). Other apps cannot read it.
- App backup is switched off (`allowBackup="false"` and `data_extraction_rules.xml`), so nothing is copied into Samsung or Google cloud backups.
- Uninstalling the app deletes the data, so save your monthly datapacks.

## Libraries

Only Google's own Android libraries (see `app/build.gradle.kts`). No ads, analytics, crash reporting or tracking.

## Signing key

`app/release.keystore` signs every build so updates install over the old version. It lives only in this private repository. Keep the repository private.
