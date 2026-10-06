package com.toka.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.toka.app.R
import com.toka.app.data.model.TemplateDTO
import com.toka.app.ui.theme.Pink
import com.toka.app.ui.theme.TextPrimary
import com.toka.app.ui.theme.TextSecondary

/**
 * "Se crea al completar otra tarea": elige la plantilla disparadora y los días de retraso.
 * [candidates] ya viene filtrada (sin la propia ni sus descendientes, para no formar ciclos).
 */
@Composable
fun TriggerPicker(
    candidates: List<TemplateDTO>,
    triggerId: String?,
    delayDays: Int,
    onChange: (triggerId: String?, delayDays: Int) -> Unit
) {
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = Pink.copy(alpha = 0.15f),
        selectedLabelColor = Pink
    )
    Column {
        Text(
            text = stringResource(R.string.create_trigger_label),
            style = MaterialTheme.typography.labelLarge,
            color = TextPrimary
        )
        Text(
            text = stringResource(R.string.create_trigger_hint),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = triggerId == null,
                    onClick = { onChange(null, 0) },
                    label = { Text(stringResource(R.string.create_trigger_none)) },
                    colors = chipColors
                )
            }
            items(candidates, key = { it.id }) { t ->
                FilterChip(
                    selected = triggerId == t.id,
                    onClick = { onChange(t.id, delayDays) },
                    label = { Text(t.name) },
                    colors = chipColors
                )
            }
        }
        if (triggerId != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.create_trigger_delay),
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(0, 1, 2, 3, 7)) { d ->
                    FilterChip(
                        selected = delayDays == d,
                        onClick = { onChange(triggerId, d) },
                        label = { Text(if (d == 0) stringResource(R.string.create_trigger_same_day) else "+$d") },
                        colors = chipColors
                    )
                }
            }
        }
    }
}
