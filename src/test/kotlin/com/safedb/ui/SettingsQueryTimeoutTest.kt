package com.safedb.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import com.safedb.loadAppVersion
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.SettingsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class SettingsQueryTimeoutTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun timeoutDraftParsesOnlyWholeSecondsInRange() {
        assertEquals(30, parseQueryTimeoutSeconds("30"))
        assertEquals(45, parseQueryTimeoutSeconds(" 45 "))
        assertEquals(1, parseQueryTimeoutSeconds("1"))
        assertEquals(120, parseQueryTimeoutSeconds("120"))
        assertNull(parseQueryTimeoutSeconds("0"))
        assertNull(parseQueryTimeoutSeconds("121"))
        assertNull(parseQueryTimeoutSeconds(""))
        assertNull(parseQueryTimeoutSeconds("10.5"))
        assertNull(parseQueryTimeoutSeconds("slow"))
        assertNull(parseQueryTimeoutSeconds("-5"))
        assertNull(parseQueryTimeoutSeconds("999"))
        assertNull(parseQueryTimeoutSeconds("1.5"))
        assertNull(parseQueryTimeoutSeconds("abc"))
    }

    @Test
    fun timeoutErrorMessageUsesTheColumnWidth() {
        val viewModel = settingsViewModel()
        withSettingsPanel(viewModel, width = 850, height = 555) { scene ->
            scene.commitTimeoutDraftByBlur("121")
            val field = scene.secondsField()
            val message = scene.requireTimeoutError()
            assertTrue(
                message.boundsInRoot.width > field.boundsInRoot.width + 80f,
                "error was constrained to the field width: message=${message.boundsInRoot} " +
                    "field=${field.boundsInRoot}\n${scene.dumpSettings()}",
            )
            assertTrue(
                message.boundsInRoot.height <= 40f,
                "error wrapped onto too many lines: ${message.boundsInRoot}\n${scene.dumpSettings()}",
            )
            assertTrue(
                message.boundsInRoot.top + 0.5f >= field.boundsInRoot.bottom - 8f,
                "error was not below the field: message=${message.boundsInRoot} " +
                    "field=${field.boundsInRoot}",
            )
            val fieldError =
                if (field.config.contains(SemanticsProperties.Error)) {
                    field.config[SemanticsProperties.Error]
                } else {
                    ""
                }
            assertTrue(
                fieldError.contains(QUERY_TIMEOUT_RANGE_ERROR) ||
                    field.label().contains(QUERY_TIMEOUT_RANGE_ERROR),
                "field was not associated with the range error: error='$fieldError' " +
                    "label='${field.label()}'",
            )
        }
    }

    @Test
    fun enterCommitsAValidTimeoutAndClearsTheError() {
        val viewModel = settingsViewModel()
        withSettingsPanel(viewModel, width = 850, height = 555) { scene ->
            scene.commitTimeoutDraftByEnter("0")
            scene.requireTimeoutError()
            scene.commitTimeoutDraftByEnter("45")
            assertNull(scene.findContaining(QUERY_TIMEOUT_RANGE_ERROR))
            assertNull(scene.findByTag(QUERY_TIMEOUT_ERROR_TAG))
            assertEquals(45, viewModel.settings.value.queryTimeoutSeconds)
        }
    }

    @Test
    fun clickingTheDialogBodyCommitsTheTimeoutDraft() {
        val viewModel = settingsViewModel()
        withSettingsPanel(viewModel, width = 850, height = 555) { scene ->
            scene.focusAndSetTimeout("abc")
            scene.click(scene.requireExact("Appearance").boundsInRoot.center)
            scene.settle()
            scene.settle()
            scene.assertTimeoutErrorVisible("abc")
            assertEquals(10, viewModel.settings.value.queryTimeoutSeconds)
            scene.focusAndSetTimeout("45")
            scene.click(scene.requireExact("Settings").boundsInRoot.center)
            scene.settle()
            scene.settle()
            assertNull(scene.findContaining(QUERY_TIMEOUT_RANGE_ERROR))
            assertNull(scene.findByTag(QUERY_TIMEOUT_ERROR_TAG))
            assertEquals(45, viewModel.settings.value.queryTimeoutSeconds)
        }
    }

    @Test
    fun settingsMenuShowsThePackagedAppVersion() {
        withSettingsPanel(settingsViewModel(), width = 1000, height = 900) { scene ->
            scene.requireExact("Version ${loadAppVersion()}")
        }
    }
}

private fun settingsViewModel() =
    SettingsViewModel(FakeSafeDbServiceSupport(), CoroutineScope(Dispatchers.Unconfined))

private fun withSettingsPanel(
    viewModel: SettingsViewModel,
    width: Int,
    height: Int,
    body: (ImageComposeScene) -> Unit,
) {
    withComposeScene(
        width = width,
        height = height,
        content = {
            SafeDbTheme(isDark = false) {
                SettingsPanel(
                    open = true,
                    viewModel = viewModel,
                    connections = emptyList(),
                    onDefaultLocationChanged = { _, _ -> },
                    onDefaultLocationCleared = {},
                    onClose = {},
                )
            }
        },
        body = body,
    )
}

private fun ImageComposeScene.focusAndSetTimeout(value: String) {
    secondsField().config[SemanticsActions.RequestFocus].action!!.invoke()
    settle()
    secondsField().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))
    settle()
}

private fun ImageComposeScene.commitTimeoutDraftByBlur(value: String) {
    focusAndSetTimeout(value)
    click(requireExact("Appearance").boundsInRoot.center)
    settle()
    settle()
}

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
private fun ImageComposeScene.commitTimeoutDraftByEnter(value: String) {
    focusAndSetTimeout(value)
    sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
    sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
    settle()
    settle()
}

private fun ImageComposeScene.assertTimeoutErrorVisible(invalid: String) {
    val error = requireTimeoutError()
    val field = secondsField()
    val close = requireExact("Close")
    assertTrue(
        error.isInsideWindow(850, 555),
        "error was not visible for '$invalid': error=${error.boundsInRoot} " +
            "field=${field.boundsInRoot} close=${close.boundsInRoot}\n${dumpSettings()}",
    )
    assertTrue(
        field.label().contains(QUERY_TIMEOUT_RANGE_ERROR) ||
            error.label().contains(QUERY_TIMEOUT_RANGE_ERROR),
        "error message missing for '$invalid': field='${field.label()}' error='${error.label()}'",
    )
    assertTrue(
        field.boundsInRoot.bottom <= close.boundsInRoot.top + 0.5f,
        "timeout field ran into Close for '$invalid': field=${field.boundsInRoot} " +
            "close=${close.boundsInRoot}",
    )
}

private fun ImageComposeScene.secondsField(): SemanticsNode =
    requireByTag(QUERY_TIMEOUT_SECONDS_TAG)

private fun ImageComposeScene.requireTimeoutError(): SemanticsNode =
    requireByTag(QUERY_TIMEOUT_ERROR_TAG)

private fun ImageComposeScene.dumpSettings(): String =
    allSemanticsNodes()
        .filter { it.label().isNotEmpty() || it.config.contains(SemanticsProperties.Error) }
        .joinToString("\n") { node ->
            val error =
                if (node.config.contains(SemanticsProperties.Error)) {
                    " error='${node.config[SemanticsProperties.Error]}'"
                } else {
                    ""
                }
            "'${node.label()}' ${node.boundsInRoot}$error"
        }
