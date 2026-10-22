package lk.kidneydiary

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

val MEALS = listOf("Breakfast", "Lunch", "Evening", "Dinner")
const val WATER_TARGET = 3000

fun today(): String = LocalDate.now().toString()
fun nowHm(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

// ---------------------------------------------------------------------------
// One logged item (a meal, a snack, or a glass of water)
// ---------------------------------------------------------------------------
data class Entry(
    val date: String,
    val time: String,
    val meal: String,
    val title: String,
    val items: List<String> = emptyList(),
    val verdict: String = "",
    val salt: String = "",
    val protein: Double = 0.0,
    val veg: Double = 0.0,
    val fruit: Double = 0.0,
    val waterMl: Int = 0,
    val street: Boolean = false,
    val skipped: Boolean = false,
    val note: String = "",
    val via: String = "App",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("date", date).put("time", time).put("meal", meal).put("title", title)
        .put("items", JSONArray(items)).put("verdict", verdict).put("salt", salt)
        .put("protein", protein).put("veg", veg).put("fruit", fruit).put("waterMl", waterMl)
        .put("street", street).put("skipped", skipped).put("note", note).put("via", via)

    companion object {
        fun fromJson(o: JSONObject): Entry {
            val arr = o.optJSONArray("items") ?: JSONArray()
            return Entry(
                date = o.optString("date"), time = o.optString("time"), meal = o.optString("meal"),
                title = o.optString("title"),
                items = (0 until arr.length()).map { arr.optString(it) },
                verdict = o.optString("verdict"), salt = o.optString("salt"),
                protein = o.optDouble("protein", 0.0), veg = o.optDouble("veg", 0.0), fruit = o.optDouble("fruit", 0.0),
                waterMl = o.optInt("waterMl", 0), street = o.optBoolean("street"),
                skipped = o.optBoolean("skipped"), note = o.optString("note"), via = o.optString("via", "App"),
            )
        }
    }
}

data class CheckResult(
    val title: String,
    val items: List<Pair<String, String>>,
    val verdict: String,
    val salt: String,
    val protein: Double,
    val veg: Double,
    val fruit: Double,
    val street: Boolean,
    val note: String,
)

// ---------------------------------------------------------------------------
// Settings and quick counters
// ---------------------------------------------------------------------------
object Prefs {
    val DEFAULT_TIMES = mapOf("Breakfast" to "08:00", "Lunch" to "13:00", "Evening" to "17:00", "Dinner" to "20:00")

    fun p(ctx: Context): SharedPreferences = ctx.getSharedPreferences("kd", Context.MODE_PRIVATE)
    fun str(ctx: Context, k: String, def: String): String = p(ctx).getString(k, def) ?: def
    fun set(ctx: Context, k: String, v: String) = p(ctx).edit().putString(k, v).apply()

    private fun time(s: String, def: String): LocalTime =
        runCatching { LocalTime.parse(s.trim()) }.getOrElse { LocalTime.parse(def) }

    fun mealTime(ctx: Context, meal: String): LocalTime {
        val def = DEFAULT_TIMES[meal] ?: "12:00"
        return time(str(ctx, "time_$meal", def), def)
    }

    fun quietStart(ctx: Context) = time(str(ctx, "quietStart", "22:30"), "22:30")
    fun quietEnd(ctx: Context) = time(str(ctx, "quietEnd", "06:30"), "06:30")

    fun loggedMeals(ctx: Context, date: String): Set<String> =
        str(ctx, "logged_$date", "").split(",").filter { it.isNotBlank() }.toSet()

    fun markLogged(ctx: Context, date: String, meal: String) {
        set(ctx, "logged_$date", (loggedMeals(ctx, date) + meal).joinToString(","))
    }

    fun exportedMonths(ctx: Context): Set<String> =
        str(ctx, "exported", "").split(",").filter { it.isNotBlank() }.toSet()

    fun markExported(ctx: Context, ym: String) = set(ctx, "exported", (exportedMonths(ctx) + ym).joinToString(","))
}

// ---------------------------------------------------------------------------
// Storage: one file per month, inside the app's private folder on the phone.
// Nothing leaves the phone unless you save or share a datapack yourself.
// ---------------------------------------------------------------------------
object Store {
    private fun dir(ctx: Context) = File(ctx.filesDir, "months").apply { mkdirs() }
    private fun file(ctx: Context, ym: String) = File(dir(ctx), "$ym.json")

    @Synchronized
    fun load(ctx: Context, ym: String): MutableList<Entry> {
        val f = file(ctx, ym)
        if (!f.exists()) return mutableListOf()
        val arr = runCatching { JSONArray(f.readText()) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { Entry.fromJson(arr.getJSONObject(it)) }.toMutableList()
    }

    @Synchronized
    private fun write(ctx: Context, ym: String, list: List<Entry>) {
        val arr = JSONArray()
        list.sortedWith(compareBy({ it.date }, { it.time })).forEach { arr.put(it.toJson()) }
        val tmp = File(dir(ctx), "$ym.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx, ym))
    }

    @Synchronized
    fun append(ctx: Context, e: Entry) {
        val ym = e.date.take(7)
        write(ctx, ym, load(ctx, ym) + e)
    }

    fun months(ctx: Context): List<String> =
        dir(ctx).listFiles()?.map { it.name }?.filter { it.endsWith(".json") }?.map { it.removeSuffix(".json") }?.sorted() ?: emptyList()

    /** Entries from the last [days] days. */
    fun recent(ctx: Context, days: Long = 45): List<Entry> {
        val from = LocalDate.now().minusDays(days)
        val months = generateSequence(YearMonth.from(from)) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(YearMonth.now()) }.map { it.toString() }.toList()
        return months.flatMap { load(ctx, it) }.filter { it.date >= from.toString() }
    }
}

// ---------------------------------------------------------------------------
// Built-in quick check, based on the food rules from the HEALTH project.
// It is a simple guide, not medical advice. Claude's monthly advice file can add to it.
// ---------------------------------------------------------------------------
object Rules {
    private val AVOID = mapOf(
        "star fruit" to "star fruit is dangerous for kidneys", "kamaranga" to "star fruit is dangerous for kidneys",
        "grapefruit" to "grapefruit can interfere with BP medicines", "pomelo" to "pomelo can interfere with BP medicines",
        "jambola" to "pomelo can interfere with BP medicines",
        "alcohol" to "avoid alcohol", "beer" to "avoid alcohol", "arrack" to "avoid alcohol", "toddy" to "avoid alcohol", "wine" to "avoid alcohol",
        "raw egg" to "raw egg is an infection risk on your medicines", "half boiled" to "undercooked egg is an infection risk",
        "half-boiled" to "undercooked egg is an infection risk", "sushi" to "raw fish is an infection risk",
        "energy drink" to "avoid energy drinks",
        "herbal" to "check herbal remedies with your nephrologist", "kola kanda" to "check herbal porridge herbs with your doctor",
        "kothamalli" to "check herbal remedies with your nephrologist", "paspanguwa" to "check herbal remedies with your nephrologist",
        "salt substitute" to "potassium salt substitutes are risky with your BP medicines",
    )
    private val HIGH_SALT = listOf(
        "kottu", "koththu", "papadam", "papadum", "pappadam", "karawala", "dried fish", "maldive fish", "umbalakada",
        "achcharu", "pickle", "noodles", "maggi", "sausage", "bacon", "ham", "mixture", "chips", "devilled", "deviled",
        "fried rice", "biryani", "buriyani", "soy sauce", "lunu miris", "katta sambol", "salted", "cheese", "instant",
    )
    private val MEDIUM_SALT = listOf(
        "sambol", "seeni sambol", "pol sambol", "bread", "butter", "cutlet", "rolls", "patties", "pattis", "wade", "vadai",
        "isso", "bun", "pizza", "burger", "short eats", "lamprais", "soup",
    )
    private val SMALL = mapOf(
        "spinach" to "oxalate: keep spinach small", "nivithi" to "oxalate: keep nivithi small",
        "beetroot" to "oxalate: keep beetroot small", "nuts" to "oxalate: nuts in small amounts",
        "cashew" to "oxalate: nuts in small amounts", "peanut" to "oxalate: nuts in small amounts",
        "almond" to "oxalate: nuts in small amounts", "strong tea" to "oxalate: not too strong",
        "chocolate" to "oxalate and sugar", "cola" to "sugar and phosphate in soft drinks", "soft drink" to "sugar in soft drinks",
        "coca" to "sugar and phosphate in soft drinks", "pepsi" to "sugar and phosphate in soft drinks", "sprite" to "sugar in soft drinks",
        "cake" to "sugar (steroid)", "kokis" to "oily and salty", "kavum" to "sugar and oil", "halape" to "sugar",
        "sweets" to "sugar (steroid)", "ice cream" to "sugar (steroid)", "watalappan" to "sugar (steroid)",
    )
    private val STREET = listOf(
        "kottu", "koththu", "rolls", "patties", "pattis", "short eats", "wade", "vadai", "isso", "cutlet", "shop", "street",
        "hotel", "bakery", "takeaway", "take away", "fried rice", "biryani", "buriyani", "burger", "pizza", "lamprais", "boutique",
    )
    private val PROTEIN = listOf(
        "chicken", "fish", "egg", "dhal", "dal", "parippu", "lentil", "beef", "pork", "mutton", "prawn", "cuttlefish",
        "sprats", "tuna", "mackerel", "salaya", "kadala", "chickpea", "mung", "soya", "meat", "crab", "squid", "paneer",
    )
    private val VEG = listOf(
        "pumpkin", "beans", "cabbage", "carrot", "gotukola", "mukunuwenna", "kankun", "pathola", "snake gourd", "karawila",
        "bitter gourd", "bandakka", "okra", "ladies finger", "brinjal", "wambatu", "leeks", "mallum", "salad", "ash plantain",
        "alu kehel", "cucumber", "tomato", "lettuce", "drumstick", "murunga", "kohila", "polos", "capsicum", "radish", "raabu",
        "vegetable", "veg", "thampala", "sarana", "beans curry", "cauliflower", "broccoli", "knolkhol", "mushroom",
    )
    private val FRUIT = listOf(
        "papaya", "pineapple", "guava", "apple", "pear", "mango", "banana", "watermelon", "orange", "grapes", "wood apple",
        "rambutan", "mangosteen", "avocado", "jambu", "fruit", "anoda", "passion",
    )

    private fun has(text: String, word: String): Boolean =
        Regex("(^|[^a-z])" + Regex.escape(word) + "(s|es)?([^a-z]|$)").containsMatchIn(text)

    private fun count(text: String, words: List<String>) = words.count { has(text, it) }.toDouble()

    private fun advice(ctx: Context): JSONObject? =
        Prefs.str(ctx, "advice", "").takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun extra(ctx: Context, key: String): List<String> {
        val arr = advice(ctx)?.optJSONObject("watch")?.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it).lowercase().trim() }.filter { it.isNotBlank() }
    }

    fun check(ctx: Context, text: String, fromShop: Boolean, recent: List<Entry>, date: String): CheckResult {
        val clean = text.trim()
        val parts = clean.split(Regex(",|\\+|\\band\\b|\\bwith\\b|\\n")).map { it.trim() }.filter { it.isNotBlank() }
        val extraAvoid = extra(ctx, "avoid")
        val extraSmall = extra(ctx, "small")
        val extraOk = extra(ctx, "ok")
        val reasons = linkedSetOf<String>()

        val items = parts.map { part ->
            val t = part.lowercase()
            val okByClaude = extraOk.any { has(t, it) }
            val avoid = AVOID.entries.firstOrNull { has(t, it.key) } ?: extraAvoid.firstOrNull { has(t, it) }?.let { it to "on Claude's avoid list" }?.let { mapOf(it).entries.first() }
            val small = SMALL.entries.firstOrNull { has(t, it.key) } ?: extraSmall.firstOrNull { has(t, it) }?.let { mapOf(it to "on Claude's watch list").entries.first() }
            val salty = HIGH_SALT.firstOrNull { has(t, it) }
            when {
                avoid != null -> { reasons += avoid.value.replaceFirstChar { it.uppercase() }; part to "Avoid" }
                okByClaude -> part to "OK"
                small != null -> { reasons += small.value.replaceFirstChar { it.uppercase() }; part to "Small amount" }
                salty != null -> { reasons += "Very salty: keep it small for your BP"; part to "Small amount" }
                else -> part to "OK"
            }
        }

        val all = clean.lowercase()
        val salt = when {
            HIGH_SALT.any { has(all, it) } -> "High"
            fromShop || MEDIUM_SALT.any { has(all, it) } -> "Medium"
            else -> "Low"
        }
        val street = fromShop || STREET.any { has(all, it) }
        val protein = count(all, PROTEIN)
        val veg = count(all, VEG)
        val fruit = count(all, FRUIT)

        // patterns from recent days
        val weekFrom = LocalDate.parse(date).minusDays(6).toString()
        val week = recent.filter { it.date >= weekFrom && it.meal in MEALS && !it.skipped }
        val streetThisWeek = week.count { it.street } + if (street) 1 else 0
        if (street && streetThisWeek >= 2) reasons += "Street/shop food $streetThisWeek times this week. Try to keep it to once a week"
        val dayEntries = recent.filter { it.date == date && it.meal in MEALS }
        if (salt == "High" && dayEntries.any { it.salt == "High" }) reasons += "Second very salty meal today. Keep the next one plain"
        val proteinToday = dayEntries.sumOf { it.protein } + protein
        if (proteinToday > 2.5) reasons += "That's more than 2 protein portions today. Keep the next meal light on meat/fish/egg/dhal"
        val sameDays = week.filter { it.title.trim().equals(clean, ignoreCase = true) }.map { it.date }.distinct().size
        if (sameDays >= 4) reasons += "You've had this on $sameDays days this week. Try something different"
        if (veg == 0.0 && parts.isNotEmpty() && reasons.isEmpty()) reasons += "Add a vegetable curry or mallum if you can"

        val verdict = Repo.worst(items.map { it.second }).ifBlank { "OK" }
        return CheckResult(
            title = clean, items = items, verdict = verdict, salt = salt,
            protein = protein, veg = veg, fruit = fruit, street = street,
            note = reasons.take(2).joinToString(". ").let { if (it.isNotBlank()) "$it." else "Looks fine for you." },
        )
    }
}

// ---------------------------------------------------------------------------
// Monthly datapack: the file you upload to the HEALTH project
// ---------------------------------------------------------------------------
object Datapack {
    fun fileName(ym: String) = "KidneyDiary_$ym.json"

    fun build(ctx: Context, ym: String, appVersion: String): JSONObject {
        val entries = Store.load(ctx, ym).sortedWith(compareBy({ it.date }, { it.time }))
        val meals = entries.filter { it.meal in MEALS }
        val eaten = meals.filter { !it.skipped }
        val dates = entries.map { it.date }.distinct().sorted()
        val loggedDays = eaten.map { it.date }.distinct()
        val waterByDay = dates.associateWith { d -> entries.filter { it.date == d }.sumOf { it.waterMl } }
        val saltRank = mapOf("Low" to 1, "Medium" to 2, "High" to 3)
        val highSaltDays = loggedDays.count { d -> eaten.any { it.date == d && it.salt == "High" } }
        val proteinByDay = loggedDays.associateWith { d -> eaten.filter { it.date == d }.sumOf { it.protein } }
        val topDishes = eaten.groupingBy { it.title.trim().lowercase() }.eachCount().entries
            .sortedByDescending { it.value }.take(12)
        val flagged = eaten.filter { it.verdict == "Avoid" || it.verdict == "Small amount" }

        val summary = JSONObject()
            .put("days_in_month", YearMonth.parse(ym).lengthOfMonth())
            .put("days_logged", loggedDays.size)
            .put("meals_logged", eaten.size)
            .put("meals_skipped", meals.count { it.skipped })
            .put("meals_by_slot", JSONObject().apply { MEALS.forEach { m -> put(m, eaten.count { it.meal == m }) } })
            .put("water_avg_ml_per_logged_day", if (waterByDay.isEmpty()) 0 else waterByDay.values.average().toInt())
            .put("days_water_under_3000ml", waterByDay.count { it.value < WATER_TARGET })
            .put("street_food_meals", eaten.count { it.street })
            .put("high_salt_days", highSaltDays)
            .put("high_salt_meals", eaten.count { it.salt == "High" })
            .put("vegetable_servings_total", eaten.sumOf { it.veg })
            .put("vegetable_servings_per_logged_day", if (loggedDays.isEmpty()) 0.0 else eaten.sumOf { it.veg } / loggedDays.size)
            .put("fruit_servings_total", eaten.sumOf { it.fruit })
            .put("protein_portions_avg_per_day", if (proteinByDay.isEmpty()) 0.0 else proteinByDay.values.average())
            .put("days_protein_over_2_portions", proteinByDay.count { it.value > 2.5 })
            .put("avoid_items", JSONArray(flagged.filter { it.verdict == "Avoid" }.map { "${it.date} ${it.meal}: ${it.title}" }))
            .put("small_amount_items", JSONArray(flagged.filter { it.verdict == "Small amount" }.map { "${it.date} ${it.meal}: ${it.title}" }))
            .put("top_dishes", JSONArray(topDishes.map { JSONObject().put("dish", it.key).put("times", it.value) }))

        val days = JSONArray()
        dates.forEach { d ->
            val de = entries.filter { it.date == d }
            days.put(
                JSONObject()
                    .put("date", d)
                    .put("water_ml", waterByDay[d] ?: 0)
                    .put("max_salt", de.maxOfOrNull { saltRank[it.salt] ?: 0 }?.let { r -> saltRank.entries.firstOrNull { it.value == r }?.key } ?: "")
                    .put("entries", JSONArray(de.filter { it.meal != "Water" }.map {
                        JSONObject().put("time", it.time).put("meal", it.meal).put("what", it.title)
                            .put("skipped", it.skipped).put("quick_check", it.verdict).put("salt", it.salt)
                            .put("street_or_shop", it.street).put("protein_portions", it.protein)
                            .put("veg_servings", it.veg).put("fruit_servings", it.fruit)
                            .put("note", it.note).put("logged_via", it.via)
                    }))
                    .put("water_times", JSONArray(de.filter { it.meal == "Water" }.map { "${it.time} ${it.waterMl}ml" }))
            )
        }

        return JSONObject()
            .put("format", "kidney-diary-datapack")
            .put("version", 1)
            .put("month", ym)
            .put("generated_at", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("app_version", appVersion)
            .put("for_claude", "Monthly food diary from the Kidney Diary app (CKD 3b + high BP, Sri Lanka). Values like salt, protein, veg and quick_check are the app's simple keyword estimates, not lab data. Please review against the HEALTH project profile and labs, update the logs, and give next month's advice (optionally as a kidney-diary-advice JSON file).")
            .put("targets", JSONObject().put("water_ml_per_day", WATER_TARGET).put("protein_portions_per_day", "1-2").put("veg_servings_per_day", 2).put("street_food_per_week", 1))
            .put("summary", summary)
            .put("days", days)
    }
}

// ---------------------------------------------------------------------------
// Everything the screens and notifications call
// ---------------------------------------------------------------------------
object Repo {
    private val VERDICT_RANK = mapOf("Avoid" to 4, "Ask doctor" to 3, "Small amount" to 2, "OK" to 1)

    fun worst(verdicts: List<String>): String = verdicts.maxByOrNull { VERDICT_RANK[it] ?: 0 } ?: ""

    /** Moves anything saved by the older Notion version into the new on-phone storage (runs once). */
    fun migrate(ctx: Context) {
        if (Prefs.p(ctx).getBoolean("migrated_v2", false)) return
        listOf("history", "pending").forEach { key ->
            val arr = runCatching { JSONArray(Prefs.str(ctx, key, "[]")) }.getOrElse { JSONArray() }
            val existing = Store.recent(ctx, 400).map { it.date + it.time + it.meal + it.title }.toMutableSet()
            for (i in 0 until arr.length()) {
                val e = Entry.fromJson(arr.getJSONObject(i))
                if (e.date.length == 10 && existing.add(e.date + e.time + e.meal + e.title)) Store.append(ctx, e)
            }
        }
        Prefs.p(ctx).edit().remove("history").remove("pending").remove("profileCache")
            .putBoolean("migrated_v2", true).apply()
        ctx.deleteSharedPreferences("kd_secure") // old Claude/Notion keys are no longer needed
    }

    fun recent(ctx: Context): List<Entry> = Store.recent(ctx, 60)

    fun water(ctx: Context, date: String): Int = Store.load(ctx, date.take(7)).filter { it.date == date }.sumOf { it.waterMl }

    fun save(ctx: Context, e: Entry) {
        Store.append(ctx, e)
        if (e.meal in MEALS) Prefs.markLogged(ctx, e.date, e.meal)
    }

    private fun timeFor(ctx: Context, meal: String, date: String) =
        if (date == today()) nowHm() else Prefs.mealTime(ctx, meal).toString()

    fun check(ctx: Context, text: String, fromShop: Boolean, date: String = today()): CheckResult =
        Rules.check(ctx, text, fromShop, recent(ctx), date)

    fun logChecked(ctx: Context, meal: String, r: CheckResult, via: String, date: String = today()) {
        save(
            ctx, Entry(
                date = date, time = timeFor(ctx, meal, date), meal = meal, title = r.title,
                items = r.items.map { it.first }, verdict = r.verdict, salt = r.salt,
                protein = r.protein, veg = r.veg, fruit = r.fruit, street = r.street, note = r.note, via = via,
            )
        )
    }

    fun logText(ctx: Context, meal: String, text: String, via: String, date: String = today()): CheckResult {
        val r = check(ctx, text, false, date)
        logChecked(ctx, meal, r, via, date)
        return r
    }

    fun usualTitles(ctx: Context, meal: String): List<String> =
        recent(ctx).filter { it.meal == meal && !it.skipped && it.title.isNotBlank() }
            .groupingBy { it.title.trim() }.eachCount()
            .entries.sortedByDescending { it.value }.take(4).map { it.key }

    fun logUsual(ctx: Context, meal: String, date: String = today()): Entry {
        val top = usualTitles(ctx, meal).firstOrNull()
        val last = recent(ctx).lastOrNull { it.meal == meal && it.title.trim() == top }
        val e = last?.copy(date = date, time = timeFor(ctx, meal, date), via = "Same as usual")
            ?: Entry(date, timeFor(ctx, meal, date), meal, "Usual ${meal.lowercase()}", note = "Logged as usual. No history yet to copy.", via = "Same as usual")
        save(ctx, e)
        return e
    }

    fun logSkip(ctx: Context, meal: String, date: String = today()) {
        save(ctx, Entry(date, timeFor(ctx, meal, date), meal, "Skipped", skipped = true))
    }

    fun logWater(ctx: Context, ml: Int) {
        save(ctx, Entry(today(), nowHm(), "Water", "Water ${ml} ml", waterMl = ml, verdict = "OK"))
    }

    // ---- patterns ----
    fun daysLogged(list: List<Entry>): Int =
        list.filter { it.meal in MEALS && !it.skipped }.map { it.date }.distinct().size

    fun streak(list: List<Entry>): Int {
        val days = list.filter { it.meal in MEALS }.map { it.date }.toSet()
        var d = LocalDate.now()
        if (d.toString() !in days) d = d.minusDays(1)
        var n = 0
        while (d.toString() in days) { n++; d = d.minusDays(1) }
        return n
    }

    data class Stats(
        val daysLogged: Int,
        val street: Int,
        val streetDishes: List<String>,
        val veg: Double,
        val proteinOkDays: Int,
        val waterLowDays: Int,
        val salt: List<Pair<LocalDate, Int>>,
        val highSaltDays: Int,
        val repeats: List<Pair<String, Int>>,
    )

    fun weekStats(list: List<Entry>): Stats {
        val days = (6 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
        val from = days.first().toString()
        val week = list.filter { it.date >= from }
        val meals = week.filter { it.meal in MEALS && !it.skipped }
        val street = meals.filter { it.street }
        val saltRank = mapOf("Low" to 1, "Medium" to 2, "High" to 3)
        val salt = days.map { d -> d to (meals.filter { it.date == d.toString() }.maxOfOrNull { saltRank[it.salt] ?: 0 } ?: 0) }
        val loggedDays = meals.map { it.date }.distinct()
        val proteinOk = loggedDays.count { d -> meals.filter { it.date == d }.sumOf { it.protein } in 0.5..2.5 }
        val waterLow = days.dropLast(1).count { d -> week.filter { it.date == d.toString() }.sumOf { it.waterMl } < WATER_TARGET }
        val repeats = meals.groupBy { it.title.trim().lowercase() }
            .mapValues { (_, v) -> v.map { it.date }.distinct().size }
            .filter { it.value >= 4 && it.key.isNotBlank() }
            .map { (k, v) -> k.replaceFirstChar { it.uppercase() } to v }
        return Stats(
            daysLogged = loggedDays.size,
            street = street.size,
            streetDishes = street.map { it.title }.distinct().take(3),
            veg = meals.sumOf { it.veg },
            proteinOkDays = proteinOk,
            waterLowDays = waterLow,
            salt = salt,
            highSaltDays = salt.count { it.second == 3 },
            repeats = repeats,
        )
    }

    // ---- Claude's monthly advice file (optional import) ----
    fun importAdvice(ctx: Context, text: String): JSONObject {
        val o = JSONObject(text)
        if (o.optString("format") != "kidney-diary-advice") throw IllegalArgumentException("This isn't a Kidney Diary advice file.")
        Prefs.set(ctx, "advice", o.toString())
        return o
    }

    fun advice(ctx: Context): JSONObject? =
        Prefs.str(ctx, "advice", "").takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
}
