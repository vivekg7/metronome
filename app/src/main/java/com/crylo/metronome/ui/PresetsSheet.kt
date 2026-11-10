package com.crylo.metronome.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crylo.metronome.R
import com.crylo.metronome.audio.MetronomeController

/** Saved presets, in the user's order: the same list serves as a setlist for a gig or practice session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsSheet(controller: MetronomeController, onDismiss: () -> Unit) {
    val presets by controller.presets.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.presets), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { naming = true }) {
                    Icon(painterResource(R.drawable.ic_add), null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.save_current))
                }
            }
            if (presets.isEmpty()) {
                Text(
                    stringResource(R.string.presets_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            LazyColumn {
                itemsIndexed(presets) { i, preset ->
                    val s = preset.settings
                    ListItem(
                        headlineContent = { Text(preset.name) },
                        supportingContent = {
                            Text(stringResource(R.string.preset_summary, s.bpm, s.timeSignature.toString(), subdivisionName(s.subdivision)))
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { controller.movePreset(i, -1) }, enabled = i > 0) {
                                    Icon(painterResource(R.drawable.ic_arrow_up), stringResource(R.string.move_up))
                                }
                                IconButton(onClick = { controller.movePreset(i, 1) }, enabled = i < presets.lastIndex) {
                                    Icon(painterResource(R.drawable.ic_arrow_down), stringResource(R.string.move_down))
                                }
                                IconButton(onClick = { controller.deletePreset(i) }) {
                                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().clickable {
                            controller.loadPreset(preset)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.save_current)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text(stringResource(R.string.preset_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.savePreset(name.trim())
                    naming = false
                }, enabled = name.isNotBlank()) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
