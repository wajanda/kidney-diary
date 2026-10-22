@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package lk.kidneydiary

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

// ---------------- colours (match the design) ----------------
val Teal = Color(0xFF0F766E)
val TealDark = Color(0xFF0F5F58)
val TealSoft = Color(0xFFE3F1EE)
val Ink = Color(0xFF1C2321)
val Muted = Color(0xFF56625D)
val Ground = Color(0xFFF4F6F5)
val Night = Color(0xFF1C2B28)
val Line = Color(0xFFE4E9E7)
val GreenFg = Color(0xFF146C3A)
val GreenBg = Color(0xFFDCF2E6)
val AmberFg = Color(0xFF8A3D05)
val AmberBg = Color(0xFFFCEBD5)
val AmberCard = Color(0xFFFFF4E6)
val RedFg = Color(0xFF8F1D1D)
val RedBg = Color(0xFFFBE1E1)
val PurpleFg = Color(0xFF5B2A86)
val PurpleBg = Color(0xFFEDE3F7)
val Blue = Color(0xFF1D4ED8)

@Composable
fun KDTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Teal, onPrimary = Color.White, primaryContainer = TealSoft, onPrimaryContainer = TealDark,
            secondaryContainer = TealSoft, onSecondaryContainer = TealDark,
            background = Ground, onBackground = Ink, surface = Color.White, onSurface = Ink,
            surfaceVariant = Line, onSurfaceVariant = Muted, outline = Color(0xFFC9D3CF),
        ),
        content = content,
    )
}

// ---------------- small building blocks ----------------

@Composable
fun Panel(modifier: Modifier = Modifier, color: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun Pill(text: String, fg: Color, bg: Color) {
    Text(
        text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
fun VerdictPill(v: String) = when (v) {
    "OK" -> Pill("OK", GreenFg, GreenBg)
    "Small amount" -> Pill("Small amount", AmberFg, AmberBg)
    "Avoid" -> Pill("Avoid", RedFg, RedBg)
    "Ask doctor" -> Pill("Ask doctor", PurpleFg, PurpleBg)
    else -> Pill("Logged", Muted, Line)
}

@Composable
fun Header(small: String, big: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(small, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(big, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
        }
        trailing()
    }
}

@Composable
fun Warn(title: String, body: String) {
    Panel(color = AmberCard) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = AmberFg)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.ExtraBold, color = Color(0xFF7A3504), fontSize = 15.sp)
                Text(body, fontSize = 13.sp, color = Color(0xFF3E2A14))
            }
        }
    }
}

@Composable
fun Bar(label: String, right: String, frac: Float, good: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(right, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (good) GreenFg else AmberFg)
        }
        LinearProgressIndicator(
            progress = { frac.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = if (good) Teal else Color(0xFFC2610C), trackColor = Line,
        )
    }
}

fun monthName(ym: String): String = YearMonth.parse(ym).format(DateTimeFormatter.ofPattern("MMMM yyyy"))

// ---------------- app shell ----------------

@Composable
fun AppRoot(openLog: MutableState<Pair<String, String>?>, openTab: MutableState<String?>) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var logTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(openLog.value) { openLog.value?.let { logTarget = it; openLog.value = null } }
    LaunchedEffect(openTab.value) {
        if (openTab.value == "monthly") { tab = 2; logTarget = null; showSettings = false }
        openTab.value = null
    }

    fun nextMeal(): String {
        val done = Prefs.loggedMeals(ctx, today())
        return MEALS.firstOrNull { it !in done } ?: "Evening"
    }

    when {
        showSettings -> SettingsScreen(onClose = { showSettings = false; refresh++ })
        logTarget != null -> LogScreen(logTarget!!.first, logTarget!!.second) { logTarget = null; refresh++ }
        else -> Scaffold(
            containerColor = Ground,
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Today") })
                    NavigationBarItem(selected = false, onClick = { logTarget = nextMeal() to today() }, icon = { Icon(Icons.Filled.AddCircle, null) }, label = { Text("Log") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Filled.Info, null) }, label = { Text("Insights") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Filled.DateRange, null) }, label = { Text("Monthly") })
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    0 -> TodayScreen(refresh, onLog = { logTarget = it to today() }, onSettings = { showSettings = true })
                    1 -> InsightsScreen(refresh)
                    else -> MonthlyScreen()
                }
            }
        }
    }
}

// ---------------- Today ----------------

@Composable
fun TodayScreen(refresh: Int, onLog: (String) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf(emptyList<Entry>()) }

    fun reload() { history = Repo.recent(ctx); Reminders.updateSummary(ctx) }
    LaunchedEffect(refresh) { reload() }

    val t = today()
    val todays = history.filter { it.date == t && it.meal in MEALS }
    val water = history.filter { it.date == t }.sumOf { it.waterMl }
    val saltRank = mapOf("Low" to 1, "Medium" to 2, "High" to 3)
    val salt = todays.filter { !it.skipped }.maxByOrNull { saltRank[it.salt] ?: 0 }?.salt?.ifBlank { null } ?: "—"
    val protein = todays.sumOf { it.protein }
    val streak = Repo.streak(history)
    val lastNote = todays.lastOrNull { it.note.isNotBlank() && !it.skipped }?.note
    val tip = remember {
        Repo.advice(ctx)?.optJSONArray("tips")?.let { a -> if (a.length() > 0) a.optString(LocalDate.now().dayOfMonth % a.length()) else null }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), "Today's meals") {
            if (streak > 0) Pill("$streak-day streak", TealDark, TealSoft)
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Muted) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel(Modifier.weight(1f)) {
                Text("Water", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                Text("%.1f / 3 L".format(water / 1000f), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                LinearProgressIndicator(progress = { water / WATER_TARGET.toFloat() }, color = Color(0xFF2563EB), trackColor = Line, modifier = Modifier.fillMaxWidth())
            }
            Panel(Modifier.weight(1f)) {
                Text("Salt", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                Text(salt, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = if (salt == "High") RedFg else if (salt == "Medium") AmberFg else Ink)
            }
            Panel(Modifier.weight(1f)) {
                Text("Protein", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                Text("%.0f / 1–2".format(protein), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = if (protein > 2.5) AmberFg else Ink)
            }
        }

        OutlinedButton(
            onClick = { scope.launch { withContext(Dispatchers.IO) { Repo.logWater(ctx, 250) }; reload() } },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("+ 250 ml water", color = Blue, fontWeight = FontWeight.Bold) }

        Panel {
            MEALS.forEachIndexed { i, meal ->
                val es = todays.filter { it.meal == meal }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(meal, fontSize = 12.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (es.isEmpty()) "Not logged yet" else es.joinToString(", ") { it.title },
                            fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (es.isEmpty()) Muted else Ink,
                        )
                    }
                    if (es.isEmpty()) Button(onClick = { onLog(meal) }) { Text("Log") }
                    else if (es.all { it.skipped }) Pill("Skipped", Muted, Line)
                    else VerdictPill(Repo.worst(es.map { it.verdict }))
                }
                if (i < MEALS.lastIndex) HorizontalDivider(color = Line)
            }
        }

        val note = lastNote ?: tip
        if (note != null) {
            Panel(color = Night) {
                Text(if (lastNote != null) "Note on your last meal" else "Claude's tip this month", color = Color(0xFF9FE0D3), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(note, color = Color(0xFFF1F5F3), fontSize = 14.sp)
            }
        }
    }
}

// ---------------- Log a meal ----------------

@Composable
fun LogScreen(initialMeal: String, date: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var meal by remember { mutableStateOf(initialMeal) }
    var text by remember { mutableStateOf("") }
    var shop by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val usual = remember(meal) { Repo.usualTitles(ctx, meal) }
    val result = remember(text, shop) { if (text.isBlank()) null else Repo.check(ctx, text, shop, date) }

    val speak = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) text = if (text.isBlank()) said else "$text, $said"
    }

    fun finish(block: () -> Unit) {
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { block() }
                Reminders.cancel(ctx, meal)
                Reminders.updateSummary(ctx)
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "Something went wrong."
            }
            busy = false
        }
    }

    BackHandler { onDone() }

    Column(
        Modifier.fillMaxSize().background(Ground).verticalScroll(rememberScrollState()).padding(20.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            Text(
                if (date == today()) "Log ${meal.lowercase()}" else "Log ${meal.lowercase()} for $date",
                fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
            )
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MEALS.forEach { m -> FilterChip(selected = meal == m, onClick = { meal = m }, label = { Text(m) }) }
        }

        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("What did you eat?") }, placeholder = { Text("e.g. 3 string hoppers, dhal, pol sambol") },
            modifier = Modifier.fillMaxWidth(), minLines = 2,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "What did you eat?")
                    try { speak.launch(i) } catch (e: Exception) { error = "Voice input isn't available. Use the keyboard mic instead." }
                },
                modifier = Modifier.height(48.dp),
            ) { Text("Say it", fontWeight = FontWeight.Bold) }
            FilterChip(selected = shop, onClick = { shop = !shop }, label = { Text("From a shop / street") })
        }

        if (usual.isNotEmpty()) {
            Text("Your usual ${meal.lowercase()}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                usual.forEach { u -> AssistChip(onClick = { text = u }, label = { Text(u) }) }
            }
        }

        if (error.isNotBlank()) Text(error, color = RedFg, fontSize = 13.sp)

        result?.let { r ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quick check", color = TealDark, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    VerdictPill(r.verdict)
                }
                r.items.forEach { (name, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, modifier = Modifier.weight(1f), fontSize = 14.sp)
                        VerdictPill(v)
                    }
                }
                Text(r.note, fontSize = 14.sp, color = Color(0xFF3E4A45))
                Text("Salt: ${r.salt}. A simple built-in guide. If unsure, ask Claude in your HEALTH project.", fontSize = 12.sp, color = Muted)
            }
        }

        Button(
            onClick = { result?.let { r -> finish { Repo.logChecked(ctx, meal, r, "App", date) } } },
            enabled = !busy && result != null,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { Text("Save ${meal.lowercase()}", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { finish { Repo.logUsual(ctx, meal, date) } }, enabled = !busy) { Text("Same as usual") }
            TextButton(onClick = { finish { Repo.logSkip(ctx, meal, date) } }, enabled = !busy) { Text("I skipped it") }
        }
    }
}

// ---------------- Insights ----------------

@Composable
fun InsightsScreen(refresh: Int) {
    val ctx = LocalContext.current
    val history = remember(refresh) { Repo.recent(ctx) }
    val s = remember(history) { Repo.weekStats(history) }
    val fmt = DateTimeFormatter.ofPattern("d MMM")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header("${LocalDate.now().minusDays(6).format(fmt)} – ${LocalDate.now().format(fmt)}", "This week")

        if (s.daysLogged == 0) {
            Panel { Text("Log a few days of meals and your patterns will show up here.", color = Muted) }
        } else {
            if (s.street >= 2) Warn("Street/shop food ${s.street} times this week", "Usually very salty and oily. Keep it to once a week. (${s.streetDishes.joinToString(", ")})")
            if (s.highSaltDays >= 2) Warn("${s.highSaltDays} very salty days", "Salt raises your blood pressure. Go easy on sambol, papadam, dried fish and achcharu.")
            if (s.waterLowDays >= 2) Warn("Water under 3 L on ${s.waterLowDays} days", "Your doctor asked for at least 3 L a day. Tap \"+ Glass of water\" each time you drink.")
            s.repeats.forEach { (dish, n) -> Warn("$dish on $n days", "Try something different for more variety.") }
            if (s.street < 2 && s.highSaltDays < 2 && s.waterLowDays < 2 && s.repeats.isEmpty()) {
                Panel(color = GreenBg) { Text("No warnings this week. Keep it up.", color = GreenFg, fontWeight = FontWeight.Bold) }
            }

            Panel {
                Text("How you're doing", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                Bar("Vegetables", "%.0f of 14 servings".format(s.veg), (s.veg / 14).toFloat(), s.veg >= 12)
                Bar("Protein (1–2 portions a day)", "On target ${s.proteinOkDays} of ${s.daysLogged} days", s.proteinOkDays / s.daysLogged.toFloat(), s.proteinOkDays >= s.daysLogged - 1)
                Bar("Days logged", "${s.daysLogged} of 7", s.daysLogged / 7f, s.daysLogged >= 6)
            }

            Panel {
                Text("Salt by day", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    s.salt.forEach { (_, lvl) ->
                        Box(
                            Modifier.weight(1f).fillMaxHeight(if (lvl == 0) 0.05f else lvl / 3f).clip(RoundedCornerShape(6.dp))
                                .background(if (lvl == 3) Color(0xFFC2610C) else if (lvl == 0) Line else Teal)
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.salt.forEach { (d, _) ->
                        Text(d.format(DateTimeFormatter.ofPattern("EEE")), fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f))
                    }
                }
                Text("Orange = very salty day. Grey = nothing logged.", fontSize = 12.sp, color = Muted)
            }
        }
    }
}

// ---------------- Monthly: datapack out, advice in ----------------

@Composable
fun MonthlyScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val months = remember { Store.months(ctx).reversed() }
    var exported by remember { mutableStateOf(Prefs.exportedMonths(ctx)) }
    var advice by remember { mutableStateOf(Repo.advice(ctx)) }
    var pendingMonth by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }

    fun packText(ym: String): String = Datapack.build(ctx, ym, version).toString(2)
    fun done(ym: String) {
        Prefs.markExported(ctx, ym)
        exported = Prefs.exportedMonths(ctx)
        Reminders.clearDatapackNotice(ctx)
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val ym = pendingMonth
        if (uri != null && ym != null) scope.launch {
            status = try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(packText(ym).toByteArray()) }
                }
                done(ym)
                "Saved ${Datapack.fileName(ym)}. Now upload it to your HEALTH project."
            } catch (e: Exception) {
                "Couldn't save: ${e.message}"
            }
        }
    }

    val openAdvice = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            status = try {
                val text = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                }
                advice = Repo.importAdvice(ctx, text)
                "Claude's advice imported."
            } catch (e: Exception) {
                "Couldn't import: ${e.message}"
            }
        }
    }

    fun share(ym: String) {
        scope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    val d = File(ctx.cacheDir, "datapacks").apply { mkdirs() }
                    File(d, Datapack.fileName(ym)).apply { writeText(packText(ym)) }
                }
                val uri: Uri = FileProvider.getUriForFile(ctx, "lk.kidneydiary.files", f)
                val send = Intent(Intent.ACTION_SEND).setType("application/json")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "Kidney Diary datapack ${monthName(ym)}")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(send, "Send datapack"))
                done(ym)
            } catch (e: Exception) {
                status = "Couldn't share: ${e.message}"
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header("Your link with Claude", "Monthly")

        Panel(color = TealSoft) {
            Text("How it works", fontWeight = FontWeight.Bold, color = TealDark)
            Text(
                "1. At the start of each month, save last month's datapack.\n" +
                    "2. Upload it to your HEALTH project in Claude.\n" +
                    "3. Claude reviews it and can give you an advice file. Import it below.",
                fontSize = 13.sp, color = Color(0xFF2F4A44),
            )
        }

        if (status.isNotBlank()) Panel(color = GreenBg) { Text(status, color = GreenFg, fontSize = 14.sp) }

        Text("Datapacks", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        if (months.isEmpty()) Panel { Text("Nothing logged yet. Your first datapack appears here once you log a meal.", color = Muted) }
        months.forEach { ym ->
            val days = remember(ym) { Store.load(ctx, ym).filter { it.meal in MEALS && !it.skipped }.map { it.date }.distinct().size }
            val isCurrent = ym == YearMonth.now().toString()
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(monthName(ym), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("$days days logged" + if (isCurrent) " · month still running" else "", fontSize = 13.sp, color = Muted)
                    }
                    if (ym in exported) Pill("Sent", GreenFg, GreenBg) else if (!isCurrent) Pill("Not sent yet", AmberFg, AmberBg)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { pendingMonth = ym; saveFile.launch(Datapack.fileName(ym)) }, modifier = Modifier.weight(1f)) { Text("Save file") }
                    OutlinedButton(onClick = { share(ym) }, modifier = Modifier.weight(1f)) { Text("Share") }
                }
            }
        }

        Text("Claude's advice", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        val a = advice
        if (a == null) {
            Panel { Text("No advice imported yet. After Claude reviews a datapack, it can give you a small advice file to import here.", color = Muted, fontSize = 14.sp) }
        } else {
            Panel {
                Text("For ${a.optString("month").ifBlank { "this month" }}", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Bold)
                if (a.optString("message").isNotBlank()) Text(a.optString("message"), fontSize = 14.sp)
                val tips = a.optJSONArray("tips")
                if (tips != null) for (i in 0 until tips.length()) Text("• " + tips.optString(i), fontSize = 14.sp)
            }
            val plan = a.optJSONObject("plan")
            if (plan != null) {
                Panel {
                    Text("Meal ideas", fontWeight = FontWeight.Bold)
                    MEALS.forEach { m ->
                        val arr = plan.optJSONArray(m)
                        if (arr != null && arr.length() > 0) {
                            Text(m.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Muted)
                            Text((0 until arr.length()).joinToString(" · ") { arr.optString(it) }, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { openAdvice.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Import advice file") }
    }
}

// ---------------- Settings ----------------

@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val times = remember { mutableStateMapOf<String, String>().apply { MEALS.forEach { put(it, Prefs.mealTime(ctx, it).toString()) } } }
    var quietStart by remember { mutableStateOf(Prefs.quietStart(ctx).toString()) }
    var quietEnd by remember { mutableStateOf(Prefs.quietEnd(ctx).toString()) }

    fun save() {
        times.forEach { (m, v) -> Prefs.set(ctx, "time_$m", v.trim()) }
        Prefs.set(ctx, "quietStart", quietStart.trim())
        Prefs.set(ctx, "quietEnd", quietEnd.trim())
        Reminders.scheduleAll(ctx)
    }

    BackHandler { save(); onClose() }

    Column(
        Modifier.fillMaxSize().background(Ground).verticalScroll(rememberScrollState()).padding(20.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { save(); onClose() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }

        Panel {
            Text("Meal times", fontWeight = FontWeight.ExtraBold)
            Text("24-hour time, like 20:00. Reminders start at these times.", fontSize = 12.sp, color = Muted)
            MEALS.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { m ->
                        OutlinedTextField(
                            times[m] ?: "", { times[m] = it }, label = { Text(m) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(quietStart, { quietStart = it }, label = { Text("Quiet from") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(quietEnd, { quietEnd = it }, label = { Text("Quiet until") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }

        Panel {
            Text("Keep reminders working (Samsung)", fontWeight = FontWeight.ExtraBold)
            Text(
                "Samsung puts apps to sleep to save battery, which can stop reminders. Open the app's settings, tap Battery, choose Unrestricted. " +
                    "Also add Kidney Diary under Settings › Battery › Background usage limits › Never sleeping apps.",
                fontSize = 13.sp,
            )
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            }, modifier = Modifier.fillMaxWidth()) { Text("Open app settings") }
            OutlinedButton(onClick = { Reminders.show(ctx, "Dinner", 3, today()) }, modifier = Modifier.fillMaxWidth()) { Text("Send a test reminder") }
        }

        Panel {
            Text("Privacy", fontWeight = FontWeight.ExtraBold)
            Text(
                "This app has no internet access. Everything stays on this phone. Data only leaves when you save or share a monthly datapack yourself.",
                fontSize = 13.sp,
            )
        }

        Button(onClick = { save(); onClose() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save", fontWeight = FontWeight.Bold) }
    }
}
