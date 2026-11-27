package com.phoebe.app.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.phoebe.app.db.PhoebeDatabase
import com.phoebe.app.domain.PlexSession
import com.phoebe.app.domain.catalogPrefix
import com.phoebe.app.platform.PhoebeDispatchers
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.withContext

/**
 * Fingerprints a library sync saves between runs, so the next sync can tell what changed on the
 * server without downloading everything again. Rows are grouped by scope; see [libraryScope] and
 * [albumScope]. Everything here is cleared with the catalog.
 */
@Inject
class RemoteSyncStateStore(private val database: PhoebeDatabase) {
    private val dispatcher = PhoebeDispatchers.io

    suspend fun value(scope: String, key: String): String? = withContext(dispatcher) {
        database.remoteSyncStateQueries.selectValue(scope, key).awaitAsOneOrNull()
    }

    suspend fun values(scope: String): Map<String, String> = withContext(dispatcher) {
        database.remoteSyncStateQueries.selectScope(scope).awaitAsList().associate { it.item_key to it.value_ }
    }

    suspend fun put(scope: String, values: Map<String, String>) {
        if (values.isEmpty()) return
        withContext(dispatcher) {
            database.transaction {
                values.forEach { (key, value) -> database.remoteSyncStateQueries.upsert(scope, key, value) }
            }
        }
    }

    /** Replaces every row in [scope] with [values], dropping keys that are no longer present. */
    suspend fun replaceScope(scope: String, values: Map<String, String>) = withContext(dispatcher) {
        database.transaction {
            database.remoteSyncStateQueries.deleteScope(scope)
            values.forEach { (key, value) -> database.remoteSyncStateQueries.upsert(scope, key, value) }
        }
    }

    suspend fun clearScope(scope: String) = withContext(dispatcher) {
        database.remoteSyncStateQueries.deleteScope(scope)
    }

    companion object {
        /** The selected library on this server, for this user. */
        fun libraryScope(session: PlexSession): String? {
            val server = session.selectedServer ?: return null
            val library = session.selectedLibrary ?: return null
            val user = session.userId?.takeIf { it.isNotBlank() } ?: session.userName
            return "library:${session.providerType.catalogPrefix}:${server.id}:${library.key}:$user"
        }

        /** Per-album fingerprints from the last full sync of this server. */
        fun albumScope(prefix: String, serverId: String): String = "albums:$prefix:$serverId"
    }
}
