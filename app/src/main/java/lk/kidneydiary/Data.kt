package lk.kidneydiary

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.KeyStore
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

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
    val waterMl: Int = 0,
    val street: Boolean = false,
    val skipped: Boolean = false,
    val note: String = "",
    val via: String = "App",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("date", date).put("time", time).put("meal", meal).put("title", title)
        .put("items", JSONArray(items)).put("verdict", verdict).put("salt", salt)
        .put("protein", protein).put("veg", veg).put("waterMl", waterMl)
        .put("street", street).put("skipped", skipped).put("note", note).put("via", via)

    companion object {
        fun fromJson(o: JSONObject): Entry {
            val arr = o.optJSONArray("items") ?: JSONArray()
            return Entry(
                date = o.optString("date"), time = o.optString("time"), meal = o.optString("meal"),
                title = o.optString("title"),
                items = (0 until arr.length()).map { arr.optString(it) },
                verdict = o.optString("verdict"), salt = o.optString("salt"),
                protein = o.optDouble("protein", 0.0), veg = o.optDouble("veg", 0.0),
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
    val street: Boolean,
    val note: String,
)

// ---------------------------------------------------------------------------
// Secrets (Claude key, Notion secret) encrypted with the phone's hardware Keystore
// ---------------------------------------------------------------------------
object SecureStore {
    private const val ALIAS = "kd_secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("kd_secure", Context.MODE_PRIVATE)

    fun put(ctx: Context, name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val enc = c.doFinal(value.trim().toByteArray())
        prefs(ctx).edit().putString(name, Base64.encodeToString(c.iv + enc, Base64.NO_WRAP)).apply()
    }

    fun get(ctx: Context, name: String): String {
        val s = prefs(ctx).getString(name, null) ?: return ""
        return try {
            val b = Base64.decode(s, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
            String(c.doFinal(b.copyOfRange(12, b.size)))
        } catch (e: Exception) {
            ""
        }
    }

    fun has(ctx: Context, name: String) = get(ctx, name).isNotBlank()
}

// ---------------------------------------------------------------------------
// Ordinary settings and today's counters
// ---------------------------------------------------------------------------
object Prefs {
    val DEFAULT_TIMES = mapOf("Breakfast" to "08:00", "Lunch" to "13:00", "Evening" to "17:00", "Dinner" to "20:00")

    fun p(ctx: Context): SharedPreferences = ctx.getSharedPreferences("kd", Context.MODE_PRIVATE)
    fun str(ctx: Context, k: String, def: String): String = p(ctx).getString(k, def) ?: def
    fun set(ctx: Context, k: String, v: String) = p(ctx).edit().putString(k, v).apply()

    fun notionDb(ctx: Context) = str(ctx, "notionDb", "e35b9eba355e40598fd996e055a961d5")
    fun profilePage(ctx: Context) = str(ctx, "profilePage", "4010cbc79e6b81ceb71cf650c275f70e")
    fun model(ctx: Context) = str(ctx, "model", "claude-haiku-5-5")

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

    fun water(ctx: Context, date: String): Int = p(ctx).getInt("water_$date", 0)
    fun setWater(ctx: Context, date: String, ml: Int) = p(ctx).edit().putInt("water_$date", ml).apply()
}

// ---------------------------------------------------------------------------
// Network: HTTPS only, and only to Claude and Notion. Anything else is refused.
// ---------------------------------------------------------------------------
object Net {
    private val ALLOWED_HOSTS = setOf("api.anthropic.com", "api.notion.com")

    fun request(url: String, method: String, headers: Map<String, String>, body: String?): JSONObject {
        val u = URL(url)
        if (u.protocol != "https" || u.host !in ALLOWED_HOSTS) throw SecurityException("Blocked address: ${u.host}")
        val c = u.openConnection() as HttpsURLConnection
        c.instanceFollowRedirects = false
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 90_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IOException(friendlyError(u.host, code, text))
        return JSONObject(text)
    }

    private fun friendlyError(host: String, code: Int, text: String): String {
        val who = if (host.contains("notion")) "Notion" else "Claude"
        return when (code) {
            401 -> "$who rejected the key. Check it in Settings."
            403 -> "$who: no access. For Notion, connect the Kidney Diary page to your integration."
            404 -> "$who: not found. For Notion, check the page is connected to your integration."
            429 -> "$who is busy. Try again in a minute."
            else -> "$who error $code: ${text.take(200)}"
        }
    }
}

// ---------------------------------------------------------------------------
// Claude API
// ---------------------------------------------------------------------------
object Claude {
    fun text(t: String): JSONObject = JSONObject().put("type", "text").put("text", t)
    fun image(b64: String): JSONObject = JSONObject().put("type", "image").put(
        "source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)
    )

    fun ask(ctx: Context, system: String, content: JSONArray, maxTokens: Int = 900): String {
        val key = SecureStore.get(ctx, "claude")
        if (key.isBlank()) throw IllegalStateException("Add your Claude API key in Settings.")
        val body = JSONObject()
            .put("model", Prefs.model(ctx))
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val r = Net.request(
            "https://api.anthropic.com/v1/messages", "POST",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body.toString()
        )
        val blocks = r.getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString().trim()
    }

    fun json(s: String): JSONObject {
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) throw IOException("Claude gave an unexpected answer. Try again.")
        return JSONObject(s.substring(a, b + 1))
    }
}

// ---------------------------------------------------------------------------
// Notion API (Meal Log database + App Health Profile page)
// ---------------------------------------------------------------------------
object Notion {
    private const val BASE = "https://api.notion.com/v1"

    private fun headers(ctx: Context): Map<String, String> {
        val token = SecureStore.get(ctx, "notion")
        if (token.isBlank()) throw IllegalStateException("Add your Notion secret in Settings.")
        return mapOf("Authorization" to "Bearer $token", "Notion-Version" to "2022-06-28")
    }

    private fun rich(s: String) = JSONObject().put(
        "rich_text", JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", s.take(1900))))
    )

    private fun select(s: String) = JSONObject().put("select", if (s.isBlank()) JSONObject.NULL else JSONObject().put("name", s))

    fun create(ctx: Context, e: Entry) {
        val time = runCatching { LocalTime.parse(e.time) }.getOrElse { LocalTime.now() }
        val dt = OffsetDateTime.of(LocalDate.parse(e.date), time, ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()))
            .truncatedTo(ChronoUnit.MINUTES)
        val items = JSONArray()
        e.items.map { it.replace(",", " ").trim().take(90) }.filter { it.isNotBlank() }.distinct().take(20)
            .forEach { items.put(JSONObject().put("name", it)) }
        val props = JSONObject()
            .put("What I ate", JSONObject().put("title", JSONArray().put(JSONObject().put("text", JSONObject().put("content", e.title.take(1900))))))
            .put("Date", JSONObject().put("date", JSONObject().put("start", dt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))))
            .put("Meal", select(e.meal))
            .put("Items", JSONObject().put("multi_select", items))
            .put("Verdict", select(e.verdict))
            .put("Salt", select(e.salt))
            .put("Protein portions", JSONObject().put("number", e.protein))
            .put("Vegetable servings", JSONObject().put("number", e.veg))
            .put("Water ml", JSONObject().put("number", e.waterMl))
            .put("Street food", JSONObject().put("checkbox", e.street))
            .put("Skipped", JSONObject().put("checkbox", e.skipped))
            .put("Claude note", rich(e.note))
            .put("Logged via", select(e.via))
        val body = JSONObject()
            .put("parent", JSONObject().put("database_id", Prefs.notionDb(ctx)))
            .put("properties", props)
        Net.request("$BASE/pages", "POST", headers(ctx), body.toString())
    }

    fun query(ctx: Context, fromDate: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var cursor: String? = null
        do {
            val body = JSONObject()
                .put("filter", JSONObject().put("property", "Date").put("date", JSONObject().put("on_or_after", fromDate)))
                .put("sorts", JSONArray().put(JSONObject().put("property", "Date").put("direction", "ascending")))
                .put("page_size", 100)
            if (cursor != null) body.put("start_cursor", cursor)
            val r = Net.request("$BASE/databases/${Prefs.notionDb(ctx)}/query", "POST", headers(ctx), body.toString())
            val results = r.getJSONArray("results")
            for (i in 0 until results.length()) parse(results.getJSONObject(i))?.let { out += it }
            cursor = if (r.optBoolean("has_more")) r.optString("next_cursor") else null
        } while (cursor != null)
        return out
    }

    private fun parse(page: JSONObject): Entry? {
        val p = page.optJSONObject("properties") ?: return null
        fun textOf(name: String, kind: String): String {
            val arr = p.optJSONObject(name)?.optJSONArray(kind) ?: return ""
            return (0 until arr.length()).joinToString("") { arr.getJSONObject(it).optString("plain_text") }
        }
        fun sel(name: String) = p.optJSONObject(name)?.optJSONObject("select")?.optString("name") ?: ""
        fun num(name: String) = p.optJSONObject(name)?.optDouble("number", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        fun check(name: String) = p.optJSONObject(name)?.optBoolean("checkbox") ?: false
        val start = p.optJSONObject("Date")?.optJSONObject("date")?.optString("start") ?: return null
        val date = start.take(10)
        val time = if (start.length >= 16) start.substring(11, 16) else "12:00"
        val ms = p.optJSONObject("Items")?.optJSONArray("multi_select") ?: JSONArray()
        return Entry(
            date = date, time = time, meal = sel("Meal"), title = textOf("What I ate", "title"),
            items = (0 until ms.length()).map { ms.getJSONObject(it).optString("name") },
            verdict = sel("Verdict"), salt = sel("Salt"),
            protein = num("Protein portions"), veg = num("Vegetable servings"), waterMl = num("Water ml").toInt(),
            street = check("Street food"), skipped = check("Skipped"),
            note = textOf("Claude note", "rich_text"), via = sel("Logged via"),
        )
    }

    fun profileText(ctx: Context): String {
        val sb = StringBuilder()
        var cursor: String? = null
        do {
            val q = if (cursor != null) "&start_cursor=$cursor" else ""
            val r = Net.request("$BASE/blocks/${Prefs.profilePage(ctx)}/children?page_size=100$q", "GET", headers(ctx), null)
            val results = r.getJSONArray("results")
            for (i in 0 until results.length()) {
                val b = results.getJSONObject(i)
                val type = b.optString("type")
                val rt = b.optJSONObject(type)?.optJSONArray("rich_text") ?: continue
                val line = (0 until rt.length()).joinToString("") { rt.getJSONObject(it).optString("plain_text") }
                val prefix = when (type) {
                    "heading_1", "heading_2", "heading_3" -> "\n## "
                    "bulleted_list_item", "numbered_list_item" -> "- "
                    else -> ""
                }
                sb.append(prefix).append(line).append('\n')
            }
            cursor = if (r.optBoolean("has_more")) r.optString("next_cursor") else null
        } while (cursor != null)
        return sb.toString().trim()
    }

    fun test(ctx: Context): String {
        Net.request("$BASE/databases/${Prefs.notionDb(ctx)}", "GET", headers(ctx), null)
        val prof = profileText(ctx)
        return if (prof.isBlank()) "Meal Log OK, but the App Health Profile page looks empty." else "Notion OK: Meal Log and App Health Profile found."
    }
}

// ---------------------------------------------------------------------------
// Photo helper: shrink to max 1024 px JPEG before sending to Claude
// ---------------------------------------------------------------------------
object Img {
    fun load(ctx: Context, uri: Uri): Pair<Bitmap, String> {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1f, 1024f / maxOf(w, h))
            dec.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return bmp to Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

// ---------------------------------------------------------------------------
// Everything the screens and notifications call
// ---------------------------------------------------------------------------
object Repo {
    private val FALLBACK_PROFILE = """
        Patient: man born 1994, Sri Lanka. Chronic kidney disease stage 3b (eGFR about 33) and high blood pressure.
        Medicines: methylprednisolone 4 mg and mycophenolate (lower immunity, raise blood sugar); irbesartan and spironolactone (can raise potassium); carvedilol, cilnidipine, prazosin.
        Rules: at least 3 L water a day. Low salt (about 1 teaspoon a day). Protein 1-2 portions a day. Potassium has been normal, so normal fruit/vegetable portions are fine.
        Small amounts only of spinach/nivithi, beetroot, nuts, strong tea (oxalate). Never star fruit (kamaranga). Avoid grapefruit/pomelo.
        No raw/undercooked meat, fish or eggs; careful with street food. Low sugar. No herbal remedies without the nephrologist. No NSAID painkillers. No alcohol.
    """.trimIndent()

    private val VERDICT_RANK = mapOf("Avoid" to 4, "Ask doctor" to 3, "Small amount" to 2, "OK" to 1)

    fun worst(verdicts: List<String>): String = verdicts.maxByOrNull { VERDICT_RANK[it] ?: 0 } ?: ""

    // ---- profile ----
    fun profile(ctx: Context): String {
        val cached = Prefs.str(ctx, "profileCache", "")
        val at = Prefs.p(ctx).getLong("profileAt", 0)
        if (cached.isNotBlank() && System.currentTimeMillis() - at < 6 * 3600_000L) return cached
        return try {
            val t = Notion.profileText(ctx)
            if (t.isNotBlank()) {
                Prefs.set(ctx, "profileCache", t)
                Prefs.p(ctx).edit().putLong("profileAt", System.currentTimeMillis()).apply()
                t
            } else cached.ifBlank { FALLBACK_PROFILE }
        } catch (e: Exception) {
            cached.ifBlank { FALLBACK_PROFILE }
        }
    }

    // ---- history cache ----
    fun cachedHistory(ctx: Context): List<Entry> {
        val arr = runCatching { JSONArray(Prefs.str(ctx, "history", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { Entry.fromJson(arr.getJSONObject(it)) }
    }

    private fun saveHistory(ctx: Context, list: List<Entry>) {
        val cutoff = LocalDate.now().minusDays(40).toString()
        val arr = JSONArray()
        list.filter { it.date >= cutoff }.sortedWith(compareBy({ it.date }, { it.time })).forEach { arr.put(it.toJson()) }
        Prefs.set(ctx, "history", arr.toString())
    }

    private fun pending(ctx: Context): MutableList<Entry> {
        val arr = runCatching { JSONArray(Prefs.str(ctx, "pending", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { Entry.fromJson(arr.getJSONObject(it)) }.toMutableList()
    }

    private fun setPending(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        Prefs.set(ctx, "pending", arr.toString())
    }

    @Synchronized
    fun flushPending(ctx: Context) {
        val left = mutableListOf<Entry>()
        for (e in pending(ctx)) {
            try { Notion.create(ctx, e) } catch (ex: Exception) { left += e }
        }
        setPending(ctx, left)
    }

    fun refreshHistory(ctx: Context): List<Entry> {
        flushPending(ctx)
        val from = LocalDate.now().minusDays(35).toString()
        val fresh = Notion.query(ctx, from) + pending(ctx)
        saveHistory(ctx, fresh)
        // keep local counters in step with Notion
        val t = today()
        fresh.filter { it.date == t && it.meal in MEALS }.forEach { Prefs.markLogged(ctx, t, it.meal) }
        val waterToday = fresh.filter { it.date == t }.sumOf { it.waterMl }
        if (waterToday > Prefs.water(ctx, t)) Prefs.setWater(ctx, t, waterToday)
        return cachedHistory(ctx)
    }

    /** Saves locally at once, then sends to Notion. If offline, it is sent next time. */
    fun save(ctx: Context, e: Entry) {
        if (e.meal in MEALS) Prefs.markLogged(ctx, e.date, e.meal)
        if (e.waterMl > 0) Prefs.setWater(ctx, e.date, Prefs.water(ctx, e.date) + e.waterMl)
        saveHistory(ctx, cachedHistory(ctx) + e)
        try {
            Notion.create(ctx, e)
        } catch (ex: Exception) {
            setPending(ctx, pending(ctx) + e)
        }
    }

    // ---- prompts ----
    private fun historyLines(list: List<Entry>, days: Long): String {
        val from = LocalDate.now().minusDays(days).toString()
        val lines = list.filter { it.date >= from }.map {
            if (it.meal == "Water") "${it.date} ${it.time} water ${it.waterMl} ml"
            else "${it.date} ${it.meal}: ${it.title}" +
                (if (it.skipped) " (skipped)" else "") +
                (if (it.verdict.isNotBlank()) " [${it.verdict}" + (if (it.salt.isNotBlank()) ", salt ${it.salt}" else "") + (if (it.street) ", street food" else "") + "]" else "")
        }
        return if (lines.isEmpty()) "(nothing logged yet)" else lines.joinToString("\n")
    }

    private fun system(ctx: Context): String = """
        You help one kidney patient in Sri Lanka choose food. Be kind, short and practical. Use plain English with no medical jargon. Know Sri Lankan foods.
        The patient's doctor and renal dietitian have the final say on anything borderline.

        PATIENT PROFILE AND FOOD RULES (from their Notion page):
        ${profile(ctx)}

        WHAT THEY ATE RECENTLY:
        ${historyLines(cachedHistory(ctx), 14)}
    """.trimIndent()

    fun check(ctx: Context, meal: String, text: String, photoB64: String?): CheckResult {
        val content = JSONArray()
        if (photoB64 != null) content.put(Claude.image(photoB64))
        content.put(
            Claude.text(
                """
                Meal: $meal (${today()}). What I ate: ${text.ifBlank { "see the photo" }}
                Check each food against my rules. Also consider today's meals and the last two weeks (for example street food or the same dish too often, too much salt today).
                Reply with ONLY this JSON:
                {"title": "short list of what was eaten", "items": [{"name": "food", "verdict": "OK|Small amount|Avoid|Ask doctor"}], "verdict": "overall verdict, same 4 options", "salt": "Low|Medium|High", "protein_portions": number, "veg_servings": number, "street_food": true or false, "note": "one or two short sentences of advice"}
                """.trimIndent()
            )
        )
        val o = Claude.json(Claude.ask(ctx, system(ctx), content))
        val arr = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).map { arr.getJSONObject(it).let { j -> j.optString("name") to j.optString("verdict") } }
        return CheckResult(
            title = o.optString("title", text).ifBlank { text },
            items = items,
            verdict = o.optString("verdict").ifBlank { worst(items.map { it.second }) },
            salt = o.optString("salt"),
            protein = o.optDouble("protein_portions", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            veg = o.optDouble("veg_servings", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            street = o.optBoolean("street_food"),
            note = o.optString("note"),
        )
    }

    private fun timeFor(ctx: Context, meal: String, date: String) =
        if (date == today()) nowHm() else Prefs.mealTime(ctx, meal).toString()

    fun logChecked(ctx: Context, meal: String, r: CheckResult, via: String, date: String = today()) {
        save(
            ctx, Entry(
                date = date, time = timeFor(ctx, meal, date), meal = meal, title = r.title,
                items = r.items.map { it.first }, verdict = r.verdict, salt = r.salt,
                protein = r.protein, veg = r.veg, street = r.street, note = r.note, via = via,
            )
        )
    }

    /** Used by notification replies: checks with Claude if possible, otherwise saves the text unchecked. */
    fun logText(ctx: Context, meal: String, text: String, via: String, date: String = today()): CheckResult {
        val r = try {
            check(ctx, meal, text, null)
        } catch (e: Exception) {
            CheckResult(text, emptyList(), "", "", 0.0, 0.0, false, "Not checked yet (no connection).")
        }
        logChecked(ctx, meal, r, via, date)
        return r
    }

    fun usualTitles(ctx: Context, meal: String): List<String> =
        cachedHistory(ctx).filter { it.meal == meal && !it.skipped && it.title.isNotBlank() }
            .groupingBy { it.title.trim() }.eachCount()
            .entries.sortedByDescending { it.value }.take(4).map { it.key }

    fun logUsual(ctx: Context, meal: String, date: String = today()): Entry {
        val top = usualTitles(ctx, meal).firstOrNull()
        val last = cachedHistory(ctx).lastOrNull { it.meal == meal && it.title.trim() == top }
        val e = last?.copy(date = date, time = timeFor(ctx, meal, date), via = "Same as usual")
            ?: Entry(date, timeFor(ctx, meal, date), meal, "Usual ${meal.lowercase()}", note = "Logged as usual. No history yet to copy.", via = "Same as usual")
        save(ctx, e)
        return e
    }

    fun logSkip(ctx: Context, meal: String, date: String = today()) {
        save(ctx, Entry(date, timeFor(ctx, meal, date), meal, "Skipped", skipped = true, via = "App"))
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

    fun weeklyReview(ctx: Context): String = Claude.ask(
        ctx, system(ctx), JSONArray().put(
            Claude.text("Look at my last 7 days. In 5 short bullet points: what went well, what to watch (salt, street food, repeats, water, vegetables, protein), and one simple change for next week.")
        ), 700
    )

    fun planTomorrow(ctx: Context): JSONObject {
        val cacheKey = "plan_${LocalDate.now().plusDays(1)}"
        Prefs.str(ctx, cacheKey, "").takeIf { it.isNotBlank() }?.let { return JSONObject(it) }
        val o = Claude.json(
            Claude.ask(
                ctx, system(ctx), JSONArray().put(
                    Claude.text(
                        """
                        Plan my meals for tomorrow (${LocalDate.now().plusDays(1)}) using everyday Sri Lankan food I can actually get.
                        Use my history: fix what I'm short on (vegetables, water), avoid repeating the same dish too often, and replace habits that are bad for me (street food, salty food) with something similar but better.
                        Reply with ONLY this JSON:
                        {"Breakfast": {"dish": "...", "reason": "short reason"}, "Lunch": {...}, "Evening": {...}, "Dinner": {...}, "tip": "one short tip for the day"}
                        """.trimIndent()
                    )
                ), 900
            )
        )
        Prefs.set(ctx, cacheKey, o.toString())
        return o
    }

    fun askAnything(ctx: Context, q: String): String = Claude.ask(
        ctx, system(ctx), JSONArray().put(
            Claude.text("$q\n\nIf this is about a food or drink, start with: OK / Small amount / Avoid / Ask doctor, then one or two short reasons.")
        ), 700
    )

    fun testClaude(ctx: Context): String {
        Claude.ask(ctx, "Reply with the single word OK.", JSONArray().put(Claude.text("Test")), 10)
        return "Claude OK."
    }
}
