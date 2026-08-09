package com.safedb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.safedb.connection.DIALECTS
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.TransportSecurityMode
import com.safedb.ui.components.BannerKind
import com.safedb.ui.components.MessageBanner
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import com.safedb.viewmodel.ConnectionsViewModel
import kotlinx.coroutines.launch

// Leave a little vertical headroom beyond Material's 56 dp minimum. An exact
// 56 dp constraint can clip glyph descenders on scaled desktop displays.
private val CompactFieldHeight = 60.dp

@Composable
private fun CompactFieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun CompactFieldPlaceholder(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun ConnectionForm(
    connectionsViewModel: ConnectionsViewModel,
    existingConnection: ConnectionDef? = null,
    onSaved: (ConnectionDef, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = remember(existingConnection?.id) { ConnectionFormState(existingConnection) }
    val scope = rememberCoroutineScope()

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
                form.testResult =
                    connectionsViewModel.testConnection(
                        form.buildDef(),
                        form.passwordForOperation(),
                    )
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
                val credentialMaterialChanged =
                    form.original?.credentialFingerprint()?.let {
                        it != def.credentialFingerprint()
                    } ?: true
                if (form.isEditing) {
                    connectionsViewModel.updateConnection(def, form.passwordForOperation())
                } else {
                    connectionsViewModel.createConnection(
                        def,
                        form.passwordForOperation() ?: "",
                    )
                }
                form.password = ""
                form.showPassword = false
                onSaved(def, credentialMaterialChanged)
            } catch (error: Exception) {
                form.formError = error.message ?: error.toString()
            } finally {
                form.saving = false
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                ConnectionStringInput(form)
                OrDetailsDivider()
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 780.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            MainConnectionFields(form)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            AdvancedConnectionFields(form)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            Box(Modifier.weight(1.15f)) { MainConnectionFields(form) }
                            VerticalDivider(
                                modifier = Modifier.fillMaxHeight(),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Box(Modifier.weight(0.95f)) { AdvancedConnectionFields(form) }
                        }
                    }
                }

                form.parseWarnings.forEach { warning ->
                    MessageBanner(text = warning, kind = BannerKind.WARNING)
                }
                form.testResult?.let {
                    MessageBanner(text = "Connected - $it", kind = BannerKind.SUCCESS)
                }
                form.testError?.let { MessageBanner(text = it, kind = BannerKind.ERROR) }
                form.formError?.let { MessageBanner(text = it, kind = BannerKind.ERROR) }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryButton(onClick = ::handleTest, enabled = !form.testing && !form.saving) {
                    if (form.testing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (form.testing) "Testing..." else "Test Connection")
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrimaryButton(onClick = ::handleSave, enabled = !form.testing && !form.saving) {
                        if (form.saving) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            when {
                                form.saving -> "Saving..."
                                form.isEditing -> "Save Changes"
                                else -> "Save Connection"
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStringInput(form: ConnectionFormState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Connection string",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = form.connectionString,
                onValueChange = {
                    form.connectionString = it
                    form.resetResultState()
                },
                modifier = Modifier.weight(1f).height(CompactFieldHeight),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = {
                    CompactFieldPlaceholder("postgresql://readonly:password@host:5432/database")
                },
                isError = form.parseError != null,
            )
            PrimaryButton(onClick = form::applyParsedInput) { Text("Apply") }
        }
        Text(
            "Apply a connection string to automatically fill the fields below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        form.parseError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun OrDetailsDivider() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            "or enter connection details",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun MainConnectionFields(form: ConnectionFormState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = form.name,
            onValueChange = form::updateName,
            modifier = Modifier.fillMaxWidth().height(CompactFieldHeight),
            textStyle = MaterialTheme.typography.bodyMedium,
            label = { CompactFieldLabel("Name") },
            placeholder = { CompactFieldPlaceholder("e.g. Production Replica") },
            singleLine = true,
        )
        Text("Database type", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DIALECTS.forEach { entry ->
                SegmentButton(
                    text = entry.label,
                    selected = form.dialect == entry.value,
                    onClick = { form.selectDialect(entry.value) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ResponsiveFieldRow {
            OutlinedTextField(
                value = form.host,
                onValueChange = form::handleHostInput,
                modifier = Modifier.weight(2f).height(CompactFieldHeight),
                textStyle = MaterialTheme.typography.bodyMedium,
                label = { CompactFieldLabel("Host") },
                placeholder = { CompactFieldPlaceholder("e.g. replica.internal.acme.io") },
                singleLine = true,
            )
            OutlinedTextField(
                value = form.port.toString().takeUnless { form.port == 0 } ?: "",
                onValueChange = form::handlePortInput,
                modifier = Modifier.weight(1f).height(CompactFieldHeight),
                textStyle = MaterialTheme.typography.bodyMedium,
                label = { CompactFieldLabel("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        OutlinedTextField(
            value = form.database,
            onValueChange = form::updateDatabase,
            modifier = Modifier.fillMaxWidth().height(CompactFieldHeight),
            textStyle = MaterialTheme.typography.bodyMedium,
            label = { CompactFieldLabel("Database") },
            placeholder = { CompactFieldPlaceholder("e.g. acme_prod") },
            singleLine = true,
        )
        ResponsiveFieldRow {
            OutlinedTextField(
                value = form.username,
                onValueChange = form::updateUsername,
                modifier = Modifier.weight(1f).height(CompactFieldHeight),
                textStyle = MaterialTheme.typography.bodyMedium,
                label = { CompactFieldLabel("Username") },
                placeholder = { CompactFieldPlaceholder("e.g. readonly") },
                singleLine = true,
            )
            Box(Modifier.weight(1f)) { PasswordField(form) }
        }
    }
}

@Composable
private fun PasswordField(form: ConnectionFormState) {
    if (form.isEditing && !form.passwordChangeEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OutlinedTextField(
                value = "Saved password",
                onValueChange = {},
                modifier = Modifier.fillMaxWidth().height(CompactFieldHeight),
                readOnly = true,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                label = { CompactFieldLabel("Password") },
                trailingIcon = { PlainTextAction("Change", onClick = form::enablePasswordChange) },
            )
            if (form.credentialMaterialChanged()) {
                Text(
                    "Re-enter or change the saved password for connection or driver parameter changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OutlinedTextField(
                value = form.password,
                onValueChange = form::updatePassword,
                modifier = Modifier.fillMaxWidth().height(CompactFieldHeight),
                textStyle = MaterialTheme.typography.bodyMedium,
                label = { CompactFieldLabel("Password") },
                placeholder = { CompactFieldPlaceholder("Enter password") },
                singleLine = true,
                visualTransformation =
                    if (form.showPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    Icon(
                        imageVector =
                            if (form.showPassword) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                        contentDescription =
                            if (form.showPassword) "Hide password" else "Show password",
                        modifier =
                            Modifier.size(18.dp).clickable {
                                form.showPassword = !form.showPassword
                            },
                    )
                },
            )
            if (form.isEditing) {
                PlainTextAction("Keep saved password", onClick = form::keepSavedPassword)
            }
        }
    }
}

@Composable
private fun AdvancedConnectionFields(form: ConnectionFormState) {
    var expanded by remember(form.connectionId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector =
                    if (expanded) Icons.Filled.KeyboardArrowDown
                    else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription =
                    if (expanded) "Collapse advanced settings" else "Expand advanced settings",
                modifier = Modifier.size(20.dp),
            )
            Text(
                "Advanced settings",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (expanded) {
            TransportSecurityDropdown(form)
            if (
                form.dialect == Dialect.Oracle &&
                    form.transportMode != TransportSecurityMode.Disabled
            ) {
                OutlinedTextField(
                    value = form.oracleWalletLocation,
                    onValueChange = form::updateOracleWallet,
                    modifier = Modifier.fillMaxWidth().height(CompactFieldHeight),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    label = { CompactFieldLabel("Oracle wallet location") },
                    singleLine = true,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "Driver parameters",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Optional JDBC driver properties",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            form.driverProperties.forEachIndexed { index, property ->
                DriverPropertyRow(form, index, property)
            }
            form.driverPropertyError()?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            PlainTextAction(
                text = "Add parameter",
                icon = Icons.Filled.Add,
                onClick = form::addDriverProperty,
            )
        }
    }
}

@Composable
private fun TransportSecurityDropdown(form: ConnectionFormState) {
    var expanded by remember { mutableStateOf(false) }
    val options = transportOptionsFor(form.dialect)
    val displayedMode = displayedTransportMode(form.dialect, form.transportMode)
    val selected = options.first { it.value == displayedMode }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Transport security", style = MaterialTheme.typography.labelLarge)
        Box {
            Surface(
                modifier =
                    Modifier.fillMaxWidth().height(CompactFieldHeight).clickable {
                        expanded = true
                    },
                shape = RoundedCornerShape(3.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (selected.recommended) "${selected.label} (recommended)"
                        else selected.label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = "Choose transport security",
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (option.recommended) "${option.label} (recommended)"
                                else option.label
                            )
                        },
                        onClick = {
                            form.changeTransportMode(option.value)
                            expanded = false
                        },
                    )
                }
            }
        }
        Text(
            selected.description,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (
                    displayedMode == TransportSecurityMode.EncryptOnly ||
                        displayedMode == TransportSecurityMode.Disabled
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

@Composable
private fun DriverPropertyRow(
    form: ConnectionFormState,
    index: Int,
    property: DriverPropertyDraft,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 390.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = property.name,
                    onValueChange = { form.updateDriverPropertyName(index, it) },
                    modifier = Modifier.fillMaxWidth().height(CompactFieldHeight),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    label = { CompactFieldLabel("Parameter") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = property.value,
                        onValueChange = { form.updateDriverPropertyValue(index, it) },
                        modifier = Modifier.weight(1f).height(CompactFieldHeight),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        label = { CompactFieldLabel("Value") },
                        singleLine = true,
                    )
                    ParameterDeleteButton { form.removeDriverProperty(index) }
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = property.name,
                    onValueChange = { form.updateDriverPropertyName(index, it) },
                    modifier = Modifier.weight(1f).height(CompactFieldHeight),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    label = { CompactFieldLabel("Parameter") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = property.value,
                    onValueChange = { form.updateDriverPropertyValue(index, it) },
                    modifier = Modifier.weight(1f).height(CompactFieldHeight),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    label = { CompactFieldLabel("Value") },
                    singleLine = true,
                )
                ParameterDeleteButton { form.removeDriverProperty(index) }
            }
        }
    }
}

@Composable
private fun ParameterDeleteButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(38.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = "Remove parameter",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ResponsiveFieldRow(
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 430.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = content,
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
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
    val action = com.safedb.ui.theme.SafeDbTheme.colors.actionPrimary
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier =
            modifier
                .background(if (selected) action.copy(alpha = 0.08f) else Color.Transparent, shape)
                .border(1.dp, if (selected) action else MaterialTheme.colorScheme.outline, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) action else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlainTextAction(
    text: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        modifier =
            Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = com.safedb.ui.theme.SafeDbTheme.colors.actionPrimary,
        )
    }
}
