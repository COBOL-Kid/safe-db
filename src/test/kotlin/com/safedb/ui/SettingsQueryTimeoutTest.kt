package com.safedb.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
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

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun invalidTimeoutShowsErrorMessageBelowTheField() {
        val viewModel = settingsViewModel()
        withSettingsPanel(viewModel, width = 850, height = 555) { scene ->
            listOf("0", "121", "999", "-5", "abc", "1.5", "").forEach { invalid ->
                scene.commitTimeoutDraft(invalid)
                val error = scene.requireTimeoutError()
                val field = scene.secondsField()
                val close = scene.requireExact("Close")
                assertTrue(
                    error.isInsideWindow(850, 555),
                    "error was not visible for '$invalid': error=${error.boundsInRoot} " +
                        "field=${field.boundsInRoot} close=${close.boundsInRoot}\n${scene.dumpSettings()}",
                )
                assertTrue(
                    field.label().contains(QUERY_TIMEOUT_RANGE_ERROR) ||
                        error.label().contains(QUERY_TIMEOUT_RANGE_ERROR),
                    "error message missing from the field for '$invalid': ${field.label()}",
                )
                assertTrue(
                    field.boundsInRoot.bottom <= close.boundsInRoot.top + 0.5f,
                    "timeout field ran into Close for '$invalid': field=${field.boundsInRoot} " +
                        "close=${close.boundsInRoot}",
                )
                assertEquals(10, viewModel.settings.value.queryTimeoutSeconds)
            }
        }
    }

    @Test
    fun validTimeoutClearsTheErrorAndSaves() {
        val viewModel = settingsViewModel()
        withSettingsPanel(viewModel, width = 850, height = 555) { scene ->
            scene.commitTimeoutDraft("0")
            scene.requireTimeoutError()
            scene.commitTimeoutDraft("45")
            assertNull(scene.findContaining(QUERY_TIMEOUT_RANGE_ERROR))
            assertEquals(45, viewModel.settings.value.queryTimeoutSeconds)
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

private fun ImageComposeScene.commitTimeoutDraft(value: String) {
    secondsField().config[SemanticsActions.RequestFocus].action!!.invoke()
    settle()
    secondsField().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))
    settle()
    val blurTarget =
        listOf("Light", "Dark", "Standard", "Cautious", "Flexible", "Off")
            .mapNotNull { findExact(it) }
            .firstOrNull { it.boundsInRoot.width >= 8f && it.boundsInRoot.height >= 8f }
            ?: error("no visible focusable to commit the timeout draft\n${dumpSettings()}")
    click(blurTarget.boundsInRoot.center)
    settle()
    settle()
}

private fun ImageComposeScene.secondsField(): SemanticsNode =
    findByTag(QUERY_TIMEOUT_SECONDS_TAG)
        ?: allSemanticsNodes().first { node ->
            node.config.contains(SemanticsActions.SetText) && node.label().contains("Seconds")
        }

private fun ImageComposeScene.requireTimeoutError(): SemanticsNode =
    findContaining(QUERY_TIMEOUT_RANGE_ERROR)
        ?: allSemanticsNodes().firstOrNull { node ->
            node.config.contains(SemanticsProperties.Error) &&
                node.config[SemanticsProperties.Error].contains(QUERY_TIMEOUT_RANGE_ERROR)
        }
        ?: error("timeout error message missing\n${dumpSettings()}")

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
