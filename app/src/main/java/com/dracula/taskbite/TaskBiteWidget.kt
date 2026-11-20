package com.dracula.taskbite

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.*
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class TaskBiteWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(180.dp, 180.dp), DpSize(280.dp, 240.dp), DpSize(280.dp, 360.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as TaskBiteApp
        val taskFlow = app.repository.dao.watchTasks()
        val initial = taskFlow.first()
        provideContent {
            val tasks by taskFlow.collectAsState(initial)
            // Persisted refresh ticks also refresh time-based counts during a live Glance session.
            val tick = currentState<Preferences>()[TaskWidgets.REFRESH_AT] ?: 0L
            val now = remember(tick, tasks) { System.currentTimeMillis() }
            TaskWidgetContent(TaskWidgetModel.from(tasks, now), now, app.preferences.onboarded)
        }
    }
}

@Composable private fun TaskWidgetContent(model: TaskWidgetModel, now: Long, onboarded: Boolean) {
    val context = LocalContext.current
    val size = LocalSize.current
    val wide = size.width >= 280.dp
    val tall = size.height >= 360.dp
    fun open(action: String, id: String? = null) = actionStartActivity(WidgetNavigation.intent(context, action, id))
    Column(GlanceModifier.fillMaxSize().appWidgetBackground()
        .background(ImageProvider(R.drawable.widget_background)).padding(if (wide) 14.dp else 8.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (wide) "taskbite / TODAY" else "taskbite", modifier = GlanceModifier.defaultWeight().clickable(open(WidgetNavigation.OPEN)),
                style = TextStyle(color = ColorProvider(Lime), fontSize = if (wide) 19.sp else 17.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Image(ImageProvider(R.drawable.ic_widget_refresh), "Refresh tasks",
                modifier = GlanceModifier.size(40.dp).clickable(actionRunCallback<RefreshTasksAction>()).padding(9.dp))
        }
        if (!onboarded) {
            Column(GlanceModifier.defaultWeight().fillMaxWidth().clickable(open(WidgetNavigation.OPEN)), verticalAlignment = Alignment.CenterVertically) {
                Text("Meet your sidekick.", style = TextStyle(color = ColorProvider(Cream), fontSize = 16.sp, fontWeight = FontWeight.Bold))
                Text("Tap to set up TaskBite.", style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp))
            }
        } else {
            Text("${model.pendingCount} pending · ${model.overdueCount} overdue",
                style = TextStyle(color = ColorProvider(if (model.overdueCount > 0) Coral else Muted), fontSize = if (wide) 13.sp else 11.sp),
                modifier = GlanceModifier.padding(bottom = 4.dp), maxLines = 1)
            if (tall) Text("${model.doneToday} done today. Bite is keeping score.",
                style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp), modifier = GlanceModifier.padding(bottom = 8.dp), maxLines = 2)
            if (model.today.isEmpty()) {
                Column(GlanceModifier.defaultWeight().fillMaxWidth().clickable(open(WidgetNavigation.OPEN)), verticalAlignment = Alignment.CenterVertically) {
                    Text("Today is clear.", style = TextStyle(color = ColorProvider(Cream), fontSize = 16.sp, fontWeight = FontWeight.Bold))
                    Text(if (model.pendingCount > 0) "${model.pendingCount} upcoming. Future you is booked." else "A little too peaceful. Add a mission?",
                        style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp), maxLines = 2)
                }
            } else {
                LazyColumn(GlanceModifier.defaultWeight().fillMaxWidth()) {
                    // Bound the RemoteViews payload for very large imported task lists.
                    items(model.today.take(50)) { task ->
                        Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Image(ImageProvider(R.drawable.ic_widget_complete), "Complete ${task.title}",
                                modifier = GlanceModifier.size(44.dp)
                                    .clickable(actionRunCallback<CompleteWidgetTaskAction>(actionParametersOf(CompleteWidgetTaskAction.TASK_ID to task.id)))
                                    .padding(10.dp))
                            Column(GlanceModifier.defaultWeight().clickable(open(WidgetNavigation.EDIT, task.id)).padding(vertical = 5.dp)) {
                                Text(task.title, style = TextStyle(color = ColorProvider(Cream), fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = if (wide) 2 else 1)
                                if (wide) Text("${if (task.dueAt <= now) "Overdue · " else ""}${Instant.ofEpochMilli(task.dueAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))} · ${task.minutes}m",
                                    style = TextStyle(color = ColorProvider(if (task.dueAt <= now) Coral else Muted), fontSize = 11.sp), maxLines = 1)
                            }
                        }
                    }
                    if (model.today.size > 50) item {
                        Text("See all ${model.today.size} in TaskBite", style = TextStyle(color = ColorProvider(Lime), fontSize = 13.sp),
                            modifier = GlanceModifier.fillMaxWidth().padding(12.dp).clickable(open(WidgetNavigation.OPEN)))
                    }
                }
            }
        }
        Row(GlanceModifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            WidgetButton(if (wide) "+ Add task" else "+ Add", open(WidgetNavigation.ADD), GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(8.dp))
            WidgetButton("Voice", open(WidgetNavigation.VOICE), GlanceModifier.defaultWeight())
        }
    }
}

@Composable private fun WidgetButton(text: String, action: Action, modifier: GlanceModifier) {
    Box(modifier.height(40.dp).background(Panel).cornerRadius(12.dp).clickable(action), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = ColorProvider(Lime), fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

class TaskBiteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskBiteWidget()
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        TaskWidgets.ensurePeriodic(context)
    }
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        TaskWidgets.ensurePeriodic(context)
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        TaskWidgets.cancelWork(context)
    }
}

class CompleteWidgetTaskAction : ActionCallback {
    companion object { val TASK_ID = ActionParameters.Key<String>("task_id") }
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TASK_ID] ?: return
        val app = context.applicationContext as TaskBiteApp
        try {
            app.repository.complete(id, true) // Explicit completion: repeated/stale taps never reopen a task.
            Reminders.clear(context, id)
            Reminders.schedule(context, app.repository)
            TaskWidgets.refreshNow(context)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            android.util.Log.w("TaskBiteWidget", "Could not complete widget task", e)
            TaskWidgets.requestRefresh(context)
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Couldn't update the task. Open TaskBite to try again.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}

class RefreshTasksAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        TaskWidgets.refreshNow(context)
    }
}

object TaskWidgets {
    val REFRESH_AT = longPreferencesKey("refresh_at")
    private const val REFRESH = "taskbite_widget_refresh"
    private const val PERIODIC = "taskbite_widget_clock"
    private val mutex = Mutex()
    fun installed(context: Context): Boolean = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, TaskBiteWidgetReceiver::class.java)).isNotEmpty()

    fun requestRefresh(context: Context) {
        if (!installed(context)) return
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(REFRESH, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build())
        } catch (e: RuntimeException) {
            // A widget failure must never turn a successful database commit into a failed save.
            android.util.Log.w("TaskBiteWidget", "Could not enqueue widget refresh", e)
        }
    }
    fun ensurePeriodic(context: Context) {
        if (!installed(context)) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).build())
    }
    fun cancelWork(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(REFRESH)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
    suspend fun refreshNow(context: Context) = mutex.withLock {
        if (!installed(context)) return@withLock
        val widget = TaskBiteWidget()
        GlanceAppWidgetManager(context).getGlanceIds(TaskBiteWidget::class.java).forEach { id ->
            updateAppWidgetState(context, id) { state -> state[REFRESH_AT] = System.currentTimeMillis() }
            widget.update(context, id)
        }
    }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        TaskWidgets.refreshNow(applicationContext)
        Result.success()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
}
