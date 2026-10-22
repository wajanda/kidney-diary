# Security and privacy

This is a personal app for one person. These are its fixed rules. You can check every one of them in the code.

## Permissions (the complete list)

| Permission | Why |
|---|---|
| Internet | To talk to Claude and Notion (only those two, see below) |
| Notifications | Meal and water reminders |
| Alarms & reminders (`USE_EXACT_ALARM`) | So reminders fire at the right time |
| Run at startup (`RECEIVE_BOOT_COMPLETED`) | So reminders come back after the phone restarts |

The app does **not** have: camera, microphone, location, contacts, photos/files, SMS, phone calls, accessibility, or "display over other apps".

- **Photos:** the app opens Samsung's own camera. Only the one photo you take comes back to the app, and it is shrunk and sent to Claude to identify the food. It is not saved to your gallery.
- **Voice:** the app opens Samsung's voice input. The app only receives the text.

Check on the phone: Settings › Apps › Kidney Diary › Permissions, and Settings › Security and privacy › Privacy.

## Where data goes

The app can only connect to:

- `api.anthropic.com` (Claude): the meal you log, your health profile text, and your recent meal history, so Claude can give personal advice.
- `api.notion.com` (your Notion): saves each meal to your **Meal Log** and reads your **App Health Profile** page.

This is enforced in `Data.kt` (`Net.ALLOWED_HOSTS`). Any other address is refused, redirects are not followed, and plain `http` is blocked.

No ads, analytics, crash reporting or tracking libraries are included. The only libraries are Google's own Android libraries (see `app/build.gradle.kts`).

## Keys

- Your Claude API key and Notion secret are typed into the app on your phone. They are encrypted with the phone's hardware Keystore and never leave the phone except to Claude and Notion.
- They are not in this repository.
- Give the Notion integration access to the **Kidney Diary** page only.
- Set a monthly spend limit on the Claude key in the Claude Console.

## Backups

App backup is switched off (`allowBackup="false"` and `data_extraction_rules.xml`), so nothing from the app is copied into Samsung or Google cloud backups. Your data lives in your Notion.

## Signing key

`app/release.keystore` signs every build so updates install over the old version. It lives only in this private repository. Keep the repository private.
