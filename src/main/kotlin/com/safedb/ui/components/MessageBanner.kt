package com.safedb.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.safedb.ui.theme.SafeDbTheme

enum class BannerKind {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

@Composable
fun MessageBanner(
    text: String,
    kind: BannerKind = BannerKind.INFO,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val (container, content) = bannerColors(kind)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                color = content,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            action?.invoke()
        }
    }
}

@Composable
private fun bannerColors(kind: BannerKind): Pair<Color, Color> {
    val c = SafeDbTheme.colors
    val s = MaterialTheme.colorScheme
    return when (kind) {
        BannerKind.INFO -> c.infoContainer to c.onInfoContainer
        BannerKind.SUCCESS -> c.successContainer to c.onSuccessContainer
        BannerKind.WARNING -> c.warningContainer to c.onWarningContainer
        BannerKind.ERROR -> s.errorContainer to s.onErrorContainer
    }
}
