package com.codex.quota.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException
import android.util.Log

object WidgetUpdateHelper {
    suspend fun updateAllWidgets(context: Context) {
        for (widget: GlanceAppWidget in listOf(SmallQuotaWidget(), MediumQuotaWidget(), MultiAccountQuotaWidget())) {
            try {
                widget.updateAll(context)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // One broken widget must not prevent the other sizes from updating.
                Log.w("WidgetUpdateHelper", "Could not update ${widget.javaClass.simpleName}", exception)
            }
        }
    }
}
