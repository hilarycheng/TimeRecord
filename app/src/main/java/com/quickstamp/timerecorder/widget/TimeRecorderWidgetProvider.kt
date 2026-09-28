package com.quickstamp.timerecorder.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.quickstamp.timerecorder.MainActivity
import com.quickstamp.timerecorder.R
import com.quickstamp.timerecorder.data.AppStore
import com.quickstamp.timerecorder.data.RecorderActions
import com.quickstamp.timerecorder.model.CommuteMode
import com.quickstamp.timerecorder.model.EventKind
import com.quickstamp.timerecorder.model.HkTime
import com.quickstamp.timerecorder.model.NextAction
import com.quickstamp.timerecorder.model.WorkflowPlanner
import kotlin.math.ceil

class TimeRecorderWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { update(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_SMART -> {
                val state = AppStore.load(context)
                when (val next = WorkflowPlanner.next(state.events, HkTime.today())) {
                    is NextAction.Single -> RecorderActions.record(
                        context,
                        next.action.label,
                        next.action.kind,
                        next.action.route,
                        next.action.terminal,
                    )
                    else -> Unit
                }
                updateAll(context)
            }
            ACTION_38 -> {
                RecorderActions.record(context, "上 38", EventKind.BUS, "38")
                updateAll(context)
            }
            ACTION_42C -> {
                RecorderActions.record(context, "上 42C", EventKind.BUS, "42C")
                updateAll(context)
            }
        }
    }

    companion object {
        private const val ACTION_SMART = "com.quickstamp.timerecorder.widget.SMART"
        private const val ACTION_38 = "com.quickstamp.timerecorder.widget.BUS38"
        private const val ACTION_42C = "com.quickstamp.timerecorder.widget.BUS42C"

        fun updateAll(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, TimeRecorderWidgetProvider::class.java))
            ids.forEach { update(app, manager, it) }
        }

        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val state = AppStore.load(context)
            val now = HkTime.now()
            val mode = state.tracking.mode ?: HkTime.modeAt(now, state.settings.morningCutoffHour)
            val snapshot = if (mode == CommuteMode.WORK) state.etaWork else state.etaHome
            val views = RemoteViews(context.packageName, R.layout.widget_time_recorder)

            fun eta(route: String): String {
                val values = snapshot.routes[route].orEmpty().filter { it.timestamp >= now - 60_000L }.take(2)
                return if (values.isEmpty()) "—" else values.joinToString(" · ") {
                    val left = it.timestamp - now
                    if (left <= 30_000L) "Due" else "${ceil(left / 60_000.0).toInt()}m"
                }
            }
            views.setTextViewText(R.id.widget_eta, "38  ${eta("38")}    42C  ${eta("42C")}")
            val next = WorkflowPlanner.next(state.events, HkTime.today(), now)
            views.setTextViewText(
                R.id.widget_smart,
                when (next) {
                    is NextAction.Single -> "下一步 · ${next.action.label}"
                    NextAction.BusChoices -> "下一步 · 揀 38 / 42C"
                    NextAction.Done -> "今日完成"
                }
            )

            views.setOnClickPendingIntent(R.id.widget_smart, broadcast(context, ACTION_SMART, 201))
            views.setOnClickPendingIntent(R.id.widget_38, broadcast(context, ACTION_38, 238))
            views.setOnClickPendingIntent(R.id.widget_42c, broadcast(context, ACTION_42C, 242))
            views.setOnClickPendingIntent(
                R.id.widget_eta,
                PendingIntent.getActivity(
                    context,
                    200,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            manager.updateAppWidget(id, views)
        }

        private fun broadcast(context: Context, action: String, request: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                request,
                Intent(context, TimeRecorderWidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
