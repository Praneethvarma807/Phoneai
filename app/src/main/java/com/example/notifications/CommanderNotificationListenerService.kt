package com.example.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.RemoteInput
import com.example.CommanderApplication
import com.example.data.preferences.CommanderSettings
import com.example.memory.MemorySecurityHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class NotificationPriorityLevel(val label: String) {
    HIGH("Important"),
    NORMAL("Normal"),
    LOW("Low")
}

data class NotificationDigestItem(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val body: String,
    val category: String,
    val priority: NotificationPriorityLevel,
    val postedAt: Long,
    val canQuickReply: Boolean = false
)

internal data class CachedReplyAction(
    val actionIntent: PendingIntent,
    val remoteInput: android.app.RemoteInput
)

/**
 * Pure filtering & priority classification logic testable in JVM/Robolectric without binder setup.
 */
internal object NotificationIntelligenceFilter {
    fun shouldCapture(
        packageName: String,
        selfPackage: String,
        title: String,
        body: String,
        category: String?,
        isOngoing: Boolean,
        settings: CommanderSettings
    ): Boolean {
        if (!settings.notificationIntelligenceEnabled) return false
        if (packageName.isBlank() || packageName == selfPackage) return false
        if (isOngoing) return false
        if (packageName in settings.notificationBlockedPackages) return false
        if (settings.notificationAllowedPackages.isNotEmpty() &&
            packageName !in settings.notificationAllowedPackages
        ) {
            return false
        }
        if (title.isBlank() && body.isBlank()) return false
        if (MemorySecurityHelper.isSensitiveNotification(title, body, category)) {
            return false
        }
        return true
    }

    fun classifyPriority(
        category: String?,
        title: String,
        body: String,
        hasReplyAction: Boolean
    ): NotificationPriorityLevel {
        val text = "$title $body".lowercase()
        return when {
            category == Notification.CATEGORY_CALL ||
                category == Notification.CATEGORY_ALARM ||
                category == Notification.CATEGORY_MESSAGE ||
                hasReplyAction ||
                text.contains("urgent") ||
                text.contains("meeting") ||
                text.contains("missed") -> NotificationPriorityLevel.HIGH
            category == Notification.CATEGORY_PROMO ||
                category == Notification.CATEGORY_RECOMMENDATION -> NotificationPriorityLevel.LOW
            else -> NotificationPriorityLevel.NORMAL
        }
    }

    fun summarizeLocally(items: List<NotificationDigestItem>): String {
        if (items.isEmpty()) {
            return "No recent non-sensitive notifications captured yet."
        }
        val important = items.filter { it.priority == NotificationPriorityLevel.HIGH }
        val grouped = items.groupBy { it.appName }
        return buildString {
            append("You have ${items.size} notification(s) across ${grouped.size} app(s)")
            if (important.isNotEmpty()) {
                append(" (${important.size} important)")
            }
            append(": ")
            val topSummaries = items.take(4).joinToString("; ") { item ->
                "${item.appName}: ${item.title} — ${item.body.take(60)}"
            }
            append(topSummaries)
        }
    }
}

class CommanderNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var settingsJob: Job? = null

    @Volatile
    private var cachedSettings: CommanderSettings = CommanderSettings()

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
        val container = runCatching { (application as CommanderApplication).container }.getOrNull()
        settingsJob?.cancel()
        if (container != null) {
            settingsJob = scope.launch {
                container.preferences.settings.collect { updated ->
                    cachedSettings = updated
                    if (!updated.notificationIntelligenceEnabled) {
                        clearCaptured()
                    }
                }
            }
        }
        runCatching {
            activeNotifications?.forEach { ingestStatusBarNotification(it) }
        }
    }

    override fun onListenerDisconnected() {
        _isConnected.value = false
        settingsJob?.cancel()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        ingestStatusBarNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        removeByKey(sbn.key)
    }

    private fun ingestStatusBarNotification(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        val extras = notif.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val body = bigText.ifBlank { text }
        val isOngoing = (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0

        if (!NotificationIntelligenceFilter.shouldCapture(
                packageName = sbn.packageName.orEmpty(),
                selfPackage = packageName,
                title = title,
                body = body,
                category = notif.category,
                isOngoing = isOngoing,
                settings = cachedSettings
            )
        ) {
            return
        }

        val appLabel = resolveAppLabel(sbn.packageName)
        val replyAction = findReplyAction(notif)
        if (replyAction != null) {
            replyActions[sbn.key] = replyAction
        }

        val item = NotificationDigestItem(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appLabel,
            title = title.ifBlank { appLabel }.take(80),
            body = body.take(240),
            category = notif.category ?: "general",
            priority = NotificationIntelligenceFilter.classifyPriority(
                category = notif.category,
                title = title,
                body = body,
                hasReplyAction = replyAction != null
            ),
            postedAt = sbn.postTime,
            canQuickReply = replyAction != null
        )

        synchronized(itemsLock) {
            val updated = listOf(item) + _notifications.value.filterNot { it.key == item.key }
            _notifications.value = updated.take(MAX_ITEMS)
        }
    }

    private fun resolveAppLabel(pkg: String): String = runCatching {
        val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(pkg, 0)
        }
        packageManager.getApplicationLabel(ai).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    private fun findReplyAction(notif: Notification): CachedReplyAction? {
        val actions = notif.actions ?: return null
        for (action in actions) {
            val inputs = action.remoteInputs ?: continue
            val firstInput = inputs.firstOrNull { it.allowFreeFormInput } ?: inputs.firstOrNull()
            if (firstInput != null && action.actionIntent != null) {
                return CachedReplyAction(action.actionIntent, firstInput)
            }
        }
        return null
    }

    override fun onDestroy() {
        _isConnected.value = false
        settingsJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MAX_ITEMS = 30
        private val itemsLock = Any()
        private val replyActions = LinkedHashMap<String, CachedReplyAction>()

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _notifications = MutableStateFlow<List<NotificationDigestItem>>(emptyList())
        val notifications: StateFlow<List<NotificationDigestItem>> = _notifications.asStateFlow()

        fun isPermissionGranted(context: Context): Boolean {
            if (_isConnected.value) return true
            val cn = ComponentName(context, CommanderNotificationListenerService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString())
        }

        fun openNotificationListenerSettings(context: Context): Boolean = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)

        fun removeByKey(key: String) {
            synchronized(itemsLock) {
                _notifications.value = _notifications.value.filterNot { it.key == key }
                replyActions.remove(key)
            }
        }

        fun clearCaptured() {
            synchronized(itemsLock) {
                _notifications.value = emptyList()
                replyActions.clear()
            }
        }

        fun sendQuickReply(context: Context, key: String, replyText: String): Result<String> {
            val clean = replyText.trim()
            if (clean.isBlank()) {
                return Result.failure(IllegalArgumentException("Reply text cannot be empty."))
            }
            val cached = synchronized(itemsLock) { replyActions[key] }
                ?: return Result.failure(IllegalStateException("Quick reply action is no longer active for this notification."))

            return runCatching {
                val replyIntent = Intent()
                val bundle = Bundle().apply {
                    putCharSequence(cached.remoteInput.resultKey, clean)
                }
                android.app.RemoteInput.addResultsToIntent(arrayOf(cached.remoteInput), replyIntent, bundle)
                cached.actionIntent.send(context.applicationContext, 0, replyIntent)
                "Reply sent via notification quick-reply."
            }
        }
    }
}
