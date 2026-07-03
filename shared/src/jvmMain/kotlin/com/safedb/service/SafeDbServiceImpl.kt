package com.safedb.service

import com.safedb.adapter.Adapter
import com.safedb.model.CompiledQuery
import com.safedb.model.ConnectionDef
import com.safedb.model.ExplainResult
import com.safedb.model.HistoryEntry
import com.safedb.model.Outcome
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.SavedQuery
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.query.QueryCoreOutcome
import com.safedb.query.QueryRunner
import com.safedb.query.runQueryCore
import com.safedb.secrets.SecretsManager
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.SettingsStore
import java.time.Instant
import java.util.UUID

internal fun interface QuerySessionFactory {
    suspend fun open(def: ConnectionDef, password: String): QuerySession
}

internal class QuerySession(
    val schema: Schema,
    val runner: QueryRunner,
    private val onClose: suspend () -> Unit = {},
) {
    suspend fun close() = onClose()
}

class SafeDbServiceImpl internal constructor(
    private val configStore: ConfigStore,
    private val queryStore: QueryStore,
    private val settingsStore: SettingsStore,
    private val querySessionFactory: QuerySessionFactory?,
) : SafeDbService {

    constructor(
        configStore: ConfigStore,
        queryStore: QueryStore,
        settingsStore: SettingsStore,
    ) : this(configStore, queryStore, settingsStore, null)

    override suspend fun testConnection(def: ConnectionDef, password: String): String {
        def.validate().getOrThrow()
        val adapter = Adapter.connect(def, password)
        return try {
            adapter.test()
        } finally {
            adapter.close()
        }
    }

    override suspend fun saveConnection(def: ConnectionDef, password: String?) {
        persistConnection(def, password)
    }

    override suspend fun createConnection(def: ConnectionDef, password: String): ConnectionDef {
        val created = def.copy(id = UUID.randomUUID().toString())
        persistConnection(created, password)
        return created
    }

    override suspend fun updateConnection(def: ConnectionDef, password: String?) {
        if (configStore.get(def.id) == null) {
            throw IllegalArgumentException("Connection not found")
        }
        persistConnection(def, password)
    }

    override suspend fun listConnections(): List<ConnectionDef> = configStore.list()

    override suspend fun deleteConnection(id: String) {
        val previous = configStore.get(id)
        configStore.delete(id)
        SecretsManager.deletePassword(id).onFailure { error ->
            if (previous != null) {
                configStore.save(previous)
            }
            throw IllegalStateException(error.message ?: error.toString())
        }
    }

    override suspend fun lockCredentials() = SecretsManager.lockCredentials()

    override suspend fun getSchema(connectionId: String): Schema {
        val def = configStore.get(connectionId) ?: throw IllegalArgumentException("Connection not found")
        val password = SecretsManager.passwordForDefinition(def).getOrThrow()
        val adapter = Adapter.connect(def, password)
        return try {
            Adapter.introspectWithTimeout(adapter)
        } finally {
            adapter.close()
        }
    }

    override suspend fun runQuery(connectionId: String, spec: QuerySpec, force: Boolean): QueryResult {
        val def = configStore.get(connectionId) ?: throw IllegalArgumentException("Connection not found")
        val password = SecretsManager.passwordForDefinition(def).getOrThrow()
        val settings = settingsStore.load()
        val session = querySessionFactory?.open(def, password) ?: openDefaultQuerySession(def, password)
        return try {
            when (val outcome = runQueryCore(session.runner, def, spec, session.schema, settings, force)) {
                is QueryCoreOutcome.Success -> {
                    recordHistory(connectionId, def.name, outcome.historySpec, outcome.result, null)
                    outcome.result
                }
                is QueryCoreOutcome.Failure -> {
                    recordHistory(
                        connectionId,
                        def.name,
                        outcome.error.historySpec ?: spec,
                        null,
                        outcome.error,
                    )
                    throw IllegalArgumentException(outcome.error.message)
                }
            }
        } finally {
            session.close()
        }
    }

    private suspend fun openDefaultQuerySession(def: ConnectionDef, password: String): QuerySession {
        val adapter = Adapter.connect(def, password)
        val schema = try {
            Adapter.introspectWithTimeout(adapter)
        } catch (error: Throwable) {
            runCatching { adapter.close() }.onFailure { closeError -> error.addSuppressed(closeError) }
            throw error
        }
        return QuerySession(
            schema = schema,
            runner = AdapterQueryRunner(adapter),
            onClose = { adapter.close() },
        )
    }

    override suspend fun listSavedQueries(): List<SavedQuery> = queryStore.listSaved()

    override suspend fun saveSavedQuery(query: SavedQuery) = queryStore.saveQuery(query)

    override suspend fun deleteSavedQuery(id: String) = queryStore.deleteSaved(id)

    override suspend fun listHistory(): List<HistoryEntry> = queryStore.listHistory()

    override suspend fun clearHistory() = queryStore.clearHistory()

    override suspend fun getSettings(): Settings = settingsStore.load()

    override suspend fun saveSettings(settings: Settings) = settingsStore.save(settings)

    private fun persistConnection(def: ConnectionDef, password: String?) {
        def.validate().getOrThrow()
        val previous = configStore.get(def.id)
        if (previous != null &&
            previous.credentialFingerprint() != def.credentialFingerprint() &&
            password == null
        ) {
            throw IllegalArgumentException("Endpoint or transport changes require the password to be re-entered")
        }
        if (previous == null && password == null) {
            throw IllegalArgumentException("A password is required when creating a connection")
        }
        configStore.save(def)
        if (password != null) {
            SecretsManager.savePasswordForDefinition(def, password).getOrElse { error ->
                if (previous != null) configStore.save(previous) else configStore.delete(def.id)
                throw IllegalStateException(error.message)
            }
        }
    }

    private fun recordHistory(
        connectionId: String,
        connectionName: String,
        spec: QuerySpec,
        result: QueryResult?,
        error: com.safedb.query.QueryCoreError?,
    ) {
        runCatching {
            queryStore.addHistory(
                HistoryEntry(
                    id = UUID.randomUUID().toString(),
                    connectionId = connectionId,
                    connectionName = connectionName,
                    spec = spec,
                    rowCount = result?.rowCount ?: 0,
                    warnings = result?.warnings ?: error?.warnings.orEmpty(),
                    error = error?.message,
                    timestamp = Instant.now().epochSecond.toString(),
                ),
            )
        }
    }

    private class AdapterQueryRunner(private val adapter: Adapter) : QueryRunner {
        override suspend fun explain(compiled: CompiledQuery): ExplainResult =
            Adapter.explainWithTimeout(adapter, compiled)

        override suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): Outcome<QueryResult> =
            runCatching { adapter.executeQuery(compiled, timeoutMs) }
                .fold(
                    onSuccess = { Outcome.ok(it) },
                    onFailure = { Outcome.err(it.message ?: it.toString()) },
                )
    }
}
