package com.safedb.service

import com.safedb.explore.ExploreRecipe
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
import com.safedb.store.RecipeStore
import com.safedb.store.SettingsStore
import java.time.Instant
import java.util.UUID

internal fun interface QuerySessionFactory {
    suspend fun open(def: ConnectionDef, password: String): QuerySession
}

internal interface ConnectedAdapter {
    suspend fun test(): String
    suspend fun introspect(): Schema
    suspend fun explain(compiled: CompiledQuery): ExplainResult
    suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): QueryResult
    fun close()
}

internal fun interface AdapterFactory {
    suspend fun connect(def: ConnectionDef, password: String): ConnectedAdapter
}

private object DefaultAdapterFactory : AdapterFactory {
    override suspend fun connect(def: ConnectionDef, password: String): ConnectedAdapter {
        val adapter = Adapter.connect(def, password)
        return object : ConnectedAdapter {
            override suspend fun test(): String = adapter.test()
            override suspend fun introspect(): Schema = Adapter.introspectWithTimeout(adapter)
            override suspend fun explain(compiled: CompiledQuery): ExplainResult =
                Adapter.explainWithTimeout(adapter, compiled)

            override suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): QueryResult =
                adapter.executeQuery(compiled, timeoutMs)

            override fun close() = adapter.close()
        }
    }
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
    private val adapterFactory: AdapterFactory = DefaultAdapterFactory,
    private val recipeStore: RecipeStore? = null,
) : SafeDbService {

    constructor(
        configStore: ConfigStore,
        queryStore: QueryStore,
        settingsStore: SettingsStore,
        recipeStore: RecipeStore? = null,
    ) : this(configStore, queryStore, settingsStore, null, DefaultAdapterFactory, recipeStore)

    override suspend fun testConnection(def: ConnectionDef, password: String): String {
        def.validate().getOrThrow()
        val adapter = adapterFactory.connect(def, password)
        return try {
            adapter.test()
        } finally {
            adapter.close()
        }
    }

    override suspend fun createConnection(def: ConnectionDef, password: String): ConnectionDef {
        require(configStore.get(def.id) == null) { "Connection already exists" }
        persistConnection(def, password)
        return def
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
        val adapter = adapterFactory.connect(def, password)
        return try {
            adapter.introspect()
        } finally {
            adapter.close()
        }
    }

    override suspend fun runQuery(request: QueryRunRequest): QueryResult {
        val def = configStore.get(request.connectionId) ?: throw IllegalArgumentException("Connection not found")
        val password = SecretsManager.passwordForDefinition(def).getOrThrow()
        val settings = settingsStore.load()
        val session = querySessionFactory?.open(def, password) ?: openDefaultQuerySession(def, password)
        return try {
            when (
                val outcome = runQueryCore(
                    session.runner,
                    def,
                    request.spec,
                    session.schema,
                    settings,
                    request.force,
                )
            ) {
                is QueryCoreOutcome.Success -> {
                    recordHistory(
                        request.connectionId,
                        def.name,
                        outcome.historySpec,
                        outcome.result,
                        null,
                        outcome.riskAssessment,
                        outcome.riskDecision,
                    )
                    outcome.result
                }
                is QueryCoreOutcome.Failure -> {
                    recordHistory(
                        request.connectionId,
                        def.name,
                        outcome.error.historySpec ?: request.spec,
                        null,
                        outcome.error,
                        (outcome.error.error as? com.safedb.query.QueryError.RiskGate)?.assessment,
                        (outcome.error.error as? com.safedb.query.QueryError.RiskGate)?.decision,
                    )
                    throw QueryFailureException(outcome.error)
                }
            }
        } finally {
            session.close()
        }
    }

    private suspend fun openDefaultQuerySession(def: ConnectionDef, password: String): QuerySession {
        val adapter = adapterFactory.connect(def, password)
        val schema = try {
            adapter.introspect()
        } catch (error: Throwable) {
            runCatching { adapter.close() }.onFailure(error::addSuppressed)
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

    override suspend fun listExploreRecipes(): List<ExploreRecipe> = recipeStore?.list().orEmpty()

    override suspend fun saveExploreRecipe(recipe: ExploreRecipe) {
        (recipeStore ?: error("Recipe store is unavailable")).save(recipe)
    }

    override suspend fun deleteExploreRecipe(id: String) {
        (recipeStore ?: error("Recipe store is unavailable")).delete(id)
    }

    override suspend fun importExploreRecipe(json: String, nowEpochSec: String): ExploreRecipe =
        (recipeStore ?: error("Recipe store is unavailable")).importJson(json, nowEpochSec)

    override suspend fun exportExploreRecipe(recipe: ExploreRecipe): String =
        (recipeStore ?: error("Recipe store is unavailable")).exportJson(recipe)

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
        riskAssessment: com.safedb.query.QueryRiskAssessment? = null,
        riskDecision: com.safedb.query.QueryRiskDecision? = null,
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
                    riskScoreVersion = riskAssessment?.scoreVersion,
                    riskSeverity = riskAssessment?.severity?.name,
                    riskSignalCodes = riskAssessment?.signals.orEmpty().map { it.code.name }.distinct(),
                    riskGateState = riskDecision?.state?.name,
                ),
            )
        }
    }

    private class AdapterQueryRunner(private val adapter: ConnectedAdapter) : QueryRunner {
        override suspend fun explain(compiled: CompiledQuery): ExplainResult =
            adapter.explain(compiled)

        override suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): Outcome<QueryResult> =
            runCatching { adapter.executeQuery(compiled, timeoutMs) }
                .fold(
                    onSuccess = { Outcome.ok(it) },
                    onFailure = { Outcome.err(it.message ?: it.toString()) },
                )
    }
}
