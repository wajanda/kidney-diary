# Kidney Diary

Personal, offline meal and water diary with reminders, for a Samsung Galaxy S25 Ultra.

- Log each meal by typing or voice. A built-in quick check flags salty food, street food and foods to avoid.
- Reminders get stronger until the meal is logged, and stop as soon as it is.
- Weekly patterns: street food, salty days, water, vegetables, repeated dishes.
- **Monthly datapack:** one JSON file per month. Upload it to the HEALTH project in Claude for a review.
- **Advice file (optional):** Claude can return a small `kidney-diary-advice` JSON file. Import it on the Monthly screen to get Claude's tips, meal ideas and extra foods to watch.

No internet access. See [SECURITY.md](SECURITY.md).

## Install on the phone

1. On the phone, open this repository on github.com (signed in), tap **Releases**, open the newest one, and tap the `.apk` file to download.
2. Samsung **Auto Blocker** may block it: Settings › Security and privacy › Auto Blocker › turn off. Turn it back on after installing.
3. Open the downloaded file. If asked, allow installs from this source, then tap **Install**.
4. Settings › Apps › Kidney Diary › Battery › **Unrestricted**. Then Settings › Battery › Background usage limits › **Never sleeping apps** › add Kidney Diary.

## Advice file format

```json
{
  "format": "kidney-diary-advice",
  "month": "2026-11",
  "message": "Short summary of last month",
  "tips": ["tip 1", "tip 2"],
  "watch": { "avoid": ["food"], "small": ["food"], "ok": ["food"] },
  "plan": { "Breakfast": ["idea"], "Lunch": ["idea"], "Evening": ["idea"], "Dinner": ["idea"] }
}
```

## Updating

Each change pushed to `main` builds a new release. Install it over the old one; your data stays.
