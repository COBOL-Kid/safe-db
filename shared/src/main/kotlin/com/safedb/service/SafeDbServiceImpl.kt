package com.safedb.service

import com.safedb.adapter.Adapter
import com.safedb.explore.ExploreRecipe
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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

internal fun interface QuerySessionFactory {
    suspend fun open(def: ConnectionDef, password: String): QuerySession
}

private data class CachedSchema(val fingerprint: String, val schema: Schema)

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

/** Adapts a live JDBC [Adapter] with introspection/explain timeouts for the service layer. */
private class TimeoutAwareAdapter(private val adapter: Adapter) : ConnectedAdapter {
    override suspend fun test(): String = adapter.test()

    override suspend fun introspect(): Schema = Adapter.introspectWithTimeout(adapter)

    override suspend fun explain(compiled: CompiledQuery): ExplainResult =
        Adapter.explainWithTimeout(adapter, compiled)

    override suspend fun executeQuery(compiled: CompiledQuery, timeoutMs: Int): QueryResult =
        adapter.executeQuery(compiled, timeoutMs)

    override fun close() = adapter.close()
}

private object DefaultAdapterFactory : AdapterFactory {
    override suspend fun connect(def: ConnectionDef, password: String): ConnectedAdapter =
        TimeoutAwareAdapter(Adapter.connect(def, password))
}

internal class QuerySession(
    val schema: Schema,
    val runner: QueryRunner,
    private val onClose: suspend () -> Unit = {},
) {
    suspend fun close() = onClose()
}

class SafeDbServiceImpl
internal constructor(
    private val configStore: ConfigStore,
    private val queryStore: QueryStore,
    private val settingsStore: SettingsStore,
    private val querySessionFactory: QuerySessionFactory?,
    private val adapterFactory: AdapterFactory = DefaultAdapterFactory,
    private val recipeStore: RecipeStore? = null,
) : SafeDbService {
    private val schemaCache = ConcurrentHashMap<String, CachedSchema>()

    constructor(
        configStore: ConfigStore,
        queryStore: QueryStore,
        settingsStore: SettingsStore,
        recipeStore: RecipeStore? = null,
    ) : this(configStore, queryStore, settingsStore, null, DefaultAdapterFactory, recipeStore)

    override suspend fun testConnection(def: ConnectionDef, password: String?): String {
        def.validate().getOrThrow()
        val resolvedPassword =
            password
                ?: run {
                    val previous =
                        configStore.get(def.id)
                            ?: throw IllegalArgumentException(
                                "A password is required for a new connection"
                            )
                    if (previous.credentialFingerprint() != def.credentialFingerprint()) {
                        throw IllegalArgumentException(
                            "Connection changes require the password to be re-entered before testing"
                        )
                    }
                    SecretsManager.passwordForDefinition(previous).getOrThrow()
                }
        val adapter = adapterFactory.connect(def, resolvedPassword)
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
        invalidateSchemaCache(id)
    }

    override suspend fun lockCredentials() {
        SecretsManager.lockCredentials()
        clearSchemaCache()
    }

    override suspend fun getSchema(connectionId: String): Schema {
        val def =
            configStore.get(connectionId) ?: throw IllegalArgumentException("Connection not found")
        cachedSchema(def)?.let {
            return it
        }
        val password = SecretsManager.passwordForDefinition(def).getOrThrow()
        val adapter = adapterFactory.connect(def, password)
        return try {
            loadAndCacheSchema(def, adapter)
        } finally {
            adapter.close()
        }
    }

    override suspend fun runQuery(request: QueryRunRequest): QueryRunResult {
        val def =
            configStore.get(request.connectionId)
                ?: throw IllegalArgumentException("Connection not found")
        val password = SecretsManager.passwordForDefinition(def).getOrThrow()
        val settings = settingsStore.load()
        val session =
            querySessionFactory?.open(def, password) ?: openDefaultQuerySession(def, password)
        return try {
            when (
                val outcome =
                    runQueryCore(
                        session.runner,
                        def,
                        request.spec,
                        session.schema,
                        settings,
                        request.confirmation,
                    )
            ) {
                is QueryCoreOutcome.Success -> {
                    recordHistory(
                        request.connectionId,
                        def.name,
                        outcome.historySpec,
                        outcome.result,
                        null,
                        outcome.riskEvaluation,
                    )
                    QueryRunResult(outcome.result, outcome.riskEvaluation)
                }
                is QueryCoreOutcome.Failure -> {
                    recordHistory(
                        request.connectionId,
                        def.name,
                        outcome.error.historySpec ?: request.spec,
                        null,
                        outcome.error,
                        outcome.error.riskEvaluation,
                    )
                    throw QueryFailureException(outcome.error)
                }
            }
        } finally {
            session.close()
        }
    }

    private suspend fun openDefaultQuerySession(
        def: ConnectionDef,
        password: String,
    ): QuerySession {
        val adapter = adapterFactory.connect(def, password)
        val schema =
            try {
                cachedSchema(def) ?: loadAndCacheSchema(def, adapter)
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

    private fun cachedSchema(def: ConnectionDef): Schema? {
        val cached = schemaCache[def.id] ?: return null
        return cached.schema.takeIf { cached.fingerprint == def.credentialFingerprint() }
    }

    private suspend fun loadAndCacheSchema(def: ConnectionDef, adapter: ConnectedAdapter): Schema {
        val schema = adapter.introspect()
        schemaCache[def.id] = CachedSchema(def.credentialFingerprint(), schema)
        return schema
    }

    private fun invalidateSchemaCache(connectionId: String) {
        schemaCache.remove(connectionId)
    }

    private fun clearSchemaCache() {
        schemaCache.clear()
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
        if (
            previous != null &&
                previous.credentialFingerprint() != def.credentialFingerprint() &&
                password == null
        ) {
            throw IllegalArgumentException(
                "Endpoint or transport changes require the password to be re-entered"
            )
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
        invalidateSchemaCache(def.id)
    }

    private fun recordHistory(
        connectionId: String,
        connectionName: String,
        spec: QuerySpec,
        result: QueryResult?,
        error: com.safedb.query.QueryCoreError?,
        riskEvaluation: com.safedb.query.QueryRiskEvaluation? = null,
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
                    riskScoreVersion = riskEvaluation?.finalAssessment?.scoreVersion,
                    riskStaticScore = riskEvaluation?.staticAssessment?.score,
                    riskFinalScore = riskEvaluation?.finalAssessment?.score,
                    riskSeverity = riskEvaluation?.finalAssessment?.severity?.name,
                    riskSignalCodes =
                        riskEvaluation
                            ?.finalAssessment
                            ?.signals
                            .orEmpty()
                            .map { it.code.name }
                            .distinct(),
                    riskUncertaintyCodes =
                        riskEvaluation
                            ?.finalAssessment
                            ?.uncertainties
                            .orEmpty()
                            .map { it.code }
                            .distinct(),
                    riskPlanStatus = riskEvaluation?.planStatus?.name,
                    riskPlanReason = riskEvaluation?.planUnavailableReason?.name,
                    riskGateState = riskEvaluation?.decision?.state?.name,
                    riskOptimizerCost = riskEvaluation?.optimizerCost,
                    riskConfirmationCodes =
                        riskEvaluation
                            ?.confirmationRequirement
                            ?.confirmation
                            ?.reasonCodes
                            .orEmpty()
                            .map { it.name }
                            .sorted(),
                    riskConfirmationAccepted =
                        riskEvaluation?.confirmationRequirement?.let {
                            riskEvaluation.confirmationAccepted
                        },
                )
            )
        }
    }

    private class AdapterQueryRunner(private val adapter: ConnectedAdapter) : QueryRunner {
        override suspend fun explain(compiled: CompiledQuery): ExplainResult =
            adapter.explain(compiled)

        override suspend fun executeQuery(
            compiled: CompiledQuery,
            timeoutMs: Int,
        ): Outcome<QueryResult> =
            try {
                Outcome.ok(adapter.executeQuery(compiled, timeoutMs))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Outcome.err(error.message ?: error.toString())
            }
    }
}
