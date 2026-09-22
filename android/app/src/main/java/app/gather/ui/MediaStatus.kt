package app.gather.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun MediaLoading(
    label: String,
    modifier: Modifier = Modifier,
    light: Boolean = true,
    onRetry: (() -> Unit)? = null,
) {
    val color = if (light) Color(0xFFEDEFE9) else MaterialTheme.colorScheme.primary
    var stalled by remember(label) { mutableStateOf(false) }
    var attempt by remember(label) { mutableIntStateOf(0) }
    LaunchedEffect(label, attempt) {
        if (onRetry != null) {
            delay(15_000)
            stalled = true
        }
    }
    Row(
        modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (stalled) GenoaIcon("clock", null, Modifier.size(20.dp), color)
        else CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
        Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
        if (stalled)
            TextButton({
                stalled = false
                attempt++
                onRetry?.invoke()
            }) {
                Text("Retry", color = color)
            }
    }
}
