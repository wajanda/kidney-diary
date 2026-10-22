# Kidney Diary

Personal meal and water tracker with reminders, for a Samsung Galaxy S25 Ultra.

- Log each meal by photo, voice or typing. Claude checks it against your health profile.
- Every meal is saved to your Notion **Meal Log**.
- Reminders get stronger until the meal is logged, and stop as soon as it is.
- Weekly patterns: street food, salty days, water, vegetables, repeated dishes.
- After 7 days of logging, Claude plans tomorrow's meals.

See [SECURITY.md](SECURITY.md) for exactly what the app can and can't do.

## Install on the phone

1. On the phone, open this repository on github.com (signed in), tap **Releases**, open the newest one, and tap the `.apk` file to download.
2. Samsung **Auto Blocker** may block it: Settings › Security and privacy › Auto Blocker › turn off. Turn it back on after installing.
3. Open the downloaded file. If asked, allow installs from this source, then tap **Install**.
4. Open Kidney Diary, paste your Claude API key and Notion secret, check the meal times, and tap **Save and test connection**.
5. Settings › Apps › Kidney Diary › Battery › **Unrestricted**. Then Settings › Battery › Background usage limits › **Never sleeping apps** › add Kidney Diary.

## Updating

Each change pushed to `main` builds a new release. Download and install it the same way; your data stays.
