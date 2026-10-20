package com.codex.quota

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.codex.quota.domain.model.*
import com.codex.quota.widget.*
import org.junit.Assert.*
import org.junit.Test

class DualWidgetModelTest {
    private val first = account("a", "Personal")
    private val second = account("b", "Work")

    @Test fun defaultsShowExactlyTwoDistinctAccounts() {
        val slots = dualWidgetAccounts(listOf(first, second, account("c", "Extra")), emptyPreferences())
        assertEquals(listOf("a", "b"), slots.map { it.data!!.account.id })
        assertEquals(listOf("Personal", "Work"), slots.map { it.label })
    }

    @Test fun selectedOrderAndCustomNamesSurviveAccountReorderAndUsageRefresh() {
        val prefs = preferencesOf(WIDGET_ACCOUNT_ID to "b", WIDGET_SECOND_ACCOUNT_ID to "a",
            WIDGET_FIRST_LABEL to "Business", WIDGET_SECOND_LABEL to "Home")
        val slots = dualWidgetAccounts(listOf(first, second), prefs)
        assertEquals(listOf("b", "a"), slots.map { it.data!!.account.id })
        assertEquals(listOf("Business", "Home"), slots.map { it.label })
        assertEquals(slots, dualWidgetAccounts(listOf(second, first), prefs))
    }

    @Test fun deletedSelectionStaysEmptyAndDoesNotDuplicateTheOtherAccount() {
        val prefs = preferencesOf(WIDGET_ACCOUNT_ID to "a", WIDGET_SECOND_ACCOUNT_ID to "b")
        val slots = dualWidgetAccounts(listOf(second), prefs)
        assertNull(slots[0].data)
        assertEquals("b", slots[1].data!!.account.id)
        val duplicate = dualWidgetAccounts(listOf(first, second), preferencesOf(
            WIDGET_ACCOUNT_ID to "a", WIDGET_SECOND_ACCOUNT_ID to "a"))
        assertNull(duplicate[1].data)
    }

    @Test fun blankAliasUsesNicknameAndMissingAccountIsAnEmptySlot() {
        val slots = dualWidgetAccounts(listOf(first), preferencesOf(WIDGET_FIRST_LABEL to "   "))
        assertEquals("Personal", slots[0].label)
        assertNull(slots[1].data)
    }

    @Test fun compactQuotaIncludesSeparateResetAndNeverInventsMissingData() {
        val now = 1_000L
        assertEquals("W 75% · 1d4h", compactQuotaLabel("W", 75.0, now + 28 * 3_600_000L, now))
        assertEquals("5h 42% · 1h15m", compactQuotaLabel("5h", 42.0, now + 75 * 60_000L, now))
        assertEquals("5h --% · —", compactQuotaLabel("5h", null, null, now))
        assertEquals("due", compactResetLabel(now - 1L, now))
    }

    private fun account(id: String, name: String) = AccountWithUsage(
        CodexAccount(id, name, null, PlanType.PLUS, null, "#10B981", AuthStatus.AUTHENTICATED, false, 0, 0L, 1_000L),
        CodexUsage.empty(id, AuthStatus.AUTHENTICATED))
}
