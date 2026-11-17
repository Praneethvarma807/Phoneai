package com.example.automation

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.CommanderApplication
import com.example.MainActivity
import com.example.R
import com.example.data.local.AutomationRoutineDao
import com.example.data.local.AutomationRoutineEntity
import com.example.data.local.CommanderDatabase
import com.example.data.local.RoutineExecutionLogDao
import com.example.data.local.RoutineExecutionLogEntity
import com.example.domain.model.ExecutionStatus
import com.example.domain.model.ToolCallRequest
import com.example.tools.ToolRegistry
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AutomationEngine(
    private val context: Context,
    private val routineDao: AutomationRoutineDao,
    private val logDao: RoutineExecutionLogDao,
    private val toolRegistry: ToolRegistry
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    val routines: Flow<List<AutomationRoutineEntity>> = routineDao.observeAll()
    val executionLogs: Flow<List<RoutineExecutionLogEntity>> = logDao.observeRecent(30)

    suspend fun ensureDefaultPresets() {
        if (routineDao.getAllOnce().isNotEmpty()) return
        val presets = listOf(
            AutomationRoutineEntity(
                name = "Morning Briefing",
                description = "Check battery, network status, and read notification summary.",
                voiceTriggers = "good morning,morning routine,morning briefing",
                stepsJson = json.encodeToString(
                    listOf(
                        ToolCallRequest("get_battery_status"),
                        ToolCallRequest("get_network_status"),
                        ToolCallRequest("summarize_notifications")
                    )
                ),
                scheduleHour = 8,
                scheduleMinute = 0,
                isEnabled = false,
                isPreset = true
            ),
            AutomationRoutineEntity(
                name = "Night Wind-Down",
                description = "Mute media volume, lower brightness to 20%, and check battery.",
                voiceTriggers = "good night,night routine,bedtime mode",
                stepsJson = json.encodeToString(
                    listOf(
                        ToolCallRequest("adjust_volume", mapOf("direction" to "mute")),
                        ToolCallRequest("adjust_brightness", mapOf("level" to "20")),
                        ToolCallRequest("get_battery_status")
                    )
                ),
                scheduleHour = 22,
                scheduleMinute = 30,
                isEnabled = false,
                isPreset = true
            ),
            AutomationRoutineEntity(
                name = "Focus Mode",
                description = "Mute volume and create a quick local focus session note.",
                voiceTriggers = "focus mode,study mode,start focus",
                stepsJson = json.encodeToString(
                    listOf(
                        ToolCallRequest("adjust_volume", mapOf("direction" to "mute")),
                        ToolCallRequest("create_note", mapOf("title" to "Focus Session", "content" to "Focus routine started"))
                    )
                ),
                isEnabled = true,
                isPreset = true
            )
        )
        presets.forEach { routineDao.insert(it) }
    }

    suspend fun matchVoiceTrigger(input: String): AutomationRoutineEntity? {
        val lower = input.trim().lowercase()
        if (lower.isBlank()) return null
        val all = routineDao.getAllOnce().filter { it.isEnabled }
        return all.firstOrNull { routine ->
            val triggers = routine.voiceTriggers
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
            val nameMatch = lower == "run ${routine.name.lowercase()}" ||
                lower == "start ${routine.name.lowercase()}" ||
                lower == routine.name.lowercase()
            nameMatch || triggers.any { trigger -> lower == trigger || lower.contains(trigger) }
        }
    }

    suspend fun findRoutineByNameOrTrigger(query: String): AutomationRoutineEntity? {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return null
        return routineDao.getAllOnce().firstOrNull { routine ->
            routine.name.equals(needle, ignoreCase = true) ||
                routine.name.lowercase().contains(needle) ||
                routine.voiceTriggers.split(",").any { it.trim().equals(needle, ignoreCase = true) }
        }
    }

    suspend fun createCustomRoutine(
        name: String,
        description: String,
        voiceTrigger: String,
        steps: List<ToolCallRequest>,
        scheduleHour: Int? = null,
        scheduleMinute: Int? = null
    ): Long {
        val entity = AutomationRoutineEntity(
            name = name.trim(),
            description = description.trim().ifBlank { "Custom automation routine" },
            voiceTriggers = voiceTrigger.trim().lowercase(),
            stepsJson = json.encodeToString(steps),
            scheduleHour = scheduleHour,
            scheduleMinute = scheduleMinute,
            isEnabled = true,
            isPreset = false
        )
        val id = routineDao.insert(entity)
        if (scheduleHour != null && scheduleMinute != null) {
            scheduleDailyRoutine(entity.copy(id = id))
        }
        return id
    }

    suspend fun setRoutineEnabled(id: Long, enabled: Boolean) {
        routineDao.setEnabled(id, enabled)
        val routine = routineDao.getById(id) ?: return
        if (enabled && routine.scheduleHour != null && routine.scheduleMinute != null) {
            scheduleDailyRoutine(routine)
        } else if (!enabled) {
            cancelRoutineSchedule(id)
        }
    }

    suspend fun deleteRoutine(id: Long) {
        cancelRoutineSchedule(id)
        routineDao.deleteById(id)
    }

    suspend fun executeRoutine(
        routineId: Long,
        triggerSource: String = "MANUAL",
        nowMs: Long = System.currentTimeMillis()
    ): RoutineExecutionLogEntity {
        val routine = routineDao.getById(routineId)
            ?: return RoutineExecutionLogEntity(
                routineId = routineId,
                routineName = "Unknown",
                triggerSource = triggerSource,
                status = "FAILED",
                summary = "Routine #$routineId no longer exists.",
                executedAt = nowMs
            )

        // Prevent duplicate scheduled triggers inside a 90-second window
        if (triggerSource == "SCHEDULE" && routine.lastExecutedAt != null &&
            (nowMs - routine.lastExecutedAt) < DUPLICATE_SCHEDULE_GUARD_MS
        ) {
            return RoutineExecutionLogEntity(
                routineId = routine.id,
                routineName = routine.name,
                triggerSource = triggerSource,
                status = "SKIPPED",
                summary = "Duplicate scheduled trigger skipped.",
                executedAt = nowMs
            )
        }

        val steps = runCatching {
            json.decodeFromString<List<ToolCallRequest>>(routine.stepsJson)
        }.getOrDefault(emptyList())

        if (steps.isEmpty()) {
            val log = RoutineExecutionLogEntity(
                routineId = routine.id,
                routineName = routine.name,
                triggerSource = triggerSource,
                status = "FAILED",
                summary = "Routine has no configured tool steps.",
                executedAt = nowMs
            )
            logDao.insert(log)
            return log
        }

        val stepNotes = mutableListOf<String>()
        var anySuccess = false
        var anyFailed = false

        for (step in steps) {
            val result = toolRegistry.executeTool(step.tool, step.arguments)
            if (result.status == ExecutionStatus.SUCCESS) {
                anySuccess = true
            } else {
                anyFailed = true
            }
            stepNotes.add("${step.tool}: ${result.message}")
        }

        val finalStatus = when {
            anySuccess && !anyFailed -> "SUCCESS"
            anySuccess && anyFailed -> "PARTIAL"
            else -> "FAILED"
        }

        routineDao.markExecuted(routine.id, nowMs)
        val log = RoutineExecutionLogEntity(
            routineId = routine.id,
            routineName = routine.name,
            triggerSource = triggerSource,
            status = finalStatus,
            summary = stepNotes.joinToString(" | ").take(420),
            executedAt = nowMs
        )
        logDao.insert(log)

        if (triggerSource == "SCHEDULE") {
            postRoutineNotification(routine.id, routine.name, log.summary)
            if (routine.isEnabled && routine.scheduleHour != null && routine.scheduleMinute != null) {
                scheduleDailyRoutine(routine)
            }
        }
        return log
    }

    suspend fun rescheduleAllEnabled() {
        routineDao.getAllOnce()
            .filter { it.isEnabled && it.scheduleHour != null && it.scheduleMinute != null }
            .forEach { scheduleDailyRoutine(it) }
    }

    fun scheduleDailyRoutine(routine: AutomationRoutineEntity) {
        val hour = routine.scheduleHour ?: return
        val minute = routine.scheduleMinute ?: return
        val nextMillis = computeNextTriggerMillis(hour, minute)
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val pendingIntent = buildPendingIntent(routine.id)

        if (alarmManager != null) {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
            if (canExact) {
                runCatching {
                    AlarmManagerCompat.setExactAndAllowWhileIdle(
                        alarmManager,
                        AlarmManager.RTC_WAKEUP,
                        nextMillis,
                        pendingIntent
                    )
                }
            } else {
                runCatching {
                    AlarmManagerCompat.setAndAllowWhileIdle(
                        alarmManager,
                        AlarmManager.RTC_WAKEUP,
                        nextMillis,
                        pendingIntent
                    )
                }
            }
        }

        val periodic = PeriodicWorkRequestBuilder<RoutineWorker>(24, TimeUnit.HOURS)
            .setInitialDelay((nextMillis - System.currentTimeMillis()).coerceAtLeast(60_000L), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(EXTRA_ROUTINE_ID to routine.id))
            .build()
        runCatching {
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                "routine_daily_${routine.id}",
                ExistingPeriodicWorkPolicy.UPDATE,
                periodic
            )
        }
    }

    fun cancelRoutineSchedule(routineId: Long) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val existing = PendingIntent.getBroadcast(
            appContext,
            (ALARM_REQUEST_BASE + routineId).toInt(),
            baseIntent(routineId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (existing != null) {
            runCatching { alarmManager?.cancel(existing) }
            runCatching { existing.cancel() }
        }
        runCatching { WorkManager.getInstance(appContext).cancelUniqueWork("routine_daily_$routineId") }
    }

    private fun baseIntent(routineId: Long): Intent =
        Intent(appContext, RoutineAlarmReceiver::class.java).apply {
            action = ACTION_RUN_ROUTINE
            data = Uri.parse("commander://routine/$routineId")
            putExtra(EXTRA_ROUTINE_ID, routineId)
        }

    private fun buildPendingIntent(routineId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            (ALARM_REQUEST_BASE + routineId).toInt(),
            baseIntent(routineId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun postRoutineNotification(routineId: Long, name: String, summary: String) {
        if (!ReminderScheduler(appContext).hasNotificationPermission()) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(ROUTINE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ROUTINE_CHANNEL_ID,
                    appContext.getString(R.string.routine_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = appContext.getString(R.string.routine_channel_description)
                }
            )
        }
        val openIntent = PendingIntent.getActivity(
            appContext,
            (ALARM_REQUEST_BASE + routineId).toInt(),
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, ROUTINE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Routine executed: $name")
            .setContentText(summary.take(120))
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        runCatching { manager.notify((ALARM_REQUEST_BASE + routineId).toInt(), notification) }
    }

    companion object {
        const val ROUTINE_CHANNEL_ID = "commander_routines"
        const val ACTION_RUN_ROUTINE = "com.example.action.RUN_ROUTINE"
        const val EXTRA_ROUTINE_ID = "extra_routine_id"
        private const val ALARM_REQUEST_BASE = 7200L
        private const val DUPLICATE_SCHEDULE_GUARD_MS = 90_000L

        internal fun computeNextTriggerMillis(hour: Int, minute: Int, nowMs: Long = System.currentTimeMillis()): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(Calendar.MINUTE, minute.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= nowMs + 5_000L) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }
    }
}

class RoutineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AutomationEngine.ACTION_RUN_ROUTINE) return
        val routineId = intent.getLongExtra(AutomationEngine.EXTRA_ROUTINE_ID, -1L)
        if (routineId <= 0L) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching {
                    val app = context.applicationContext as? CommanderApplication
                    app?.container?.automationEngine?.executeRoutine(routineId, triggerSource = "SCHEDULE")
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }
}

class RoutineWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val routineId = inputData.getLong(AutomationEngine.EXTRA_ROUTINE_ID, -1L)
        if (routineId <= 0L) return Result.success()
        val app = applicationContext as? CommanderApplication ?: return Result.success()
        app.container.automationEngine.executeRoutine(routineId, triggerSource = "SCHEDULE")
        return Result.success()
    }
}
