package com.codex.quota

import com.codex.quota.domain.model.AccountWithUsage
import com.codex.quota.domain.model.AuthStatus
import com.codex.quota.domain.model.CodexAccount
import com.codex.quota.domain.model.CodexUsage
import com.codex.quota.domain.model.PlanType
import com.codex.quota.widget.selectWidgetAccount
import com.codex.quota.widget.widgetSyncLabel
import org.junit.Assert.*
import org.junit.Test

class WidgetStateTest {
    private val now = 2_000_000_000_000L

    @Test
    fun widgetKeepsSelectedAccountAfterReorder() {
        val first = account("first")
        val selected = account("selected")
        assertEquals("selected", selectWidgetAccount(listOf(first, selected), "selected")!!.account.id)
        assertEquals("selected", selectWidgetAccount(listOf(selected, first), "selected")!!.account.id)
    }

    @Test
    fun removedSelectionDoesNotSilentlyShowAnotherPersonsQuota() {
        assertNull(selectWidgetAccount(listOf(account("remaining")), "deleted"))
        assertEquals("first", selectWidgetAccount(listOf(account("first")), null)!!.account.id)
    }

    @Test
    fun cachedOfflineReadingIsNotLabeledSynced() {
        assertTrue(account("a", AuthStatus.OFFLINE).widgetSyncLabel(now).startsWith("Offline · "))
        assertTrue(account("a", AuthStatus.TEMPORARY_ERROR).widgetSyncLabel(now).startsWith("Retry needed · "))
        assertTrue(account("a", AuthStatus.AUTHENTICATION_REQUIRED).widgetSyncLabel(now).startsWith("Sign in · "))
    }

    @Test
    fun oldReadingIsStaleAndMissingReadingDoesNotHaveAFakeTimestamp() {
        assertTrue(account("a", lastSync = now - 3_600_000L).widgetSyncLabel(now).startsWith("Stale · "))
        assertTrue(account("a").widgetSyncLabel(now).startsWith("Updated · "))
        val missing = account("a").let { it.copy(account = it.account.copy(lastSuccessfulSyncEpochMs = null), usage = null) }
        assertEquals("No reading yet", missing.widgetSyncLabel(now))
    }

    private fun account(id: String, status: AuthStatus = AuthStatus.AUTHENTICATED, lastSync: Long = now - 60_000L) = AccountWithUsage(
        CodexAccount(id, id, null, PlanType.PLUS, null, "#10B981", status, false, 0, 0L, lastSync),
        CodexUsage.empty(id, status).copy(remainingPercent = 65.0, fetchedAtEpochMs = lastSync)
    )
}
