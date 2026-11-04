package com.safedb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.safedb.SchemaSelectionIntent
import com.safedb.SchemaSelectionSource
import com.safedb.model.ColumnInfo
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.TableInfo
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.QueryViewModel
import com.safedb.viewmodel.RecipesViewModel
import com.safedb.viewmodel.SavedQueriesViewModel
import com.safedb.viewmodel.SchemaViewModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class BuilderLargeLimitGuidanceTest {
    @Test
    fun guidanceCopyIsOneShortLine() {
        assertEquals(
            "Large limit: add filters or pick columns to keep queries fast.",
            LARGE_LIMIT_GUIDANCE_TEXT,
        )
        assertFalse(LARGE_LIMIT_GUIDANCE_TEXT.contains('\n'))
    }

    @Test
    fun overlayEndPaddingClearsTheWidestQueryOptionsPanel() {
        assertEquals(16.dp, QueryControlsStartPadding)
        assertEquals(16.dp, QueryOptionsPanelEndPadding)
        assertEquals(256.dp, QueryOptionsPanelMaxWidth)
        assertEquals(8.dp, QueryOptionsPanelGap)
        assertEquals(280.dp, QueryOptionsOverlayEndPadding)
        assertEquals(
            QueryOptionsPanelEndPadding + QueryOptionsPanelMaxWidth + QueryOptionsPanelGap,
            QueryOptionsOverlayEndPadding,
        )
    }

    @Test
    fun guidanceRangeStopsBeforeQueryOptionsAtTypicalWorkspaceWidths() {
        listOf(400f, 640f, 900f, 1100f, 1280f, 1920f).forEach { workspace ->
            val guidance = largeLimitGuidanceRange(workspace)
            val options = queryOptionsPanelRange(workspace)
            assertTrue(
                guidance.end <= options.start,
                "At ${workspace}dp, guidance [${guidance.start}, ${guidance.end}] " +
                    "overlaps options [${options.start}, ${options.end}]",
            )
            assertEquals(QueryControlsStartPadding.value, guidance.start)
            assertEquals(QueryOptionsPanelGap.value, options.start - guidance.end)
        }
    }

    @Test
    fun composedGuidanceStripDoesNotOverlapQueryOptions() {
        listOf(500, 800, 1100, 1400).forEach { width ->
            var bannerLeft = 0f
            var bannerRight = 0f
            var optionsLeft = 0f
            ImageComposeScene(width = width, height = 240, density = Density(1f)) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            modifier =
                                Modifier.align(Alignment.TopEnd)
                                    .padding(
                                        top = QueryControlsVerticalPadding,
                                        end = QueryOptionsPanelEndPadding,
                                    )
                                    .width(QueryOptionsPanelMaxWidth)
                                    .height(80.dp)
                                    .onGloballyPositioned { coords ->
                                        optionsLeft = coords.positionInRoot().x
                                    }
                        )
                        Box(
                            modifier =
                                Modifier.align(Alignment.TopStart)
                                    .padding(
                                        start = QueryControlsStartPadding,
                                        top = QueryControlsVerticalPadding,
                                        end = QueryOptionsOverlayEndPadding,
                                    )
                                    .fillMaxWidth()
                                    .height(36.dp)
                                    .onGloballyPositioned { coords ->
                                        bannerLeft = coords.positionInRoot().x
                                        bannerRight =
                                            coords.positionInRoot().x + coords.size.width.toFloat()
                                    }
                        )
                    }
                }
                .use { scene ->
                    scene.render(0L)
                    assertTrue(
                        bannerRight <= optionsLeft,
                        "At ${width}px, banner [$bannerLeft, $bannerRight] overlaps options at $optionsLeft",
                    )
                }
        }
    }

    @Test
    fun builderScreenRendersTheShortLargeLimitStrip() {
        val previewDir = File("/tmp/safedb-preview")
        previewDir.mkdirs()
        renderBuilderWithAllLimit(
            width = 1400,
            height = 900,
            File(previewDir, "all-limit-banner-wide.png"),
        )
        renderBuilderWithAllLimit(
            width = 960,
            height = 700,
            File(previewDir, "all-limit-banner-narrow.png"),
        )
    }
}

private fun renderBuilderWithAllLimit(width: Int, height: Int, output: File) {
    val scope = CoroutineScope(Dispatchers.Unconfined)
    val table =
        TableInfo(
            schema = "public",
            name = "big_events",
            columns =
                listOf(
                    ColumnInfo("id", "bigint", nullable = false, isIndexed = true),
                    ColumnInfo("event_name", "varchar", nullable = true, isIndexed = false),
                    ColumnInfo("amount", "numeric", nullable = true, isIndexed = false),
                ),
            indexes = emptyList(),
            foreignKeys = emptyList(),
        )
    val service =
        object : FakeSafeDbServiceSupport() {
            override suspend fun getSchema(connectionId: String) = Schema(listOf(table))
        }
    val queryViewModel = QueryViewModel(service, scope)
    val schemaViewModel = SchemaViewModel(service, scope)
    val selection = SchemaSelectionIntent("public", SchemaSelectionSource.User)
    schemaViewModel.load("c1", selection = selection)
    queryViewModel.addTable(table)
    queryViewModel.setUncapped(true)
    val connection =
        ConnectionDef(
            id = "c1",
            name = "mysql-main",
            dialect = Dialect.MySql,
            host = "localhost",
            port = 3306,
            database = "safedb_test",
            username = "readonly",
        )

    ImageComposeScene(width = width, height = height, density = Density(1f)) {
            SafeDbTheme(isDark = true) {
                BuilderScreen(
                    connection = connection,
                    connections = listOf(connection),
                    queryViewModel = queryViewModel,
                    savedQueriesViewModel = SavedQueriesViewModel(service, scope),
                    recipesViewModel = RecipesViewModel(service, scope),
                    schemaViewModel = schemaViewModel,
                    schemaSelection = selection,
                    schemaHistoryError = null,
                    settings = Settings(),
                    sqlBusy = false,
                    onConnectionSelected = {},
                    onSchemaSelected = {},
                    onUnavailableSchemaSelection = {},
                    onDismissSchemaHistoryError = {},
                    onOpenExplore = {},
                    onOpenSettings = {},
                    onApplyRecipe = { _, _ -> },
                )
            }
        }
        .use { scene ->
            val image = scene.render(0L)
            output.writeBytes(checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
            assertEquals(width, image.width)
            assertEquals(height, image.height)
        }
}

private data class OverlayRange(val start: Float, val end: Float)

private fun largeLimitGuidanceRange(workspaceWidth: Float): OverlayRange {
    val start = QueryControlsStartPadding.value
    val end = (workspaceWidth - QueryOptionsOverlayEndPadding.value).coerceAtLeast(start)
    return OverlayRange(start, end)
}

private fun queryOptionsPanelRange(
    workspaceWidth: Float,
    panelWidth: Float = QueryOptionsPanelMaxWidth.value,
): OverlayRange {
    val end = workspaceWidth - QueryOptionsPanelEndPadding.value
    return OverlayRange(end - panelWidth, end)
}
