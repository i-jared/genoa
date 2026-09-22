package app.gather.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The shared confirmation/form surface keeps actions readable without oversized alert headings. */
@Composable
fun GenoaDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier =
                Modifier.padding(24.dp)
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .heightIn(
                        max =
                            (androidx.compose.ui.platform.LocalConfiguration.current
                                    .screenHeightDp * 0.85f)
                                .dp
                    )
                    .imePadding(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                Box(Modifier.weight(1f, fill = false)) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
