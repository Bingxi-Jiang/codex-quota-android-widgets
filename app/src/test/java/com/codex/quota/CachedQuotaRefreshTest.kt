package com.codex.quota

import com.codex.quota.data.remote.CodexAccountDataSource
import com.codex.quota.data.repository.CodexAccountRepositoryImpl
import com.codex.quota.domain.model.AuthStatus
import com.codex.quota.domain.model.CodexAccount
import com.codex.quota.domain.model.CodexUsage
import com.codex.quota.domain.model.PlanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CachedQuotaRefreshTest {
    @Test
    fun failedReadings_preserveBothWindowsAndLastSuccessfulTimestamp() = runTest {
        for (status in listOf(AuthStatus.OFFLINE, AuthStatus.TEMPORARY_ERROR, AuthStatus.AUTHENTICATION_REQUIRED)) {
            val fixture = Fixture(this)
            val accountId = fixture.addAccount()
            fixture.source.next = Result.success(CodexUsage.empty(accountId, status))
            fixture.repository.refreshAccount(accountId)
            val cached = fixture.repository.getAccount(accountId)!!
            val usage = requireNotNull(cached.usage)
            assertEquals(61.0, usage.remainingPercent!!, 0.0)
            assertEquals(34.0, usage.fiveHourRemainingPercent!!, 0.0)
            assertEquals(9_000_000L, usage.resetAtEpochMs)
            assertEquals(8_000_000L, usage.fiveHourResetAtEpochMs)
            assertEquals(1_000L, usage.fetchedAtEpochMs)
            assertEquals(1_000L, cached.account.lastSuccessfulSyncEpochMs)
            assertEquals(status, usage.status)
            assertEquals(status, cached.account.authStatus)
        }
    }

    @Test
    fun thrownException_preservesCacheAndMakesRetryStateVisible() = runTest {
        val fixture = Fixture(this)
        val id = fixture.addAccount()
        fixture.source.failure = IOException("network broke")
        assertTrue(fixture.repository.refreshAccount(id).isFailure)
        val cached = fixture.repository.getAccount(id)!!
        assertEquals(61.0, cached.usage!!.remainingPercent!!, 0.0)
        assertEquals(AuthStatus.TEMPORARY_ERROR, cached.account.authStatus)
        assertEquals(1_000L, cached.usage!!.fetchedAtEpochMs)
    }

    @Test
    fun refreshAllFailure_recordsFailureInsteadOfSilentlyLeavingAccountActive() = runTest {
        val fixture = Fixture(this)
        val id = fixture.addAccount()
        fixture.source.next = Result.failure(IOException("network broke"))
        fixture.repository.refreshAllAccounts()
        val cached = fixture.repository.getAccount(id)!!
        assertEquals(AuthStatus.TEMPORARY_ERROR, cached.account.authStatus)
        assertEquals(61.0, cached.usage!!.remainingPercent!!, 0.0)
    }

    @Test
    fun recovery_replacesCacheAndAdvancesTimestamp() = runTest {
        val fixture = Fixture(this)
        val id = fixture.addAccount()
        fixture.source.next = Result.success(CodexUsage.empty(id, AuthStatus.OFFLINE))
        fixture.repository.refreshAccount(id)
        fixture.source.next = Result.success(fixture.source.good.copy(
            remainingPercent = 55.0, fetchedAtEpochMs = 2_000L
        ))
        fixture.repository.refreshAccount(id)
        val refreshed = fixture.repository.getAccount(id)!!
        assertEquals(55.0, refreshed.usage!!.remainingPercent!!, 0.0)
        assertEquals(AuthStatus.AUTHENTICATED, refreshed.account.authStatus)
        assertEquals(2_000L, refreshed.account.lastSuccessfulSyncEpochMs)
    }

    @Test
    fun firstOfflineRefresh_doesNotInventQuotaOrSuccessfulSync() = runTest {
        val fixture = Fixture(this)
        fixture.source.next = Result.success(CodexUsage.empty("unused", AuthStatus.OFFLINE))
        val id = fixture.addAccount()
        val account = fixture.repository.getAccount(id)!!
        assertNull(account.usage!!.remainingPercent)
        assertNull(account.account.lastSuccessfulSyncEpochMs)
    }

    @Test
    fun cancellation_isNotConvertedToErrorOrWrittenToCache() = runTest {
        val fixture = Fixture(this)
        val id = fixture.addAccount()
        fixture.source.failure = CancellationException("cancelled")
        try {
            fixture.repository.refreshAccount(id)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(AuthStatus.AUTHENTICATED, fixture.repository.getAccount(id)!!.account.authStatus)
        }
    }

    private class Fixture(scope: CoroutineScope) {
        val source = ScriptedSource()
        val repository = CodexAccountRepositoryImpl(
            FakeAccountDao(), FakeUsageSnapshotDao(), FakeCredentialStore(), source, source, scope
        )
        suspend fun addAccount() = repository.addAccount(
            "Test", null, "test", PlanType.PLUS, null, "#10B981", false
        ).getOrThrow().id
    }

    private class ScriptedSource : CodexAccountDataSource {
        val good = CodexUsage.empty("unused", AuthStatus.AUTHENTICATED).copy(
            remainingPercent = 61.0, usedPercent = 39.0, resetAtEpochMs = 9_000_000L,
            fiveHourRemainingPercent = 34.0, fiveHourUsedPercent = 66.0,
            fiveHourResetAtEpochMs = 8_000_000L, fetchedAtEpochMs = 1_000L
        )
        var next = Result.success(good)
        var failure: Exception? = null
        override suspend fun fetchUsage(account: CodexAccount, apiKey: String): Result<CodexUsage> {
            failure?.let { throw it }
            return next.map { it.copy(accountId = account.id) }
        }
    }
}
