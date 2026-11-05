package fr.dreadaxe.beeperveille

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import fr.dreadaxe.beeperveille.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var repo: BeeperRepository
    private var allRooms: List<BeeperRoom> = emptyList()
    private var roomMap: Map<String, BeeperRoom> = emptyMap()
    private var loadedMessages: List<BeeperMessage> = emptyList()
    private var loadedWords: List<String> = emptyList()
    private var pendingExport = ""
    private var activeWatchId: String? = null

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
            listOf("Depuis le dernier passage", "Aujourd'hui", "Hier", "7 derniers jours", "30 derniers jours", "Tout l'historique Beeper"))
        b.watchIntervalSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Toutes les heures", "Toutes les 6 h", "Toutes les 12 h", "Toutes les 24 h"))
        val savedHours = Prefs.intervalHours(this)
        b.watchIntervalSpinner.setSelection(listOf(1L,6L,12L,24L).indexOf(savedHours).coerceAtLeast(0))
        b.keywordsInput.setText(Prefs.keywords(this))
        b.watchSwitch.isChecked = false

        WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME)
        renderSavedWatches()

        b.searchRooms.doAfterTextChanged { renderRooms(it?.toString().orEmpty()) }
        b.reloadRoomsButton.setOnClickListener { loadRooms() }
        b.newWatchButton.setOnClickListener { newWatch() }
        b.saveWatchButton.setOnClickListener { saveCurrentWatch() }
        b.loadButton.setOnClickListener { loadWatch(activeWatchId) }
        b.copyButton.setOnClickListener { copyExport() }
        b.shareButton.setOnClickListener { shareExport() }
        b.exportButton.setOnClickListener { exportTxt() }
        b.shareChatgptButton.setOnClickListener { shareToChatGpt() }
        b.watchSwitch.setOnCheckedChangeListener { _, _ -> }
        b.watchIntervalSpinner.setOnItemSelectedListener(SimpleItemSelectedListener { })

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
        val q = query.trim()
        val filtered = allRooms.filter { room ->
            if (q.isBlank()) true
            else listOf(
                room.title,
                room.contextLabel,
                Prefs.roomAlias(this, room.roomId),
                room.messagePreview,
                shortRoomId(room.roomId)
            ).any { it.contains(q, true) }
        }.take(250)

        val duplicateTitles = allRooms
            .groupingBy { it.title.removePrefix("#").lowercase(Locale.FRENCH) }
            .eachCount()

        filtered.forEach { room ->
            val server = roomServerLabel(room)
            val channel = room.title.removePrefix("#")
            val isDuplicate = (duplicateTitles[channel.lowercase(Locale.FRENCH)] ?: 0) > 1
            val serverLine = if (server.isNotBlank()) {
                "Serveur : $server"
            } else {
                "Serveur : non renseigné — appui long pour l’ajouter"
            }
            val preview = room.messagePreview.replace(Regex("\\s+"), " ").trim()
                .takeIf { it.isNotBlank() }
                ?.let { if (it.length <= 100) it else it.take(99) + "…" }

            val meta = buildList {
                if (room.unreadCount > 0) add("${room.unreadCount} non lu(s)")
                if (Prefs.roomUrl(this@MainActivity, room.roomId).isNotBlank()) add("🔗 Discord")
                if (isDuplicate && server.isBlank()) add("⚠ nom de salon dupliqué")
                add("ID ${shortRoomId(room.roomId)}")
            }.joinToString("  •  ")

            val cb = CheckBox(this).apply {
                text = buildString {
                    append("#$channel")
                    append("\n$serverLine")
                    if (preview != null) append("\nDernier message : $preview")
                    append("\n$meta")
                }
                tag = room.roomId
                isChecked = selected.contains(room.roomId)
                setOnCheckedChangeListener { _, _ -> saveRoomSelection() }
                setOnLongClickListener {
                    editRoomAlias(room)
                    true
                }
            }
            b.roomsContainer.addView(cb)
        }
        if (filtered.isEmpty() && allRooms.isNotEmpty()) {
            val cb = CheckBox(this).apply { text = "Aucun salon ne correspond à « $query »"; isEnabled = false }
            b.roomsContainer.addView(cb)
        }
    }

    private fun roomServerLabel(room: BeeperRoom): String {
        return Prefs.roomAlias(this, room.roomId).ifBlank { room.contextLabel }
    }

    private fun shortRoomId(roomId: String): String {
        val core = roomId.substringBefore(':').removePrefix("!")
        return if (core.length <= 8) core else core.takeLast(8)
    }

    private fun effectiveRoomMap(): Map<String, BeeperRoom> {
        return roomMap.mapValues { (_, room) ->
            val server = roomServerLabel(room)
            val channel = room.title.removePrefix("#")
            val display = if (server.isNotBlank()) {
                "$server › #$channel"
            } else {
                "#$channel [${shortRoomId(room.roomId)}]"
            }
            room.copy(title = display)
        }
    }

    private fun editRoomAlias(room: BeeperRoom) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, 0, p, 0)
        }
        val alias = EditText(this).apply {
            hint = "Nom du serveur Discord"
            setText(Prefs.roomAlias(this@MainActivity, room.roomId).ifBlank { room.contextLabel })
        }
        val url = EditText(this).apply {
            hint = "URL du salon Discord (facultatif)"
            setText(Prefs.roomUrl(this@MainActivity, room.roomId))
        }
        box.addView(alias)
        box.addView(url)

        AlertDialog.Builder(this)
            .setTitle("Identifier #${room.title.removePrefix("#")}")
            .setMessage("Tu peux ajouter le nom du serveur et, si nécessaire, coller manuellement l’URL Discord du salon. Elle servira à créer des liens directs vers les messages quand l’identifiant du message est exploitable.")
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                Prefs.setRoomAlias(this, room.roomId, alias.text?.toString().orEmpty())
                Prefs.setRoomUrl(this, room.roomId, url.text?.toString().orEmpty())
                renderRooms(b.searchRooms.text?.toString().orEmpty())
            }
            .setNeutralButton("Effacer") { _, _ ->
                Prefs.setRoomAlias(this, room.roomId, "")
                Prefs.setRoomUrl(this, room.roomId, "")
                renderRooms(b.searchRooms.text?.toString().orEmpty())
            }
            .setNegativeButton("Annuler", null)
            .show()
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

    private fun loadWatch(watchId: String? = null) {
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
                val fullHistory = b.periodSpinner.selectedItemPosition == 5

                selected.forEach { roomId ->
                    val roomName = effectiveRoomMap()[roomId]?.title ?: roomMap[roomId]?.title ?: roomId
                    val msgs = if (fullHistory) {
                        repo.allMessages(roomId) { fetched, total ->
                            runOnUiThread {
                                b.statusText.text = "Historique complet — $roomName : $fetched / $total message(s)…"
                            }
                        }
                    } else {
                        val (since, until) = periodBounds(roomId, now, watchId)
                        repo.messagesSince(roomId, since, until)
                    }

                    val filtered = if (loadedWords.isNotEmpty()) {
                        DigestBuilder.selectKeywordContext(msgs, loadedWords, if (b.contextCheck.isChecked) 2 else 0)
                    } else msgs

                    all += filtered
                    val newest = msgs.maxOfOrNull { it.timestamp }
                    if (newest != null) {
                        if (watchId != null) Prefs.setLastWatchRun(this@MainActivity, watchId, roomId, newest)
                        else Prefs.setLastManual(this@MainActivity, roomId, newest)
                    }
                }

                loadedMessages = all.distinctBy { it.originalId }.sortedBy { it.timestamp }
                b.digestText.text = DigestBuilder.localDigest(effectiveRoomMap(), loadedMessages, loadedWords, messageLinks())

                val oldest = loadedMessages.minOfOrNull { it.timestamp }
                val oldestText = oldest?.let {
                    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH).format(Date(it))
                }
                b.statusText.text = buildString {
                    append("${loadedMessages.size} message(s) retenu(s).")
                    if (oldestText != null) append(" Plus ancien : $oldestText.")
                    if (fullHistory) append(" Historique Beeper parcouru jusqu'au bout.")
                }

                val hasMessages = loadedMessages.isNotEmpty()
                b.copyButton.isEnabled = hasMessages
                b.shareButton.isEnabled = hasMessages
                b.exportButton.isEnabled = hasMessages
                b.shareChatgptButton.isEnabled = hasMessages
            } catch (e: Throwable) {
                b.statusText.text = "Erreur : ${e.message ?: e.javaClass.simpleName}"
            } finally {
                b.loadButton.isEnabled = true
            }
        }
    }

    private fun periodBounds(roomId: String, now: Long, watchId: String? = null): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        fun start(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (b.periodSpinner.selectedItemPosition) {
            0 -> ((watchId?.let { Prefs.lastWatchRun(this, it, roomId) } ?: Prefs.lastManual(this, roomId)).takeIf { it > 0 } ?: start(today)) to now
            1 -> start(today) to now
            2 -> start(today.minusDays(1)) to (start(today) - 1)
            3 -> start(today.minusDays(6)) to now
            else -> start(today.minusDays(29)) to now
        }
    }

    private fun messageLinks(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        loadedMessages.forEach { m ->
            val base = Prefs.roomUrl(this, m.roomId)
            if (base.isBlank()) return@forEach
            val snowflake = Regex("""\d{15,22}""").findAll(m.originalId).lastOrNull()?.value
            if (snowflake != null) out[m.originalId] = "$base/$snowflake"
        }
        return out
    }

    private fun currentRawExport(): String =
        DigestBuilder.rawTxt(effectiveRoomMap(), loadedMessages, loadedWords, messageLinks())

    private fun copyExport() {
        val text = currentRawExport()
        if (text.length > 500_000) {
            toast("Texte trop long pour un collage fiable. Utilise Partager : l'app enverra un fichier TXT.")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Beeper Veille", text))
        toast("Veille copiée dans le presse-papiers.")
    }

    private fun shareExport() {
        val text = currentRawExport()
        if (text.length <= 180_000) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(intent, "Partager la veille"))
            return
        }

        val dir = File(cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "Beeper_Veille_${LocalDate.now()}.txt")
        file.writeText(text, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(this, "${packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Partager la veille (fichier TXT)"))
    }

    private fun exportTxt() {
        pendingExport = currentRawExport()
        val date = LocalDate.now().toString()
        createTxt.launch("Beeper_Veille_$date.txt")
    }

    private fun shareToChatGpt() {
        var text = DigestBuilder.chatGptPrompt(effectiveRoomMap(), loadedMessages, loadedWords, messageLinks())
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

    private fun newWatch() {
        activeWatchId = null
        b.watchNameInput.setText("")
        Prefs.setSelectedRooms(this, emptySet())
        b.keywordsInput.setText("")
        b.periodSpinner.setSelection(0)
        b.contextCheck.isChecked = true
        b.watchSwitch.isChecked = false
        b.watchIntervalSpinner.setSelection(1)
        renderRooms(b.searchRooms.text?.toString().orEmpty())
        toast("Nouvelle veille : choisis les salons et paramètres.")
    }

    private fun renderSavedWatches() {
        b.savedWatchesContainer.removeAllViews()
        val watches = Prefs.savedWatches(this)
        if (watches.isEmpty()) {
            val empty = android.widget.TextView(this).apply { text = "Aucune veille enregistrée." }
            b.savedWatchesContainer.addView(empty)
            return
        }

        watches.forEach { watch ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val run = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val mode = if (watch.autoEnabled) "AUTO ${watch.intervalHours} h" else "MANUEL"
                text = "▶  ${watch.name}  •  $mode"
                setOnClickListener {
                    applyWatchConfig(watch)
                    loadWatch(watch.id)
                }
                setOnLongClickListener {
                    applyWatchConfig(watch)
                    toast("Veille « ${watch.name} » chargée pour modification.")
                    true
                }
            }
            val del = Button(this).apply {
                text = "×"
                setOnClickListener {
                    WorkManager.getInstance(this@MainActivity).cancelUniqueWork(savedWorkName(watch.id))
                    Prefs.deleteWatch(this@MainActivity, watch.id)
                    if (activeWatchId == watch.id) activeWatchId = null
                    renderSavedWatches()
                }
            }
            row.addView(run)
            row.addView(del)
            b.savedWatchesContainer.addView(row)
        }
    }

    private fun saveCurrentWatch() {
        saveRoomSelection()
        val name = b.watchNameInput.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            toast("Donne un nom à la veille.")
            return
        }
        val rooms = Prefs.selectedRooms(this)
        if (rooms.isEmpty()) {
            toast("Choisis au moins un salon.")
            return
        }
        val hours = listOf(1L, 6L, 12L, 24L)[b.watchIntervalSpinner.selectedItemPosition.coerceIn(0, 3)]
        val id = activeWatchId ?: UUID.randomUUID().toString()
        val watch = WatchConfig(
            id = id,
            name = name,
            roomIds = rooms,
            keywords = b.keywordsInput.text?.toString().orEmpty(),
            periodIndex = b.periodSpinner.selectedItemPosition,
            includeContext = b.contextCheck.isChecked,
            autoEnabled = b.watchSwitch.isChecked,
            intervalHours = hours
        )
        Prefs.saveWatch(this, watch)
        activeWatchId = id
        scheduleSavedWatch(watch)
        renderSavedWatches()
        toast("Veille « $name » enregistrée.")
    }

    private fun applyWatchConfig(watch: WatchConfig) {
        activeWatchId = watch.id
        b.watchNameInput.setText(watch.name)
        Prefs.setSelectedRooms(this, watch.roomIds)
        b.keywordsInput.setText(watch.keywords)
        b.periodSpinner.setSelection(watch.periodIndex.coerceIn(0, 5))
        b.contextCheck.isChecked = watch.includeContext
        b.watchSwitch.isChecked = watch.autoEnabled
        b.watchIntervalSpinner.setSelection(listOf(1L, 6L, 12L, 24L).indexOf(watch.intervalHours).coerceAtLeast(0))
        renderRooms(b.searchRooms.text?.toString().orEmpty())
    }

    private fun scheduleSavedWatch(watch: WatchConfig) {
        val wm = WorkManager.getInstance(this)
        val workName = savedWorkName(watch.id)
        if (!watch.autoEnabled) {
            wm.cancelUniqueWork(workName)
            return
        }

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val data = Data.Builder().putString("watchId", watch.id).build()
        val req = PeriodicWorkRequestBuilder<WatchWorker>(watch.intervalHours.coerceAtLeast(1), TimeUnit.HOURS)
            .setInputData(data)
            .build()
        wm.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    private fun savedWorkName(id: String) = "beeper_saved_watch_$id"

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
