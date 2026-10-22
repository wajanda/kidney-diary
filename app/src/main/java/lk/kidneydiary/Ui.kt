@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package lk.kidneydiary

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
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
    else -> Pill("Not checked", Muted, Line)
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

fun configured(ctx: Context) = SecureStore.has(ctx, "notion")
fun hasClaude(ctx: Context) = SecureStore.has(ctx, "claude")

// ---------------- app shell ----------------

@Composable
fun AppRoot(openLog: MutableState<Pair<String, String>?>) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(!configured(ctx)) }
    var logTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(openLog.value) {
        openLog.value?.let { logTarget = it; openLog.value = null }
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
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Filled.Star, null) }, label = { Text("Suggest") })
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    0 -> TodayScreen(refresh, onLog = { logTarget = it to today() }, onSettings = { showSettings = true })
                    1 -> InsightsScreen()
                    else -> SuggestScreen()
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
    var history by remember { mutableStateOf(Repo.cachedHistory(ctx)) }
    var water by remember { mutableIntStateOf(Prefs.water(ctx, today())) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(refresh) {
        status = "Syncing with Notion…"
        status = try {
            history = withContext(Dispatchers.IO) { Repo.refreshHistory(ctx) }
            ""
        } catch (e: Exception) {
            "Offline: showing saved data. ${e.message ?: ""}"
        }
        water = Prefs.water(ctx, today())
        Reminders.updateSummary(ctx)
    }

    val t = today()
    val todays = history.filter { it.date == t && it.meal in MEALS }
    val saltRank = mapOf("Low" to 1, "Medium" to 2, "High" to 3)
    val salt = todays.maxByOrNull { saltRank[it.salt] ?: 0 }?.salt?.ifBlank { null } ?: "—"
    val protein = todays.sumOf { it.protein }
    val streak = Repo.streak(history)
    val lastNote = todays.lastOrNull { it.note.isNotBlank() }?.note

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), "Today's meals") {
            if (streak > 0) Pill("$streak-day streak", TealDark, TealSoft)
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Muted) }
        }
        if (status.isNotBlank()) Text(status, color = Muted, fontSize = 12.sp)

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
                Text("%.1f / 1–2".format(protein), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = if (protein > 2.5) AmberFg else Ink)
            }
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { Repo.logWater(ctx, 250) }
                    water = Prefs.water(ctx, today())
                    history = Repo.cachedHistory(ctx)
                    Reminders.updateSummary(ctx)
                }
            },
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

        if (lastNote != null) {
            Panel(color = Night) {
                Text("Claude's note", color = Color(0xFF9FE0D3), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(lastNote, color = Color(0xFFF1F5F3), fontSize = 14.sp)
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
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var photoB64 by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CheckResult?>(null) }
    var error by remember { mutableStateOf("") }
    val usual = remember(meal) { Repo.usualTitles(ctx, meal) }

    val photoUri: Uri = remember {
        val f = File(ctx.cacheDir, "photos/meal.jpg").apply { parentFile?.mkdirs() }
        FileProvider.getUriForFile(ctx, "lk.kidneydiary.files", f)
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) scope.launch {
            try {
                val (bmp, b64) = withContext(Dispatchers.IO) { Img.load(ctx, photoUri) }
                photo = bmp; photoB64 = b64; result = null
            } catch (e: Exception) {
                error = "Couldn't read the photo."
            }
        }
    }
    val speak = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) { text = if (text.isBlank()) said else "$text, $said"; result = null }
    }

    fun finish(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                block()
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
            MEALS.forEach { m ->
                FilterChip(selected = meal == m, onClick = { meal = m; result = null }, label = { Text(m) })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { takePhoto.launch(photoUri) }, modifier = Modifier.weight(1f).height(56.dp)) {
                Text("Take a photo", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = {
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "What did you eat?")
                    try { speak.launch(i) } catch (e: Exception) { error = "Voice input isn't available. Use the keyboard mic instead." }
                },
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text("Say it", fontWeight = FontWeight.Bold) }
        }

        photo?.let {
            Image(
                it.asImageBitmap(), contentDescription = "Meal photo", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)),
            )
        }

        OutlinedTextField(
            value = text, onValueChange = { text = it; result = null },
            label = { Text("What did you eat?") }, placeholder = { Text("e.g. 3 string hoppers, dhal, pol sambol") },
            modifier = Modifier.fillMaxWidth(), minLines = 2,
        )

        if (usual.isNotEmpty()) {
            Text("Your usual ${meal.lowercase()}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                usual.forEach { u -> AssistChip(onClick = { text = u; result = null }, label = { Text(u) }) }
            }
        }

        if (error.isNotBlank()) Text(error, color = RedFg, fontSize = 13.sp)

        result?.let { r ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Claude's check", color = TealDark, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    VerdictPill(r.verdict)
                }
                r.items.forEach { (name, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, modifier = Modifier.weight(1f), fontSize = 14.sp)
                        VerdictPill(v)
                    }
                }
                if (r.note.isNotBlank()) Text(r.note, fontSize = 14.sp, color = Color(0xFF3E4A45))
            }
        }

        val r = result
        if (r == null && !hasClaude(ctx)) {
            Button(
                onClick = {
                    finish {
                        withContext(Dispatchers.IO) {
                            Repo.save(ctx, Entry(date, nowHm(), meal, text, items = text.split(",").map { it.trim() }, via = "App"))
                        }
                    }
                },
                enabled = !busy && text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) { Text("Save ${meal.lowercase()}", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
            Text("Tip: ask Claude in your HEALTH project if you're unsure about a food. Add a Claude API key in Settings for instant checks here.", fontSize = 12.sp, color = Muted)
        } else if (r == null) {
            Button(
                onClick = {
                    scope.launch {
                        busy = true; error = ""
                        try {
                            result = withContext(Dispatchers.IO) { Repo.check(ctx, meal, text, photoB64) }
                        } catch (e: Exception) {
                            error = e.message ?: "Couldn't reach Claude."
                        }
                        busy = false
                    }
                },
                enabled = !busy && (text.isNotBlank() || photoB64 != null),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) { Text(if (busy) "Checking…" else "Check with Claude", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
            if (text.isNotBlank()) {
                TextButton(onClick = {
                    finish {
                        withContext(Dispatchers.IO) {
                            Repo.save(ctx, Entry(date, nowHm(), meal, text, items = text.split(",").map { it.trim() }, note = "Saved without checking."))
                        }
                    }
                }, enabled = !busy) { Text("Save without checking") }
            }
        } else {
            Button(
                onClick = { finish { withContext(Dispatchers.IO) { Repo.logChecked(ctx, meal, r.copy(title = text.ifBlank { r.title }), "App", date) } } },
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp),
            ) { Text("Save ${meal.lowercase()}", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { finish { withContext(Dispatchers.IO) { Repo.logUsual(ctx, meal, date) } } }, enabled = !busy) { Text("Same as usual") }
            TextButton(onClick = { finish { withContext(Dispatchers.IO) { Repo.logSkip(ctx, meal, date) } } }, enabled = !busy) { Text("I skipped it") }
        }
    }
}

// ---------------- Insights ----------------

@Composable
fun InsightsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf(Repo.cachedHistory(ctx)) }
    var review by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { history = withContext(Dispatchers.IO) { Repo.refreshHistory(ctx) } } }
    val s = remember(history) { Repo.weekStats(history) }
    val fmt = DateTimeFormatter.ofPattern("d MMM")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header("${LocalDate.now().minusDays(6).format(fmt)} – ${LocalDate.now().format(fmt)}", "This week")

        if (s.daysLogged == 0) {
            Panel { Text("Log a few days of meals and your patterns will show up here.", color = Muted) }
            return@Column
        }

        if (s.street >= 2) Warn("Street food ${s.street} times this week", "Usually very salty and oily. Keep it to once a week. (${s.streetDishes.joinToString(", ")})")
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

        if (hasClaude(ctx)) Button(
            onClick = {
                scope.launch {
                    busy = true
                    review = try { withContext(Dispatchers.IO) { Repo.weeklyReview(ctx) } } catch (e: Exception) { e.message ?: "Couldn't reach Claude." }
                    busy = false
                }
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (busy) "Asking Claude…" else "Ask Claude for a weekly review", fontWeight = FontWeight.Bold) }
        if (review.isNotBlank()) Panel { Text(review, fontSize = 14.sp) }
    }
}

// ---------------- Suggestions + Ask Claude ----------------

@Composable
fun SuggestScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = remember { Repo.cachedHistory(ctx) }
    val days = Repo.daysLogged(history)
    var plan by remember { mutableStateOf<JSONObject?>(null) }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(LocalDate.now().plusDays(1).format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), "Tomorrow's plan")

        Panel(color = TealSoft) {
            Row {
                Text("Learning your habits", fontWeight = FontWeight.Bold, color = TealDark, modifier = Modifier.weight(1f))
                Text("$days days logged", fontWeight = FontWeight.Bold, color = TealDark)
            }
            LinearProgressIndicator(progress = { days / 7f }, color = Teal, trackColor = Color(0xFFC3DFD8), modifier = Modifier.fillMaxWidth())
            Text(
                if (days >= 7) "Suggestions are on. They get more personal after 30 days." else "Suggestions start after 7 days of logging. ${7 - days} to go.",
                fontSize = 12.sp, color = Color(0xFF2F4A44),
            )
        }

        if (!hasClaude(ctx)) {
            Panel { Text("Meal plans and \"Ask Claude\" need a Claude API key (Settings). Until then, ask Claude in your HEALTH project.", color = Muted, fontSize = 14.sp) }
        }
        if (days >= 7 && hasClaude(ctx)) {
            val p = plan
            if (p == null) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true; error = ""
                            try { plan = withContext(Dispatchers.IO) { Repo.planTomorrow(ctx) } } catch (e: Exception) { error = e.message ?: "Couldn't reach Claude." }
                            busy = false
                        }
                    },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (busy) "Planning…" else "Plan tomorrow's meals", fontWeight = FontWeight.Bold) }
            } else {
                Panel {
                    MEALS.forEachIndexed { i, m ->
                        val o = p.optJSONObject(m)
                        if (o != null) {
                            Text(m.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Muted)
                            Text(o.optString("dish"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            if (o.optString("reason").isNotBlank()) Pill(o.optString("reason"), GreenFg, GreenBg)
                            if (i < MEALS.lastIndex) HorizontalDivider(color = Line)
                        }
                    }
                }
                if (p.optString("tip").isNotBlank()) Panel(color = Night) { Text(p.optString("tip"), color = Color(0xFFF1F5F3)) }
            }
        }

        if (error.isNotBlank()) Text(error, color = RedFg, fontSize = 13.sp)

        if (hasClaude(ctx)) Text("Ask Claude anything", fontWeight = FontWeight.Bold, color = Color(0xFF3E4A45))
        if (hasClaude(ctx)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = question, onValueChange = { question = it },
                placeholder = { Text("Is wood apple juice OK for me?") }, modifier = Modifier.weight(1f),
            )
            FilledIconButton(
                onClick = {
                    val q = question
                    scope.launch {
                        busy = true; answer = ""
                        answer = try { withContext(Dispatchers.IO) { Repo.askAnything(ctx, q) } } catch (e: Exception) { e.message ?: "Couldn't reach Claude." }
                        busy = false
                    }
                },
                enabled = !busy && question.isNotBlank(), modifier = Modifier.size(52.dp),
            ) { Icon(Icons.Filled.Send, contentDescription = "Send") }
        }
        if (busy && plan != null || busy && answer.isEmpty() && question.isNotBlank()) Text("Thinking…", color = Muted)
        if (answer.isNotBlank()) Panel { Text(answer, fontSize = 14.sp) }
    }
}

// ---------------- Settings ----------------

@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var claudeKey by remember { mutableStateOf("") }
    var notionKey by remember { mutableStateOf("") }
    var claudeSaved by remember { mutableStateOf(SecureStore.has(ctx, "claude")) }
    var hasNotion by remember { mutableStateOf(SecureStore.has(ctx, "notion")) }
    var db by remember { mutableStateOf(Prefs.notionDb(ctx)) }
    var page by remember { mutableStateOf(Prefs.profilePage(ctx)) }
    var model by remember { mutableStateOf(Prefs.model(ctx)) }
    val times = remember { mutableStateMapOf<String, String>().apply { MEALS.forEach { put(it, Prefs.mealTime(ctx, it).toString()) } } }
    var quietStart by remember { mutableStateOf(Prefs.quietStart(ctx).toString()) }
    var quietEnd by remember { mutableStateOf(Prefs.quietEnd(ctx).toString()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun save() {
        if (claudeKey.isNotBlank()) { SecureStore.put(ctx, "claude", claudeKey); claudeKey = ""; claudeSaved = true }
        if (notionKey.isNotBlank()) { SecureStore.put(ctx, "notion", notionKey); notionKey = ""; hasNotion = true }
        fun notionId(s: String): String =
            Regex("[0-9a-fA-F]{32,}").findAll(s.substringBefore("?").replace("-", "")).lastOrNull()?.value?.takeLast(32) ?: s.trim()
        Prefs.set(ctx, "notionDb", notionId(db))
        Prefs.set(ctx, "profilePage", notionId(page))
        Prefs.set(ctx, "model", model.trim())
        times.forEach { (m, v) -> Prefs.set(ctx, "time_$m", v.trim()) }
        Prefs.set(ctx, "quietStart", quietStart.trim())
        Prefs.set(ctx, "quietEnd", quietEnd.trim())
        Reminders.scheduleAll(ctx)
    }

    BackHandler(enabled = configured(ctx)) { onClose() }

    Column(
        Modifier.fillMaxSize().background(Ground).verticalScroll(rememberScrollState()).padding(20.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (configured(ctx)) IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            Text(if (configured(ctx)) "Settings" else "Set up Kidney Diary", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }

        Panel {
            Text("Your keys", fontWeight = FontWeight.ExtraBold)
            Text("Stored encrypted on this phone. Only ever sent to Claude and Notion.", fontSize = 12.sp, color = Muted)
            OutlinedTextField(
                claudeKey, { claudeKey = it }, label = { Text(if (claudeSaved) "Claude API key (saved)" else "Claude API key (optional)") },
                placeholder = { Text(if (claudeSaved) "Paste only to replace" else "sk-ant-…") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                notionKey, { notionKey = it }, label = { Text(if (hasNotion) "Notion secret (saved)" else "Notion secret") },
                placeholder = { Text(if (hasNotion) "Paste only to replace" else "ntn_…") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
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
            Text("Advanced", fontWeight = FontWeight.ExtraBold)
            OutlinedTextField(db, { db = it }, label = { Text("Notion Meal Log ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(page, { page = it }, label = { Text("Notion App Health Profile ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("Claude model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("This app only connects to api.anthropic.com and api.notion.com.", fontSize = 12.sp, color = Muted)
        }

        if (status.isNotBlank()) Panel(color = TealSoft) { Text(status, color = TealDark, fontSize = 14.sp) }

        Button(
            onClick = {
                save()
                scope.launch {
                    busy = true
                    val lines = mutableListOf<String>()
                    lines += try { withContext(Dispatchers.IO) { Notion.test(ctx) } } catch (e: Exception) { "Notion: ${e.message}" }
                    lines += if (!hasClaude(ctx)) "Claude: no key (optional). In-app food checks are off." else try { withContext(Dispatchers.IO) { Repo.testClaude(ctx) } } catch (e: Exception) { "Claude: ${e.message}" }
                    status = lines.joinToString("\n")
                    busy = false
                }
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (busy) "Testing…" else "Save and test connection", fontWeight = FontWeight.Bold) }

        if (configured(ctx)) {
            OutlinedButton(onClick = { save(); onClose() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
        }
    }
}
