package com.toka.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.toka.app.MainActivity
import com.toka.app.R
import com.toka.app.data.di.AppContainer
import com.toka.app.notifications.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Widget "Mis tareas de hoy": las pendientes que vencen hoy o ya están atrasadas, de la
 * persona de este teléfono (o sin asignar), con texto grande y pocas filas.
 *
 * Lee de Room, igual que el resto de la app, así que funciona sin red. Se redibuja cuando
 * cambian los datos ([refresh]) y en cada ciclo del ReminderWorker.
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // El render es suspend (lee Room); goAsync mantiene vivo el receiver hasta que acabe.
        val pending = goAsync()
        scope.launch {
            try {
                render(context, manager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val MAX_ROWS = 3
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Redibuja todos los widgets puestos en el launcher. No hace nada si no hay ninguno. */
        fun refresh(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, TodayWidgetProvider::class.java))
            if (ids.isEmpty()) return
            scope.launch { render(appContext, manager, ids) }
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val container = AppContainer.instance
            val signedIn = container.sessionCache.uid != null && container.sessionCache.householdId != null
            val tasks = if (signedIn) container.taskRepository.todayTasks() else emptyList()

            val rowIds = intArrayOf(R.id.widget_task_1, R.id.widget_task_2, R.id.widget_task_3)
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_today)

                views.setTextViewText(
                    R.id.widget_title,
                    if (tasks.isEmpty()) context.getString(R.string.widget_title_empty)
                    else context.getString(R.string.widget_title_today, tasks.size)
                )

                for ((index, rowId) in rowIds.withIndex()) {
                    val task = tasks.getOrNull(index)
                    when {
                        task != null -> {
                            views.setViewVisibility(rowId, View.VISIBLE)
                            views.setTextViewText(rowId, task.name)
                            views.setOnClickPendingIntent(rowId, openApp(context, task.id))
                        }
                        // La primera fila sirve de mensaje cuando no hay nada que mostrar.
                        index == 0 -> {
                            views.setViewVisibility(rowId, View.VISIBLE)
                            views.setTextViewText(
                                rowId,
                                context.getString(
                                    if (signedIn) R.string.widget_empty else R.string.widget_signed_out
                                )
                            )
                        }
                        else -> views.setViewVisibility(rowId, View.GONE)
                    }
                }

                val extra = tasks.size - MAX_ROWS
                if (extra > 0) {
                    views.setViewVisibility(R.id.widget_more, View.VISIBLE)
                    views.setTextViewText(R.id.widget_more, context.getString(R.string.widget_more, extra))
                } else {
                    views.setViewVisibility(R.id.widget_more, View.GONE)
                }

                views.setOnClickPendingIntent(R.id.widget_root, openApp(context, null))
                manager.updateAppWidget(id, views)
            }
        }

        /** Abre la app, y la tarea concreta si se toca una fila (mismo extra que las notificaciones). */
        private fun openApp(context: Context, taskId: String?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (taskId != null) putExtra(Notifications.EXTRA_TASK_ID, taskId)
            }
            return PendingIntent.getActivity(
                context,
                // requestCode distinto por tarea: si no, FLAG_UPDATE_CURRENT pisa los extras.
                (taskId ?: "").hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
