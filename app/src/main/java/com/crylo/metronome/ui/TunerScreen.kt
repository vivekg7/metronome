package com.crylo.metronome.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crylo.metronome.R
import com.crylo.metronome.core.Instrument
import com.crylo.metronome.core.NoteMath
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val IN_TUNE_CENTS = 5.0

@Composable
fun TunerScreen() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    fun hasMic() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasMic()) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        // After a denial the system stops showing the dialog once the rationale flag goes false.
        permanentlyDenied = !ok && activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
    }
    // The user may grant access in system settings and come back.
    LifecycleResumeEffect(Unit) {
        granted = hasMic()
        onPauseOrDispose { }
    }
    if (granted) {
        TunerContent()
    } else {
        MicPermissionCard(
            permanentlyDenied = permanentlyDenied,
            onRequest = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
        )
    }
}

@Composable
private fun MicPermissionCard(permanentlyDenied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Card {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.mic_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.mic_explanation), style = MaterialTheme.typography.bodyMedium)
                if (permanentlyDenied) {
                    Text(stringResource(R.string.mic_denied), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
                } else {
                    Button(onClick = onRequest) { Text(stringResource(R.string.mic_allow)) }
                }
            }
        }
    }
}

@Composable
private fun TunerContent(vm: TunerViewModel = viewModel()) {
    LifecycleResumeEffect(vm) {
        vm.start()
        onPauseOrDispose { vm.stop() }
    }
    // Hands are on the instrument while tuning; don't let the screen time out.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val reading by vm.reading.collectAsStateWithLifecycle()
    val a4 by vm.a4.collectAsStateWithLifecycle()
    val instrument by vm.instrument.collectAsStateWithLifecycle()
    val micAvailable by vm.micAvailable.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val inTuneColor = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
    val r = reading
    val inTune = r != null && abs(r.cents) <= IN_TUNE_CENTS

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Instrument.entries.forEach { inst ->
                FilterChip(selected = inst == instrument, onClick = { vm.setInstrument(inst) }, label = { Text(instrumentLabel(inst)) })
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                r?.let { NoteMath.name(it.midi) } ?: stringResource(R.string.no_note),
                fontSize = 96.sp,
                fontWeight = FontWeight.Light,
                color = if (inTune) inTuneColor else cs.onSurface,
            )
            Text(
                r?.let { "${NoteMath.octave(it.midi)}" } ?: "",
                style = MaterialTheme.typography.headlineMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }
        Text(
            if (r != null) stringResource(R.string.frequency_hz, r.frequency) else stringResource(R.string.play_a_note),
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurfaceVariant,
        )
        CentsGauge(r?.cents, inTune, inTuneColor)
        if (r != null) {
            Text(
                stringResource(
                    when {
                        inTune -> R.string.in_tune
                        r.cents < 0 -> R.string.tune_up
                        else -> R.string.tune_down
                    },
                ) + "  ·  " + stringResource(R.string.cents_value, r.cents.roundToInt()),
                style = MaterialTheme.typography.titleMedium,
                color = if (inTune) inTuneColor else cs.onSurface,
            )
        }
        if (instrument.strings.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                instrument.strings.forEachIndexed { i, midi ->
                    val active = r?.stringIndex == i
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (active) (if (inTune) inTuneColor else cs.primary) else cs.surfaceContainerHigh,
                        contentColor = if (active) cs.surface else cs.onSurface,
                    ) {
                        Text(NoteMath.label(midi), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.setA4(a4 - 1) }, enabled = a4 > NoteMath.A4_MIN) {
                Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.a4_lower))
            }
            Text(stringResource(R.string.a4_calibration, a4), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { vm.setA4(a4 + 1) }, enabled = a4 < NoteMath.A4_MAX) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.a4_higher))
            }
        }
        if (!micAvailable) {
            Text(stringResource(R.string.mic_unavailable), color = cs.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun instrumentLabel(i: Instrument) = stringResource(
    when (i) {
        Instrument.CHROMATIC -> R.string.instrument_chromatic
        Instrument.GUITAR -> R.string.instrument_guitar
        Instrument.BASS -> R.string.instrument_bass
        Instrument.UKULELE -> R.string.instrument_ukulele
        Instrument.VIOLIN -> R.string.instrument_violin
    },
)

/** A ±50 cent arc with a needle; the green band in the middle is the in-tune zone. */
@Composable
private fun CentsGauge(cents: Double?, inTune: Boolean, inTuneColor: Color) {
    val cs = MaterialTheme.colorScheme
    val needle by animateFloatAsState(
        (cents ?: 0.0).coerceIn(-50.0, 50.0).toFloat(),
        spring(stiffness = 300f),
        label = "needle",
    )
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val pivot = Offset(size.width / 2, size.height - 8.dp.toPx())
        val radius = minOf(size.width / 2, size.height) * 0.92f
        val degreesPerCent = 1.2f // ±50 cents spans ±60°
        fun point(c: Float, r: Float): Offset {
            val a = Math.toRadians((-90f + c * degreesPerCent).toDouble())
            return pivot + Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat())
        }
        val zoneDegrees = IN_TUNE_CENTS.toFloat() * degreesPerCent
        val bandRadius = radius * 0.93f
        drawArc(
            inTuneColor.copy(alpha = 0.35f),
            startAngle = -90f - zoneDegrees,
            sweepAngle = 2 * zoneDegrees,
            useCenter = false,
            topLeft = pivot - Offset(bandRadius, bandRadius),
            size = Size(2 * bandRadius, 2 * bandRadius),
            style = Stroke(radius * 0.14f),
        )
        for (tick in -50..50 step 10) {
            val major = tick % 50 == 0 || tick == 0
            drawLine(
                if (tick == 0) inTuneColor else cs.outline,
                point(tick.toFloat(), radius * (if (major) 0.8f else 0.88f)),
                point(tick.toFloat(), radius),
                strokeWidth = (if (major) 3 else 2).dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        val needleColor = when {
            cents == null -> cs.outlineVariant
            inTune -> inTuneColor
            else -> cs.primary
        }
        drawLine(needleColor, pivot, point(needle, radius * 0.95f), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(needleColor, 8.dp.toPx(), pivot)
    }
}
