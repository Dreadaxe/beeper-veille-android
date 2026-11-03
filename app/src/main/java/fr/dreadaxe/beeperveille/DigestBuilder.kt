package fr.dreadaxe.beeperveille

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DigestBuilder {
    private val dayFmt = SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRENCH)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.FRENCH)
    private val fullFmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRENCH)

    fun keywords(raw: String): List<String> = raw.split(',', ';', '\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.FRENCH) }

    fun selectKeywordContext(messages: List<BeeperMessage>, words: List<String>, radius: Int): List<BeeperMessage> {
        if (words.isEmpty()) return messages
        val indexes = linkedSetOf<Int>()
        messages.forEachIndexed { i, m ->
            if (words.any { w -> m.text.contains(w, ignoreCase = true) }) {
                for (j in (i - radius).coerceAtLeast(0)..(i + radius).coerceAtMost(messages.lastIndex)) indexes += j
            }
        }
        return indexes.sorted().map { messages[it] }
    }

    fun localDigest(rooms: Map<String, BeeperRoom>, messages: List<BeeperMessage>, words: List<String>): String {
        if (messages.isEmpty()) return "Aucun message correspondant à la période et aux filtres."
        val byDay = messages.groupBy { dayFmt.format(Date(it.timestamp)) }
        return buildString {
            appendLine("CONDENSÉ DE VEILLE")
            appendLine("${messages.size} message(s) retenu(s)")
            if (words.isNotEmpty()) appendLine("Mots-clés : ${words.joinToString(", ")}")
            appendLine()
            byDay.forEach { (day, dayMessages) ->
                appendLine("=== $day ===")
                dayMessages.groupBy { it.roomId }.forEach { (roomId, rm) ->
                    val title = rooms[roomId]?.title ?: roomId
                    val participants = rm.groupingBy { it.displayName }.eachCount().entries
                        .sortedByDescending { it.value }.take(5)
                        .joinToString(", ") { "${it.key} (${it.value})" }
                    val hits = if (words.isEmpty()) 0 else rm.count { m -> words.any { m.text.contains(it, true) } }
                    appendLine("$title — ${rm.size} message(s)${if (words.isNotEmpty()) ", $hits occurrence(s)" else ""}")
                    appendLine("Participants : $participants")
                    rm.filter { it.text.isNotBlank() }
                        .sortedWith(compareByDescending<BeeperMessage> { m -> words.count { m.text.contains(it, true) } }.thenByDescending { it.text.length })
                        .take(6)
                        .sortedBy { it.timestamp }
                        .forEach { m -> appendLine("  ${timeFmt.format(Date(m.timestamp))} ${m.displayName}: ${oneLine(m.text, 220)}") }
                    appendLine()
                }
            }
        }.trim()
    }

    fun rawTxt(rooms: Map<String, BeeperRoom>, messages: List<BeeperMessage>, words: List<String>): String = buildString {
        appendLine("BEEPER VEILLE — EXPORT DISCORD")
        appendLine("Export généré : ${fullFmt.format(Date())}")
        if (words.isNotEmpty()) appendLine("Filtre mots-clés : ${words.joinToString(", ")}")
        appendLine()
        var currentDay = ""
        var currentRoom = ""
        messages.sortedBy { it.timestamp }.forEach { m ->
            val day = dayFmt.format(Date(m.timestamp))
            if (day != currentDay) {
                currentDay = day
                currentRoom = ""
                appendLine("\n===== $day =====")
            }
            val room = rooms[m.roomId]?.title ?: m.roomId
            if (room != currentRoom) {
                currentRoom = room
                appendLine("\n--- $room ---")
            }
            val marker = if (words.any { m.text.contains(it, true) }) " [MOT-CLÉ]" else ""
            appendLine("[${timeFmt.format(Date(m.timestamp))}] ${m.displayName}$marker")
            appendLine(m.text.ifBlank { "[${m.type}]" })
            if (m.reactions.isNotBlank()) appendLine("Réactions : ${m.reactions}")
            appendLine()
        }
    }

    fun chatGptPrompt(rooms: Map<String, BeeperRoom>, messages: List<BeeperMessage>, words: List<String>): String {
        val raw = rawTxt(rooms, messages, words)
        val header = """
            Je veux une veille concise de ces discussions Discord récupérées via Beeper.
            Fais un condensé JOUR PAR JOUR et SALON PAR SALON.
            Pour chaque bloc, distingue :
            - sujets discutés ;
            - informations ou liens importants ;
            - décisions / consensus ;
            - désaccords ou questions encore ouvertes ;
            - actions à faire ou personnes qui doivent vérifier quelque chose ;
            - occurrences pertinentes des mots-clés, sans surinterpréter.
            Ne complète jamais ce qui n'apparaît pas dans les messages. Si un passage manque de contexte, indique-le.

        """.trimIndent()
        return header + raw
    }

    private fun oneLine(s: String, max: Int): String {
        val clean = s.replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= max) clean else clean.take(max - 1) + "…"
    }
}
