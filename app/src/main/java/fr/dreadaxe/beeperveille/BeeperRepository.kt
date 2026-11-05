package fr.dreadaxe.beeperveille

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BeeperRepository(private val resolver: ContentResolver) {

    @Volatile var lastRawChatCount: Int = 0
        private set
    @Volatile var lastProtocols: Set<String> = emptySet()
        private set
    @Volatile var usedFallbackScan: Boolean = false
        private set
    @Volatile var lastChatColumns: Set<String> = emptySet()
        private set

    suspend fun listDiscordRooms(limit: Int = 1000): List<BeeperRoom> = withContext(Dispatchers.IO) {
        usedFallbackScan = false

        // Fast path documented by Beeper.
        val filtered = readChats(limit = limit, protocol = "discord")
        if (filtered.isNotEmpty()) {
            lastRawChatCount = filtered.size
            lastProtocols = filtered.map { it.protocol }.filter { it.isNotBlank() }.toSet()
            return@withContext filtered.distinctBy { it.roomId }.sortedByDescending { it.timestamp }
        }

        // Some Beeper builds/accounts may return 0 rows for protocol filtering.
        // Fall back to an unfiltered scan and detect Discord client-side.
        usedFallbackScan = true
        val all = readChats(limit = limit, protocol = null)
        lastRawChatCount = all.size
        lastProtocols = all.map { it.protocol }.filter { it.isNotBlank() }.toSet()

        all.filter { room ->
            room.protocol.contains("discord", ignoreCase = true) ||
                room.roomId.contains("discord", ignoreCase = true)
        }.distinctBy { it.roomId }.sortedByDescending { it.timestamp }
    }

    private fun readChats(limit: Int, protocol: String?): List<BeeperRoom> {
        val out = mutableListOf<BeeperRoom>()
        var offset = 0
        val pageSize = 100

        while (offset < limit) {
            val builder = Uri.parse("content://com.beeper.api/chats").buildUpon()
                .appendQueryParameter("limit", pageSize.toString())
                .appendQueryParameter("offset", offset.toString())
            if (!protocol.isNullOrBlank()) builder.appendQueryParameter("protocol", protocol)
            val uri = builder.build()

            var rows = 0
            resolver.query(uri, null, null, null, null)?.use { c ->
                val roomIdx = c.getColumnIndexOrThrow("roomId")
                val titleIdx = c.getColumnIndexOrThrow("title")
                val protocolIdx = c.getColumnIndexOrThrow("protocol")
                val unreadIdx = c.getColumnIndexOrThrow("unreadCount")
                val tsIdx = c.getColumnIndexOrThrow("timestamp")
                val previewIdx = c.getColumnIndex("messagePreview")
                lastChatColumns = c.columnNames.toSet()

                val contextColumns = listOf(
                    "serverName", "guildName", "guild_name",
                    "spaceName", "spaceTitle", "space_name",
                    "parentName", "parentTitle", "parent_name",
                    "communityName", "workspaceName"
                )

                while (c.moveToNext()) {
                    rows++
                    val contextLabel = contextColumns.firstNotNullOfOrNull { name ->
                        val idx = c.getColumnIndex(name)
                        if (idx >= 0) c.getString(idx)?.trim()?.takeIf { it.isNotBlank() } else null
                    }.orEmpty()
                    out += BeeperRoom(
                        roomId = c.getString(roomIdx) ?: "",
                        title = c.getString(titleIdx) ?: "(sans nom)",
                        protocol = c.getString(protocolIdx) ?: "",
                        unreadCount = c.getInt(unreadIdx),
                        timestamp = normalizeTimestamp(c.getLong(tsIdx)),
                        messagePreview = if (previewIdx >= 0) c.getString(previewIdx).orEmpty() else "",
                        contextLabel = contextLabel
                    )
                }
            }
            if (rows < pageSize) break
            offset += pageSize
        }
        return out
    }

    suspend fun messagesSince(roomId: String, sinceMs: Long, untilMs: Long = Long.MAX_VALUE, maxMessages: Int = Int.MAX_VALUE): List<BeeperMessage> =
        withContext(Dispatchers.IO) {
            val total = countMessages(roomId)
            if (total <= 0) return@withContext emptyList()

            val pageSize = 300
            val first = readPage(roomId, 0, minOf(pageSize, total))
            if (first.isEmpty()) return@withContext emptyList()
            val tailOffset = (total - pageSize).coerceAtLeast(0)
            val tail = if (tailOffset == 0) first else readPage(roomId, tailOffset, pageSize)

            val firstNewest = first.maxOfOrNull { it.timestamp } ?: 0L
            val tailNewest = tail.maxOfOrNull { it.timestamp } ?: 0L
            val newestAtHead = firstNewest >= tailNewest

            val collected = LinkedHashMap<String, BeeperMessage>()
            var offset = if (newestAtHead) 0 else tailOffset
            while (offset >= 0 && offset < total && collected.size < maxMessages) {
                val page = when {
                    offset == 0 -> first
                    offset == tailOffset && tailOffset != 0 -> tail
                    else -> readPage(roomId, offset, pageSize)
                }
                if (page.isEmpty()) break
                page.forEach { m ->
                    if (!m.deleted && m.timestamp in sinceMs..untilMs) collected[m.originalId] = m
                }
                val oldestOnPage = page.minOfOrNull { it.timestamp } ?: Long.MAX_VALUE
                val newestOnPage = page.maxOfOrNull { it.timestamp } ?: 0L
                if (oldestOnPage <= sinceMs && newestOnPage >= sinceMs) break
                if (newestOnPage < sinceMs) break

                offset = if (newestAtHead) offset + pageSize else offset - pageSize
                if (!newestAtHead && offset < 0) break
            }
            collected.values.sortedBy { it.timestamp }
        }

    suspend fun allMessages(
        roomId: String,
        progress: (fetched: Int, total: Int) -> Unit = { _, _ -> }
    ): List<BeeperMessage> = withContext(Dispatchers.IO) {
        val total = countMessages(roomId)
        if (total <= 0) return@withContext emptyList()

        val pageSize = 500
        val collected = LinkedHashMap<String, BeeperMessage>()
        var offset = 0

        while (offset < total) {
            val page = readPage(roomId, offset, minOf(pageSize, total - offset))
            if (page.isEmpty()) break
            page.forEach { m -> collected[m.originalId] = m }
            offset += page.size
            progress(collected.size, total)

            // Defensive guard in case a provider returns fewer rows without being at the end.
            if (page.size < pageSize && offset < total) {
                offset = ((offset / pageSize) + 1) * pageSize
            }
        }

        collected.values.sortedBy { it.timestamp }
    }

    fun messageCount(roomId: String): Int = countMessages(roomId)

    private fun countMessages(roomId: String): Int {
        val uri = Uri.parse("content://com.beeper.api/messages/count").buildUpon()
            .appendQueryParameter("roomIds", roomId)
            .build()
        return resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow("count")) else 0
        } ?: 0
    }

    private fun normalizeTimestamp(value: Long): Long =
        if (value in 1..9_999_999_999L) value * 1000L else value

    private fun readPage(roomId: String, offset: Int, limit: Int): List<BeeperMessage> {
        val uri = Uri.parse("content://com.beeper.api/messages").buildUpon()
            .appendQueryParameter("roomIds", roomId)
            .appendQueryParameter("limit", limit.toString())
            .appendQueryParameter("offset", offset.toString())
            .build()
        val out = mutableListOf<BeeperMessage>()
        resolver.query(uri, null, null, null, null)?.use { c ->
            val roomIdx = c.getColumnIndexOrThrow("roomId")
            val idIdx = c.getColumnIndexOrThrow("originalId")
            val senderIdx = c.getColumnIndexOrThrow("senderContactId")
            val tsIdx = c.getColumnIndexOrThrow("timestamp")
            val meIdx = c.getColumnIndexOrThrow("isSentByMe")
            val deletedIdx = c.getColumnIndexOrThrow("isDeleted")
            val typeIdx = c.getColumnIndexOrThrow("type")
            val textIdx = c.getColumnIndexOrThrow("text_content")
            val reactionsIdx = c.getColumnIndexOrThrow("reactions")
            val displayIdx = c.getColumnIndexOrThrow("displayName")
            val matchIdx = c.getColumnIndex("is_search_match")
            while (c.moveToNext()) {
                out += BeeperMessage(
                    roomId = c.getString(roomIdx) ?: roomId,
                    originalId = c.getString(idIdx) ?: "${c.getLong(tsIdx)}:${c.position}",
                    senderId = c.getString(senderIdx) ?: "",
                    timestamp = normalizeTimestamp(c.getLong(tsIdx)),
                    sentByMe = c.getInt(meIdx) == 1,
                    deleted = c.getInt(deletedIdx) == 1,
                    type = c.getString(typeIdx) ?: "",
                    text = c.getString(textIdx) ?: "",
                    reactions = c.getString(reactionsIdx) ?: "",
                    displayName = c.getString(displayIdx) ?: "Inconnu",
                    searchMatch = matchIdx >= 0 && c.getInt(matchIdx) == 1
                )
            }
        }
        return out
    }
}
