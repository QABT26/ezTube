package com.qabt.eztube.account

/**
 * Local-first identity/sync contract.
 *
 * V1 deliberately has no network dependency and no required sign-in. Stable local identity lets
 * History/Favorites/Preferences evolve into account sync later without changing playback screens.
 */
data class AccountIdentity(
    val accountId: String? = null,
    val deviceId: String,
    val signedIn: Boolean = false
)

enum class SyncEntityType {
    HISTORY,
    FAVORITE,
    PREFERENCE,
    QUEUE
}

data class SyncRecord(
    val entityType: SyncEntityType,
    val entityId: String,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

interface AccountSyncGateway {
    suspend fun push(identity: AccountIdentity, records: List<SyncRecord>)
    suspend fun pull(identity: AccountIdentity, changedAfter: Long): List<SyncRecord>
}

/**
 * Conflict rule for future multi-device sync: latest update wins. Deletions participate using
 * their deletion timestamp so a stale device cannot resurrect removed data.
 */
fun newest(local: SyncRecord?, remote: SyncRecord?): SyncRecord? =
    listOfNotNull(local, remote).maxByOrNull { maxOf(it.updatedAt, it.deletedAt ?: Long.MIN_VALUE) }
