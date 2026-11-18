package com.company.supportvisit.shortcuts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.app.TaskStackBuilder
import com.company.supportvisit.MainActivity
import com.company.supportvisit.NewVisitActivity
import com.company.supportvisit.PastVisitsActivity
import com.company.supportvisit.R

/** Home-screen widget: one big "New visit report" button and a "Past visits" button. */
class QuickActionsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    private fun views(context: Context) = RemoteViews(context.packageName, R.layout.widget_quick_actions).apply {
        setOnClickPendingIntent(R.id.widgetNewVisit, open(context, NewVisitActivity::class.java, REQUEST_NEW_VISIT))
        setOnClickPendingIntent(R.id.widgetPastVisits, open(context, PastVisitsActivity::class.java, REQUEST_PAST_VISITS))
    }

    /** Opens the screen on top of the home screen, so Back returns there. */
    private fun open(context: Context, target: Class<*>, requestCode: Int): PendingIntent? =
        TaskStackBuilder.create(context)
            .addNextIntent(Intent(context, MainActivity::class.java))
            .addNextIntent(Intent(context, target))
            .getPendingIntent(requestCode, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        private const val REQUEST_NEW_VISIT = 1
        private const val REQUEST_PAST_VISITS = 2

        /** Redraws placed widgets, e.g. after the language changes. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, QuickActionsWidget::class.java))
            if (ids.isNotEmpty()) QuickActionsWidget().onUpdate(context, manager, ids)
        }
    }
}
