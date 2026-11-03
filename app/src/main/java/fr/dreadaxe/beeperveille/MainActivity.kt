package fr.dreadaxe.beeperveille

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import fr.dreadaxe.beeperveille.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var repo: BeeperRepository
    private var allRooms: List<BeeperRoom> = emptyList()
    private var roomMap: Map<String, BeeperRoom> = emptyMap()
    private var loadedMessages: List<BeeperMessage> = emptyList()
    private var loadedWords: List<String> = emptyList()
    private var pendingExport = ""

    private val beeperPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) loadRooms() else b.statusText.text = "Autorisation Beeper refusée."
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val createTxt = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) contentResolver.openOutputStream(uri)?.use { it.write(pendingExport.toByteArray(Charsets.UTF_8)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        repo = BeeperRepository(contentResolver)

        b.periodSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Depuis le dernier passage", "Aujourd'hui", "Hier", "7 derniers jours", "30 derniers jours"))
        b.watchIntervalSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Toutes les heures", "Toutes les 6 h", "Toutes les 12 h", "Toutes les 24 h"))
        val savedHours = Prefs.intervalHours(this)
        b.watchIntervalSpinner.setSelection(listOf(1L,6L,12L,24L).indexOf(savedHours).coerceAtLeast(0))
        b.keywordsInput.setText(Prefs.keywords(this))
        b.watchSwitch.isChecked = Prefs.autoEnabled(this)

        b.searchRooms.doAfterTextChanged { renderRooms(it?.toString().orEmpty()) }
        b.reloadRoomsButton.setOnClickListener { loadRooms() }
        b.loadButton.setOnClickListener { loadWatch() }
        b.exportButton.setOnClickListener { exportTxt() }
        b.shareChatgptButton.setOnClickListener { shareToChatGpt() }
        b.watchSwitch.setOnCheckedChangeListener { _, checked -> configureWatch(checked) }
        b.watchIntervalSpinner.setOnItemSelectedListener(SimpleItemSelectedListener { if (b.watchSwitch.isChecked) configureWatch(true) })

        requestBeeperAccess()
    }

    private fun requestBeeperAccess() {
        val permission = "com.beeper.android.permission.READ_PERMISSION"
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) loadRooms()
        else beeperPermission.launch(permission)
    }

    private fun diagnoseBeeper(): String {
        val permission = "com.beeper.android.permission.READ_PERMISSION"
        val readGranted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

        val beeperVersion = try {
            val info = packageManager.getPackageInfo("com.beeper.android", 0)
            info.versionName ?: "version inconnue"
        } catch (_: PackageManager.NameNotFoundException) {
            "non visible / non installé"
        }

        val provider = packageManager.resolveContentProvider("com.beeper.api", PackageManager.MATCH_ALL)
        val providerText = if (provider == null) {
            "absent"
        } else {
            "${provider.packageName} / exported=${provider.exported}"
        }

        val rawQuery = try {
            val uri = Uri.parse("content://com.beeper.api/chats?limit=1")
            val count = contentResolver.query(uri, null, null, null, null)?.use { it.count } ?: -1
            "OK ($count ligne)"
        } catch (e: SecurityException) {
            "REFUSÉE: ${e.message ?: "permission"}"
        } catch (e: Throwable) {
            "ERREUR: ${e.javaClass.simpleName}: ${e.message ?: "sans détail"}"
        }

        return "Diagnostic — Beeper $beeperVersion • READ=${if (readGranted) "accordée" else "REFUSÉE"} • Provider=$providerText • Requête brute=$rawQuery"
    }

    private fun loadRooms() {
        b.statusText.text = "Lecture des salons Discord depuis Beeper…"
        lifecycleScope.launch {
            try {
                val diagnostic = diagnoseBeeper()
                allRooms = repo.listDiscordRooms()
                roomMap = allRooms.associateBy { it.roomId }
                renderRooms(b.searchRooms.text?.toString().orEmpty())
                b.statusText.text = if (allRooms.isNotEmpty()) {
                    val mode = if (repo.usedFallbackScan) " (détection de secours)" else ""
                    "${allRooms.size} salon(s) Discord trouvé(s).$mode\n$diagnostic"
                } else {
                    val protocols = repo.lastProtocols.sorted().joinToString(", ").ifBlank { "aucun protocole visible" }
                    "0 salon Discord. Beeper a renvoyé ${repo.lastRawChatCount} conversation(s). Protocoles vus : $protocols\n$diagnostic"
                }
            } catch (e: Throwable) {
                b.statusText.text = "Impossible de lire Beeper : ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun renderRooms(query: String) {
        val selected = Prefs.selectedRooms(this)
        b.roomsContainer.removeAllViews()
        val filtered = allRooms.filter { it.title.contains(query, true) }.take(250)
        filtered.forEach { room ->
            val cb = CheckBox(this).apply {
                text = buildString {
                    append(room.title)
                    if (room.unreadCount > 0) append("  • ${room.unreadCount} non lu(s)")
                }
                tag = room.roomId
                isChecked = selected.contains(room.roomId)
                setOnCheckedChangeListener { _, _ -> saveRoomSelection() }
            }
            b.roomsContainer.addView(cb)
        }
        if (filtered.isEmpty() && allRooms.isNotEmpty()) {
            val cb = CheckBox(this).apply { text = "Aucun salon ne correspond à « $query »"; isEnabled = false }
            b.roomsContainer.addView(cb)
        }
    }

    private fun currentSelectedRooms(): Set<String> {
        val out = mutableSetOf<String>()
        for (i in 0 until b.roomsContainer.childCount) {
            val v = b.roomsContainer.getChildAt(i)
            if (v is CheckBox && v.isChecked && v.tag is String) out += v.tag as String
        }
        return out
    }

    private fun saveRoomSelection() {
        val visible = currentSelectedRooms()
        val visibleIds = (0 until b.roomsContainer.childCount).mapNotNull { i -> b.roomsContainer.getChildAt(i).tag as? String }.toSet()
        val keptHidden = Prefs.selectedRooms(this) - visibleIds
        Prefs.setSelectedRooms(this, keptHidden + visible)
    }

    private fun loadWatch() {
        saveRoomSelection()
        val selected = Prefs.selectedRooms(this)
        if (selected.isEmpty()) { toast("Choisis au moins un salon."); return }
        loadedWords = DigestBuilder.keywords(b.keywordsInput.text?.toString().orEmpty())
        Prefs.setKeywords(this, b.keywordsInput.text?.toString().orEmpty())
        b.statusText.text = "Récupération des messages…"
        b.loadButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val now = System.currentTimeMillis()
                val all = mutableListOf<BeeperMessage>()
                selected.forEach { roomId ->
                    val (since, until) = periodBounds(roomId, now)
                    val msgs = repo.messagesSince(roomId, since, until)
                    val filtered = if (loadedWords.isNotEmpty()) DigestBuilder.selectKeywordContext(msgs, loadedWords, if (b.contextCheck.isChecked) 2 else 0) else msgs
                    all += filtered
                    val newest = msgs.maxOfOrNull { it.timestamp }
                    if (newest != null) Prefs.setLastManual(this@MainActivity, roomId, newest)
                }
                loadedMessages = all.distinctBy { it.originalId }.sortedBy { it.timestamp }
                b.digestText.text = DigestBuilder.localDigest(roomMap, loadedMessages, loadedWords)
                b.statusText.text = "${loadedMessages.size} message(s) retenu(s)."
                b.exportButton.isEnabled = loadedMessages.isNotEmpty()
                b.shareChatgptButton.isEnabled = loadedMessages.isNotEmpty()
            } catch (e: Throwable) {
                b.statusText.text = "Erreur : ${e.message ?: e.javaClass.simpleName}"
            } finally {
                b.loadButton.isEnabled = true
            }
        }
    }

    private fun periodBounds(roomId: String, now: Long): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        fun start(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (b.periodSpinner.selectedItemPosition) {
            0 -> (Prefs.lastManual(this, roomId).takeIf { it > 0 } ?: start(today)) to now
            1 -> start(today) to now
            2 -> start(today.minusDays(1)) to (start(today) - 1)
            3 -> start(today.minusDays(6)) to now
            else -> start(today.minusDays(29)) to now
        }
    }

    private fun exportTxt() {
        pendingExport = DigestBuilder.rawTxt(roomMap, loadedMessages, loadedWords)
        val date = LocalDate.now().toString()
        createTxt.launch("Beeper_Veille_$date.txt")
    }

    private fun shareToChatGpt() {
        var text = DigestBuilder.chatGptPrompt(roomMap, loadedMessages, loadedWords)
        if (text.length > 180_000) text = text.take(180_000) + "\n\n[Export tronqué par l'application : réduire la période ou les salons pour tout transmettre.]"
        val direct = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage("com.openai.chatgpt")
        }
        try { startActivity(direct) } catch (_: ActivityNotFoundException) {
            direct.setPackage(null)
            startActivity(Intent.createChooser(direct, "Partager pour faire le condensé"))
        }
    }

    private fun configureWatch(enabled: Boolean) {
        saveRoomSelection()
        Prefs.setKeywords(this, b.keywordsInput.text?.toString().orEmpty())
        Prefs.setAutoEnabled(this, enabled)
        val wm = WorkManager.getInstance(this)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        if (Prefs.selectedRooms(this).isEmpty()) {
            b.watchSwitch.isChecked = false
            toast("Choisis au moins un salon avant d'activer la veille.")
            return
        }
        val hours = listOf(1L, 6L, 12L, 24L)[b.watchIntervalSpinner.selectedItemPosition.coerceIn(0, 3)]
        Prefs.setIntervalHours(this, hours)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val req = PeriodicWorkRequestBuilder<WatchWorker>(hours, TimeUnit.HOURS).build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        toast("Veille activée toutes les $hours h.")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object { const val WORK_NAME = "beeper_discord_watch" }
}
