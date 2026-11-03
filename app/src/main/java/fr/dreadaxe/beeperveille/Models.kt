package fr.dreadaxe.beeperveille

data class BeeperRoom(
    val roomId: String,
    val title: String,
    val protocol: String,
    val unreadCount: Int,
    val timestamp: Long
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
