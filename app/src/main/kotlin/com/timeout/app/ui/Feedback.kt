package com.timeout.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timeout.app.R
import com.timeout.app.ui.theme.LocalSupportingColors

enum class FeedbackKind(val label: String) {
    SUCCESS("Done"), WARNING("Needs attention"), ERROR("Couldn't finish"), INFO("Note")
}

data class FeedbackVisuals(
    override val message: String,
    val kind: FeedbackKind,
    override val duration: SnackbarDuration = if (kind == FeedbackKind.ERROR) SnackbarDuration.Long else SnackbarDuration.Short,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
) : SnackbarVisuals

suspend fun SnackbarHostState.showFeedback(message: String, kind: FeedbackKind) =
    showSnackbar(FeedbackVisuals(message, kind))

@Composable
fun FeedbackSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        val visuals = data.visuals as? FeedbackVisuals ?: FeedbackVisuals(data.visuals.message, FeedbackKind.INFO)
        val supporting = LocalSupportingColors.current
        val scheme = MaterialTheme.colorScheme
        val container = when (visuals.kind) {
            FeedbackKind.SUCCESS -> supporting.successContainer
            FeedbackKind.WARNING -> supporting.warningContainer
            FeedbackKind.ERROR -> scheme.errorContainer
            FeedbackKind.INFO -> scheme.inverseSurface
        }
        val content = when (visuals.kind) {
            FeedbackKind.SUCCESS -> supporting.onSuccess
            FeedbackKind.WARNING -> supporting.onWarning
            FeedbackKind.ERROR -> scheme.onErrorContainer
            FeedbackKind.INFO -> scheme.inverseOnSurface
        }
        val icon = when (visuals.kind) {
            FeedbackKind.SUCCESS -> R.drawable.ic_check
            FeedbackKind.WARNING -> R.drawable.ic_warning
            FeedbackKind.ERROR -> R.drawable.ic_close
            FeedbackKind.INFO -> R.drawable.ic_info
        }
        Snackbar(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "${visuals.kind.label}. ${visuals.message}" },
            shape = RoundedCornerShape(16.dp), containerColor = container, contentColor = content) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(icon, tint = content)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(visuals.kind.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(visuals.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
