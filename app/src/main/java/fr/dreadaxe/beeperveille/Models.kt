package fr.dreadaxe.beeperveille

data class BeeperRoom(
    val roomId: String,
    val title: String,
    val protocol: String,
    val unreadCount: Int,
    val timestamp: Long,
    val messagePreview: String = "",
    val contextLabel: String = ""
)

data class BeeperMessage(
    val roomId: String,
    val originalId: String,
    val senderId: String,
    val timestamp: Long,
    val sentByMe: Boolean,
    val deleted: Boolean,
    val type: String,
    val text: String,
    val reactions: String,
    val displayName: String,
    val searchMatch: Boolean = false
)

data class WatchConfig(
    val id: String,
    val name: String,
    val roomIds: Set<String>,
    val keywords: String,
    val periodIndex: Int,
    val includeContext: Boolean,
    val autoEnabled: Boolean,
    val intervalHours: Long
)
