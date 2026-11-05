package fr.dreadaxe.beeperveille

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private const val NAME = "beeper_veille"
    private const val ROOMS = "selected_rooms"
    private const val KEYWORDS = "keywords"
    private const val INTERVAL_HOURS = "interval_hours"
    private const val AUTO_ENABLED = "auto_enabled"
    private const val LAST_AUTO_PREFIX = "last_auto_"
    private const val LAST_MANUAL_PREFIX = "last_manual_"
    private const val ROOM_ALIAS_PREFIX = "room_alias_"
    private const val ROOM_URL_PREFIX = "room_url_"
    private const val SAVED_WATCHES = "saved_watches_v2"
    private const val LAST_WATCH_PREFIX = "last_watch_"

    private fun p(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun selectedRooms(context: Context): Set<String> = p(context).getStringSet(ROOMS, emptySet()) ?: emptySet()
    fun setSelectedRooms(context: Context, ids: Set<String>) = p(context).edit().putStringSet(ROOMS, ids).apply()

    fun keywords(context: Context): String = p(context).getString(KEYWORDS, "") ?: ""
    fun setKeywords(context: Context, value: String) = p(context).edit().putString(KEYWORDS, value).apply()

    fun intervalHours(context: Context): Long = p(context).getLong(INTERVAL_HOURS, 6L)
    fun setIntervalHours(context: Context, value: Long) = p(context).edit().putLong(INTERVAL_HOURS, value).apply()

    fun autoEnabled(context: Context): Boolean = p(context).getBoolean(AUTO_ENABLED, false)
    fun setAutoEnabled(context: Context, value: Boolean) = p(context).edit().putBoolean(AUTO_ENABLED, value).apply()

    fun lastAuto(context: Context, roomId: String): Long = p(context).getLong(LAST_AUTO_PREFIX + roomId, 0L)
    fun setLastAuto(context: Context, roomId: String, value: Long) = p(context).edit().putLong(LAST_AUTO_PREFIX + roomId, value).apply()

    fun lastManual(context: Context, roomId: String): Long = p(context).getLong(LAST_MANUAL_PREFIX + roomId, 0L)
    fun setLastManual(context: Context, roomId: String, value: Long) = p(context).edit().putLong(LAST_MANUAL_PREFIX + roomId, value).apply()

    fun roomAlias(context: Context, roomId: String): String = p(context).getString(ROOM_ALIAS_PREFIX + roomId, "") ?: ""
    fun setRoomAlias(context: Context, roomId: String, value: String) {
        val e = p(context).edit()
        if (value.isBlank()) e.remove(ROOM_ALIAS_PREFIX + roomId)
        else e.putString(ROOM_ALIAS_PREFIX + roomId, value.trim())
        e.apply()
    }

    fun roomUrl(context: Context, roomId: String): String = p(context).getString(ROOM_URL_PREFIX + roomId, "") ?: ""
    fun setRoomUrl(context: Context, roomId: String, value: String) {
        val clean = normalizeDiscordChannelUrl(value)
        val e = p(context).edit()
        if (clean.isBlank()) e.remove(ROOM_URL_PREFIX + roomId)
        else e.putString(ROOM_URL_PREFIX + roomId, clean)
        e.apply()
    }

    private fun normalizeDiscordChannelUrl(raw: String): String {
        val s = raw.trim()
        val m = Regex("""https?://(?:www\.)?discord(?:app)?\.com/channels/(\d+)/(\d+)(?:/\d+)?""", RegexOption.IGNORE_CASE).find(s)
        return if (m != null) "https://discord.com/channels/${m.groupValues[1]}/${m.groupValues[2]}" else ""
    }

    fun savedWatches(context: Context): List<WatchConfig> {
        val raw = p(context).getString(SAVED_WATCHES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val rooms = buildSet {
                        val r = o.optJSONArray("rooms") ?: JSONArray()
                        for (j in 0 until r.length()) add(r.optString(j))
                    }
                    add(
                        WatchConfig(
                            id = o.optString("id"),
                            name = o.optString("name"),
                            roomIds = rooms,
                            keywords = o.optString("keywords"),
                            periodIndex = o.optInt("periodIndex", 0),
                            includeContext = o.optBoolean("includeContext", true),
                            autoEnabled = o.optBoolean("autoEnabled", false),
                            intervalHours = o.optLong("intervalHours", 6L)
                        )
                    )
                }
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveWatch(context: Context, watch: WatchConfig) {
        val list = savedWatches(context).toMutableList()
        val idx = list.indexOfFirst { it.id == watch.id }
        if (idx >= 0) list[idx] = watch else list += watch
        persistWatches(context, list)
    }

    fun deleteWatch(context: Context, watchId: String) {
        persistWatches(context, savedWatches(context).filterNot { it.id == watchId })
    }

    fun getWatch(context: Context, watchId: String): WatchConfig? = savedWatches(context).firstOrNull { it.id == watchId }

    private fun persistWatches(context: Context, watches: List<WatchConfig>) {
        val arr = JSONArray()
        watches.forEach { w ->
            arr.put(JSONObject().apply {
                put("id", w.id)
                put("name", w.name)
                put("rooms", JSONArray(w.roomIds.toList()))
                put("keywords", w.keywords)
                put("periodIndex", w.periodIndex)
                put("includeContext", w.includeContext)
                put("autoEnabled", w.autoEnabled)
                put("intervalHours", w.intervalHours)
            })
        }
        p(context).edit().putString(SAVED_WATCHES, arr.toString()).apply()
    }

    fun lastWatchRun(context: Context, watchId: String, roomId: String): Long =
        p(context).getLong(LAST_WATCH_PREFIX + watchId + "_" + roomId, 0L)

    fun setLastWatchRun(context: Context, watchId: String, roomId: String, value: Long) =
        p(context).edit().putLong(LAST_WATCH_PREFIX + watchId + "_" + roomId, value).apply()
}
