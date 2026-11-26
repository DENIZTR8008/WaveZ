package com.frightg.wavez

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.pow

private val DlgBg: Color @Composable get() = LocalWaveZPalette.current.panel
private val DlgBorder: Color @Composable get() = LocalWaveZPalette.current.border
private val DlgAccent: Color @Composable get() = LocalWaveZPalette.current.waveSel
private val DlgNeon: Color @Composable get() = LocalWaveZPalette.current.neon
private val DlgCyan: Color @Composable get() = LocalWaveZPalette.current.cyan
private val DlgText: Color @Composable get() = LocalWaveZPalette.current.text

@Composable
fun FreeNumberSlider(
    label: String,
    value: Float,
    sliderRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    isInteger: Boolean = false,
    accentColor: Color = DlgAccent
) {
    var text by remember {
        mutableStateOf(if (isInteger) value.toInt().toString() else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.'))
    }
    var userEditing by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (!userEditing) {
            text = if (isInteger) value.toInt().toString() else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    userEditing = true
                    text = raw
                    val parsed = raw.replace(',', '.').toFloatOrNull()
                    if (parsed != null && parsed.isFinite()) {
                        onValueChange(parsed)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = DlgText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                modifier = Modifier.width(96.dp)
            )
        }
        Slider(
            value = value.coerceIn(sliderRange.start, sliderRange.endInclusive),
            onValueChange = {
                userEditing = false
                val v = if (isInteger) it.toInt().toFloat() else it
                onValueChange(v)
                text = if (isInteger) v.toInt().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
            },
            valueRange = sliderRange
        )
    }
}

@Composable
private fun PreviewButton(onPreview: () -> Unit) {
    OutlinedButton(
        onClick = onPreview,
        border = BorderStroke(1.dp, DlgCyan),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Icon(Icons.Default.PlayArrow, null, tint = DlgCyan, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Preview 4с", color = DlgCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AmplifyDialog(
    onDismiss: () -> Unit,
    onPreview: (Float) -> Unit,
    onApply: (Float) -> Unit
) {
    var gainDb by remember { mutableFloatStateOf(3.0f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Усиление (Amplify)", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeNumberSlider("Громкость (dB)", gainDb, -24f..24f, { gainDb = it })
                PreviewButton { onPreview(gainDb) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(gainDb) }) { Text("Применить", color = DlgNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun ChangeSpeedDialog(
    currentDurationSec: Double = 10.0,
    onDismiss: () -> Unit,
    onPreview: (Double) -> Unit,
    onApply: (Double) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("wavez_presets", Context.MODE_PRIVATE) }
    var customPresets by remember {
        val raw = prefs.getString("speed_presets", "") ?: ""
        val list = raw.split(";").mapNotNull { entry ->
            val parts = entry.split("=")
            if (parts.size == 2) parts[1].toDoubleOrNull()?.let { parts[0] to it } else null
        }
        mutableStateOf(list)
    }

    fun savePresets(list: List<Pair<String, Double>>) {
        customPresets = list
        prefs.edit().putString("speed_presets", list.joinToString(";") { "${it.first}=${it.second}" }).apply()
    }

    val safeCurDur = currentDurationSec.coerceAtLeast(0.001)
    var speedRatio by remember { mutableDoubleStateOf(1.0) }
    var ratioText by remember { mutableStateOf("1.000") }
    var pctText by remember { mutableStateOf("0.000") }
    var newDurText by remember { mutableStateOf(String.format(Locale.US, "%.3f", safeCurDur)) }
    var presetName by remember { mutableStateOf("") }
    var srcHzStr by remember { mutableStateOf("440.0") }
    var dstHzStr by remember { mutableStateOf("440.0") }
    var cents by remember { mutableFloatStateOf(0f) }

    fun setRatio(r: Double, syncCents: Boolean = true) {
        val safe = if (r.isFinite() && r > 0.0) r else 1.0
        speedRatio = safe
        ratioText = String.format(Locale.US, "%.5f", safe)
        pctText = String.format(Locale.US, "%.3f", (safe - 1.0) * 100.0)
        newDurText = String.format(Locale.US, "%.3f", safeCurDur / safe)
        if (syncCents) cents = (1200.0 * kotlin.math.ln(safe) / kotlin.math.ln(2.0)).toFloat()
    }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Изменение скорости и тона", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = ratioText,
                        onValueChange = { txt ->
                            ratioText = txt
                            val parsed = if ("/" in txt) {
                                val p = txt.split("/")
                                val a = p.getOrNull(0)?.trim()?.toDoubleOrNull()
                                val b = p.getOrNull(1)?.trim()?.toDoubleOrNull()
                                if (a != null && b != null && b != 0.0) a / b else null
                            } else txt.replace(',', '.').toDoubleOrNull()
                            if (parsed != null && parsed > 0.0) {
                                speedRatio = parsed
                                pctText = String.format(Locale.US, "%.3f", (parsed - 1.0) * 100.0)
                                newDurText = String.format(Locale.US, "%.3f", safeCurDur / parsed)
                                cents = (1200.0 * kotlin.math.ln(parsed) / kotlin.math.ln(2.0)).toFloat()
                            }
                        },
                        label = { Text("Множитель / дробь") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pctText,
                        onValueChange = { txt ->
                            pctText = txt
                            val pct = txt.replace(',', '.').toDoubleOrNull()
                            if (pct != null) {
                                val r = 1.0 + pct / 100.0
                                if (r > 0.0) {
                                    speedRatio = r
                                    ratioText = String.format(Locale.US, "%.5f", r)
                                    newDurText = String.format(Locale.US, "%.3f", safeCurDur / r)
                                    cents = (1200.0 * kotlin.math.ln(r) / kotlin.math.ln(2.0)).toFloat()
                                }
                            }
                        },
                        label = { Text("Процент (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                }
                Slider(
                    value = speedRatio.toFloat().coerceIn(0.25f, 4.0f),
                    onValueChange = { setRatio(it.toDouble()) },
                    valueRange = 0.25f..4.0f
                )
                Text("Об/мин пластинок:", color = DlgText.copy(alpha = 0.75f), fontSize = 11.sp)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("33⅓→45" to (45.0 / 33.333333), "45→33⅓" to (33.333333 / 45.0), "33⅓→78" to (78.0 / 33.333333), "78→33⅓" to (33.333333 / 78.0), "45→78" to (78.0 / 45.0)).forEach { (lbl, r) ->
                        FilterChip(selected = kotlin.math.abs(speedRatio - r) < 0.002, onClick = { setRatio(r) }, label = { Text(lbl, fontSize = 10.sp) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format(Locale.US, "Тек: %.2fс", safeCurDur), color = DlgText.copy(alpha = 0.75f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    OutlinedTextField(
                        value = newDurText,
                        onValueChange = { txt ->
                            newDurText = txt
                            val nd = txt.replace(',', '.').toDoubleOrNull()
                            if (nd != null && nd > 0.0) {
                                val r = safeCurDur / nd
                                speedRatio = r
                                ratioText = String.format(Locale.US, "%.5f", r)
                                pctText = String.format(Locale.US, "%.3f", (r - 1.0) * 100.0)
                                cents = (1200.0 * kotlin.math.ln(r) / kotlin.math.ln(2.0)).toFloat()
                            }
                        },
                        label = { Text("Новая длина (с)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = srcHzStr, onValueChange = { srcHzStr = it }, label = { Text("Исх. Гц") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = dstHzStr, onValueChange = { dstHzStr = it }, label = { Text("Цель Гц") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = {
                            val s = srcHzStr.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 } ?: 440.0
                            val d = dstHzStr.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 } ?: 440.0
                            setRatio(d / s)
                        },
                        border = BorderStroke(1.dp, DlgNeon),
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text("OK", color = DlgNeon, fontSize = 11.sp) }
                }
                FreeNumberSlider(
                    label = "Сдвиг в центах",
                    value = cents,
                    sliderRange = -1200f..1200f,
                    onValueChange = { c ->
                        cents = c
                        setRatio(2.0.pow(c.toDouble() / 1200.0), syncCents = false)
                    },
                    accentColor = DlgNeon
                )

                // Custom user presets
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = presetName, onValueChange = { presetName = it }, label = { Text("Имя пресета") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = {
                            val nm = presetName.trim().ifEmpty { String.format(Locale.US, "x%.3f", speedRatio) }.replace(";", "").replace("=", "")
                            savePresets(customPresets.filterNot { it.first == nm } + (nm to speedRatio))
                            presetName = ""
                        },
                        border = BorderStroke(1.dp, DlgCyan)
                    ) { Text("+ Сохранить", color = DlgCyan, fontSize = 11.sp) }
                }
                if (customPresets.isNotEmpty()) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        customPresets.forEach { (lbl, v) ->
                            InputChip(
                                selected = false,
                                onClick = { setRatio(v) },
                                label = { Text(lbl, fontSize = 11.sp, color = DlgText) },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, "Удалить", tint = DlgText.copy(alpha = 0.7f), modifier = Modifier.size(14.dp).clickable {
                                        savePresets(customPresets.filterNot { it.first == lbl })
                                    })
                                }
                            )
                        }
                    }
                }
                PreviewButton { onPreview(speedRatio.coerceAtLeast(0.05)) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(speedRatio.coerceAtLeast(0.05)) }) { Text("Применить", color = DlgNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun SoundTouchDialog(
    currentDurationSec: Double = 10.0,
    currentBpm: Double = 120.0,
    onDismiss: () -> Unit,
    onPreview: (Double, Double) -> Unit,
    onApply: (Double, Double) -> Unit
) {
    var modeTab by remember { mutableIntStateOf(0) } // 0 = Оба, 1 = Высота тона (ноты/Гц), 2 = Темп (BPM/длина)
    var tempoChangePct by remember { mutableFloatStateOf(0f) }
    var pitchSemi by remember { mutableFloatStateOf(0f) }

    val notes = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    var fromNote by remember { mutableIntStateOf(9) } // A
    var fromOct by remember { mutableIntStateOf(4) }
    var toNote by remember { mutableIntStateOf(9) }
    var toOct by remember { mutableIntStateOf(4) }
    var fromHzStr by remember { mutableStateOf("440.0") }
    var toHzStr by remember { mutableStateOf("440.0") }
    var pitchPctStr by remember { mutableStateOf("0.000") }

    val safeDur = currentDurationSec.coerceAtLeast(0.001)
    var fromBpmStr by remember { mutableStateOf(String.format(Locale.US, "%.1f", currentBpm)) }
    var toBpmStr by remember { mutableStateOf(String.format(Locale.US, "%.1f", currentBpm)) }
    var toDurStr by remember { mutableStateOf(String.format(Locale.US, "%.2f", safeDur)) }

    fun syncPitchFromSemis(semi: Float) {
        pitchSemi = semi
        val ratio = 2.0.pow(semi.toDouble() / 12.0)
        pitchPctStr = String.format(Locale.US, "%.3f", (ratio - 1.0) * 100.0)
        val f0 = fromHzStr.replace(',', '.').toDoubleOrNull() ?: 440.0
        toHzStr = String.format(Locale.US, "%.3f", f0 * ratio)
    }

    val tempoRatio = (1.0 + tempoChangePct / 100.0).coerceAtLeast(0.05)

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Темп и Высота тона (SoundTouch)", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Темп + Тон", "Тон (Ноты / Гц)", "Темп (BPM / Длина)").forEachIndexed { i, lbl ->
                        FilterChip(selected = modeTab == i, onClick = { modeTab = i }, label = { Text(lbl, fontSize = 11.sp) })
                    }
                }
                if (modeTab == 0 || modeTab == 2) {
                    FreeNumberSlider("Изменение темпа (%)", tempoChangePct, -75f..200f, { v ->
                        tempoChangePct = v
                        val tr = (1.0 + v / 100.0).coerceAtLeast(0.05)
                        toDurStr = String.format(Locale.US, "%.2f", safeDur / tr)
                    })
                    if (modeTab == 2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(value = fromBpmStr, onValueChange = { fromBpmStr = it }, label = { Text("BPM от") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = toBpmStr, onValueChange = {
                                toBpmStr = it
                                val b1 = fromBpmStr.replace(',', '.').toDoubleOrNull()
                                val b2 = it.replace(',', '.').toDoubleOrNull()
                                if (b1 != null && b2 != null && b1 > 0.0 && b2 > 0.0) {
                                    tempoChangePct = ((b2 / b1 - 1.0) * 100.0).toFloat()
                                    toDurStr = String.format(Locale.US, "%.2f", safeDur * b1 / b2)
                                }
                            }, label = { Text("BPM до") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(String.format(Locale.US, "Длина от %.2fс", safeDur), color = DlgText.copy(alpha = 0.75f), fontSize = 11.sp)
                            OutlinedTextField(value = toDurStr, onValueChange = {
                                toDurStr = it
                                val nd = it.replace(',', '.').toDoubleOrNull()
                                if (nd != null && nd > 0.0) {
                                    tempoChangePct = ((safeDur / nd - 1.0) * 100.0).toFloat()
                                }
                            }, label = { Text("Длина до (сек)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (modeTab == 0 || modeTab == 1) {
                    FreeNumberSlider("Полутона", pitchSemi, -12f..12f, { syncPitchFromSemis(it) }, accentColor = DlgNeon)
                    if (modeTab == 1) {
                        Text("От ноты -> До ноты:", color = DlgText.copy(alpha = 0.8f), fontSize = 11.sp)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            notes.forEachIndexed { idx, n ->
                                FilterChip(selected = fromNote == idx, onClick = {
                                    fromNote = idx
                                    val semi = ((toOct - fromOct) * 12 + (toNote - idx)).toFloat()
                                    syncPitchFromSemis(semi)
                                }, label = { Text("$n$fromOct", fontSize = 10.sp) })
                            }
                        }
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            notes.forEachIndexed { idx, n ->
                                FilterChip(selected = toNote == idx, onClick = {
                                    toNote = idx
                                    val semi = ((toOct - fromOct) * 12 + (idx - fromNote)).toFloat()
                                    syncPitchFromSemis(semi)
                                }, label = { Text("→$n$toOct", fontSize = 10.sp) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(value = fromHzStr, onValueChange = { fromHzStr = it }, label = { Text("От (Гц)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = toHzStr, onValueChange = {
                                toHzStr = it
                                val f1 = fromHzStr.replace(',', '.').toDoubleOrNull()
                                val f2 = it.replace(',', '.').toDoubleOrNull()
                                if (f1 != null && f2 != null && f1 > 0.0 && f2 > 0.0) {
                                    val semi = (12.0 * kotlin.math.ln(f2 / f1) / kotlin.math.ln(2.0)).toFloat()
                                    pitchSemi = semi
                                    pitchPctStr = String.format(Locale.US, "%.3f", (f2 / f1 - 1.0) * 100.0)
                                }
                            }, label = { Text("До (Гц)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                        }
                        OutlinedTextField(value = pitchPctStr, onValueChange = {
                            pitchPctStr = it
                            val p = it.replace(',', '.').toDoubleOrNull()
                            if (p != null && (1.0 + p / 100.0) > 0.0) {
                                pitchSemi = (12.0 * kotlin.math.ln(1.0 + p / 100.0) / kotlin.math.ln(2.0)).toFloat()
                            }
                        }, label = { Text("Процент изменения тона (%)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                PreviewButton { onPreview(tempoRatio, pitchSemi.toDouble()) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(tempoRatio, pitchSemi.toDouble()) }) { Text("Применить", color = DlgNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun PaulstretchDialog(
    onDismiss: () -> Unit,
    onPreview: (Double, Double) -> Unit,
    onApply: (Double, Double) -> Unit
) {
    var factor by remember { mutableFloatStateOf(10.0f) }
    var timeRes by remember { mutableFloatStateOf(0.25f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("PaulStretch", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeNumberSlider("Коэффициент растяжения", factor, 1.0f..20f, { factor = it })
                FreeNumberSlider("Разрешение времени (сек.)", timeRes, 0.05f..2.0f, { timeRes = it }, accentColor = DlgNeon)
                PreviewButton { onPreview(factor.coerceAtLeast(0.1f).toDouble(), timeRes.coerceAtLeast(0.01f).toDouble()) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(factor.coerceAtLeast(0.1f).toDouble(), timeRes.coerceAtLeast(0.01f).toDouble()) }) { Text("Применить", color = DlgNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun NoiseGateDialog(
    onDismiss: () -> Unit,
    onPreview: (Float, Float, Float) -> Unit,
    onApply: (Float, Float, Float) -> Unit
) {
    var thresh by remember { mutableFloatStateOf(-42f) }
    var attack by remember { mutableFloatStateOf(5f) }
    var release by remember { mutableFloatStateOf(150f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Noise Gate", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FreeNumberSlider("Порог (dB)", thresh, -90f..0f, { thresh = it })
                FreeNumberSlider("Атака (мс)", attack, 1f..100f, { attack = it }, accentColor = DlgNeon)
                FreeNumberSlider("Спад (мс)", release, 10f..1000f, { release = it }, accentColor = DlgCyan)
                PreviewButton { onPreview(thresh, attack.coerceAtLeast(0.1f), release.coerceAtLeast(0.1f)) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(thresh, attack.coerceAtLeast(0.1f), release.coerceAtLeast(0.1f)) }) { Text("Применить", color = DlgNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun GenerateDialog(
    bpm: Double,
    onDismiss: () -> Unit,
    onGenerateSignal: (type: Int, freqHz: Double, amp: Float, durSec: Double) -> Unit,
    onGenerateChirp: (f1: Double, f2: Double, durSec: Double) -> Unit,
    onGenerateRhythm: (bpm: Double, durSec: Double) -> Unit,
    onGenerateDtmf: (digits: String, toneSec: Double) -> Unit,
    onGeneratePluck: (freqHz: Double, durSec: Double) -> Unit = { _, _ -> },
    onGenerateRissetDrum: (freqHz: Double, durSec: Double) -> Unit = { _, _ -> }
) {
    var mode by remember { mutableIntStateOf(0) } // 0..5 = Sine/Sq/Saw/White/Pink/Brown, 6 = Chirp, 7 = Rhythm, 8 = DTMF, 9 = Pluck, 10 = Risset Drum
    var freqText by remember { mutableStateOf("440") }
    var freq2Text by remember { mutableStateOf("2000") }
    var dtmfText by remember { mutableStateOf("1234567890*#") }
    var durText by remember { mutableStateOf("5.0") }
    val types = listOf("Синус", "Меандр", "Пила", "Белый шум", "Розовый шум", "Коричн. шум", "Chirp", "Метроном", "DTMF", "Pluck", "Risset Drum")

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Генератор сигналов", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.forEachIndexed { idx, label ->
                        FilterChip(selected = mode == idx, onClick = { mode = idx }, label = { Text(label, fontSize = 11.sp) })
                    }
                }
                if (mode <= 2 || mode == 6 || mode == 9 || mode == 10) {
                    OutlinedTextField(value = freqText, onValueChange = { freqText = it }, label = { Text(if (mode == 6) "Нач. частота (Гц)" else "Частота (Гц)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (mode == 6) {
                    OutlinedTextField(value = freq2Text, onValueChange = { freq2Text = it }, label = { Text("Кон. частота (Гц)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (mode == 8) {
                    OutlinedTextField(value = dtmfText, onValueChange = { dtmfText = it }, label = { Text("Цифры DTMF (0-9, *, #, A-D)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(value = durText, onValueChange = { durText = it }, label = { Text("Длительность (сек)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val f = freqText.toDoubleOrNull()?.coerceAtLeast(1.0) ?: 440.0
                val f2 = freq2Text.toDoubleOrNull()?.coerceAtLeast(1.0) ?: 2000.0
                val d = durText.toDoubleOrNull()?.coerceAtLeast(0.01) ?: 5.0
                when (mode) {
                    6 -> onGenerateChirp(f, f2, d)
                    7 -> onGenerateRhythm(bpm, d)
                    8 -> onGenerateDtmf(dtmfText, 0.15)
                    9 -> onGeneratePluck(f, d)
                    10 -> onGenerateRissetDrum(f, d)
                    else -> onGenerateSignal(mode, f, 0.7f, d)
                }
            }) { Text("Создать", color = DlgNeon, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun AnalysisDialog(stats: FloatArray, seamSamples: FloatArray, onDismiss: () -> Unit, onRepairSeam: () -> Unit) {
    val peakL = stats.getOrElse(0) { -96f }
    val peakR = stats.getOrElse(1) { -96f }
    val rmsL = stats.getOrElse(2) { -96f }
    val rmsR = stats.getOrElse(3) { -96f }
    val clips = stats.getOrElse(4) { 0f }.toLong()
    val seamDelta = stats.getOrElse(5) { 0f }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Анализ и Осциллограф шва", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(String.format(Locale.US, "Пик L/R: %.1f dB / %.1f dB", peakL, peakR), color = DlgText, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text(String.format(Locale.US, "RMS L/R: %.1f dB / %.1f dB", rmsL, rmsR), color = DlgText, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text("Клиппинг сэмплов: $clips", color = if (clips > 0) Color(0xFFFF5252) else DlgNeon, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text(String.format(Locale.US, "Разрыв шва (Δ): %.2f %%", seamDelta), color = if (seamDelta > 3f) Color(0xFFFFB74D) else DlgNeon, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

                if (seamSamples.size >= 4) {
                    Surface(color = Color(0xFF11151B), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, DlgBorder), modifier = Modifier.fillMaxWidth().height(90.dp)) {
                        val accentCol = DlgAccent
                        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                            val midY = size.height / 2f
                            drawLine(Color(0xFF2B3442), Offset(0f, midY), Offset(size.width, midY), 1f)
                            drawLine(Color(0xFFFFB74D), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1.5f)
                            val n = seamSamples.size / 2
                            val stepX = size.width / (n - 1).coerceAtLeast(1)
                            for (i in 0 until n - 1) {
                                val y1 = midY - seamSamples[i * 2] * (size.height * 0.44f)
                                val y2 = midY - seamSamples[(i + 1) * 2] * (size.height * 0.44f)
                                drawLine(accentCol, Offset(i * stepX, y1), Offset((i + 1) * stepX, y2), 2f)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onRepairSeam) { Text("LSAR Repair стыка", color = DlgNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun SelectionEditDialog(
    initialStart: Long, initialEnd: Long, maxFrames: Long, sampleRate: Int,
    initialBpm: Double, initialBeatsPerBar: Int, initialGridOffset: Long,
    initialShowGrid: Boolean, playheadSample: Long,
    onDismiss: () -> Unit, onApply: (start: Long, end: Long, bpm: Double, beatsPerBar: Int, gridOffset: Long, showGrid: Boolean) -> Unit
) {
    var startStr by remember { mutableStateOf(initialStart.toString()) }
    var endStr by remember { mutableStateOf(initialEnd.toString()) }
    var bpmStr by remember { mutableStateOf(String.format(Locale.US, "%.2f", initialBpm)) }
    var beatsPerBar by remember { mutableIntStateOf(initialBeatsPerBar) }
    var offsetStr by remember { mutableStateOf(initialGridOffset.toString()) }
    var showGrid by remember { mutableStateOf(initialShowGrid) }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Сетка, BPM, Размер и Границы", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sample rate: $sampleRate Hz • Всего: $maxFrames smp", color = DlgText.copy(alpha = 0.75f), fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = showGrid, onClick = { showGrid = !showGrid }, label = { Text(if (showGrid) "Сетка: ВКЛ" else "Сетка: ВЫКЛ") })
                    OutlinedButton(onClick = { offsetStr = playheadSample.toString() }, border = BorderStroke(1.dp, DlgBorder)) {
                        Text("Доля = Курсор", color = DlgAccent, fontSize = 11.sp)
                    }
                }
                OutlinedTextField(value = bpmStr, onValueChange = { bpmStr = it }, label = { Text("Темп сетки (BPM)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Тактовый размер:", color = DlgText.copy(alpha = 0.85f), fontSize = 11.sp)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2 to "2/4", 3 to "3/4", 4 to "4/4", 5 to "5/4", 6 to "6/8", 7 to "7/8").forEach { (b, lbl) ->
                        FilterChip(selected = beatsPerBar == b, onClick = { beatsPerBar = b }, label = { Text(lbl, fontSize = 11.sp) })
                    }
                }
                OutlinedTextField(value = offsetStr, onValueChange = { offsetStr = it }, label = { Text("Смещение сильной доли (gridOffset)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = startStr, onValueChange = { startStr = it }, label = { Text("Начало выделения (сэмпл)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = endStr, onValueChange = { endStr = it }, label = { Text("Конец выделения (сэмпл)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = (startStr.toLongOrNull() ?: initialStart).coerceIn(0L, (maxFrames - 1).coerceAtLeast(0L))
                val e = (endStr.toLongOrNull() ?: initialEnd).coerceIn((s + 1).coerceAtMost(maxFrames), maxFrames)
                val b = (bpmStr.toDoubleOrNull() ?: initialBpm).coerceIn(20.0, 300.0)
                val off = (offsetStr.toLongOrNull() ?: initialGridOffset).coerceIn(0L, maxFrames)
                onApply(s, e, b, beatsPerBar, off, showGrid)
            }) { Text("Применить", color = DlgNeon, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    WAV_16("WAV 16-bit PCM (+smpl)", "wav", "audio/wav"),
    WAV_24("WAV 24-bit PCM (+smpl)", "wav", "audio/wav"),
    WAV_32F("WAV 32-bit IEEE Float", "wav", "audio/wav"),
    FLAC_16("FLAC 16-bit Lossless", "flac", "audio/flac"),
    FLAC_24("FLAC 24-bit Studio", "flac", "audio/flac"),
    AIFF_16("AIFF 16-bit Apple/Studio", "aiff", "audio/aiff"),
    M4A_AAC("M4A (AAC 256 kbps)", "m4a", "audio/mp4")
}

@Composable
fun ExportFormatDialog(onDismiss: () -> Unit, onExport: (ExportFormat, Boolean) -> Unit) {
    var selected by remember { mutableStateOf(ExportFormat.WAV_16) }
    var writeSmpl by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Экспорт аудио", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Выбери формат:", color = DlgText.copy(alpha = 0.8f), fontSize = 11.sp)
                ExportFormat.entries.forEach { fmt ->
                    FilterChip(selected = selected == fmt, onClick = { selected = fmt }, label = { Text(fmt.label, fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
                }
                if (selected == ExportFormat.WAV_16 || selected == ExportFormat.WAV_24 || selected == ExportFormat.WAV_32F) {
                    FilterChip(selected = writeSmpl, onClick = { writeSmpl = !writeSmpl }, label = { Text("Записать RIFF smpl-чанк лупа", fontSize = 11.sp) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onExport(selected, writeSmpl) }) { Text("Экспортировать", color = DlgNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun CompressorDialog(onDismiss: () -> Unit, onPreview: (Float, Float) -> Unit, onApply: (Float, Float) -> Unit) {
    var thresh by remember { mutableFloatStateOf(-14f) }
    var ratio by remember { mutableFloatStateOf(4f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Компрессор", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeNumberSlider("Порог (dB)", thresh, -40f..0f, { thresh = it })
                FreeNumberSlider("Сжатие (:1)", ratio, 1.5f..10f, { ratio = it }, accentColor = DlgNeon)
                PreviewButton { onPreview(thresh, ratio.coerceAtLeast(1f)) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(thresh, ratio.coerceAtLeast(1f)) }) { Text("Применить", color = DlgNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun FilterDialog(onDismiss: () -> Unit, onPreview: (Int, Double) -> Unit, onApply: (Int, Double) -> Unit) {
    var type by remember { mutableIntStateOf(1) }
    var cutoff by remember { mutableFloatStateOf(120f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Фильтр (EQ)", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == 1, onClick = { type = 1 }, label = { Text("ФВЧ (High-Pass)") })
                    FilterChip(selected = type == 0, onClick = { type = 0 }, label = { Text("ФНЧ (Low-Pass)") })
                }
                FreeNumberSlider("Частота среза (Гц)", cutoff, 40f..12000f, { cutoff = it })
                PreviewButton { onPreview(type, cutoff.coerceAtLeast(1f).toDouble()) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(type, cutoff.coerceAtLeast(1f).toDouble()) }) { Text("Применить", color = DlgNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun BassTrebleDialog(onDismiss: () -> Unit, onPreview: (Float, Float) -> Unit, onApply: (Float, Float) -> Unit) {
    var bass by remember { mutableFloatStateOf(0f) }
    var treble by remember { mutableFloatStateOf(0f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Низкие и Высокие (Bass & Treble)", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeNumberSlider("Бас (dB)", bass, -15f..15f, { bass = it })
                FreeNumberSlider("Высокие (dB)", treble, -15f..15f, { treble = it }, accentColor = DlgNeon)
                PreviewButton { onPreview(bass, treble) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(bass, treble) }) { Text("Применить", color = DlgNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun EchoReverbDialog(
    isReverb: Boolean,
    bpm: Double,
    onDismiss: () -> Unit,
    onPreview: (Float, Float, Boolean) -> Unit,
    onApply: (Float, Float, Boolean) -> Unit
) {
    var p1 by remember { mutableFloatStateOf(if (isReverb) 0.55f else 250f) }
    var p2 by remember { mutableFloatStateOf(if (isReverb) 0.35f else 0.45f) }
    var wrapTail by remember { mutableStateOf(true) }
    val beatMs = (60_000.0 / bpm.coerceAtLeast(1.0)).toFloat()

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text(if (isReverb) "Реверберация (Reverb)" else "Эхо (Echo + BPM Sync)", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeNumberSlider(
                    if (isReverb) "Комната (0..1)" else "Задержка (мс)",
                    p1,
                    if (isReverb) 0.1f..1f else 40f..1000f,
                    { p1 = it }
                )
                if (!isReverb) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(beatMs to "1/4", beatMs * 0.5f to "1/8", beatMs * 0.75f to "1/8.", beatMs / 3f to "1/3").forEach { (ms, lbl) ->
                            OutlinedButton(onClick = { p1 = ms.coerceAtLeast(1f) }, border = BorderStroke(1.dp, DlgBorder), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.height(28.dp)) {
                                Text(lbl, fontSize = 10.sp, color = DlgText)
                            }
                        }
                    }
                }
                FreeNumberSlider(
                    if (isReverb) "Микс Wet (0..1)" else "Затухание (0..1)",
                    p2,
                    0.05f..0.85f,
                    { p2 = it },
                    accentColor = DlgNeon
                )
                FilterChip(selected = wrapTail, onClick = { wrapTail = !wrapTail }, label = { Text("Wrap Tail into Loop (бесшовный хвост)", fontSize = 11.sp) })
                PreviewButton { onPreview(p1, p2, wrapTail) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(p1, p2, wrapTail) }) { Text("Применить", color = DlgNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun SpectrumDialog(spectrumDb: FloatArray, sampleRate: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("График спектра (FFT)", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Диапазон: 0 .. ${sampleRate / 2} Гц (-96 .. 0 dB)", color = DlgText.copy(alpha = 0.75f), fontSize = 11.sp)
                Surface(color = Color(0xFF11151B), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, DlgBorder), modifier = Modifier.fillMaxWidth().height(150.dp)) {
                    val accentCol = DlgAccent
                    Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                        if (spectrumDb.isNotEmpty()) {
                            val bw = size.width / spectrumDb.size
                            spectrumDb.forEachIndexed { i, db ->
                                val norm = ((db + 96f) / 96f).coerceIn(0f, 1f)
                                val h = norm * size.height
                                drawRect(accentCol, Offset(i * bw, size.height - h), Size((bw * 0.8f).coerceAtLeast(1.5f), h))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = DlgNeon, fontWeight = FontWeight.Bold) } }
    )
}

@Composable
fun SmoothBoundaryDialog(
    onDismiss: () -> Unit,
    onApplyMicroFade: (Float) -> Unit,
    onApplyLoopCrossfade: (Float) -> Unit
) {
    var ms by remember { mutableFloatStateOf(20f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Границы сглаживания", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(String.format(Locale.US, "Ширина сглаживания: %.0f мс", ms), color = DlgAccent, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Slider(value = ms, onValueChange = { ms = it }, valueRange = 2f..150f)
                Text("• Микро-фейд сглаживает вход/выход выделения.\n• Кроссфейд шва смешивает конец и начало петли без щелчка.", color = DlgText.copy(alpha = 0.75f), fontSize = 11.sp)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onApplyMicroFade(ms) }, border = BorderStroke(1.dp, DlgAccent)) {
                        Text("Микро-фейд", color = DlgAccent, fontSize = 12.sp)
                    }
                    Button(onClick = { onApplyLoopCrossfade(ms) }, colors = ButtonDefaults.buttonColors(containerColor = DlgNeon, contentColor = Color.Black)) {
                        Text("Кроссфейд шва", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun Eq5Dialog(
    onDismiss: () -> Unit,
    onPreview: (Float, Float, Float, Float, Float) -> Unit,
    onApply: (Float, Float, Float, Float, Float) -> Unit
) {
    var g60 by remember { mutableFloatStateOf(0f) }
    var g250 by remember { mutableFloatStateOf(0f) }
    var g1k by remember { mutableFloatStateOf(0f) }
    var g4k by remember { mutableFloatStateOf(0f) }
    var g12k by remember { mutableFloatStateOf(0f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Графический Эквалайзер (5 полос)", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FreeNumberSlider("60 Гц (dB)", g60, -15f..15f, { g60 = it })
                FreeNumberSlider("250 Гц (dB)", g250, -15f..15f, { g250 = it })
                FreeNumberSlider("1 кГц (dB)", g1k, -15f..15f, { g1k = it }, accentColor = DlgNeon)
                FreeNumberSlider("4 кГц (dB)", g4k, -15f..15f, { g4k = it }, accentColor = DlgCyan)
                FreeNumberSlider("12 кГц (dB)", g12k, -15f..15f, { g12k = it }, accentColor = DlgCyan)
                PreviewButton { onPreview(g60, g250, g1k, g4k, g12k) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(g60, g250, g1k, g4k, g12k) }) { Text("Применить", color = DlgNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun PhaserDialog(
    onDismiss: () -> Unit,
    onPreview: (Float, Float, Float) -> Unit,
    onApply: (Float, Float, Float) -> Unit
) {
    var rate by remember { mutableFloatStateOf(0.5f) }
    var depth by remember { mutableFloatStateOf(0.7f) }
    var fb by remember { mutableFloatStateOf(0.4f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Фейзер (Phaser)", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FreeNumberSlider("LFO Скорость (Гц)", rate, 0.1f..5.0f, { rate = it })
                FreeNumberSlider("Глубина (0..1)", depth, 0.1f..1.0f, { depth = it }, accentColor = DlgNeon)
                FreeNumberSlider("Обратная связь", fb, -0.8f..0.8f, { fb = it }, accentColor = DlgCyan)
                PreviewButton { onPreview(rate, depth, fb) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(rate, depth, fb) }) { Text("Применить", color = DlgNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun WahwahDialog(
    onDismiss: () -> Unit,
    onPreview: (Float, Float, Float) -> Unit,
    onApply: (Float, Float, Float) -> Unit
) {
    var freq by remember { mutableFloatStateOf(1.5f) }
    var depth by remember { mutableFloatStateOf(0.75f) }
    var res by remember { mutableFloatStateOf(2.5f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Вау-вау (Wahwah)", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FreeNumberSlider("LFO Частота (Гц)", freq, 0.2f..6.0f, { freq = it })
                FreeNumberSlider("Глубина (0..1)", depth, 0.1f..1.0f, { depth = it }, accentColor = DlgNeon)
                FreeNumberSlider("Резонанс (Q)", res, 0.5f..8.0f, { res = it }, accentColor = DlgCyan)
                PreviewButton { onPreview(freq, depth, res) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(freq, depth, res) }) { Text("Применить", color = DlgNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun DistortionDialog(
    onDismiss: () -> Unit,
    onPreview: (Int, Float, Float) -> Unit,
    onApply: (Int, Float, Float) -> Unit
) {
    var mode by remember { mutableIntStateOf(0) } // 0=Soft, 1=Hard, 2=Bitcrush
    var drive by remember { mutableFloatStateOf(4.0f) }
    var mix by remember { mutableFloatStateOf(0.7f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Дисторшн / Bitcrusher", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Soft Overdrive", "Hard Clip", "Bitcrusher 8-bit").forEachIndexed { i, lbl ->
                        FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(lbl, fontSize = 11.sp) })
                    }
                }
                FreeNumberSlider("Драйв (x)", drive, 1f..16f, { drive = it })
                FreeNumberSlider("Микс Wet (0..1)", mix, 0.1f..1.0f, { mix = it }, accentColor = DlgNeon)
                PreviewButton { onPreview(mode, drive, mix) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(mode, drive, mix) }) { Text("Применить", color = DlgNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun RepeatDialog(onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    var count by remember { mutableFloatStateOf(2f) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Повтор выделения (Repeat)", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeNumberSlider("Количество повторов", count, 1f..12f, { count = it }, isInteger = true)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(count.toInt().coerceAtLeast(1)) }) { Text("Применить", color = DlgNeon, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

enum class CustomSnapUnit { BEAT_DIV, SAMPLES, MS }

@Composable
fun SnapConfigDialog(
    currentMode: String,
    currentCustomDiv: Int,
    currentCustomSamples: Long,
    currentCustomMs: Double,
    currentCustomUnit: CustomSnapUnit,
    onDismiss: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onApplyCustom: (unit: CustomSnapUnit, div: Int, samples: Long, ms: Double) -> Unit
) {
    var unit by remember { mutableStateOf(currentCustomUnit) }
    var divStr by remember { mutableStateOf(currentCustomDiv.toString()) }
    var smpStr by remember { mutableStateOf(currentCustomSamples.toString()) }
    var msStr by remember { mutableStateOf(String.format(Locale.US, "%.1f", currentCustomMs)) }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Настройка привязки (Snap)", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Быстрый выбор:", color = DlgText.copy(alpha = 0.8f), fontSize = 11.sp)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Выкл", "Zero", "1 такт", "1/4", "1/8", "1/16", "1/3", "1/6").forEach { lbl ->
                        FilterChip(selected = currentMode == lbl, onClick = { onSelectPreset(lbl) }, label = { Text(lbl, fontSize = 11.sp) })
                    }
                }
                HorizontalDivider(color = DlgBorder)
                Text("Кастомный шаг:", color = DlgNeon, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = unit == CustomSnapUnit.BEAT_DIV, onClick = { unit = CustomSnapUnit.BEAT_DIV }, label = { Text("Доля 1/N", fontSize = 11.sp) })
                    FilterChip(selected = unit == CustomSnapUnit.SAMPLES, onClick = { unit = CustomSnapUnit.SAMPLES }, label = { Text("Сэмплы", fontSize = 11.sp) })
                    FilterChip(selected = unit == CustomSnapUnit.MS, onClick = { unit = CustomSnapUnit.MS }, label = { Text("Мс", fontSize = 11.sp) })
                }
                when (unit) {
                    CustomSnapUnit.BEAT_DIV -> OutlinedTextField(
                        value = divStr, onValueChange = { divStr = it },
                        label = { Text("Делитель такта/доли N (напр. 5, 7, 12, 32)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    CustomSnapUnit.SAMPLES -> OutlinedTextField(
                        value = smpStr, onValueChange = { smpStr = it },
                        label = { Text("Шаг в сэмплах (напр. 512, 2048)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    CustomSnapUnit.MS -> OutlinedTextField(
                        value = msStr, onValueChange = { msStr = it },
                        label = { Text("Шаг в миллисекундах (напр. 12.5)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = (divStr.toIntOrNull() ?: currentCustomDiv).coerceAtLeast(1)
                val s = (smpStr.toLongOrNull() ?: currentCustomSamples).coerceAtLeast(1L)
                val m = (msStr.toDoubleOrNull() ?: currentCustomMs).coerceAtLeast(0.001)
                onApplyCustom(unit, d, s, m)
            }) { Text("Применить кастомный", color = DlgNeon, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun ThemePickerDialog(
    isDark: Boolean,
    isMaterial3: Boolean,
    onDismiss: () -> Unit,
    onSelect: (dark: Boolean, m3: Boolean) -> Unit
) {
    var dark by remember { mutableStateOf(isDark) }
    var m3 by remember { mutableStateOf(isMaterial3) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Тема оформления", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Стиль интерфейса:", color = DlgText.copy(alpha = 0.8f), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !m3, onClick = { m3 = false }, label = { Text("Старая (Studio)") })
                    FilterChip(selected = m3, onClick = { m3 = true }, label = { Text("Material 3 (M3)") })
                }
                Text("Яркость:", color = DlgText.copy(alpha = 0.8f), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = dark, onClick = { dark = true }, label = { Text("Тёмная") })
                    FilterChip(selected = !dark, onClick = { dark = false }, label = { Text("Светлая") })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(dark, m3) }) { Text("Применить", color = DlgNeon, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun LabelEditDialog(
    initialText: String,
    initialColorHex: Long,
    isRegion: Boolean,
    rangeText: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (String, Long) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    var colorHex by remember { mutableLongStateOf(initialColorHex) }
    val palette = listOf(0xFFFFB300L, 0xFF00E5FFL, 0xFF39FF14L, 0xFFFF5252L, 0xFFE040FBL)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text(if (isRegion) "Метка-регион" else "Метка-точка", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(rangeText, color = DlgText.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Название метки") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = DlgText, unfocusedTextColor = DlgText)
                )
                Text("Цвет:", color = DlgText.copy(alpha = 0.8f), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.forEach { hex ->
                        val selected = hex == colorHex
                        Surface(
                            color = Color(hex),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(if (selected) 2.5.dp else 1.dp, if (selected) DlgText else DlgBorder),
                            modifier = Modifier.size(28.dp).clickable { colorHex = hex }
                        ) {}
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onDelete) { Text("Удалить", color = Color(0xFFFF5252)) }
                TextButton(onClick = { onSave(text.ifBlank { "Метка" }, colorHex) }) {
                    Text("Сохранить", color = DlgNeon, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DlgText.copy(alpha = 0.75f)) } }
    )
}

@Composable
fun PlaybackSpeedDialog(
    currentSpeed: Float,
    onDismiss: () -> Unit,
    onApply: (Float) -> Unit
) {
    var speed by remember { mutableFloatStateOf(currentSpeed.coerceAtLeast(0.01f)) }
    val presets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DlgBg,
        title = { Text("Скорость прослушивания", color = DlgText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FreeNumberSlider("Скорость (x)", speed, 0.25f..3.0f, { v -> speed = v; onApply(v.coerceAtLeast(0.01f)) }, accentColor = DlgNeon)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { p ->
                        FilterChip(
                            selected = kotlin.math.abs(speed - p) < 0.02f,
                            onClick = { speed = p; onApply(p) },
                            label = { Text("${p}x", fontSize = 11.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Готово", color = DlgNeon, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = { speed = 1.0f; onApply(1.0f); onDismiss() }) { Text("Сброс 1.0x", color = DlgText.copy(alpha = 0.75f)) }
        }
    )
}

