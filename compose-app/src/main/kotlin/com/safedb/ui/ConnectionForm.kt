package com.safedb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.safedb.connection.ConnectionErrorContext
import com.safedb.connection.ConnectionErrorKind
import com.safedb.connection.ConnectionStringParseError
import com.safedb.connection.DIALECTS
import com.safedb.connection.DatabaseLocation
import com.safedb.connection.classifyConnectionError
import com.safedb.connection.inferLocation
import com.safedb.connection.isLocalHost
import com.safedb.connection.parseConnectionString
import com.safedb.connection.securityLabelForMode
import com.safedb.connection.transportPresetForLocation
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import com.safedb.service.SafeDbService
import com.safedb.ui.components.BannerKind
import com.safedb.ui.components.MessageBanner
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import java.util.UUID
import kotlinx.coroutines.launch

private enum class EntryPath {
    Unset,
    String,
    Guided,
}

private enum class FormStep {
    Choose,
    StringInput,
    Location,
    Credentials,
}

private data class LocationCard(
    val id: DatabaseLocation,
    val title: String,
    val subtitle: String,
)

private val LOCATION_CARDS = listOf(
    LocationCard(DatabaseLocation.Local, "On this computer", "Local development or testing"),
    LocationCard(DatabaseLocation.Cloud, "Online or in the cloud", "AWS, Google, Supabase, etc."),
    LocationCard(DatabaseLocation.Organization, "From my organization", "Work or school database"),
)

@Composable
fun ConnectionForm(
    service: SafeDbService,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var entryPath by remember { mutableStateOf(EntryPath.Unset) }
    var formStep by remember { mutableStateOf(FormStep.Choose) }
    var location by remember { mutableStateOf<DatabaseLocation?>(null) }
    var parsedFromString by remember { mutableStateOf(false) }
    var connectionString by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<String?>(null) }
    var parseWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var transportOverridden by remember { mutableStateOf(false) }
    var portIsAuto by remember { mutableStateOf(true) }

    var name by remember { mutableStateOf("") }
    var dialect by remember { mutableStateOf(Dialect.Postgres) }
    var host by remember { mutableStateOf("localhost") }
    var port by remember { mutableStateOf(5432) }
    var database by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var transportMode by remember { mutableStateOf(TransportSecurityMode.VerifyIdentity) }
    var caPem by remember { mutableStateOf("") }
    var oracleWalletLocation by remember { mutableStateOf("") }

    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val selectedDialect = DIALECTS.firstOrNull { it.value == dialect }
    val securityLabel = securityLabelForMode(transportMode, host)
    val errorClassification = testError?.let {
        classifyConnectionError(
            it,
            ConnectionErrorContext(location = location, remoteHost = !isLocalHost(host)),
        )
    }

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
        val previous = selectedDialect
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

    fun handleTest() {
        val validationError = validateForm()
        if (validationError != null) {
            testError = validationError
            return
        }
        testing = true
        testResult = null
        testError = null
        scope.launch {
            try {
                val def = buildDef()
                testResult = service.testConnection(def, password)
            } catch (error: Exception) {
                testError = error.message ?: error.toString()
            } finally {
                testing = false
            }
        }
    }

    fun handleSave() {
        val validationError = validateForm()
        if (validationError != null) {
            formError = validationError
            return
        }
        saving = true
        formError = null
        scope.launch {
            try {
                val def = buildDef()
                service.saveConnection(def, password)
                password = ""
                showPassword = false
                onSaved()
            } catch (error: Exception) {
                formError = error.message ?: error.toString()
            } finally {
                saving = false
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("New Connection", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Connect to a database. Credentials are stored in your OS keychain.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (formStep != FormStep.Choose) {
                    PlainTextAction("Change path", onClick = { resetToChoose() })
                }
            }

            when (formStep) {
                FormStep.Choose -> ChoosePathStep(
                    onChooseString = { choosePath(EntryPath.String) },
                    onChooseGuided = { choosePath(EntryPath.Guided) },
                )

                FormStep.StringInput -> StringInputStep(
                    connectionString = connectionString,
                    onConnectionStringChange = { connectionString = it },
                    parseError = parseError,
                    onSwitchToGuided = { switchToGuided() },
                    onContinue = { applyParsedInput() },
                )

                FormStep.Location -> LocationStep(
                    selectedLocation = location,
                    onSelectLocation = { applyLocationPreset(it) },
                    onSwitchToString = { switchToString() },
                )

                FormStep.Credentials -> CredentialsStep(
                    parsedFromString = parsedFromString,
                    selectedDialectLabel = selectedDialect?.label ?: dialect.name,
                    host = host,
                    port = port,
                    database = database,
                    securityLabelText = securityLabel.text,
                    parseWarnings = parseWarnings,
                    location = location,
                    isRemoteHost = isRemoteHost(host),
                    onApplyCloudDefaults = { applyLocationPreset(DatabaseLocation.Cloud) },
                    name = name,
                    onNameChange = { name = it; resetResultState() },
                    dialect = dialect,
                    onSelectDialect = { selectDialect(it) },
                    hostValue = host,
                    onHostChange = { handleHostInput(it) },
                    portValue = port.toString(),
                    onPortChange = { handlePortInput(it) },
                    databaseValue = database,
                    onDatabaseChange = { database = it; resetResultState() },
                    username = username,
                    onUsernameChange = { username = it; resetResultState() },
                    password = password,
                    onPasswordChange = { password = it; resetResultState() },
                    showPassword = showPassword,
                    onToggleShowPassword = { showPassword = !showPassword },
                    dialectIsOracle = dialect == Dialect.Oracle,
                    transportMode = transportMode,
                    oracleWalletLocation = oracleWalletLocation,
                    onOracleWalletChange = { handleOracleWalletInput(it) },
                    transportOverridden = transportOverridden,
                    onReapplyRecommended = { reapplyRecommendedSettings() },
                    caPem = caPem,
                    onCaPemChange = { caPem = it },
                    onTransportModeChange = { transportMode = it },
                    onTransportManualChange = { markTransportManual() },
                    testResult = testResult,
                    testError = testError,
                    errorClassification = errorClassification,
                    onTroubleshootingCaChange = { applyTroubleshootingCa(it) },
                    formError = formError,
                    testing = testing,
                    saving = saving,
                    onTest = { handleTest() },
                    onCancel = onCancel,
                    onSave = { handleSave() },
                )
            }
        }
    }
}

@Composable
private fun ChoosePathStep(
    onChooseString: () -> Unit,
    onChooseGuided: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PathCard(
            modifier = Modifier.weight(1f),
            title = "I have a connection string",
            subtitle = "Paste from your host or dashboard",
            onClick = onChooseString,
        )
        PathCard(
            modifier = Modifier.weight(1f),
            title = "Help me set it up",
            subtitle = "Local, cloud, or work database",
            onClick = onChooseGuided,
        )
    }
}

@Composable
private fun PathCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PanelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)
    val action = com.safedb.ui.theme.SafeDbTheme.colors.actionPrimary
    val onAction = com.safedb.ui.theme.SafeDbTheme.colors.onActionPrimary
    val background = when {
        selected -> action
        hovered -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surface
    }
    val borderColor = when {
        selected -> action
        hovered -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outline
    }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides if (selected) {
            onAction
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(background)
                .border(1.dp, borderColor, shape)
                .hoverable(interactionSource)
                .clickable(onClick = onClick)
                .padding(16.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SegmentButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)
    val action = com.safedb.ui.theme.SafeDbTheme.colors.actionPrimary
    val onAction = com.safedb.ui.theme.SafeDbTheme.colors.onActionPrimary
    val background = when {
        selected -> action
        hovered -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surface
    }
    val borderColor = when {
        selected -> action
        hovered -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outline
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) onAction else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlainTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (hovered) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun InlineIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = modifier
            .size(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (hovered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun StringInputStep(
    connectionString: String,
    onConnectionStringChange: (String) -> Unit,
    parseError: String?,
    onSwitchToGuided: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = connectionString,
            onValueChange = onConnectionStringChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Connection string") },
            minLines = 5,
            textStyle = MaterialTheme.typography.bodySmall,
            placeholder = { Text("postgresql://readonly:password@host:5432/database") },
        )

        if (parseError != null) {
            MessageBanner(text = parseError, kind = BannerKind.ERROR) {
                PlainTextAction("Use guided setup", onClick = onSwitchToGuided)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlainTextAction("Use guided setup", onClick = onSwitchToGuided)
            PrimaryButton(onClick = onContinue) {
                Text("Continue")
            }
        }
    }
}

@Composable
private fun LocationStep(
    selectedLocation: DatabaseLocation?,
    onSelectLocation: (DatabaseLocation) -> Unit,
    onSwitchToString: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Database location", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (card in LOCATION_CARDS) {
                val selected = selectedLocation == card.id
                PanelButton(
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectLocation(card.id) },
                    selected = selected,
                ) {
                    Column {
                            Text(card.title, style = MaterialTheme.typography.labelLarge)
                            Text(
                                card.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.75f),
                            )
                    }
                }
            }
        }
        PlainTextAction("I have a connection string", onClick = onSwitchToString)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CredentialsStep(
    parsedFromString: Boolean,
    selectedDialectLabel: String,
    host: String,
    port: Int,
    database: String,
    securityLabelText: String,
    parseWarnings: List<String>,
    location: DatabaseLocation?,
    isRemoteHost: Boolean,
    onApplyCloudDefaults: () -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    dialect: Dialect,
    onSelectDialect: (Dialect) -> Unit,
    hostValue: String,
    onHostChange: (String) -> Unit,
    portValue: String,
    onPortChange: (String) -> Unit,
    databaseValue: String,
    onDatabaseChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    showPassword: Boolean,
    onToggleShowPassword: () -> Unit,
    dialectIsOracle: Boolean,
    transportMode: TransportSecurityMode,
    oracleWalletLocation: String,
    onOracleWalletChange: (String) -> Unit,
    transportOverridden: Boolean,
    onReapplyRecommended: () -> Unit,
    caPem: String,
    onCaPemChange: (String) -> Unit,
    onTransportModeChange: (TransportSecurityMode) -> Unit,
    onTransportManualChange: () -> Unit,
    testResult: String?,
    testError: String?,
    errorClassification: com.safedb.connection.ConnectionErrorClassification?,
    onTroubleshootingCaChange: (String) -> Unit,
    formError: String?,
    testing: Boolean,
    saving: Boolean,
    onTest: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (parsedFromString) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                tonalElevation = 0.dp,
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SummaryChip(selectedDialectLabel)
                        SummaryChip("$host:$port")
                        SummaryChip(database.ifEmpty { "No database" })
                        SummaryChip(securityLabelText)
                    }
                    for (warning in parseWarnings) {
                        Text(
                            warning,
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (location == DatabaseLocation.Local && isRemoteHost) {
            MessageBanner(
                text = "This host is not local. Apply cloud security defaults before testing or saving.",
                kind = BannerKind.WARNING,
            ) {
                PlainTextAction("Apply cloud defaults", onClick = onApplyCloudDefaults)
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name") },
            placeholder = { Text("My Production DB") },
        )

        Text("Database type", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (entry in DIALECTS) {
                SegmentButton(
                    text = entry.label,
                    selected = dialect == entry.value,
                    onClick = { onSelectDialect(entry.value) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = hostValue,
                onValueChange = onHostChange,
                modifier = Modifier.weight(2f),
                label = { Text("Host") },
                placeholder = { Text("localhost") },
            )
            OutlinedTextField(
                value = portValue,
                onValueChange = onPortChange,
                modifier = Modifier.weight(1f),
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        OutlinedTextField(
            value = databaseValue,
            onValueChange = onDatabaseChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Database") },
            placeholder = { Text("mydb") },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                modifier = Modifier.weight(1f),
                label = { Text("Username") },
                placeholder = { Text("readonly") },
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                modifier = Modifier.weight(1f),
                label = { Text("Password") },
                placeholder = { Text("Password") },
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    InlineIconButton(
                        icon = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                        onClick = onToggleShowPassword,
                    )
                },
            )
        }

        if (dialectIsOracle && transportMode != TransportSecurityMode.Disabled) {
            OutlinedTextField(
                value = oracleWalletLocation,
                onValueChange = onOracleWalletChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Oracle wallet location") },
            )
        }

        if (transportOverridden && location != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Transport settings differ from the recommended preset.",
                    style = MaterialTheme.typography.bodySmall,
                )
                PlainTextAction("Reapply recommended settings", onClick = onReapplyRecommended)
            }
        }

        ConnectionAdvancedPanel(
            transportMode = transportMode,
            onTransportModeChange = onTransportModeChange,
            caPem = caPem,
            onCaPemChange = onCaPemChange,
            onManualChange = onTransportManualChange,
        )

        MessageBanner(
            text = "For best safety, connect as a dedicated read-only database role.",
            kind = BannerKind.INFO,
        )

        if (testResult != null) {
            MessageBanner(text = "Connected - $testResult", kind = BannerKind.SUCCESS)
        }

        if (testError != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MessageBanner(text = testError, kind = BannerKind.ERROR)
                when (errorClassification?.kind) {
                    ConnectionErrorKind.UntrustedCa -> SslTroubleshootingPanel(
                        caPem = caPem,
                        onCaPemChange = onTroubleshootingCaChange,
                    )

                    ConnectionErrorKind.HostnameMismatch -> MessageBanner(
                        text = "Certificate hostname does not match this host. Verify the host value is correct, or use a certificate issued for this hostname.",
                        kind = BannerKind.INFO,
                    )

                    ConnectionErrorKind.CertificateRequired -> MessageBanner(
                        text = if (dialectIsOracle) {
                            "This server requires encrypted transport. Provide an Oracle wallet location in the field above, then test again."
                        } else {
                            "This server requires encrypted transport. Enable SSL/TLS transport or provide the required certificate, then test again."
                        },
                        kind = BannerKind.INFO,
                    )

                    ConnectionErrorKind.Unknown -> {
                        if (errorClassification.showTroubleshooting) {
                            SslTroubleshootingPanel(
                                caPem = caPem,
                                onCaPemChange = onTroubleshootingCaChange,
                            )
                        }
                    }

                    null -> Unit
                }
            }
        }

        if (formError != null) {
            MessageBanner(text = formError, kind = BannerKind.ERROR)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryButton(
                onClick = onTest,
                enabled = !testing && !saving,
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (testing) "Testing..." else "Test Connection")
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlainTextAction(
                    "Cancel",
                    onClick = onCancel,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                PrimaryButton(
                    onClick = onSave,
                    enabled = !saving && !testing,
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (saving) "Saving..." else "Save Connection")
                }
            }
        }
    }
}

@Composable
private fun SummaryChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 0.dp,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun SslTroubleshootingPanel(
    caPem: String,
    onCaPemChange: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Your organization may require a security file.",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Paste the CA certificate PEM below, then test again. This will use certificate verification.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = caPem,
                onValueChange = onCaPemChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("CA certificate PEM") },
                minLines = 4,
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
