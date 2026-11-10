package com.crylo.metronome.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crylo.metronome.R
import com.crylo.metronome.audio.MetronomeController
import com.crylo.metronome.core.Accent
import com.crylo.metronome.core.ClickSound
import com.crylo.metronome.core.Subdivision
import com.crylo.metronome.core.TapTempo
import com.crylo.metronome.core.Tempo
import com.crylo.metronome.core.TempoMarking
import com.crylo.metronome.core.TickEvent
import com.crylo.metronome.core.TimeSignature
import com.crylo.metronome.core.TrainerSettings
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val DEGREES_PER_BPM = 3f

@Composable
fun MetronomeScreen(controller: MetronomeController) {
    val s by controller.settings.collectAsStateWithLifecycle()
    val playing by controller.playing.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Follow the audio clock once per display frame; see ClickEngine.pollTick.
    var tick by remember { mutableStateOf<TickEvent?>(null) }
    LaunchedEffect(playing) {
        tick = null
        while (playing) {
            withFrameNanos { }
            val t = controller.engine.pollTick()
            if (t != tick) tick = t
        }
    }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(tick) {
        val t = tick ?: return@LaunchedEffect
        if (t.accent == Accent.MUTE) return@LaunchedEffect
        flash.snapTo(1f)
        flash.animateTo(0f, tween(220))
    }

    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        controller.play()
    }
    val onPlay = {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (!playing && needsNotificationPermission && !askedNotifications) {
            // Asked once, at the moment it matters: the notification is the stop button while the screen is off.
            askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            controller.toggle()
        }
    }

    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val tapTempo = remember { TapTempo() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BeatDots(s.accents, current = tick?.beat, onTap = { i -> controller.update { it.cycleAccent(i) } })
        Text(
            stringResource(R.string.beat_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TempoDial(
            bpm = s.bpm,
            flash = flash.value,
            accented = tick?.accent == Accent.ACCENT,
            onBpmChange = { bpm -> controller.update { it.withBpm(bpm) } },
            onCenterClick = { dialog = "bpm" },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilledTonalIconButton(onClick = { controller.update { it.withBpm(it.bpm - 1) } }, Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.bpm_minus))
            }
            FilledTonalButton(
                onClick = { tapTempo.tap(SystemClock.uptimeMillis())?.let { bpm -> controller.update { it.withBpm(bpm) } } },
                modifier = Modifier.height(56.dp).width(160.dp),
            ) { Text(stringResource(R.string.tap_tempo)) }
            FilledTonalIconButton(onClick = { controller.update { it.withBpm(it.bpm + 1) } }, Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.bpm_plus))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { dialog = "ts" }, label = { Text(s.timeSignature.toString()) })
            ChoiceChip(
                selected = s.subdivision,
                options = Subdivision.entries,
                label = { subdivisionLabel(it) },
                onSelect = { v -> controller.update { it.copy(subdivision = v) } },
            )
            ChoiceChip(
                selected = s.sound,
                options = ClickSound.entries,
                label = { soundLabel(it) },
                onSelect = { v -> controller.update { it.copy(sound = v) } },
            )
        }
        Row(
            Modifier.fillMaxWidth().clickable { dialog = "trainer" }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.trainer), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.trainer_summary, s.trainer.stepBpm, s.trainer.everyBars, s.trainer.targetBpm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = s.trainer.enabled,
                onCheckedChange = { on -> controller.update { it.copy(trainer = it.trainer.copy(enabled = on)) } },
            )
        }
        Button(onClick = onPlay, modifier = Modifier.height(64.dp).width(200.dp)) {
            Icon(painterResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play), null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (playing) R.string.stop else R.string.start), style = MaterialTheme.typography.titleMedium)
        }
    }

    when (dialog) {
        "bpm" -> BpmDialog(s.bpm, onDismiss = { dialog = null }) { bpm ->
            controller.update { it.withBpm(bpm) }
            dialog = null
        }
        "ts" -> TimeSignatureDialog(s.timeSignature, onDismiss = { dialog = null }) { ts ->
            controller.update { it.withTimeSignature(ts) }
            dialog = null
        }
        "trainer" -> TrainerDialog(s.trainer, s.bpm, onDismiss = { dialog = null }) { t ->
            controller.update { it.copy(trainer = t) }
            dialog = null
        }
    }
}

@Composable
private fun subdivisionLabel(s: Subdivision) = stringResource(
    when (s) {
        Subdivision.NONE -> R.string.subdivision_none
        Subdivision.EIGHTHS -> R.string.subdivision_eighths
        Subdivision.TRIPLETS -> R.string.subdivision_triplets
        Subdivision.SIXTEENTHS -> R.string.subdivision_sixteenths
    },
)

@Composable
private fun soundLabel(s: ClickSound) = stringResource(
    when (s) {
        ClickSound.CLICK -> R.string.sound_click
        ClickSound.WOOD -> R.string.sound_wood
        ClickSound.BEEP -> R.string.sound_beep
        ClickSound.COWBELL -> R.string.sound_cowbell
    },
)

@Composable
fun subdivisionName(s: Subdivision) = subdivisionLabel(s)

@Composable
private fun markingLabel(m: TempoMarking) = stringResource(
    when (m) {
        TempoMarking.GRAVE -> R.string.marking_grave
        TempoMarking.LARGO -> R.string.marking_largo
        TempoMarking.ADAGIO -> R.string.marking_adagio
        TempoMarking.ANDANTE -> R.string.marking_andante
        TempoMarking.MODERATO -> R.string.marking_moderato
        TempoMarking.ALLEGRO -> R.string.marking_allegro
        TempoMarking.PRESTO -> R.string.marking_presto
        TempoMarking.PRESTISSIMO -> R.string.marking_prestissimo
    },
)

@Composable
private fun <T> ChoiceChip(selected: T, options: List<T>, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { expanded = true }, label = { Text(label(selected)) })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = {
                    onSelect(option)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun BeatDots(accents: List<Accent>, current: Int?, onTap: (Int) -> Unit) {
    val perRow = if (accents.size > 8) (accents.size + 1) / 2 else 8
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        accents.indices.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { i -> BeatDot(i + 1, accents[i], active = i == current) { onTap(i) } }
            }
        }
    }
}

@Composable
private fun BeatDot(number: Int, accent: Accent, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scale by animateFloatAsState(if (active) 1.2f else 1f, tween(60), label = "beat")
    val (fill, content) = when {
        active -> cs.tertiary to cs.onTertiary
        accent == Accent.ACCENT -> cs.primary to cs.onPrimary
        accent == Accent.NORMAL -> cs.secondaryContainer to cs.onSecondaryContainer
        else -> Color.Transparent to cs.outline
    }
    val stateLabel = stringResource(
        when (accent) {
            Accent.ACCENT -> R.string.accent_accent
            Accent.NORMAL -> R.string.accent_normal
            Accent.MUTE -> R.string.accent_mute
        },
    )
    val description = stringResource(R.string.beat_description, number, stateLabel)
    Box(
        Modifier
            .size(if (accent == Accent.ACCENT) 40.dp else 34.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(fill, CircleShape)
            .border(1.5.dp, if (accent == Accent.MUTE && !active) cs.outline else Color.Transparent, CircleShape)
            .clickable(onClickLabel = stringResource(R.string.cycle_accent), onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text("$number", color = content, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun TempoDial(bpm: Int, flash: Float, accented: Boolean, onBpmChange: (Int) -> Unit, onCenterClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val currentBpm by rememberUpdatedState(bpm)
    val change by rememberUpdatedState(onBpmChange)
    var accumulated by remember { mutableFloatStateOf(0f) }
    val description = stringResource(R.string.tempo_dial_description)
    Box(
        Modifier
            .size(280.dp)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                // A jog wheel: dragging around the ring changes the tempo by the angle travelled,
                // so it works the same wherever the drag starts.
                var last = Offset.Zero
                detectDragGestures(onDragStart = {
                    last = it
                    accumulated = 0f
                }) { pointer, _ ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val a0 = atan2(last.y - center.y, last.x - center.x)
                    val a1 = atan2(pointer.position.y - center.y, pointer.position.x - center.x)
                    var degrees = Math.toDegrees((a1 - a0).toDouble()).toFloat()
                    if (degrees > 180f) degrees -= 360f
                    if (degrees < -180f) degrees += 360f
                    accumulated += degrees / DEGREES_PER_BPM
                    val steps = accumulated.toInt()
                    if (steps != 0) {
                        accumulated -= steps
                        change(currentBpm + steps)
                    }
                    last = pointer.position
                    pointer.consume()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val pulse = if (accented) cs.primary else cs.tertiary
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke
            val diameter = size.minDimension - 2 * inset
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            val arcSize = Size(diameter, diameter)
            val fraction = (bpm - Tempo.MIN).toFloat() / (Tempo.MAX - Tempo.MIN)
            drawArc(cs.surfaceContainerHighest, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(cs.primary, 135f, 270f * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val angle = Math.toRadians(135.0 + 270.0 * fraction)
            val radius = diameter / 2
            val thumb = center + Offset((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
            drawCircle(cs.primary, stroke * 0.9f, thumb)
            drawCircle(cs.onPrimary, stroke * 0.35f, thumb)
            drawCircle(cs.surfaceContainerHigh, radius * 0.72f)
            drawCircle(pulse.copy(alpha = 0.7f * flash), radius * 0.72f * (1f + 0.03f * flash))
        }
        Column(
            Modifier.clickable(onClickLabel = stringResource(R.string.set_tempo), onClick = onCenterClick).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("$bpm", style = MaterialTheme.typography.displayLarge, color = cs.onSurface)
            Text(stringResource(R.string.bpm), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Text(markingLabel(Tempo.marking(bpm)), style = MaterialTheme.typography.titleSmall, color = cs.primary)
        }
    }
}

@Composable
private fun BpmDialog(bpm: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf("$bpm") }
    val value = text.toIntOrNull()?.takeIf { it in Tempo.MIN..Tempo.MAX }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_tempo)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(3) },
                label = { Text(stringResource(R.string.bpm)) },
                supportingText = { Text(stringResource(R.string.tempo_range, Tempo.MIN, Tempo.MAX)) },
                isError = value == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { value?.let(onConfirm) }, enabled = value != null) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TimeSignatureDialog(current: TimeSignature, onDismiss: () -> Unit, onConfirm: (TimeSignature) -> Unit) {
    var beats by remember { mutableIntStateOf(current.beats) }
    var note by remember { mutableIntStateOf(current.noteValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.time_signature)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeSignature.COMMON.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { ts ->
                            FilterChip(
                                selected = beats == ts.beats && note == ts.noteValue,
                                onClick = {
                                    beats = ts.beats
                                    note = ts.noteValue
                                },
                                label = { Text(ts.toString()) },
                            )
                        }
                    }
                }
                Text(stringResource(R.string.custom), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Stepper(stringResource(R.string.beats_per_bar), beats, TimeSignature.MIN_BEATS..TimeSignature.MAX_BEATS) { beats = it }
                Text(stringResource(R.string.note_value), style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow {
                    TimeSignature.NOTE_VALUES.forEachIndexed { i, v ->
                        SegmentedButton(
                            selected = note == v,
                            onClick = { note = v },
                            shape = SegmentedButtonDefaults.itemShape(i, TimeSignature.NOTE_VALUES.size),
                        ) { Text("$v") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(TimeSignature(beats, note)) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TrainerDialog(current: TrainerSettings, bpm: Int, onDismiss: () -> Unit, onConfirm: (TrainerSettings) -> Unit) {
    var step by remember { mutableIntStateOf(current.stepBpm) }
    var every by remember { mutableIntStateOf(current.everyBars) }
    var target by remember { mutableIntStateOf(if (current.targetBpm > bpm) current.targetBpm else Tempo.clamp(bpm + 40)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trainer)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Stepper(stringResource(R.string.trainer_step), step, 1..20) { step = it }
                Stepper(stringResource(R.string.trainer_every), every, 1..32) { every = it }
                Stepper(stringResource(R.string.trainer_target), target, Tempo.MIN..Tempo.MAX, bigStep = 5) { target = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(TrainerSettings(enabled = true, stepBpm = step, everyBars = every, targetBpm = target)) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, bigStep: Int = 1, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onChange((value - bigStep).coerceIn(range)) }, enabled = value > range.first) {
            Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.decrease))
        }
        Text("$value", Modifier.width(40.dp), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange((value + bigStep).coerceIn(range)) }, enabled = value < range.last) {
            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.increase))
        }
    }
}
