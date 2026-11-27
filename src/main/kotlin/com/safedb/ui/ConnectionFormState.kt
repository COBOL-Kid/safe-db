package com.safedb.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.safedb.connection.ConnectionStringParseError
import com.safedb.connection.DIALECTS
import com.safedb.connection.formatConnectionString
import com.safedb.connection.inferLocation
import com.safedb.connection.parseConnectionString
import com.safedb.connection.sanitizeBestEffortConnectionString
import com.safedb.connection.transportPresetForLocation
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.DriverProperty
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import com.safedb.model.displayName
import com.safedb.model.validateDriverProperties
import com.safedb.secrets.MissingPasswordReason
import com.safedb.secrets.PasswordNotFoundException
import java.util.UUID

data class DriverPropertyDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val value: String = "",
)

class ConnectionFormState(val original: ConnectionDef? = null) {
    val connectionId: String = original?.id ?: UUID.randomUUID().toString()
    val isEditing: Boolean = original != null

    var connectionString by mutableStateOf(original?.let(::formatConnectionString).orEmpty())
    var parseError by mutableStateOf<String?>(null)
        private set

    var parseWarnings by mutableStateOf<List<String>>(emptyList())
        private set

    var transportOverridden by mutableStateOf(original != null)
        private set

    private var transportSettingsChanged = false
    private var portIsAuto by mutableStateOf(original == null)

    var name by mutableStateOf(original?.name.orEmpty())
    var dialect by mutableStateOf(original?.dialect ?: Dialect.Postgres)
        private set

    var host by mutableStateOf(original?.host ?: "localhost")
        private set

    var port by mutableStateOf(original?.port ?: 5432)
        private set

    var database by mutableStateOf(original?.database.orEmpty())
    var username by mutableStateOf(original?.username.orEmpty())
    var password by mutableStateOf("")
    var passwordChangeEnabled by mutableStateOf(original == null)
        private set

    var savedPasswordMissing by mutableStateOf(false)
        private set

    var passwordFocusRequest by mutableStateOf(0)
        private set

    var showPassword by mutableStateOf(false)
    var transportMode by
        mutableStateOf(original?.transportSecurity?.mode ?: TransportSecurityMode.Disabled)
    var oracleWalletLocation by
        mutableStateOf(original?.transportSecurity?.oracleWalletLocation.orEmpty())
        private set

    val driverProperties =
        mutableStateListOf<DriverPropertyDraft>().apply {
            addAll(
                original?.driverProperties.orEmpty().map {
                    DriverPropertyDraft(name = it.name, value = it.value)
                },
            )
        }

    var testing by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var testResult by mutableStateOf<String?>(null)
    var testError by mutableStateOf<String?>(null)
    var formError by mutableStateOf<String?>(null)

    init {
        if (original == null) applyRecommendedTransport()
    }

    fun resetResultState() {
        testResult = null
        testError = null
        formError = null
        parseError = null
    }

    fun updateConnectionString(value: String) {
        connectionString = value
        parseWarnings = emptyList()
        resetResultState()
    }

    fun applyParsedInput() {
        parseWarnings = emptyList()
        resetResultState()
        try {
            val parsed = parseConnectionString(connectionString)
            dialect = parsed.dialect
            host = parsed.host
            port = parsed.port
            portIsAuto = true
            database = parsed.database
            if (parsed.username.isNotEmpty() || !isEditing) {
                username = parsed.username
            }
            if (parsed.password != null || !isEditing) {
                password = parsed.password.orEmpty()
                passwordChangeEnabled = true
            }
            applyTransportSecurity(parsed.transportSecurity)
            transportSettingsChanged = true
            transportOverridden = false
            driverProperties.clear()
            driverProperties.addAll(
                parsed.driverProperties.map {
                    DriverPropertyDraft(name = it.name, value = it.value)
                },
            )
            parseWarnings =
                parsed.warnings +
                    listOfNotNull(
                        transportCompatibilityWarning(
                            parsed.dialect,
                            parsed.transportSecurity.mode,
                        ),
                    )
            connectionString = parsed.sanitizedInput
        } catch (error: ConnectionStringParseError) {
            connectionString = sanitizeBestEffortConnectionString(connectionString)
            parseError = error.message
        } catch (_: Exception) {
            connectionString = sanitizeBestEffortConnectionString(connectionString)
            parseError = "This connection string could not be parsed."
        }
    }

    fun enablePasswordChange() {
        password = ""
        passwordChangeEnabled = true
        showPassword = false
        passwordFocusRequest += 1
        resetResultState()
    }

    private fun requirePasswordReentry(message: String) {
        savedPasswordMissing = true
        enablePasswordChange()
        testError = message
    }

    fun handleTestError(error: Throwable) {
        resetResultState()
        if (error is PasswordNotFoundException && isEditing && !passwordChangeEnabled) {
            when (error.reason) {
                MissingPasswordReason.DisabledOverride ->
                    testError = error.message ?: error.toString()
                MissingPasswordReason.MemoryStore ->
                    requirePasswordReentry(
                        "Password not found because credentials are held in memory only for this session. Enter the password above to test or save.",
                    )
                MissingPasswordReason.NotStored ->
                    requirePasswordReentry(
                        "Password not found for this connection. Enter the password above to test or save.",
                    )
            }
        } else {
            testError = error.message ?: error.toString()
        }
    }

    fun keepSavedPassword() {
        if (!isEditing || savedPasswordMissing) return
        password = ""
        passwordChangeEnabled = false
        showPassword = false
        resetResultState()
    }

    fun selectDialect(nextDialect: Dialect) {
        val previous = DIALECTS.firstOrNull { it.value == dialect }
        if (nextDialect != dialect) transportSettingsChanged = true
        dialect = nextDialect
        transportMode = normalizedTransportMode(nextDialect, transportMode)
        val entry = DIALECTS.firstOrNull { it.value == nextDialect }
        if (entry != null && (portIsAuto || port == previous?.defaultPort)) {
            port = entry.defaultPort
            portIsAuto = true
        }
        resetResultState()
    }

    fun handlePortInput(value: String) {
        val digits = value.filter(Char::isDigit).take(5)
        port = digits.toIntOrNull() ?: 0
        portIsAuto = false
        resetResultState()
    }

    fun handleHostInput(value: String) {
        host = value
        if (!transportOverridden) applyRecommendedTransport()
        resetResultState()
    }

    fun changeTransportMode(value: TransportSecurityMode) {
        if (value != transportMode) transportSettingsChanged = true
        transportMode = value
        transportOverridden = true
        resetResultState()
    }

    fun applyTransportSecurity(security: TransportSecurity) {
        transportMode = security.mode
        oracleWalletLocation = security.oracleWalletLocation.orEmpty()
    }

    private fun applyRecommendedTransport() {
        applyTransportSecurity(transportPresetForLocation(inferLocation(host)))
    }

    fun updateOracleWallet(value: String) {
        if (value != oracleWalletLocation) transportSettingsChanged = true
        oracleWalletLocation = value
        transportOverridden = true
        resetResultState()
    }

    fun addDriverProperty() {
        if (driverProperties.size < com.safedb.model.MAX_DRIVER_PROPERTIES) {
            driverProperties += DriverPropertyDraft()
            resetResultState()
        }
    }

    fun updateDriverPropertyName(index: Int, value: String) {
        driverProperties[index] = driverProperties[index].copy(name = value)
        resetResultState()
    }

    fun updateDriverPropertyValue(index: Int, value: String) {
        driverProperties[index] = driverProperties[index].copy(value = value)
        resetResultState()
    }

    fun removeDriverProperty(index: Int) {
        driverProperties.removeAt(index)
        resetResultState()
    }

    private fun resolvedName(): String =
        name.trim().ifEmpty { "${dialect.displayName()} ${host.trim()}:$port" }

    fun buildDef(): ConnectionDef {
        val preserveLoadedTransport = original != null && !transportSettingsChanged
        val persistedWallet =
            if (preserveLoadedTransport) {
                original.transportSecurity.oracleWalletLocation
            } else if (
                dialect == Dialect.Oracle && transportMode != TransportSecurityMode.Disabled
            ) {
                oracleWalletLocation.trim().ifEmpty { null }
            } else {
                null
            }
        return ConnectionDef(
            id = connectionId,
            name = resolvedName(),
            dialect = dialect,
            host = host.trim(),
            port = port,
            database = database.trim(),
            username = username.trim(),
            transportSecurity =
                TransportSecurity(
                    mode = transportMode,
                    oracleWalletLocation = persistedWallet,
                ),
            driverProperties = driverProperties.map { DriverProperty(it.name.trim(), it.value) },
        )
    }

    fun credentialMaterialChanged(): Boolean =
        original?.credentialFingerprint()?.let { it != buildDef().credentialFingerprint() } ?: true

    fun passwordForOperation(): String? =
        if (!isEditing || passwordChangeEnabled) password else null

    fun driverPropertyError(): String? =
        validateDriverProperties(
                dialect,
                driverProperties.map { DriverProperty(it.name.trim(), it.value) },
            )
            .exceptionOrNull()
            ?.message

    fun validateForm(existingConnections: List<ConnectionDef> = emptyList()): String? {
        if (host.trim().isEmpty()) return "Host is required"
        if (database.trim().isEmpty()) return "Database is required"
        if (username.trim().isEmpty()) return "Username is required"
        if (port !in 1..65535) return "Port must be between 1 and 65535"
        val resolvedName = resolvedName()
        if (
            existingConnections.any {
                it.id != connectionId && it.name.trim().equals(resolvedName, ignoreCase = true)
            }
        ) {
            return "A connection named '$resolvedName' already exists"
        }
        if (
            dialect == Dialect.Oracle &&
                transportMode != TransportSecurityMode.Disabled &&
                oracleWalletLocation.trim().isEmpty()
        ) {
            return "Oracle TCPS requires a wallet location"
        }
        driverPropertyError()?.let {
            return it
        }
        if (isEditing && credentialMaterialChanged() && !passwordChangeEnabled) {
            return "Connection or driver parameter changes require the saved password to be changed or re-entered"
        }
        return null
    }

    fun updateName(value: String) {
        name = value
        resetResultState()
    }

    fun updateDatabase(value: String) {
        database = value
        resetResultState()
    }

    fun updateUsername(value: String) {
        username = value
        resetResultState()
    }

    fun updatePassword(value: String) {
        password = value
        resetResultState()
    }
}
