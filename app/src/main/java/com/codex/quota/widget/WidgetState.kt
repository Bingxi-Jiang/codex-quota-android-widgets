package com.codex.quota.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.codex.quota.domain.model.AccountWithUsage
import com.codex.quota.domain.model.AuthStatus
import com.codex.quota.worker.WorkScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val WIDGET_ACCOUNT_ID = stringPreferencesKey("account_id")

fun selectWidgetAccount(accounts: List<AccountWithUsage>, accountId: String?): AccountWithUsage? =
    if (accountId == null) accounts.firstOrNull() else accounts.firstOrNull { it.account.id == accountId }

fun AccountWithUsage.widgetSyncLabel(now: Long = System.currentTimeMillis()): String {
    val lastSync = account.lastSuccessfulSyncEpochMs
        ?: usage?.takeIf { it.status == AuthStatus.AUTHENTICATED }?.fetchedAtEpochMs
    val time = lastSync?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }
    val status = usage?.status ?: account.authStatus
    val state = when {
        status == AuthStatus.AUTHENTICATION_REQUIRED -> "Sign in"
        status == AuthStatus.OFFLINE -> "Offline"
        status == AuthStatus.TEMPORARY_ERROR -> "Retry needed"
        lastSync == null -> "No reading yet"
        now - lastSync > com.codex.quota.domain.model.CodexUsage.STALE_THRESHOLD_MS -> "Stale"
        else -> "Updated"
    }
    return if (time == null) state else "$state · $time"
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // WorkManager owns the request so it survives the widget callback/process.
        WorkScheduler.requestImmediateRefresh(context)
    }
}

@Composable
fun WidgetRefreshButton(colors: WidgetColors, compact: Boolean = false) {
    Text(
        text = if (compact) "↻" else "↻ Refresh",
        modifier = GlanceModifier.padding(horizontal = 6.dp, vertical = 2.dp)
            .clickable(actionRunCallback<RefreshWidgetAction>()),
        style = TextStyle(color = colors.accentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    )
}
