package com.example.automation

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.MainActivity
import com.example.R
import com.example.data.local.CommanderDatabase
import com.example.data.local.LocalReminderDao
import com.example.data.local.LocalReminderEntity
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class ReminderMechanism(val label: String) {
    EXACT_ALARM("AlarmManager Exact"),
    INEXACT_ALARM_AND_WORK("AlarmManager + WorkManager"),
    WORK_MANAGER_ONLY("WorkManager"),
    SKIPPED_COMPLETED("Skipped (Completed)")
}

data class ReminderScheduleResult(
    val reminderId: Long,
    val triggerAtMillis: Long,
    val mechanism: ReminderMechanism,
    val notificationPermissionGranted: Boolean,
    val exactAlarmPermissionGranted: Boolean
)

class ReminderScheduler(private val context: Context) {
    private val appContext = context.applicationContext

    fun ensureNotificationChannel() {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(REMINDER_CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            appContext.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = appContext.getString(R.string.reminder_channel_description)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
    }

    fun scheduleReminder(reminder: LocalReminderEntity): ReminderScheduleResult {
        ensureNotificationChannel()
        val notifGranted = hasNotificationPermission()
        val exactGranted = canScheduleExactAlarms()

        if (reminder.isCompleted || reminder.id <= 0L) {
            if (reminder.id > 0L) cancelReminder(reminder.id)
            return ReminderScheduleResult(
                reminderId = reminder.id,
                triggerAtMillis = reminder.triggerAtMillis,
                mechanism = ReminderMechanism.SKIPPED_COMPLETED,
                notificationPermissionGranted = notifGranted,
                exactAlarmPermissionGranted = exactGranted
            )
        }

        // Prevent duplicates before scheduling
        cancelAlarmOnly(reminder.id)
        clearFiredMarker(appContext, reminder.id)

        val now = System.currentTimeMillis()
        val triggerAt = reminder.triggerAtMillis.coerceAtLeast(now + 1_000L)
        val delayMs = (triggerAt - now).coerceAtLeast(0L)
        val pendingIntent = buildAlarmPendingIntent(reminder.id, reminder.title, reminder.notes, triggerAt)
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

        var mechanism = ReminderMechanism.WORK_MANAGER_ONLY

        if (alarmManager != null && exactGranted) {
            val exactOk = runCatching {
                AlarmManagerCompat.setExactAndAllowWhileIdle(
                    alarmManager,
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }.isSuccess
            if (exactOk) {
                mechanism = ReminderMechanism.EXACT_ALARM
            }
        }

        if (mechanism != ReminderMechanism.EXACT_ALARM && alarmManager != null) {
            runCatching {
                AlarmManagerCompat.setAndAllowWhileIdle(
                    alarmManager,
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
            enqueueBackupWork(reminder, delayMs)
            mechanism = ReminderMechanism.INEXACT_ALARM_AND_WORK
        } else if (alarmManager == null) {
            enqueueBackupWork(reminder, delayMs)
        } else {
            // Exact alarm scheduled; cancel any stale backup WorkManager job
            runCatching { WorkManager.getInstance(appContext).cancelUniqueWork(uniqueWorkName(reminder.id)) }
        }

        return ReminderScheduleResult(
            reminderId = reminder.id,
            triggerAtMillis = triggerAt,
            mechanism = mechanism,
            notificationPermissionGranted = notifGranted,
            exactAlarmPermissionGranted = exactGranted
        )
    }

    private fun enqueueBackupWork(reminder: LocalReminderEntity, delayMs: Long) {
        val workRequest = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    EXTRA_REMINDER_ID to reminder.id,
                    EXTRA_REMINDER_TITLE to reminder.title,
                    EXTRA_REMINDER_NOTES to reminder.notes,
                    EXTRA_TRIGGER_AT to reminder.triggerAtMillis
                )
            )
            .addTag(WORK_TAG_PREFIX + reminder.id)
            .build()

        runCatching {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                uniqueWorkName(reminder.id),
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
    }

    fun cancelReminder(reminderId: Long) {
        cancelAlarmOnly(reminderId)
        runCatching { WorkManager.getInstance(appContext).cancelUniqueWork(uniqueWorkName(reminderId)) }
        val nm = appContext.getSystemService(NotificationManager::class.java)
        runCatching { nm?.cancel(NOTIFICATION_BASE_ID + reminderId.toInt()) }
        clearFiredMarker(appContext, reminderId)
    }

    suspend fun rescheduleAllPending(dao: LocalReminderDao): Int {
        val pending = dao.getPendingOnce()
        val now = System.currentTimeMillis()
        var count = 0
        for (item in pending) {
            // Reschedule future reminders and recently-missed ones (within 24 hours) so none are silently lost on reboot
            if (!item.isCompleted && item.triggerAtMillis >= now - PAST_GRACE_WINDOW_MS) {
                scheduleReminder(item)
                count++
            }
        }
        return count
    }

    private fun cancelAlarmOnly(reminderId: Long) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val existingPi = PendingIntent.getBroadcast(
            appContext,
            reminderId.toInt(),
            baseAlarmIntent(reminderId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (existingPi != null) {
            runCatching { alarmManager.cancel(existingPi) }
            runCatching { existingPi.cancel() }
        }
    }

    private fun baseAlarmIntent(reminderId: Long): Intent =
        Intent(appContext, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE_REMINDER
            data = Uri.parse("commander://reminder/$reminderId")
        }

    private fun buildAlarmPendingIntent(
        reminderId: Long,
        title: String,
        notes: String,
        triggerAt: Long
    ): PendingIntent {
        val intent = baseAlarmIntent(reminderId).apply {
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_REMINDER_TITLE, title)
            putExtra(EXTRA_REMINDER_NOTES, notes)
            putExtra(EXTRA_TRIGGER_AT, triggerAt)
        }
        return PendingIntent.getBroadcast(
            appContext,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val REMINDER_CHANNEL_ID = "commander_reminders"
        const val NOTIFICATION_BASE_ID = 5200
        const val ACTION_FIRE_REMINDER = "com.example.action.FIRE_REMINDER"
        const val ACTION_COMPLETE_REMINDER = "com.example.action.COMPLETE_REMINDER"
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_REMINDER_TITLE = "extra_reminder_title"
        const val EXTRA_REMINDER_NOTES = "extra_reminder_notes"
        const val EXTRA_TRIGGER_AT = "extra_trigger_at"
        private const val WORK_TAG_PREFIX = "commander_reminder_"
        private const val PREFS_FIRED = "commander_fired_reminders"
        private const val PAST_GRACE_WINDOW_MS = 24 * 60 * 60_000L

        internal fun uniqueWorkName(id: Long): String = "reminder_$id"

        fun openExactAlarmSettings(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
            val intent = Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }

        internal fun markFiredIfNew(context: Context, reminderId: Long, triggerAt: Long): Boolean {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_FIRED, Context.MODE_PRIVATE)
            val key = "fired_$reminderId"
            val lastTrigger = prefs.getLong(key, -1L)
            if (lastTrigger == triggerAt && triggerAt > 0L) return false
            prefs.edit().putLong(key, triggerAt).apply()
            return true
        }

        internal fun clearFiredMarker(context: Context, reminderId: Long) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_FIRED, Context.MODE_PRIVATE)
            prefs.edit().remove("fired_$reminderId").apply()
        }

        internal fun showReminderNotification(
            context: Context,
            reminderId: Long,
            title: String,
            notes: String,
            triggerAt: Long
        ) {
            val appCtx = context.applicationContext
            val scheduler = ReminderScheduler(appCtx)
            scheduler.ensureNotificationChannel()
            if (!scheduler.hasNotificationPermission()) return
            if (!markFiredIfNew(appCtx, reminderId, triggerAt)) return

            val openIntent = PendingIntent.getActivity(
                appCtx,
                reminderId.toInt(),
                Intent(appCtx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val completeIntent = PendingIntent.getBroadcast(
                appCtx,
                (100_000L + reminderId).toInt(),
                Intent(appCtx, ReminderReceiver::class.java).apply {
                    action = ACTION_COMPLETE_REMINDER
                    data = Uri.parse("commander://reminder/complete/$reminderId")
                    putExtra(EXTRA_REMINDER_ID, reminderId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val formattedTime = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(triggerAt))
            val body = if (notes.isNotBlank()) "$notes • $formattedTime" else "Scheduled for $formattedTime"

            val notification = NotificationCompat.Builder(appCtx, REMINDER_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Reminder: $title")
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(openIntent)
                .addAction(0, "Mark Done", completeIntent)
                .build()

            val manager = appCtx.getSystemService(NotificationManager::class.java)
            runCatching { manager?.notify(NOTIFICATION_BASE_ID + reminderId.toInt(), notification) }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val reminderId = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1L)
        if (reminderId <= 0L) return

        val pendingResult: PendingResult? = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching {
                    val db = CommanderDatabase.build(context.applicationContext)
                    val dao = db.localReminderDao()
                    when (action) {
                        ReminderScheduler.ACTION_COMPLETE_REMINDER -> {
                            dao.setCompleted(reminderId, true)
                            ReminderScheduler(context).cancelReminder(reminderId)
                        }
                        ReminderScheduler.ACTION_FIRE_REMINDER -> {
                            val existing = dao.getById(reminderId)
                            if (existing != null && !existing.isCompleted) {
                                ReminderScheduler.showReminderNotification(
                                    context = context,
                                    reminderId = existing.id,
                                    title = existing.title,
                                    notes = existing.notes,
                                    triggerAt = existing.triggerAtMillis
                                )
                            }
                        }
                    }
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }
}

class ReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val reminderId = inputData.getLong(ReminderScheduler.EXTRA_REMINDER_ID, -1L)
        if (reminderId <= 0L) return Result.success()

        val db = CommanderDatabase.build(applicationContext)
        val existing = db.localReminderDao().getById(reminderId)
        if (existing != null && !existing.isCompleted) {
            ReminderScheduler.showReminderNotification(
                context = applicationContext,
                reminderId = existing.id,
                title = existing.title,
                notes = existing.notes,
                triggerAt = existing.triggerAtMillis
            )
        }
        return Result.success()
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val pendingResult: PendingResult? = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching {
                    val db = CommanderDatabase.build(context.applicationContext)
                    ReminderScheduler(context.applicationContext).rescheduleAllPending(db.localReminderDao())
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
