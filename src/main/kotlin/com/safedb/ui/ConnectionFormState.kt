package com.safedb.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.safedb.connection.ConnectionStringParseError
import com.safedb.connection.DIALECTS
import com.safedb.connection.DatabaseLocation
import com.safedb.connection.inferLocation
import com.safedb.connection.isLocalHost
import com.safedb.connection.parseConnectionString
import com.safedb.connection.transportPresetForLocation
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import java.util.UUID

enum class EntryPath {
    Unset,
    String,
    Guided,
}

enum class FormStep {
    Choose,
    StringInput,
    Location,
    Credentials,
}

class ConnectionFormState {
    var entryPath by mutableStateOf(EntryPath.Unset)
        private set
    var formStep by mutableStateOf(FormStep.Choose)
        private set
    var location by mutableStateOf<DatabaseLocation?>(null)
        private set
    var parsedFromString by mutableStateOf(false)
        private set
    var connectionString by mutableStateOf("")
    var parseError by mutableStateOf<String?>(null)
        private set
    var parseWarnings by mutableStateOf<List<String>>(emptyList())
        private set
    var transportOverridden by mutableStateOf(false)
        private set
    var portIsAuto by mutableStateOf(true)
        private set

    var name by mutableStateOf("")
    var dialect by mutableStateOf(Dialect.Postgres)
        private set
    var host by mutableStateOf("localhost")
        private set
    var port by mutableStateOf(5432)
        private set
    var database by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var showPassword by mutableStateOf(false)
    var transportMode by mutableStateOf(TransportSecurityMode.VerifyIdentity)
    var caPem by mutableStateOf("")
    var oracleWalletLocation by mutableStateOf("")
        private set

    var testing by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var testResult by mutableStateOf<String?>(null)
    var testError by mutableStateOf<String?>(null)
    var formError by mutableStateOf<String?>(null)

    fun resetResultState() {
        testResult = null
        testError = null
        formError = null
    }

    fun clearConnectionStringState() {
        connectionString = ""
        parseError = null
        parseWarnings = emptyList()
        parsedFromString = false
    }

    fun resetToChoose() {
        entryPath = EntryPath.Unset
        formStep = FormStep.Choose
        password = ""
        showPassword = false
        clearConnectionStringState()
        resetResultState()
    }

    fun choosePath(path: EntryPath) {
        entryPath = path
        formStep = if (path == EntryPath.String) FormStep.StringInput else FormStep.Location
        resetResultState()
    }

    fun switchToGuided() {
        entryPath = EntryPath.Guided
        formStep = FormStep.Location
        clearConnectionStringState()
    }

    fun switchToString() {
        entryPath = EntryPath.String
        formStep = FormStep.StringInput
        parseError = null
    }

    fun applyTransportSecurity(security: TransportSecurity) {
        transportMode = security.mode
        caPem = security.caPem.orEmpty()
        oracleWalletLocation = security.oracleWalletLocation.orEmpty()
    }

    fun isRemoteHost(value: String): Boolean =
        value.trim().isNotEmpty() && !isLocalHost(value)

    fun recommendedLocationForCurrentHost(): DatabaseLocation =
        if (isRemoteHost(host)) DatabaseLocation.Cloud else DatabaseLocation.Local

    fun applyLocationPreset(nextLocation: DatabaseLocation) {
        val presetLocation =
            if (nextLocation == DatabaseLocation.Local && isRemoteHost(host)) {
                recommendedLocationForCurrentHost()
            } else {
                nextLocation
            }
        location = presetLocation
        transportOverridden = false
        applyTransportSecurity(transportPresetForLocation(presetLocation))
        formStep = FormStep.Credentials
        resetResultState()
    }

    fun reapplyRecommendedSettings() {
        val currentLocation = location ?: return
        val presetLocation =
            if (currentLocation == DatabaseLocation.Organization) {
                currentLocation
            } else {
                recommendedLocationForCurrentHost()
            }
        location = presetLocation
        transportOverridden = false
        applyTransportSecurity(transportPresetForLocation(presetLocation))
        resetResultState()
    }

    fun markTransportManual() {
        transportOverridden = true
        resetResultState()
    }

    fun applyTroubleshootingCa(value: String) {
        caPem = value
        transportMode = TransportSecurityMode.VerifyCa
        transportOverridden = true
    }

    fun handleOracleWalletInput(value: String) {
        oracleWalletLocation = value
        transportOverridden = true
        resetResultState()
    }

    fun selectDialect(nextDialect: Dialect) {
        val previous = DIALECTS.firstOrNull { it.value == dialect }
        dialect = nextDialect
        val entry = DIALECTS.firstOrNull { it.value == nextDialect }
        if (entry != null && (portIsAuto || port == previous?.defaultPort)) {
            port = entry.defaultPort
            portIsAuto = true
        }
        resetResultState()
    }

    fun handlePortInput(value: String) {
        port = value.toIntOrNull() ?: port
        portIsAuto = false
        resetResultState()
    }

    fun handleHostInput(nextHost: String) {
        host = nextHost
        if (!transportOverridden && location != null && location != DatabaseLocation.Organization) {
            val nextLocation = inferLocation(nextHost)
            if (nextLocation != location) {
                location = nextLocation
                applyTransportSecurity(transportPresetForLocation(nextLocation))
            }
        }
        resetResultState()
    }

    fun applyParsedInput() {
        parseError = null
        parseWarnings = emptyList()
        resetResultState()

        try {
            val parsed = parseConnectionString(connectionString)
            parsedFromString = true
            dialect = parsed.dialect
            host = parsed.host
            port = parsed.port
            portIsAuto = true
            database = parsed.database
            username = parsed.username
            password = parsed.password.orEmpty()
            applyTransportSecurity(parsed.transportSecurity)
            transportOverridden = false
            location = parsed.inferredLocation
            parseWarnings = parsed.warnings
            connectionString = parsed.sanitizedInput
            formStep = FormStep.Credentials
        } catch (error: ConnectionStringParseError) {
            parseError = error.message
        } catch (_: Exception) {
            parseError = "This connection string could not be parsed."
        }
    }

    fun buildDef(): ConnectionDef =
        ConnectionDef(
            version = 2,
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "$dialect $host:$port" },
            dialect = dialect,
            host = host.trim(),
            port = port,
            database = database.trim(),
            username = username.trim(),
            transportSecurity = TransportSecurity(
                mode = transportMode,
                caPem = caPem.trim().ifEmpty { null },
                oracleWalletLocation = oracleWalletLocation.trim().ifEmpty { null },
                legacyImplicit = false,
            ),
        )

    fun validateForm(): String? {
        if (host.trim().isEmpty()) return "Host is required"
        if (database.trim().isEmpty()) return "Database is required"
        if (username.trim().isEmpty()) return "Username is required"
        if (port !in 1..65535) return "Port must be between 1 and 65535"
        if (dialect == Dialect.Oracle &&
            transportMode != TransportSecurityMode.Disabled &&
            oracleWalletLocation.trim().isEmpty()
        ) {
            return "Oracle TCPS requires a wallet location"
        }
        return null
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

    fun updateName(value: String) {
        name = value
        resetResultState()
    }
}
