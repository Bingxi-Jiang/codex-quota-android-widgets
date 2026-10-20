package com.codex.quota.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.codex.quota.domain.model.AccountWithUsage
import com.codex.quota.ui.util.formatQuotaPercent
import com.codex.quota.ui.util.formatResetCountdown

val WIDGET_SECOND_ACCOUNT_ID = stringPreferencesKey("second_account_id")
val WIDGET_FIRST_LABEL = stringPreferencesKey("first_account_label")
val WIDGET_SECOND_LABEL = stringPreferencesKey("second_account_label")

data class DualWidgetAccount(val data: AccountWithUsage?, val label: String)

/** An explicit selection stays empty when deleted rather than showing a different account. */
fun dualWidgetAccounts(accounts: List<AccountWithUsage>, prefs: Preferences): List<DualWidgetAccount> {
    val firstId = prefs[WIDGET_ACCOUNT_ID]
    val first = if (firstId == null) accounts.firstOrNull() else accounts.find { it.account.id == firstId }
    val secondId = prefs[WIDGET_SECOND_ACCOUNT_ID]
    val second = if (secondId == null) accounts.firstOrNull { it.account.id != first?.account?.id }
        else accounts.find { it.account.id == secondId && it.account.id != first?.account?.id }
    return listOf(
        DualWidgetAccount(first, prefs[WIDGET_FIRST_LABEL]?.takeIf { it.isNotBlank() } ?: first?.account?.nickname ?: "Add account"),
        DualWidgetAccount(second, prefs[WIDGET_SECOND_LABEL]?.takeIf { it.isNotBlank() } ?: second?.account?.nickname ?: "Add account")
    )
}

fun compactResetLabel(epochMs: Long?, now: Long): String =
    epochMs?.let { if (it <= now) "due" else formatResetCountdown(it, now).replace(" ", "") } ?: "—"

fun compactQuotaLabel(label: String, remaining: Double?, resetAt: Long?, now: Long): String =
    "$label ${formatQuotaPercent(remaining)} · ${compactResetLabel(resetAt, now)}"
