package com.toka.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.toka.app.R
import com.toka.app.ui.theme.TextMuted

/**
 * Editor de horas de recordatorio. En vez de un campo de texto "09:00, 20:00",
 * muestra cada hora como chip y las añade/borra con un selector de hora.
 *
 * `value` es la representación que viaja al backend ("HH:MM,HH:MM"), o null si no hay
 * ninguna. Las horas se interpretan como hora local del teléfono.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderTimesField(
    value: String?,
    onValueChange: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val times = remember(value) {
        value?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
    }
    var showPicker by remember { mutableStateOf(false) }
    val pickerState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            times.forEach { time ->
                InputChip(
                    selected = false,
                    onClick = {
                        val remaining = times - time
                        onValueChange(remaining.joinToString(",").ifBlank { null })
                    },
                    label = { Text(time) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.reminders_remove, time)
                        )
                    }
                )
            }
            AssistChip(
                onClick = { showPicker = true },
                label = { Text(stringResource(R.string.reminders_add_hour)) },
                leadingIcon = {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            )
        }
        if (times.isEmpty()) {
            Text(
                text = stringResource(R.string.reminders_none),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.reminders_add_title)) },
            text = {
                TimeInput(state = pickerState)
            },
            confirmButton = {
                TextButton(onClick = {
                    val hhmm = "%02d:%02d".format(pickerState.hour, pickerState.minute)
                    val updated = (times + hhmm).distinct().sorted()
                    onValueChange(updated.joinToString(",").ifBlank { null })
                    showPicker = false
                }) { Text(stringResource(R.string.common_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
