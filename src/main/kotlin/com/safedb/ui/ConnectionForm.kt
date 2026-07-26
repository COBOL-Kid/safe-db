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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.safedb.connection.ConnectionErrorContext
import com.safedb.connection.ConnectionErrorKind
import com.safedb.connection.DIALECTS
import com.safedb.connection.DatabaseLocation
import com.safedb.connection.classifyConnectionError
import com.safedb.connection.isLocalHost
import com.safedb.connection.securityLabelForMode
import com.safedb.model.Dialect
import com.safedb.model.TransportSecurityMode
import com.safedb.service.SafeDbService
import com.safedb.ui.components.BannerKind
import com.safedb.ui.components.MessageBanner
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import kotlinx.coroutines.launch

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
    val form = remember { ConnectionFormState() }
    val scope = rememberCoroutineScope()
    val selectedDialect = DIALECTS.firstOrNull { it.value == form.dialect }
    val securityLabel = securityLabelForMode(form.transportMode, form.host)
    val errorClassification = form.testError?.let {
        classifyConnectionError(
            it,
            ConnectionErrorContext(location = form.location, remoteHost = !isLocalHost(form.host)),
        )
    }

    fun handleTest() {
        val validationError = form.validateForm()
        if (validationError != null) {
            form.testError = validationError
            return
        }
        form.testing = true
        form.testResult = null
        form.testError = null
        scope.launch {
            try {
                val def = form.buildDef()
                form.testResult = service.testConnection(def, form.password)
            } catch (error: Exception) {
                form.testError = error.message ?: error.toString()
            } finally {
                form.testing = false
            }
        }
    }

    fun handleSave() {
        val validationError = form.validateForm()
        if (validationError != null) {
            form.formError = validationError
            return
        }
        form.saving = true
        form.formError = null
        scope.launch {
            try {
                val def = form.buildDef()
                service.createConnection(def, form.password)
                form.password = ""
                form.showPassword = false
                onSaved()
            } catch (error: Exception) {
                form.formError = error.message ?: error.toString()
            } finally {
                form.saving = false
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
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                if (form.formStep != FormStep.Choose) {
                    PlainTextAction("Change path", onClick = { form.resetToChoose() })
                }
            }

            when (form.formStep) {
                FormStep.Choose -> ChoosePathStep(
                    onChooseString = { form.choosePath(EntryPath.String) },
                    onChooseGuided = { form.choosePath(EntryPath.Guided) },
                )

                FormStep.StringInput -> StringInputStep(
                    connectionString = form.connectionString,
                    onConnectionStringChange = { form.connectionString = it },
                    parseError = form.parseError,
                    onSwitchToGuided = { form.switchToGuided() },
                    onContinue = { form.applyParsedInput() },
                )

                FormStep.Location -> LocationStep(
                    selectedLocation = form.location,
                    onSelectLocation = { form.applyLocationPreset(it) },
                    onSwitchToString = { form.switchToString() },
                )

                FormStep.Credentials -> CredentialsStep(
                    form = form,
                    selectedDialectLabel = selectedDialect?.label ?: form.dialect.name,
                    securityLabelText = securityLabel.text,
                    onApplyCloudDefaults = { form.applyLocationPreset(DatabaseLocation.Cloud) },
                    errorClassification = errorClassification,
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
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 500.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PathCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = "I have a connection string",
                    subtitle = "Paste from your host or dashboard",
                    onClick = onChooseString,
                )
                PathCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = "Help me set it up",
                    subtitle = "Local, cloud, or work database",
                    onClick = onChooseGuided,
                )
            }
        } else {
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
    val shape = RoundedCornerShape(3.dp)
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
                .padding(12.dp),
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
    val shape = RoundedCornerShape(3.dp)
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
            .padding(horizontal = 12.dp, vertical = 7.dp),
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
            .clip(RoundedCornerShape(2.dp))
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
            .size(28.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (hovered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
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
            minLines = 4,
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Database location", style = MaterialTheme.typography.labelLarge)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth < 620.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (card in LOCATION_CARDS) {
                        LocationPanelButton(
                            card = card,
                            selected = selectedLocation == card.id,
                            onClick = { onSelectLocation(card.id) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (card in LOCATION_CARDS) {
                        LocationPanelButton(
                            card = card,
                            selected = selectedLocation == card.id,
                            onClick = { onSelectLocation(card.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        PlainTextAction("I have a connection string", onClick = onSwitchToString)
    }
}

@Composable
private fun LocationPanelButton(
    card: LocationCard,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelButton(
        modifier = modifier,
        onClick = onClick,
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

private data class ResponsiveFormRowItem(
    val weight: Float,
    val minWidth: Dp,
    val content: @Composable () -> Unit,
)

private class ResponsiveFormRowScope {
    val items = mutableListOf<ResponsiveFormRowItem>()

    fun item(
        weight: Float = 1f,
        minWidth: Dp = 0.dp,
        content: @Composable () -> Unit,
    ) {
        items += ResponsiveFormRowItem(weight, minWidth, content)
    }
}

@Composable
private fun ResponsiveFormRow(
    collapseBelow: Dp,
    modifier: Modifier = Modifier,
    content: ResponsiveFormRowScope.() -> Unit,
) {
    val scope = ResponsiveFormRowScope().apply(content)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (maxWidth < collapseBelow) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in scope.items) {
                    item.content()
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (item in scope.items) {
                    Box(
                        modifier = Modifier
                            .weight(item.weight)
                            .widthIn(min = item.minWidth),
                    ) {
                        item.content()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CredentialsStep(
    form: ConnectionFormState,
    selectedDialectLabel: String,
    securityLabelText: String,
    onApplyCloudDefaults: () -> Unit,
    errorClassification: com.safedb.connection.ConnectionErrorClassification?,
    onTest: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val parsedFromString = form.parsedFromString
    val host = form.host
    val port = form.port
    val database = form.database
    val parseWarnings = form.parseWarnings
    val location = form.location
    val isRemoteHost = form.isRemoteHost(form.host)
    val name = form.name
    val dialect = form.dialect
    val hostValue = form.host
    val portValue = form.port.toString()
    val databaseValue = form.database
    val username = form.username
    val password = form.password
    val showPassword = form.showPassword
    val dialectIsOracle = form.dialect == Dialect.Oracle
    val transportMode = form.transportMode
    val oracleWalletLocation = form.oracleWalletLocation
    val transportOverridden = form.transportOverridden
    val caPem = form.caPem
    val testResult = form.testResult
    val testError = form.testError
    val formError = form.formError
    val testing = form.testing
    val saving = form.saving
    val onNameChange = form::updateName
    val onSelectDialect = form::selectDialect
    val onHostChange = form::handleHostInput
    val onPortChange = form::handlePortInput
    val onDatabaseChange = form::updateDatabase
    val onUsernameChange = form::updateUsername
    val onPasswordChange = form::updatePassword
    val onToggleShowPassword = { form.showPassword = !form.showPassword }
    val onOracleWalletChange = form::handleOracleWalletInput
    val onReapplyRecommended = form::reapplyRecommendedSettings
    val onCaPemChange = { value: String -> form.caPem = value }
    val onTransportModeChange = { value: TransportSecurityMode -> form.transportMode = value }
    val onTransportManualChange = form::markTransportManual
    val onTroubleshootingCaChange = form::applyTroubleshootingCa
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

        ResponsiveFormRow(collapseBelow = 520.dp) {
            item(weight = 2f) {
                OutlinedTextField(
                    value = hostValue,
                    onValueChange = onHostChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Host") },
                    placeholder = { Text("localhost") },
                )
            }
            item(weight = 1f, minWidth = 140.dp) {
                OutlinedTextField(
                    value = portValue,
                    onValueChange = onPortChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }

        OutlinedTextField(
            value = databaseValue,
            onValueChange = onDatabaseChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Database") },
            placeholder = { Text("mydb") },
        )

        ResponsiveFormRow(collapseBelow = 520.dp) {
            item(weight = 1f) {
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Username") },
                    placeholder = { Text("readonly") },
                )
            }
            item(weight = 1f) {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
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
