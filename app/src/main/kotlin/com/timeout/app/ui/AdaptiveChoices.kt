package com.timeout.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.timeout.app.ui.theme.DesignTokens
import kotlin.math.max

@Immutable
data class ChoiceOption(val key: String, val label: String, @param:DrawableRes val icon: Int? = null)

/** The same accessible choice surface for filters, presets, days, and editor modes. */
@Composable
fun AdaptiveChoices(options: List<ChoiceOption>, selected: Set<String>, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier, multiple: Boolean = false, enabled: Boolean = true) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val interactive = enabled && LocalDestinationActive.current
    BoxWithConstraints(modifier.fillMaxWidth().then(if (multiple) Modifier else Modifier.selectableGroup())) {
        val measuredWidth = remember(options, style, density) {
            options.maxOfOrNull { option ->
                val text = with(density) { textMeasurer.measure(option.label, style, softWrap = false).size.width.toDp().value }
                text + DesignTokens.choiceContentPaddingDp * 2 +
                    if (option.icon != null) DesignTokens.choiceIconWidthDp + DesignTokens.choiceIconGapDp else 0
            } ?: 0f
        }
        val minimum = max(DesignTokens.choiceMinimumWidthDp.toFloat(), measuredWidth)
        val rows = remember(options.size, maxWidth, minimum) {
            choiceRowSizes(options.size, maxWidth.value, minimum, DesignTokens.choiceGapDp.toFloat())
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DesignTokens.choiceGapDp.dp)) {
            var start = 0
            rows.forEach { count ->
                val row = options.subList(start, start + count)
                start += count
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DesignTokens.choiceGapDp.dp)) {
                    row.forEach { option -> key(option.key) {
                        val checked = option.key in selected
                        FilterChip(checked, { onSelect(option.key) }, enabled = interactive,
                            modifier = Modifier.weight(1f).heightIn(min = DesignTokens.choiceMinimumHeightDp.dp),
                            shape = RoundedCornerShape(DesignTokens.choiceCornerRadiusDp.dp),
                            border = BorderStroke(1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface,
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onSurface, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer),
                            label = { Text(option.label, style = style, textAlign = TextAlign.Center) },
                            leadingIcon = option.icon?.let { icon -> { Symbol(icon, Modifier.size(DesignTokens.choiceIconWidthDp.dp),
                                tint = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary) } })
                    } }
                }
            }
        }
    }
}
