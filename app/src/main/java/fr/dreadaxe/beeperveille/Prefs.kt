package fr.dreadaxe.beeperveille

import android.content.Context

object Prefs {
    private const val NAME = "beeper_veille"
    private const val ROOMS = "selected_rooms"
    private const val KEYWORDS = "keywords"
    private const val INTERVAL_HOURS = "interval_hours"
    private const val AUTO_ENABLED = "auto_enabled"
    private const val LAST_AUTO_PREFIX = "last_auto_"
    private const val LAST_MANUAL_PREFIX = "last_manual_"

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
}
