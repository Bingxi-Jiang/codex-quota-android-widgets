package com.codex.quota.data.repository

import com.codex.quota.data.local.dao.AccountDao
import com.codex.quota.data.local.dao.UsageSnapshotDao
import com.codex.quota.data.local.entity.AccountEntity
import com.codex.quota.data.local.entity.UsageSnapshotEntity
import com.codex.quota.data.remote.CodexAccountDataSource
import com.codex.quota.data.remote.MockOpenAiDataSource
import com.codex.quota.data.remote.RealOpenAiDataSource
import com.codex.quota.domain.model.AccountWithUsage
import com.codex.quota.domain.model.AuthStatus
import com.codex.quota.domain.model.CodexAccount
import com.codex.quota.domain.model.CodexUsage
import com.codex.quota.domain.model.PlanType
import com.codex.quota.domain.repository.CodexAccountRepository
import com.codex.quota.security.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CodexAccountRepositoryImpl(
    private val accountDao: AccountDao,
    private val usageSnapshotDao: UsageSnapshotDao,
    private val credentialStore: CredentialStore,
    private val realDataSource: CodexAccountDataSource = RealOpenAiDataSource(),
    private val mockDataSource: CodexAccountDataSource = MockOpenAiDataSource(),
    private val refreshScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : CodexAccountRepository {

    private val refreshMutex = Mutex()
    private var activeRefresh: Deferred<Result<List<CodexUsage>>>? = null
    private val accountRefreshLocks = ConcurrentHashMap<String, Mutex>()

    override fun observeAccounts(): Flow<List<AccountWithUsage>> {
        return combine(
            accountDao.observeAll(),
            usageSnapshotDao.observeAll()
        ) { accounts, snapshots ->
            val snapshotMap = snapshots.associateBy { it.accountId }
            accounts.map { accountEntity ->
                val domainAccount = accountEntity.toDomain()
                val domainUsage = snapshotMap[accountEntity.id]?.toDomain()
                AccountWithUsage(domainAccount, domainUsage)
            }
        }
    }

    override fun observeAccount(accountId: String): Flow<AccountWithUsage?> {
        return combine(
            accountDao.observeById(accountId),
            usageSnapshotDao.observeByAccountId(accountId)
        ) { accountEntity, snapshotEntity ->
            if (accountEntity == null) null
            else {
                AccountWithUsage(
                    account = accountEntity.toDomain(),
                    usage = snapshotEntity?.toDomain()
                )
            }
        }
    }

    override suspend fun getAccount(accountId: String): AccountWithUsage? {
        val accountEntity = accountDao.getById(accountId) ?: return null
        val snapshotEntity = usageSnapshotDao.getByAccountId(accountId)
        return AccountWithUsage(
            account = accountEntity.toDomain(),
            usage = snapshotEntity?.toDomain()
        )
    }

    override suspend fun getAllAccounts(): List<AccountWithUsage> {
        val accounts = accountDao.getAll()
        val snapshots = usageSnapshotDao.getAll().associateBy { it.accountId }
        return accounts.map {
            AccountWithUsage(it.toDomain(), snapshots[it.id]?.toDomain())
        }
    }

    override suspend fun addAccount(
        nickname: String,
        email: String?,
        apiKey: String,
        planType: PlanType,
        organizationId: String?,
        colorHex: String,
        isDemoAccount: Boolean
    ): Result<CodexAccount> {
        val accountId = UUID.randomUUID().toString()
        val existing = accountDao.getAll()
        val nextOrder = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
        val now = System.currentTimeMillis()

        val domainAccount = CodexAccount(
            id = accountId,
            nickname = nickname,
            email = email,
            planType = planType,
            organizationId = organizationId,
            colorHex = colorHex,
            authStatus = AuthStatus.REFRESHING,
            isDemoAccount = isDemoAccount,
            orderIndex = nextOrder,
            createdAtEpochMs = now,
            lastSuccessfulSyncEpochMs = null
        )

        credentialStore.storeApiKey(accountId, apiKey)
        accountDao.insert(AccountEntity.fromDomain(domainAccount))

        // Trigger initial refresh
        val refreshResult = refreshAccountInternal(domainAccount)
        return if (refreshResult.isSuccess) {
            val updatedAccount = accountDao.getById(accountId)?.toDomain() ?: domainAccount
            Result.success(updatedAccount)
        } else {
            Result.success(domainAccount)
        }
    }

    override suspend fun updateAccount(
        accountId: String,
        nickname: String,
        colorHex: String,
        customRenewalDateEpochMs: Long?
    ): Result<Unit> {
        accountDao.updateDetails(accountId, nickname, colorHex, customRenewalDateEpochMs)
        return Result.success(Unit)
    }

    override suspend fun setAccountRenewalDate(
        accountId: String,
        renewalDateEpochMs: Long?
    ): Result<Unit> {
        accountDao.updateRenewalDate(accountId, renewalDateEpochMs)
        return Result.success(Unit)
    }

    override suspend fun reauthenticateAccount(
        accountId: String,
        newApiKey: String
    ): Result<CodexUsage> {
        val account = accountDao.getById(accountId)?.toDomain()
            ?: return Result.failure(IllegalArgumentException("Account $accountId not found"))

        credentialStore.storeApiKey(accountId, newApiKey)
        return refreshAccountInternal(account)
    }

    override suspend fun removeAccount(accountId: String): Result<Unit> {
        credentialStore.removeApiKey(accountId)
        usageSnapshotDao.deleteByAccountId(accountId)
        accountDao.deleteById(accountId)
        return Result.success(Unit)
    }

    override suspend fun reorderAccounts(accountIdsInOrder: List<String>): Result<Unit> {
        accountDao.updateOrderIndices(accountIdsInOrder)
        return Result.success(Unit)
    }

    override suspend fun refreshAccount(accountId: String): Result<CodexUsage> {
        val account = accountDao.getById(accountId)?.toDomain()
            ?: return Result.failure(IllegalArgumentException("Account $accountId not found"))
        return refreshAccountInternal(account)
    }

    private suspend fun refreshAccountInternal(
        account: CodexAccount
    ): Result<CodexUsage> = accountRefreshLocks.getOrPut(account.id) { Mutex() }.withLock {
        val dataSource = if (account.isDemoAccount) mockDataSource else realDataSource
        val result = try {
            // Read credentials inside the lock, after any preceding reauthentication.
            val apiKey = credentialStore.getApiKey(account.id).orEmpty()
            dataSource.fetchUsage(account, apiKey)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Result.failure(exception)
        }
        val previous = usageSnapshotDao.getByAccountId(account.id)?.toDomain()

        if (result.isSuccess) {
            val incoming = result.getOrThrow()
            val usage = if (incoming.status == AuthStatus.AUTHENTICATED || previous == null) {
                incoming
            } else {
                // Preserve both quota windows and their original timestamp on failure.
                previous.copy(status = incoming.status, errorMessage = incoming.errorMessage)
            }
            // The account may have been removed while the request was in flight.
            val currentAccount = accountDao.getById(account.id)
                ?: return@withLock Result.failure(IllegalStateException("Account was removed"))
            usageSnapshotDao.insertOrUpdate(UsageSnapshotEntity.fromDomain(usage))

            val lastSync = if (usage.status == AuthStatus.AUTHENTICATED) {
                usage.fetchedAtEpochMs
            } else {
                currentAccount.lastSuccessfulSyncEpochMs
            }

            accountDao.updateAuthStatusAndSyncTime(
                accountId = account.id,
                authStatus = usage.status.name,
                lastSync = lastSync
            )
            Result.success(usage)
        } else {
            val exception = result.exceptionOrNull() ?: Exception("Unknown sync error")
            if (accountDao.getById(account.id) != null) {
                val cached = (previous ?: CodexUsage.empty(account.id)).copy(
                    status = AuthStatus.TEMPORARY_ERROR,
                    errorMessage = "Refresh failed. Showing the last successful reading."
                )
                usageSnapshotDao.insertOrUpdate(UsageSnapshotEntity.fromDomain(cached))
                accountDao.updateAuthStatusAndSyncTime(
                    account.id, AuthStatus.TEMPORARY_ERROR.name,
                    accountDao.getById(account.id)?.lastSuccessfulSyncEpochMs
                )
            }
            Result.failure(exception)
        }
    }

    override suspend fun refreshAllAccounts(): Result<List<CodexUsage>> {
        val refresh = refreshMutex.withLock {
            activeRefresh?.takeIf { it.isActive } ?: refreshScope.async {
                refreshAllAccountsInternal()
            }.also { activeRefresh = it }
        }

        return try {
            refresh.await()
        } finally {
            refreshMutex.withLock {
                if (activeRefresh === refresh && refresh.isCompleted) {
                    activeRefresh = null
                }
            }
        }
    }

    private suspend fun refreshAllAccountsInternal(): Result<List<CodexUsage>> = coroutineScope {
        val accounts = accountDao.getAll()
        val concurrencyLimit = Semaphore(MAX_CONCURRENT_ACCOUNT_REFRESHES)
        val results = accounts.map { account ->
            async {
                concurrencyLimit.withPermit {
                    try {
                        val domainAccount = account.toDomain()
                        refreshAccountInternal(domainAccount).getOrNull()
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll()
        Result.success(results.filterNotNull())
    }

    override suspend fun clearAllData(): Result<Unit> {
        credentialStore.clearAll()
        usageSnapshotDao.deleteAll()
        accountDao.deleteAll()
        return Result.success(Unit)
    }

    private companion object {
        const val MAX_CONCURRENT_ACCOUNT_REFRESHES = 4
    }
}
