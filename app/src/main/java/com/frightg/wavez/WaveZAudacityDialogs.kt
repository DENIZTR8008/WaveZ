package com.frightg.wavez

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AudBg: Color @Composable get() = LocalWaveZPalette.current.panel
private val AudNeon: Color @Composable get() = LocalWaveZPalette.current.neon
private val AudCyan: Color @Composable get() = LocalWaveZPalette.current.cyan
private val AudText: Color @Composable get() = LocalWaveZPalette.current.text

@Composable
private fun AudPreviewBtn(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Text("▶ Preview", color = AudCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun NoiseReductionDialog(
    hasProfile: Boolean,
    onCaptureProfile: () -> Unit,
    onDismiss: () -> Unit,
    onPreview: (Float, Float, Int, Boolean) -> Unit,
    onApply: (Float, Float, Int, Boolean) -> Unit
) {
    var profReady by remember { mutableStateOf(hasProfile) }
    var redDb by remember { mutableFloatStateOf(12f) }
    var sens by remember { mutableFloatStateOf(6f) }
    var smooth by remember { mutableFloatStateOf(3f) }
    var residue by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Noise Reduction (Шумоподавление)", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Шаг 1: Выделите участок чистого шума и снимите профиль:", color = AudText.copy(alpha = 0.8f), fontSize = 11.sp)
                Button(onClick = { onCaptureProfile(); profReady = true }) {
                    Text(if (profReady) "✓ Профиль шума снят (обновить)" else "Get Noise Profile")
                }
                HorizontalDivider()
                Text("Шаг 2: Выделите аудио для очистки:", color = AudText.copy(alpha = 0.8f), fontSize = 11.sp)
                FreeNumberSlider("Noise reduction (dB)", redDb, 0f..48f, { redDb = it }, accentColor = AudNeon)
                FreeNumberSlider("Sensitivity", sens, 0f..24f, { sens = it }, accentColor = AudText)
                FreeNumberSlider("Smoothing (bands)", smooth, 0f..6f, { smooth = it }, isInteger = true, accentColor = AudText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !residue, onClick = { residue = false }, label = { Text("Reduce") })
                    FilterChip(selected = residue, onClick = { residue = true }, label = { Text("Residue (шум)") })
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (profReady) AudPreviewBtn { onPreview(redDb, sens, smooth.toInt().coerceAtLeast(0), residue) }
                TextButton(enabled = profReady, onClick = { onApply(redDb, sens, smooth.toInt().coerceAtLeast(0), residue) }) {
                    Text("Применить", color = if (profReady) AudNeon else AudText.copy(alpha = 0.3f), fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun NotchFilterDialog(
    onDismiss: () -> Unit,
    onPreview: (Double, Double) -> Unit,
    onApply: (Double, Double) -> Unit
) {
    var freqVal by remember { mutableFloatStateOf(60f) }
    var qVal by remember { mutableFloatStateOf(5.0f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Notch Filter (Режекторный)", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeNumberSlider("Частота (Гц)", freqVal, 20f..16000f, { freqVal = it }, accentColor = AudNeon)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(50f, 60f, 1000f).forEach { f ->
                        FilterChip(selected = kotlin.math.abs(freqVal - f) < 0.5f, onClick = { freqVal = f }, label = { Text("${f.toInt()} Гц", fontSize = 10.sp) })
                    }
                }
                FreeNumberSlider("Quality (Q)", qVal, 0.5f..30f, { qVal = it }, accentColor = AudText)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val hz = freqVal.coerceAtLeast(1f).toDouble()
                val q = qVal.coerceAtLeast(0.05f).toDouble()
                AudPreviewBtn { onPreview(hz, q) }
                TextButton(onClick = { onApply(hz, q) }) { Text("Применить", color = AudNeon, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun VocalReductionDialog(
    onDismiss: () -> Unit,
    onPreview: (Int, Float, Double, Double) -> Unit,
    onApply: (Int, Float, Double, Double) -> Unit
) {
    var mode by remember { mutableIntStateOf(0) }
    var strength by remember { mutableFloatStateOf(1.0f) }
    var lowCut by remember { mutableFloatStateOf(120f) }
    var highCut by remember { mutableFloatStateOf(6000f) }
    val modes = listOf("Remove Vocals", "Isolate Vocals", "Remove Center", "Isolate Center")
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Vocal Reduction / Isolation", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    modes.forEachIndexed { i, m -> FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(m, fontSize = 10.sp) }) }
                }
                FreeNumberSlider("Strength", strength, 0.1f..2.0f, { strength = it }, accentColor = AudNeon)
                FreeNumberSlider("Low Cut (Hz)", lowCut, 40f..1000f, { lowCut = it }, accentColor = AudText)
                FreeNumberSlider("High Cut (Hz)", highCut, 1500f..14000f, { highCut = it }, accentColor = AudText)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudPreviewBtn { onPreview(mode, strength, lowCut.coerceAtLeast(1f).toDouble(), highCut.coerceAtLeast(1f).toDouble()) }
                TextButton(onClick = { onApply(mode, strength, lowCut.coerceAtLeast(1f).toDouble(), highCut.coerceAtLeast(1f).toDouble()) }) { Text("Применить", color = AudNeon, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun TremoloDialog(
    onDismiss: () -> Unit,
    onPreview: (Int, Float, Float, Float) -> Unit,
    onApply: (Int, Float, Float, Float) -> Unit
) {
    var wf by remember { mutableIntStateOf(0) }
    var phase by remember { mutableFloatStateOf(0f) }
    var wet by remember { mutableFloatStateOf(50f) }
    var freq by remember { mutableFloatStateOf(4.0f) }
    val wfs = listOf("Sine", "Tri", "Saw", "InvSaw", "Square")
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Tremolo (Тремоло)", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    wfs.forEachIndexed { i, n -> FilterChip(selected = wf == i, onClick = { wf = i }, label = { Text(n, fontSize = 10.sp) }) }
                }
                FreeNumberSlider("Wet level (%)", wet, 0f..100f, { wet = it }, accentColor = AudNeon)
                FreeNumberSlider("Frequency (Hz)", freq, 0.5f..25f, { freq = it }, accentColor = AudText)
                FreeNumberSlider("Starting phase (°)", phase, 0f..360f, { phase = it }, accentColor = AudText)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudPreviewBtn { onPreview(wf, phase, wet, freq) }
                TextButton(onClick = { onApply(wf, phase, wet, freq) }) { Text("Применить", color = AudNeon, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun SlidingStretchDialog(
    onDismiss: () -> Unit,
    onPreview: (Double, Double, Double, Double) -> Unit,
    onApply: (Double, Double, Double, Double) -> Unit
) {
    var initT by remember { mutableFloatStateOf(0f) }
    var finT by remember { mutableFloatStateOf(-30f) }
    var initP by remember { mutableFloatStateOf(0f) }
    var finP by remember { mutableFloatStateOf(-5f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Sliding Stretch (Плавный разгон/тормоз)", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeNumberSlider("Initial Tempo (%)", initT, -75f..150f, { initT = it }, accentColor = AudNeon)
                FreeNumberSlider("Final Tempo (%)", finT, -75f..150f, { finT = it }, accentColor = AudNeon)
                FreeNumberSlider("Initial Pitch (st)", initP, -12f..12f, { initP = it }, accentColor = AudText)
                FreeNumberSlider("Final Pitch (st)", finP, -12f..12f, { finP = it }, accentColor = AudText)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudPreviewBtn { onPreview(initT.toDouble(), finT.toDouble(), initP.toDouble(), finP.toDouble()) }
                TextButton(onClick = { onApply(initT.toDouble(), finT.toDouble(), initP.toDouble(), finP.toDouble()) }) { Text("Применить", color = AudNeon, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun AdjustableFadeDialog(
    onDismiss: () -> Unit,
    onPreview: (Int, Float, Float, Float, Int) -> Unit,
    onApply: (Int, Float, Float, Float, Int) -> Unit
) {
    var fadeType by remember { mutableIntStateOf(1) }
    var curveType by remember { mutableIntStateOf(2) }
    var midBoost by remember { mutableFloatStateOf(0f) }
    var startPct by remember { mutableFloatStateOf(0f) }
    var endPct by remember { mutableFloatStateOf(100f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Adjustable / Studio Fade", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = fadeType == 0, onClick = { fadeType = 0 }, label = { Text("Fade Up") })
                    FilterChip(selected = fadeType == 1, onClick = { fadeType = 1 }, label = { Text("Fade Down") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Linear", "Log/Exp", "S-Curve").forEachIndexed { i, n ->
                        FilterChip(selected = curveType == i, onClick = { curveType = i }, label = { Text(n, fontSize = 10.sp) })
                    }
                }
                FreeNumberSlider("Mid-fade boost (%)", midBoost, -50f..50f, { midBoost = it }, accentColor = AudNeon)
                FreeNumberSlider("Start (%)", startPct, 0f..100f, { startPct = it }, accentColor = AudText)
                FreeNumberSlider("End (%)", endPct, 0f..100f, { endPct = it }, accentColor = AudText)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudPreviewBtn { onPreview(fadeType, midBoost, startPct, endPct, curveType) }
                TextButton(onClick = { onApply(fadeType, midBoost, startPct, endPct, curveType) }) { Text("Применить", color = AudNeon, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun AutoDuckDialog(
    trackNames: List<String>,
    activeTrackIdx: Int,
    onDismiss: () -> Unit,
    onApply: (Int, Float, Float, Float, Float) -> Unit
) {
    val candidates = trackNames.indices.filter { it != activeTrackIdx }
    var ctrlIdx by remember { mutableIntStateOf(candidates.firstOrNull() ?: 0) }
    var duckDb by remember { mutableFloatStateOf(-12f) }
    var threshDb by remember { mutableFloatStateOf(-30f) }
    var outerSec by remember { mutableFloatStateOf(0.5f) }
    var innerSec by remember { mutableFloatStateOf(0.2f) }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Auto Duck (Автоприглушение)", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (candidates.isEmpty()) {
                    Text("Добавьте вторую дорожку (голос/сайдчейн) для Auto Duck.", color = Color(0xFFFF5252), fontSize = 12.sp)
                } else {
                    Text("Управляющая дорожка:", color = AudText.copy(alpha = 0.8f), fontSize = 11.sp)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        candidates.forEach { idx ->
                            FilterChip(selected = ctrlIdx == idx, onClick = { ctrlIdx = idx }, label = { Text("${idx + 1}: ${trackNames[idx]}", fontSize = 10.sp) })
                        }
                    }
                    FreeNumberSlider("Duck amount (dB)", duckDb, -24f..0f, { duckDb = it }, accentColor = AudNeon)
                    FreeNumberSlider("Threshold (dB)", threshDb, -60f..0f, { threshDb = it }, accentColor = AudText)
                    FreeNumberSlider("Outer fade (s)", outerSec, 0.05f..2.0f, { outerSec = it }, accentColor = AudText)
                    FreeNumberSlider("Inner fade (s)", innerSec, 0.05f..2.0f, { innerSec = it }, accentColor = AudText)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = candidates.isNotEmpty(), onClick = { onApply(ctrlIdx, duckDb, threshDb, outerSec.coerceAtLeast(0.001f), innerSec.coerceAtLeast(0.001f)) }) {
                Text("Применить", color = if (candidates.isNotEmpty()) AudNeon else AudText.copy(alpha = 0.3f), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun NormalizeDialog(
    onDismiss: () -> Unit,
    onApply: (Float, Boolean) -> Unit
) {
    var peakDb by remember { mutableFloatStateOf(-1.0f) }
    var removeDc by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Normalize (Нормализация)", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeNumberSlider("Peak amplitude (dB)", peakDb, -24f..0f, { peakDb = it }, accentColor = AudNeon)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = removeDc, onCheckedChange = { removeDc = it })
                    Text("Remove any DC offset", color = AudText, fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(peakDb, removeDc) }) { Text("Применить", color = AudNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun LimiterDialog(
    onDismiss: () -> Unit,
    onApply: (Float) -> Unit
) {
    var ceilDb by remember { mutableFloatStateOf(-0.5f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Limiter (Лимитер)", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeNumberSlider("Limit to (dB)", ceilDb, -12f..0f, { ceilDb = it }, accentColor = AudNeon)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(ceilDb) }) { Text("Применить", color = AudNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun TruncateSilenceDialog(
    onDismiss: () -> Unit,
    onApply: (Float, Float) -> Unit
) {
    var threshDb by remember { mutableFloatStateOf(-50f) }
    var minSec by remember { mutableFloatStateOf(0.25f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Truncate Silence (Сжать паузы)", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeNumberSlider("Threshold (dB)", threshDb, -80f..-10f, { threshDb = it }, accentColor = AudNeon)
                FreeNumberSlider("Min Duration (s)", minSec, 0.05f..3.0f, { minSec = it }, accentColor = AudText)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(threshDb, minSec.coerceAtLeast(0.001f)) }) { Text("Применить", color = AudNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun FinderDialog(
    mode: Int, // 0=Silence, 1=Sound, 2=Beat, 3=Clipping
    onDismiss: () -> Unit,
    onRun: (Float, Float, Float) -> Unit
) {
    var p1 by remember { mutableFloatStateOf(if (mode == 2) 65f else if (mode == 3) 3f else -30f) }
    var p2 by remember { mutableFloatStateOf(if (mode == 3) 3f else 0.5f) }
    var p3 by remember { mutableFloatStateOf(0.25f) }
    val titles = listOf("Silence Finder (Метки пауз)", "Sound Finder (Метки регионов)", "Beat Finder (Метки ударов)", "Find Clipping (Метки клипов)")
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text(titles.getOrElse(mode) { "Finder" }, color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (mode) {
                    0, 1 -> {
                        FreeNumberSlider("Threshold (dB)", p1, -60f..-5f, { p1 = it }, accentColor = AudNeon)
                        FreeNumberSlider("Min Silence (s)", p2, 0.05f..3.0f, { p2 = it }, accentColor = AudText)
                        if (mode == 1) {
                            FreeNumberSlider("Min Sound (s)", p3, 0.05f..2.0f, { p3 = it }, accentColor = AudText)
                        }
                    }
                    2 -> {
                        FreeNumberSlider("Threshold (%)", p1, 10f..95f, { p1 = it }, accentColor = AudNeon)
                    }
                    else -> {
                        FreeNumberSlider("Start threshold (smp)", p1, 1f..20f, { p1 = it }, isInteger = true, accentColor = AudNeon)
                        FreeNumberSlider("Stop threshold (smp)", p2, 1f..20f, { p2 = it }, isInteger = true, accentColor = AudText)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRun(p1, p2, p3) }) { Text("Найти и расставить метки", color = AudNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun PhantomCenterDialog(
    onDismiss: () -> Unit,
    onPreview: (Double, Double, Float, Float) -> Unit,
    onApply: (Double, Double, Float, Float) -> Unit
) {
    var hpfHz by remember { mutableFloatStateOf(120f) }
    var lpfHz by remember { mutableFloatStateOf(16000f) }
    var mixPct by remember { mutableFloatStateOf(-100f) }
    var outputDb by remember { mutableFloatStateOf(0f) }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Phantom Center (M/S Кроссовер)", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LR4 24 dB/oct полосовой Mid/Side (моно-бас и верх не трогаются)", color = AudText.copy(alpha = 0.75f), fontSize = 11.sp)
                FreeNumberSlider("HPF срез низа (Гц)", hpfHz, 20f..2000f, { hpfHz = it }, accentColor = AudText)
                FreeNumberSlider("LPF срез верха (Гц)", lpfHz, 1000f..20000f, { lpfHz = it }, accentColor = AudText)
                FreeNumberSlider("Mix (-100% Side ↔ +100% Mid)", mixPct, -100f..100f, { mixPct = it }, accentColor = AudNeon)
                FreeNumberSlider("Output Gain (dB)", outputDb, -24f..24f, { outputDb = it }, accentColor = AudCyan)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudPreviewBtn { onPreview(hpfHz.coerceAtLeast(5f).toDouble(), lpfHz.coerceAtLeast(15f).toDouble(), mixPct, outputDb) }
                TextButton(onClick = { onApply(hpfHz.coerceAtLeast(5f).toDouble(), lpfHz.coerceAtLeast(15f).toDouble(), mixPct, outputDb) }) {
                    Text("Применить", color = AudNeon, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun ClipFixDialog(
    onDismiss: () -> Unit,
    onPreview: (Float, Float) -> Unit,
    onApply: (Float, Float) -> Unit
) {
    var threshPct by remember { mutableFloatStateOf(95f) }
    var reduceDb by remember { mutableFloatStateOf(-3f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Clip Fix (Устранение клиппинга)", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeNumberSlider("Threshold (%)", threshPct, 50f..99.9f, { threshPct = it }, accentColor = AudNeon)
                FreeNumberSlider("Reduce amplitude (dB)", reduceDb, -24f..0f, { reduceDb = it }, accentColor = AudText)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudPreviewBtn { onPreview(threshPct, reduceDb) }
                TextButton(onClick = { onApply(threshPct, reduceDb) }) { Text("Применить", color = AudNeon, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun VocoderDialog(
    onDismiss: () -> Unit,
    onPreview: (Int, Double, Float) -> Unit,
    onApply: (Int, Double, Float) -> Unit
) {
    var bands by remember { mutableFloatStateOf(16f) }
    var carrierHz by remember { mutableFloatStateOf(120f) }
    var mixPct by remember { mutableFloatStateOf(100f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Vocoder (Вокодер)", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeNumberSlider("Полосы (Bands)", bands, 4f..32f, { bands = it }, isInteger = true, accentColor = AudNeon)
                FreeNumberSlider("Несущая (Carrier Гц)", carrierHz, 40f..800f, { carrierHz = it }, accentColor = AudText)
                FreeNumberSlider("Mix (%)", mixPct, 0f..100f, { mixPct = it }, accentColor = AudCyan)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudPreviewBtn { onPreview(bands.toInt().coerceAtLeast(4), carrierHz.toDouble(), mixPct) }
                TextButton(onClick = { onApply(bands.toInt().coerceAtLeast(4), carrierHz.toDouble(), mixPct) }) { Text("Применить", color = AudNeon, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun DeEsserDialog(
    onDismiss: () -> Unit,
    onPreview: (Double, Float, Float) -> Unit,
    onApply: (Double, Float, Float) -> Unit
) {
    var freqHz by remember { mutableFloatStateOf(6500f) }
    var threshDb by remember { mutableFloatStateOf(-22f) }
    var ratio by remember { mutableFloatStateOf(4f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("De-Esser (Подавление сибилянтов)", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeNumberSlider("Частота от (Гц)", freqHz, 2000f..12000f, { freqHz = it }, accentColor = AudText)
                FreeNumberSlider("Порог (dB)", threshDb, -60f..0f, { threshDb = it }, accentColor = AudNeon)
                FreeNumberSlider("Сжатие (:1)", ratio, 1f..12f, { ratio = it }, accentColor = AudCyan)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudPreviewBtn { onPreview(freqHz.coerceAtLeast(100f).toDouble(), threshDb, ratio.coerceAtLeast(1f)) }
                TextButton(onClick = { onApply(freqHz.coerceAtLeast(100f).toDouble(), threshDb, ratio.coerceAtLeast(1f)) }) { Text("Применить", color = AudNeon, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun MultibandCompDialog(
    onDismiss: () -> Unit,
    onPreview: (Double, Double, Float, Float, Float) -> Unit,
    onApply: (Double, Double, Float, Float, Float) -> Unit
) {
    var loHz by remember { mutableFloatStateOf(200f) }
    var hiHz by remember { mutableFloatStateOf(3000f) }
    var threshDb by remember { mutableFloatStateOf(-18f) }
    var ratio by remember { mutableFloatStateOf(3.5f) }
    var makeupDb by remember { mutableFloatStateOf(2f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("3-Band Multiband Compressor", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FreeNumberSlider("Low/Mid срез (Гц)", loHz, 50f..1000f, { loHz = it }, accentColor = AudText)
                FreeNumberSlider("Mid/High срез (Гц)", hiHz, 1000f..10000f, { hiHz = it }, accentColor = AudText)
                FreeNumberSlider("Порог (dB)", threshDb, -48f..0f, { threshDb = it }, accentColor = AudNeon)
                FreeNumberSlider("Сжатие (:1)", ratio, 1f..12f, { ratio = it }, accentColor = AudCyan)
                FreeNumberSlider("Makeup Gain (dB)", makeupDb, -12f..24f, { makeupDb = it }, accentColor = AudText)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudPreviewBtn { onPreview(loHz.toDouble(), hiHz.toDouble(), threshDb, ratio.coerceAtLeast(1f), makeupDb) }
                TextButton(onClick = { onApply(loHz.toDouble(), hiHz.toDouble(), threshDb, ratio.coerceAtLeast(1f), makeupDb) }) { Text("Применить", color = AudNeon, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun SaturatorDialog(
    onDismiss: () -> Unit,
    onPreview: (Int, Float, Float, Float) -> Unit,
    onApply: (Int, Float, Float, Float) -> Unit
) {
    var mode by remember { mutableIntStateOf(0) }
    var driveDb by remember { mutableFloatStateOf(6f) }
    var warmth by remember { mutableFloatStateOf(0.5f) }
    var mixPct by remember { mutableFloatStateOf(100f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Tape / Tube Saturator", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Tape (Лента)") })
                    FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Tube (Лампа)") })
                }
                FreeNumberSlider("Drive (dB)", driveDb, 0f..24f, { driveDb = it }, accentColor = AudNeon)
                FreeNumberSlider("Warmth (0..1)", warmth, 0f..1f, { warmth = it }, accentColor = AudText)
                FreeNumberSlider("Mix (%)", mixPct, 0f..100f, { mixPct = it }, accentColor = AudCyan)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudPreviewBtn { onPreview(mode, driveDb, warmth, mixPct) }
                TextButton(onClick = { onApply(mode, driveDb, warmth, mixPct) }) { Text("Применить", color = AudNeon, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun RegularIntervalLabelsDialog(
    onDismiss: () -> Unit,
    onApplyCount: (Int) -> Unit
) {
    var count by remember { mutableFloatStateOf(4f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Регулярный интервал меток", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeNumberSlider("Количество меток", count, 2f..32f, { count = it }, isInteger = true, accentColor = AudNeon)
            }
        },
        confirmButton = { TextButton(onClick = { onApplyCount(count.toInt().coerceAtLeast(1)) }) { Text("Расставить", color = AudNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun EffectRackDialog(
    rackSteps: List<Pair<String, () -> Unit>>,
    availableEffects: List<Pair<String, () -> Unit>>,
    onAddStep: (Pair<String, () -> Unit>) -> Unit,
    onRemoveStep: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onApplyRack: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Effect Rack (Цепочка эффектов)", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Активная цепь (${rackSteps.size}):", color = AudNeon, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (rackSteps.isEmpty()) {
                    Text("Пусто — добавьте эффекты ниже", color = AudText.copy(alpha = 0.6f), fontSize = 11.sp)
                } else {
                    rackSteps.forEachIndexed { idx, (name, _) ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${idx + 1}. $name", color = AudText, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            if (idx > 0) TextButton(onClick = { onMoveUp(idx) }) { Text("↑", color = AudCyan) }
                            TextButton(onClick = { onRemoveStep(idx) }) { Text("✕", color = Color(0xFFFF5252)) }
                        }
                    }
                }
                HorizontalDivider()
                Text("Добавить пресет в цепь:", color = AudText.copy(alpha = 0.8f), fontSize = 11.sp)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    availableEffects.forEach { item ->
                        AssistChip(onClick = { onAddStep(item) }, label = { Text("+ ${item.first}", fontSize = 10.sp) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = rackSteps.isNotEmpty(), onClick = onApplyRack) {
                Text("Применить цепь", color = if (rackSteps.isNotEmpty()) AudNeon else AudText.copy(alpha = 0.3f), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = AudText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun AboutSummaryDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("WaveZ Studio — Сводка и Справка", color = AudNeon, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Проект: WaveZ Audio Editor (com.frightg.wavez)\nАвтор проекта: frightG / DENIZTR", color = AudText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                HorizontalDivider()
                Text("Краткий гайд по работе:", color = AudNeon, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "• Начало работы: откройте аудио («Файл → Открыть»), начните запись с микрофона (кнопка записи / Overdub) или создайте сигнал через «Генератор».\n" +
                    "• Жесты на волне: 1 палец — скролл и позиция курсора; 2 пальца — зум; долгое нажатие или ушки — выделение фрагмента.\n" +
                    "• Дорожки и монтаж: панель слева управляет громкостью, панорамой, Mute/Solo и режимами (Time Shift для сдвига, Envelope для огибающей); верхняя шкала меток позволяет ставить метки и делать Export Multiple.\n" +
                    "• Эффекты и точный ввод: в диалогах эффектов можно вводить любые числа вручную в поле значения, слушать превью и собирать цепочки в Effect Rack.\n" +
                    "• Анализ и настройка: в меню «Анализ» доступны FFT-спектр, спектрограмма, поиск тишины, битов, клиппинга и тональности; там же переключаются dB-шкала и сетка.",
                    color = AudText.copy(alpha = 0.9f), fontSize = 11.sp
                )
                HorizontalDivider()
                Text("Использованный стек и открытые библиотеки:", color = AudCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "• C++20 Native DSP Engine (WaveZDsp, EditDecisionList, WaveZPlayer)\n" +
                    "• Google Oboe 1.9.3 (Apache-2.0) — low-latency аудиовывод\n" +
                    "• SoundTouch (LGPL) — изменение темпа и тональности\n" +
                    "• libFLAC (BSD) — кодирование и декодирование FLAC\n" +
                    "• Android MediaCodec / MediaExtractor — AAC/M4A и декодирование аудио\n" +
                    "• Jetpack Compose + Material Design 3 Expressive (Apache-2.0)",
                    color = AudText.copy(alpha = 0.9f), fontSize = 11.sp
                )
                HorizontalDivider()
                Text("Clean-Room DSP и Функционал (40+ инструментов):", color = AudNeon, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "• Динамика: Amplify, Normalize, Loudness (-16 LUFS), Compressor, 3-Band Multiband Comp, Limiter, De-Esser, Noise Gate, Auto Duck, Fade In/Out, Adjustable Fade\n" +
                    "• Тон и Время: Kaiser Sinc Speed/Pitch (свободный ввод Гц/центов/дробей/об-мин), SoundTouch (ноты/BPM/длина), Sliding Stretch, PaulStretch\n" +
                    "• Частоты и Пространство: 5-Band EQ, HPF/LPF, Notch, Bass & Treble, Phantom Center (LR4 M/S кроссовер), Vocal Reduction, Tape/Tube Saturator, Echo (BPM Sync + Wrap Tail), Reverb (Wrap Tail), Vocoder, Phaser, Wahwah, Tremolo, Distortion/Bitcrusher\n" +
                    "• Реставрация и Швы: Noise Reduction (STFT по профилю), Clip Fix, LSAR Repair, Auto-Seam, Crossfade Loop Seam, Click Removal, Trim/Truncate Silence\n" +
                    "• Монтаж и Анализ: Многодорожечность, Запись + Overdub, Time Shift, Envelope, Метки + Export Multiple, Silence/Sound/Beat/Clipping/Key/BPM Finders, FFT Спектр и Спектрограмма, dB-шкала волны.",
                    color = AudText.copy(alpha = 0.85f), fontSize = 11.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = AudNeon, fontWeight = FontWeight.Bold) } }
    )
}

@Composable
fun BitcrusherDialog(
    onDismiss: () -> Unit,
    onPreview: (Float, Int, Float) -> Unit,
    onApply: (Float, Int, Float) -> Unit
) {
    var bitDepth by remember { mutableFloatStateOf(8f) }
    var downsample by remember { mutableFloatStateOf(4f) }
    var mixPct by remember { mutableFloatStateOf(100f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AudBg,
        title = { Text("Bitcrusher (Bit Depth + Downsample)", color = AudText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeNumberSlider("Bit Depth (бит)", bitDepth, 1f..16f, { bitDepth = it }, accentColor = AudNeon)
                FreeNumberSlider("Downsample (Sample & Hold, x)", downsample, 1f..32f, { downsample = it }, isInteger = true, accentColor = AudText)
                FreeNumberSlider("Mix (%)", mixPct, 0f..100f, { mixPct = it }, accentColor = AudCyan)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudPreviewBtn { onPreview(bitDepth, downsample.toInt().coerceAtLeast(1), mixPct) }
                TextButton(onClick = { onApply(bitDepth, downsample.toInt().coerceAtLeast(1), mixPct) }) { Text("Применить", color = AudNeon, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = AudText.copy(alpha = 0.75f)) } }
    )
}
