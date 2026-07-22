package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.speech.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.Locale

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LogStore.init(this)
        Notifications.create(this)
    }
}

object LogStore {
    private lateinit var file: File

    fun init(context: Context) {
        file = File(context.filesDir, "logs/actions.log").also { it.parentFile?.mkdirs() }
    }

    @Synchronized
    fun action(trigger: String, action: String, details: String, result: String = "ok") {
        val row = JSONObject(
            mapOf(
                "timestamp" to Instant.now().toString(),
                "trigger" to trigger,
                "action" to action,
                "details" to details,
                "result" to result
            )
        )
        file.appendText(row.toString() + "\n")
    }

    fun read(context: Context): List<String> =
        File(context.filesDir, "logs/actions.log")
            .takeIf { it.exists() }
            ?.readLines()
            ?.takeLast(200)
            ?.reversed()
            ?: emptyList()
}

class Prefs(private val context: Context) {
    private val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var userName: String
        get() = prefs.getString("name", "Felix")!!
        set(value) = prefs.edit().putString("name", value).apply()

    var modelMode: String
        get() = prefs.getString("model", "local custom model")!!
        set(value) = prefs.edit().putString("model", value).apply()

    var apiKey: String
        get() = prefs.getString("api", "")!!
        set(value) = prefs.edit().putString("api", value).apply()

    var ollamaUrl: String
        get() = prefs.getString("ollama", "http://192.168.0.2:11434")!!
        set(value) = prefs.edit().putString("ollama", value).apply()

    var taskerToken: String
        get() = prefs.getString("token", "")!!
        set(value) = prefs.edit().putString("token", value).apply()

    var observedPackages: Set<String>
        get() = prefs.getStringSet("observed", emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet("observed", value).apply()

    var controlledPackages: Set<String>
        get() = prefs.getStringSet("controlled", emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet("controlled", value).apply()

    var trainedCommands: Set<String>
        get() = prefs.getStringSet("commands", emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet("commands", value.take(10).toSet()).apply()
}

data class Decision(val action: String, val confidence: Double, val summary: String)

object LocalBrain {
    private val rules = listOf(
        Regex("(?i)(termin|treffen|geburtstag).*(morgen|montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|\\d{1,2}[.:]\\d{2})") to "calendar_event",
        Regex("(?i)(erinner|denk.*dran)") to "reminder",
        Regex("(?i)(wecker|weck mich)") to "alarm",
        Regex("(?i)(öffne|starte).*(app|whatsapp|youtube|kalender)") to "open_app",
        Regex("(?i)(schreib|tippe|antworte)") to "draft_text",
        Regex("(?i)(fass|zusammenfass)") to "summarize",
        Regex("(?i)(scroll|wisch)") to "scroll"
    )

    fun classify(text: String): Decision {
        val match = rules.firstOrNull { it.first.containsMatchIn(text) }
        return if (match != null) {
            Decision(match.second, 0.88, text.take(160))
        } else {
            Decision("note", 0.55, text.take(160))
        }
    }
}

object EventStore {
    fun add(context: Context, trigger: String, text: String, action: String, address: String? = null) {
        val row = JSONObject(
            mapOf(
                "time" to Instant.now().toString(),
                "trigger" to trigger,
                "text" to text,
                "action" to action,
                "address" to address
            )
        )
        File(context.filesDir, "events.jsonl").appendText(row.toString() + "\n")
    }

    fun read(context: Context): List<String> =
        File(context.filesDir, "events.jsonl")
            .takeIf { it.exists() }
            ?.readLines()
            ?.takeLast(100)
            ?.reversed()
            ?: emptyList()
}

object CommandProcessor {
    fun process(context: Context, instruction: String, trigger: String, address: String? = null) {
        val decision = LocalBrain.classify(instruction)
        LogStore.action(
            trigger,
            decision.action,
            "instruction=$instruction address=${address.orEmpty()} confidence=${decision.confidence}"
        )

        when (decision.action) {
            "calendar_event" -> {
                val intent = Intent(Intent.ACTION_INSERT)
                    .setData(android.provider.CalendarContract.Events.CONTENT_URI)
                    .putExtra(android.provider.CalendarContract.Events.TITLE, instruction)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            "alarm" -> {
                val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                    .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, instruction)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            else -> EventStore.add(context, trigger, instruction, decision.action, address)
        }
    }
}

open class IntentReceiver(
    private val receiverType: String,
    private val mediaReceiver: Boolean = false
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        val suppliedToken = intent.getStringExtra("token").orEmpty()

        if (prefs.taskerToken.isNotBlank() && suppliedToken != prefs.taskerToken) {
            LogStore.action(receiverType, "reject", "invalid token", "denied")
            return
        }

        val instruction = listOf("instruction", "command", "data", "text", "extra")
            .firstNotNullOfOrNull { intent.getStringExtra(it) }
            .orEmpty()

        val address = if (mediaReceiver) {
            listOf("address", "uri", "url", "file")
                .firstNotNullOfOrNull { intent.getStringExtra(it) }
        } else null

        if (instruction.isNotBlank()) {
            CommandProcessor.process(context, instruction, "tasker:$receiverType", address)
        }
    }
}

class receiveNewCommand : IntentReceiver("command")
class receiveNewReminder : IntentReceiver("reminder")
class receiveNewData : IntentReceiver("data")
class receiveNewVideo : IntentReceiver("video", true)
class receiveNewAudio : IntentReceiver("audio", true)

class JarvisAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName !in Prefs(this).observedPackages) return

        val text = buildString { collectText(rootInActiveWindow, this, 0) }.trim()
        if (text.isNotBlank()) {
            val action = LocalBrain.classify(text).action
            EventStore.add(this, "accessibility:$packageName", text.take(4000), action)
            LogStore.action("accessibility:$packageName", "observe", text.take(500))
        }
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (node == null || depth > 15) return
        node.text?.let { out.append(it).append(' ') }
        node.contentDescription?.let { out.append(it).append(' ') }
        for (index in 0 until node.childCount) {
            collectText(node.getChild(index), out, depth + 1)
        }
    }

    override fun onInterrupt() = Unit
}

object Notifications {
    const val CHANNEL = "jarvis_active"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Jarvis aktiv", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    fun foreground(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setOngoing(true)
            .build()
}

class VoiceForegroundService : Service(), RecognitionListener {
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(7, Notifications.foreground(this, "Hört auf Hey Jarvis"))
        startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        recognizer?.startListening(intent)
    }

    private fun handleRecognizedText(text: String) {
        val position = text.lowercase(Locale.GERMAN).indexOf("hey jarvis")
        if (position >= 0) {
            text.drop(position + "hey jarvis".length)
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let { CommandProcessor.process(this, it, "voice") }
        }
    }

    override fun onResults(results: Bundle?) {
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.let(::handleRecognizedText)
        startListening()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.let(::handleRecognizedText)
    }

    override fun onError(error: Int) {
        Handler(mainLooper).postDelayed(::startListening, 1200)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}

class ScreenCaptureService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(8, Notifications.foreground(this, "Bildschirmfreigabe aktiv"))
        LogStore.action("ui", "screen_capture", "MediaProjection consent accepted")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { JarvisUi() } }
    }
}

@Composable
fun JarvisUi() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var screen by remember { mutableStateOf("home") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenCaptureService::class.java)
            )
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.READ_CALENDAR,
                android.Manifest.permission.WRITE_CALENDAR
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Jarvis") },
                navigationIcon = {
                    TextButton(onClick = { screen = "chat" }) { Text("Chat") }
                },
                actions = {
                    TextButton(onClick = { screen = "settings" }) { Text("Einstellungen") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (screen) {
                "chat" -> ChatScreen { screen = "home" }
                "settings" -> SettingsScreen { screen = "home" }
                "logs" -> ListScreen("Actions.log", LogStore.read(context)) { screen = "home" }
                "events" -> ListScreen("Erkannte Daten", EventStore.read(context)) { screen = "home" }
                else -> HomeScreen(
                    openEvents = { screen = "events" },
                    openLogs = { screen = "logs" },
                    startVoice = {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, VoiceForegroundService::class.java)
                        )
                    },
                    stopVoice = { context.stopService(Intent(context, VoiceForegroundService::class.java)) },
                    startCapture = {
                        val manager = context.getSystemService(MediaProjectionManager::class.java)
                        captureLauncher.launch(manager.createScreenCaptureIntent())
                    },
                    openAccessibility = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    openEvents: () -> Unit,
    openLogs: () -> Unit,
    startVoice: () -> Unit,
    stopVoice: () -> Unit,
    startCapture: () -> Unit,
    openAccessibility: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(
        Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth().clickable(onClick = openEvents)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Erkannte Informationen", style = MaterialTheme.typography.titleLarge)
                    Text("${EventStore.read(context).size} lokale Einträge")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().clickable(onClick = openLogs)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Aktionsprotokoll", style = MaterialTheme.typography.titleLarge)
                    Text("Jede Aktion wird mit Zeit und Auslöser protokolliert")
                }
            }
        }
        item { Button(startVoice, Modifier.fillMaxWidth()) { Text("Hey-Jarvis-Dienst starten") } }
        item { Button(stopVoice, Modifier.fillMaxWidth()) { Text("Sprachdienst stoppen") } }
        item { Button(startCapture, Modifier.fillMaxWidth()) { Text("Bildschirmfreigabe starten") } }
        item { Button(openAccessibility, Modifier.fillMaxWidth()) { Text("Bedienungshilfe aktivieren") } }
    }
}

@Composable
private fun ListScreen(title: String, rows: List<String>, back: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Button(onClick = back) { Text("Zurück") }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        LazyColumn { items(rows) { Text(it, Modifier.padding(vertical = 6.dp)) } }
    }
}

@Composable
private fun ChatScreen(back: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(EventStore.read(context).take(30)) }

    Column(Modifier.padding(16.dp)) {
        Button(onClick = back) { Text("Zurück") }
        LazyColumn(Modifier.weight(1f)) {
            items(messages) { Text(it, Modifier.padding(6.dp)) }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Nachricht") }
        )
        Button(
            onClick = {
                if (input.isNotBlank()) {
                    CommandProcessor.process(context, input, "chat")
                    input = ""
                    messages = EventStore.read(context).take(30)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Senden") }
    }
}

@Composable
private fun SettingsScreen(back: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(context) }
    var name by remember { mutableStateOf(prefs.userName) }
    var mode by remember { mutableStateOf(prefs.modelMode) }
    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    var token by remember { mutableStateOf(prefs.taskerToken) }
    var showApps by remember { mutableStateOf(false) }

    Column(Modifier.padding(16.dp).fillMaxSize()) {
        Button(onClick = back) { Text("Zurück") }
        OutlinedTextField(
            name,
            { name = it; prefs.userName = it },
            Modifier.fillMaxWidth(),
            label = { Text("Name") }
        )
        Text("Modell")
        listOf("local custom model", "openai-api", "local ollama model").forEach { option ->
            Row {
                RadioButton(mode == option, { mode = option; prefs.modelMode = option })
                Text(option, Modifier.padding(top = 12.dp))
            }
        }
        if (mode == "openai-api") {
            OutlinedTextField(
                apiKey,
                { apiKey = it; prefs.apiKey = it },
                Modifier.fillMaxWidth(),
                label = { Text("API-Key") }
            )
        }
        OutlinedTextField(
            token,
            { token = it; prefs.taskerToken = it },
            Modifier.fillMaxWidth(),
            label = { Text("Optionaler Tasker-Token") }
        )
        Button(onClick = { showApps = !showApps }, modifier = Modifier.fillMaxWidth()) {
            Text("Beobachtete Apps auswählen")
        }
        if (showApps) AppPicker(prefs)
        Text(
            "Sprecherprofil, Audio-Playback-Capture, OCR und echtes Modell-Finetuning benötigen zusätzliche native ML-Modelle. Die sicheren Erweiterungspunkte und Android-Grenzen sind in der README dokumentiert."
        )
    }
}

@Composable
private fun AppPicker(prefs: Prefs) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val apps = remember {
        context.packageManager.getInstalledApplications(0)
            .filter { context.packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { context.packageManager.getApplicationLabel(it).toString() }
    }
    var selected by remember { mutableStateOf(prefs.observedPackages) }

    LazyColumn(Modifier.height(300.dp)) {
        items(apps) { app ->
            val label = context.packageManager.getApplicationLabel(app).toString()
            Row(
                Modifier.fillMaxWidth().clickable {
                    selected = if (app.packageName in selected) {
                        selected - app.packageName
                    } else {
                        selected + app.packageName
                    }
                    prefs.observedPackages = selected
                }.padding(4.dp)
            ) {
                Checkbox(app.packageName in selected, null)
                Text("$label\n${app.packageName}")
            }
        }
    }
}
