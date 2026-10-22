package lk.kidneydiary

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Reminders.createChannels(this)
    }
}

/**
 * Meal reminders that get stronger until the meal is logged:
 * level 1 at meal time (silent), 2 after 20 min (sound), 3 after 45 min (pop-up),
 * 4 after 90 min (stays until logged), 5 = next-morning catch-up for a missed dinner.
 * Nothing fires during quiet hours. Logging the meal anywhere stops the rest.
 */
object Reminders {
    const val CH_GENTLE = "gentle"
    const val CH_SOUND = "sound"
    const val CH_URGENT = "urgent"
    const val CH_SUMMARY = "summary"
    const val CH_INFO = "info"

    const val A_REPLY = "lk.kidneydiary.REPLY"
    const val A_USUAL = "lk.kidneydiary.USUAL"
    const val A_SKIP = "lk.kidneydiary.SKIP"
    const val A_WATER = "lk.kidneydiary.WATER"
    const val KEY_REPLY = "reply"

    private const val ID_SUMMARY = 1
    private const val ID_INFO = 2
    private const val ID_WATER = 3
    private const val ID_DATAPACK = 4

    private val OFFSETS = listOf(0L, 20L, 45L, 90L)
    private val WATER_SLOTS = listOf("11:00", "14:00", "17:00", "19:30")

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)
    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    fun createChannels(ctx: Context) {
        val n = nm(ctx)
        n.createNotificationChannel(NotificationChannel(CH_GENTLE, "Meal time (quiet)", NotificationManager.IMPORTANCE_LOW))
        n.createNotificationChannel(NotificationChannel(CH_SOUND, "Meal reminders", NotificationManager.IMPORTANCE_DEFAULT))
        n.createNotificationChannel(NotificationChannel(CH_URGENT, "Meal not logged (pop-up)", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
        })
        n.createNotificationChannel(NotificationChannel(CH_SUMMARY, "Today's progress", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        n.createNotificationChannel(NotificationChannel(CH_INFO, "Water and results", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun mealId(meal: String) = 200 + MEALS.indexOf(meal).coerceAtLeast(0)

    // ---------------- scheduling ----------------

    fun scheduleAll(ctx: Context) {
        MEALS.forEach { scheduleBase(ctx, it) }
        WATER_SLOTS.indices.forEach { scheduleWater(ctx, it) }
        scheduleDatapack(ctx)
    }

    private fun scheduleDatapack(ctx: Context) {
        val at = nextAt(LocalTime.of(9, 30))
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("datapack", true)
        val pi = PendingIntent.getBroadcast(ctx, 150, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am(ctx).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pi)
    }

    /** Every morning from the 1st: reminds you to save last month's datapack until you do. */
    fun onDatapack(ctx: Context) {
        scheduleDatapack(ctx)
        val prev = java.time.YearMonth.now().minusMonths(1).toString()
        if (prev in Prefs.exportedMonths(ctx) || Store.load(ctx, prev).isEmpty()) return
        val name = java.time.YearMonth.parse(prev).month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        val n = Notification.Builder(ctx, CH_SOUND)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFF0F766E.toInt())
            .setContentTitle("Your $name datapack is ready")
            .setContentText("Save it and upload it to your HEALTH project for Claude's monthly review.")
            .setStyle(Notification.BigTextStyle().bigText("Save it and upload it to your HEALTH project for Claude's monthly review."))
            .setContentIntent(openTab(ctx, "monthly"))
            .setAutoCancel(true)
            .build()
        nm(ctx).notify(ID_DATAPACK, n)
    }

    private fun openTab(ctx: Context, tab: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("openTab", tab)
        return PendingIntent.getActivity(ctx, 350, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun clearDatapackNotice(ctx: Context) = nm(ctx).cancel(ID_DATAPACK)

    private fun nextAt(t: LocalTime): ZonedDateTime {
        val now = ZonedDateTime.now()
        var at = now.with(t).withSecond(0).withNano(0)
        if (!at.isAfter(now.plusSeconds(30))) at = at.plusDays(1)
        return at
    }

    fun scheduleBase(ctx: Context, meal: String) {
        val at = nextAt(Prefs.mealTime(ctx, meal))
        setAlarm(ctx, at, meal, 1, at.toLocalDate().toString())
    }

    private fun alarmIntent(ctx: Context, meal: String, level: Int, date: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .putExtra("meal", meal).putExtra("level", level).putExtra("date", date)
        val code = MEALS.indexOf(meal) * 10 + level
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setAlarm(ctx: Context, at: ZonedDateTime, meal: String, level: Int, date: String) {
        am(ctx).setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), alarmIntent(ctx, meal, level, date)
        )
    }

    private fun scheduleWater(ctx: Context, idx: Int) {
        val at = nextAt(LocalTime.parse(WATER_SLOTS[idx]))
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("water", idx)
        val pi = PendingIntent.getBroadcast(ctx, 100 + idx, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am(ctx).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pi)
    }

    fun inQuiet(ctx: Context, t: LocalTime): Boolean {
        val s = Prefs.quietStart(ctx)
        val e = Prefs.quietEnd(ctx)
        return if (s > e) (t >= s || t < e) else (t >= s && t < e)
    }

    private fun scheduleCatchup(ctx: Context, date: String) {
        val at = nextAt(Prefs.quietEnd(ctx).plusMinutes(30))
        setAlarm(ctx, at, "Dinner", 5, date)
    }

    // ---------------- firing ----------------

    fun onReminder(ctx: Context, meal: String, level: Int, date: String) {
        if (level == 1) scheduleBase(ctx, meal) // set up tomorrow's
        if (meal in Prefs.loggedMeals(ctx, date)) {
            cancel(ctx, meal)
            return
        }
        val now = ZonedDateTime.now()
        if (level < 5 && inQuiet(ctx, now.toLocalTime())) {
            if (meal == "Dinner") scheduleCatchup(ctx, date)
            return
        }
        show(ctx, meal, level, date)
        if (level in 1..3) {
            val next = now.plusMinutes(OFFSETS[level] - OFFSETS[level - 1])
            if (inQuiet(ctx, next.toLocalTime())) {
                if (meal == "Dinner") scheduleCatchup(ctx, date)
            } else {
                setAlarm(ctx, next, meal, level + 1, date)
            }
        }
    }

    fun onWater(ctx: Context, idx: Int) {
        scheduleWater(ctx, idx)
        val now = LocalTime.now()
        if (inQuiet(ctx, now)) return
        val start = LocalTime.of(6, 30).toSecondOfDay()
        val end = LocalTime.of(21, 30).toSecondOfDay()
        val frac = ((now.toSecondOfDay() - start).toFloat() / (end - start)).coerceIn(0f, 1f)
        val expected = (WATER_TARGET * frac).toInt()
        val had = Repo.water(ctx, today())
        if (had + 250 >= expected) return
        val left = (WATER_TARGET - had).coerceAtLeast(0)
        val n = Notification.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFF0F766E.toInt())
            .setContentTitle("%.1f L of water to go today".format(left / 1000f))
            .setContentText("Have a glass now. Your doctor asked for at least 3 L.")
            .setContentIntent(openApp(ctx, null, null))
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "+ Glass of water", actionPI(ctx, A_WATER, "", "", 90)).build())
            .build()
        nm(ctx).notify(ID_WATER, n)
    }

    // ---------------- notifications ----------------

    private fun openApp(ctx: Context, meal: String?, date: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (meal != null) i.putExtra("openLog", meal).putExtra("openDate", date)
        val code = if (meal == null) 0 else 300 + MEALS.indexOf(meal)
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionPI(ctx: Context, action: String, meal: String, date: String, code: Int, mutable: Boolean = false): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra("meal", meal).putExtra("date", date)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(ctx, code, i, flags)
    }

    fun show(ctx: Context, meal: String, level: Int, date: String) {
        val m = meal.lowercase()
        val (title, text) = when (level) {
            1 -> "${meal} time" to "Log your $m when you're done."
            2 -> "Did you have $m?" to "Tap to log it in 10 seconds."
            3 -> "$meal still not logged" to "Reminder 3. Type what you ate right here."
            4 -> "Last call for $m" to "This stays until you log it."
            else -> "Fill in last night's dinner" to "Then log breakfast."
        }
        val channel = when (level) { 1 -> CH_GENTLE; 2 -> CH_SOUND; else -> CH_URGENT }
        val idx = MEALS.indexOf(meal)
        val reply = Notification.Action.Builder(
            null, "Type what I ate", actionPI(ctx, A_REPLY, meal, date, 400 + idx, mutable = true)
        ).addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("e.g. 3 string hoppers, dhal").build()).build()
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFF0F766E.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(ctx, meal, date))
            .addAction(reply)
            .addAction(Notification.Action.Builder(null, "Same as usual", actionPI(ctx, A_USUAL, meal, date, 500 + idx)).build())
            .addAction(Notification.Action.Builder(null, "Skipped", actionPI(ctx, A_SKIP, meal, date, 600 + idx)).build())
        if (level >= 4) b.setOngoing(true) else b.setAutoCancel(true)
        if (level >= 2) b.setSubText("Reminder $level")
        nm(ctx).notify(mealId(meal), b.build())
    }

    /** Stops a meal's reminders: removes the notification and any pending stronger ones. */
    fun cancel(ctx: Context, meal: String) {
        nm(ctx).cancel(mealId(meal))
        val date = today()
        for (level in 2..5) {
            val pi = alarmIntent(ctx, meal, level, date)
            am(ctx).cancel(pi)
        }
    }

    fun info(ctx: Context, title: String, text: String) {
        val n = Notification.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFF0F766E.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx, null, null))
            .setAutoCancel(true)
            .build()
        nm(ctx).notify(ID_INFO, n)
    }

    /** The always-on "today" line in the notification bar. */
    fun updateSummary(ctx: Context) {
        val t = today()
        val meals = Prefs.loggedMeals(ctx, t).count { it in MEALS }
        val water = Repo.water(ctx, t)
        val n = Notification.Builder(ctx, CH_SUMMARY)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFF0F766E.toInt())
            .setContentTitle("$meals of 4 meals logged · %.1f / 3 L water".format(water / 1000f))
            .setContentText(if (meals == 4 && water >= WATER_TARGET) "All done for today. Well done." else "Kidney Diary · Today")
            .setOngoing(true)
            .setShowWhen(false)
            .setProgress(4, meals, false)
            .setContentIntent(openApp(ctx, null, null))
            .addAction(Notification.Action.Builder(null, "+ Glass of water", actionPI(ctx, A_WATER, "", "", 91)).build())
            .build()
        nm(ctx).notify(ID_SUMMARY, n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.getBooleanExtra("datapack", false)) {
            Reminders.onDatapack(ctx)
            return
        }
        if (intent.hasExtra("water")) {
            Reminders.onWater(ctx, intent.getIntExtra("water", 0))
            return
        }
        val meal = intent.getStringExtra("meal") ?: return
        Reminders.onReminder(ctx, meal, intent.getIntExtra("level", 1), intent.getStringExtra("date") ?: today())
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        val meal = intent.getStringExtra("meal") ?: ""
        val date = intent.getStringExtra("date")?.takeIf { it.isNotBlank() } ?: today()
        Thread {
            try {
                when (intent.action) {
                    Reminders.A_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Reminders.KEY_REPLY)?.toString().orEmpty()
                        if (text.isNotBlank()) {
                            Reminders.cancel(ctx, meal)
                            val r = Repo.logText(ctx, meal, text, "Notification reply", date)
                            Reminders.info(ctx, "$meal saved: ${r.verdict}", r.note)
                        }
                    }
                    Reminders.A_USUAL -> {
                        Reminders.cancel(ctx, meal)
                        val e = Repo.logUsual(ctx, meal, date)
                        Reminders.info(ctx, "$meal saved", e.title)
                    }
                    Reminders.A_SKIP -> {
                        Reminders.cancel(ctx, meal)
                        Repo.logSkip(ctx, meal, date)
                    }
                    Reminders.A_WATER -> {
                        Repo.logWater(ctx, 250)
                        ctx.getSystemService(android.app.NotificationManager::class.java).cancel(3)
                    }
                }
            } catch (e: Exception) {
                Reminders.info(ctx, "Couldn't save", e.message ?: "Please try again in the app.")
            } finally {
                Reminders.updateSummary(ctx)
                pending.finish()
            }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Reminders.scheduleAll(ctx)
            Reminders.updateSummary(ctx)
        }
    }
}
