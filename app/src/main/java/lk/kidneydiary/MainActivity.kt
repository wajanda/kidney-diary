package lk.kidneydiary

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    private val openLog = mutableStateOf<Pair<String, String>?>(null)
    private val openTab = mutableStateOf<String?>(null)

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.migrate(this)
        handle(intent)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Reminders.scheduleAll(this)
        Reminders.updateSummary(this)
        setContent { KDTheme { AppRoot(openLog, openTab) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        i.getStringExtra("openTab")?.let { openTab.value = it; i.removeExtra("openTab") }
        val meal = i.getStringExtra("openLog") ?: return
        openLog.value = meal to (i.getStringExtra("openDate") ?: today())
        i.removeExtra("openLog")
    }
}
