package com.frightg.wavez

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frightg.wavez.audio.M4aExporter
import com.frightg.wavez.audio.WaveZAudioDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

private val StudioChrome: Color @Composable get() = LocalWaveZPalette.current.chrome
private val StudioPanel: Color @Composable get() = LocalWaveZPalette.current.panel
private val StudioWorkspace: Color @Composable get() = LocalWaveZPalette.current.workspace
private val StudioLane: Color @Composable get() = LocalWaveZPalette.current.lane
private val StudioBorder: Color @Composable get() = LocalWaveZPalette.current.border
private val StudioWave: Color @Composable get() = LocalWaveZPalette.current.wave
private val StudioWaveSel: Color @Composable get() = LocalWaveZPalette.current.waveSel
private val StudioWaveRms: Color @Composable get() = LocalWaveZPalette.current.waveRms
private val StudioNeon: Color @Composable get() = LocalWaveZPalette.current.neon
private val StudioCyan: Color @Composable get() = LocalWaveZPalette.current.cyan
private val StudioAmber: Color @Composable get() = LocalWaveZPalette.current.amber
private val StudioMagenta: Color @Composable get() = LocalWaveZPalette.current.magenta
private val StudioText: Color @Composable get() = LocalWaveZPalette.current.text

private enum class SnapMode(val label: String) {
    OFF("Выкл"),
    ZERO("Zero"),
    BAR("1 такт"),
    BEAT("1/4"),
    EIGHTH("1/8"),
    SIXTEENTH("1/16"),
    TRIPLET("1/3"),
    SIXTH("1/6"),
    CUSTOM("Свой")
}

private enum class WaveZToolMode(val label: String) {
    SELECT("Курсор"),
    TIME_SHIFT("Сдвиг ⇄"),
    ENVELOPE("Огиб. 📈")
}

private data class WaveZLabel(
    val id: Long,
    val startSample: Long,
    val endSample: Long,
    val text: String,
    val colorHex: Long = 0xFFFFB300L
)

private data class TrackUi(
    val name: String,
    val gain: Float = 1.0f,
    val pan: Float = 0.0f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val offsetFrames: Long = 0L,
    val envelope: List<Pair<Long, Float>> = emptyList(),
    val peaks: FloatArray = FloatArray(0),
    val rawSamples: FloatArray = FloatArray(0),
    val spectrogram: FloatArray = FloatArray(0)
)

private object WaveZSession {
    var introPlayed = false
}

class MainActivity : ComponentActivity() {
    var keyHandler: ((KeyEvent) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WaveZStudioApp(registerKeyHandler = { keyHandler = it })
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && keyHandler?.invoke(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            WaveZNative.release()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaveZStudioApp(registerKeyHandler: ((KeyEvent) -> Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tracks by remember { mutableStateOf<List<TrackUi>>(emptyList()) }
    var activeTrackIdx by remember { mutableIntStateOf(0) }
    var sourceUriString by remember { mutableStateOf("") }
    var sampleRate by remember { mutableIntStateOf(44100) }
    var channels by remember { mutableIntStateOf(2) }
    val hasAudio = tracks.isNotEmpty()

    var isLoading by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var overdubEnabled by remember { mutableStateOf(true) }
    val recorder = remember { WaveZRecorder() }
    var playheadSample by remember { mutableLongStateOf(0L) }
    var meterLevels by remember { mutableStateOf(floatArrayOf(-96f, -96f)) }

    var timelineFrames by remember { mutableLongStateOf(0L) }
    var viewStart by remember { mutableLongStateOf(0L) }
    var viewEnd by remember { mutableLongStateOf(0L) }

    var selStart by remember { mutableLongStateOf(0L) }
    var selEnd by remember { mutableLongStateOf(0L) }

    // 4-point Rotation [M1..M4] + GridSpec
    var m1Sample by remember { mutableLongStateOf(0L) }
    var m2Sample by remember { mutableLongStateOf(0L) }
    var m3Sample by remember { mutableLongStateOf(0L) }
    var m4Sample by remember { mutableLongStateOf(0L) }
    var bpm by remember { mutableDoubleStateOf(120.0) }
    var beatsPerBar by remember { mutableIntStateOf(4) }
    var gridOffsetSample by remember { mutableLongStateOf(0L) }

    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var hasClipboard by remember { mutableStateOf(false) }
    var loopEnabled by remember { mutableStateOf(false) }
    var isRotationMode by remember { mutableStateOf(false) }
    var showBeatGrid by remember { mutableStateOf(false) }
    var showClipping by remember { mutableStateOf(true) }
    var showRms by remember { mutableStateOf(true) }
    var showSpectrogram by remember { mutableStateOf(false) }
    var waveformDbScale by remember { mutableStateOf(false) }
    var verticalZoom by remember { mutableFloatStateOf(1.0f) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showPlaybackSpeedDlg by remember { mutableStateOf(false) }
    var toolMode by remember { mutableStateOf(WaveZToolMode.SELECT) }
    var labels by remember { mutableStateOf<List<WaveZLabel>>(emptyList()) }
    var editingLabel by remember { mutableStateOf<WaveZLabel?>(null) }
    var snapMode by remember { mutableStateOf(SnapMode.OFF) }
    var customSnapUnit by remember { mutableStateOf(CustomSnapUnit.BEAT_DIV) }
    var customSnapDiv by remember { mutableIntStateOf(12) }
    var customSnapSamples by remember { mutableLongStateOf(1024L) }
    var customSnapMs by remember { mutableDoubleStateOf(25.0) }
    var showSnapDlg by remember { mutableStateOf(false) }
    var showThemeDlg by remember { mutableStateOf(false) }
    val themePrefs = remember { context.getSharedPreferences("wavez_prefs", android.content.Context.MODE_PRIVATE) }
    var themeDark by remember { mutableStateOf(themePrefs.getBoolean("theme_dark", true)) }
    var themeM3 by remember { mutableStateOf(themePrefs.getBoolean("theme_m3", true)) }

    var openMenu by remember { mutableStateOf<String?>(null) }
    var showTrackSheet by remember { mutableStateOf(false) }
    var showEffectsSheet by remember { mutableStateOf(false) }
    var effectsTab by remember { mutableIntStateOf(0) }
    var lastEffectLabel by remember { mutableStateOf<String?>(null) }
    var lastEffectAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showAmplifyDlg by remember { mutableStateOf(false) }
    var showSpeedDlg by remember { mutableStateOf(false) }
    var showSoundTouchDlg by remember { mutableStateOf(false) }
    var showPaulstretchDlg by remember { mutableStateOf(false) }
    var showNoiseGateDlg by remember { mutableStateOf(false) }
    var showEq5Dlg by remember { mutableStateOf(false) }
    var showPhaserDlg by remember { mutableStateOf(false) }
    var showWahwahDlg by remember { mutableStateOf(false) }
    var showDistortionDlg by remember { mutableStateOf(false) }
    var showRepeatDlg by remember { mutableStateOf(false) }
    var showGenerateDlg by remember { mutableStateOf(false) }
    var showSelectionDlg by remember { mutableStateOf(false) }
    var showCompressorDlg by remember { mutableStateOf(false) }
    var showFilterDlg by remember { mutableStateOf(false) }
    var showBassTrebleDlg by remember { mutableStateOf(false) }
    var showSmoothDlg by remember { mutableStateOf(false) }
    var showNoiseReductionDlg by remember { mutableStateOf(false) }
    var showNotchDlg by remember { mutableStateOf(false) }
    var showVocalDlg by remember { mutableStateOf(false) }
    var showTremoloDlg by remember { mutableStateOf(false) }
    var showSlidingStretchDlg by remember { mutableStateOf(false) }
    var showAdjFadeDlg by remember { mutableStateOf(false) }
    var showAutoDuckDlg by remember { mutableStateOf(false) }
    var showNormalizeDlg by remember { mutableStateOf(false) }
    var showLimiterDlg by remember { mutableStateOf(false) }
    var showTruncateSilDlg by remember { mutableStateOf(false) }
    var showPhantomCenterDlg by remember { mutableStateOf(false) }
    var showClipFixDlg by remember { mutableStateOf(false) }
    var showVocoderDlg by remember { mutableStateOf(false) }
    var showDeEsserDlg by remember { mutableStateOf(false) }
    var showMultibandDlg by remember { mutableStateOf(false) }
    var showSaturatorDlg by remember { mutableStateOf(false) }
    var showBitcrusherDlg by remember { mutableStateOf(false) }
    var showRegLabelsDlg by remember { mutableStateOf(false) }
    var showEffectRackDlg by remember { mutableStateOf(false) }
    var showAboutDlg by remember { mutableStateOf(false) }
    var rackSteps by remember { mutableStateOf<List<Pair<String, () -> Unit>>>(emptyList()) }
    var finderMode by remember { mutableStateOf<Int?>(null) }
    var showExportDlg by remember { mutableStateOf(false) }
    var pendingExportFormat by remember { mutableStateOf(ExportFormat.WAV_16) }
    var pendingWriteSmpl by remember { mutableStateOf(true) }
    var echoReverbMode by remember { mutableStateOf<Boolean?>(null) }
    var spectrumData by remember { mutableStateOf<FloatArray?>(null) }
    var analysisData by remember { mutableStateOf<Pair<FloatArray, FloatArray>?>(null) }

    fun refreshWaveform() {
        val tl = WaveZNative.getTotalFrames()
        val count = WaveZNative.getTrackCount()
        timelineFrames = tl
        sampleRate = WaveZNative.getSampleRate().coerceAtLeast(8000)
        channels = WaveZNative.getChannels().coerceIn(1, 2)
        activeTrackIdx = WaveZNative.getActiveTrackIndex().coerceIn(0, (count - 1).coerceAtLeast(0))
        canUndo = WaveZNative.canUndo()
        canRedo = WaveZNative.canRedo()
        hasClipboard = WaveZNative.hasClipboard()
        playheadSample = WaveZNative.getPlayheadSample()

        if (tl <= 0L || count <= 0) {
            tracks = emptyList()
            return
        }
        if (viewEnd <= viewStart || viewEnd > tl) { viewStart = 0L; viewEnd = tl }
        if (selEnd <= selStart || selEnd > tl) { selStart = 0L; selEnd = tl }
        if (m4Sample <= m2Sample || m4Sample > tl) {
            m1Sample = 0L; m2Sample = selStart; m3Sample = (selStart + selEnd) / 2L; m4Sample = selEnd
        }

        val span = (viewEnd - viewStart).coerceAtLeast(1L)
        val oldTracks = tracks
        tracks = (0 until count).map { idx ->
            val prev = oldTracks.getOrNull(idx)
            TrackUi(
                name = prev?.name ?: "Дорожка ${idx + 1}",
                gain = prev?.gain ?: 1.0f,
                pan = prev?.pan ?: 0.0f,
                muted = prev?.muted ?: false,
                solo = prev?.solo ?: false,
                offsetFrames = WaveZNative.getTrackOffset(idx),
                envelope = WaveZNative.getTrackEnvelope(idx),
                peaks = if (!showSpectrogram && span > 140L) WaveZNative.computeStereoPeaksForTrack(idx, viewStart, viewEnd, 360) else FloatArray(0),
                rawSamples = if (!showSpectrogram && span <= 140L) WaveZNative.getRawSamplesForTrack(idx, viewStart, viewEnd) else FloatArray(0),
                spectrogram = if (showSpectrogram) WaveZNative.computeSpectrogram(idx, viewStart, viewEnd, 96, 24) else FloatArray(0)
            )
        }
    }

    fun applySnap(frame: Long, matchSlopeOf: Long? = null): Long {
        val tl = timelineFrames.coerceAtLeast(1L)
        val clamped = frame.coerceIn(0L, tl)
        return when (snapMode) {
            SnapMode.OFF -> clamped
            SnapMode.ZERO -> {
                val reqSlope = if (matchSlopeOf != null) WaveZNative.getZeroCrossingSlope(matchSlopeOf) else 0
                WaveZNative.findZeroCrossing(clamped, reqSlope)
            }
            SnapMode.BAR -> {
                val step = (sampleRate * 60.0 / bpm * beatsPerBar).coerceAtLeast(1.0)
                (gridOffsetSample + (Math.round((clamped - gridOffsetSample) / step) * step).roundToLong()).coerceIn(0L, tl)
            }
            SnapMode.BEAT -> {
                val step = (sampleRate * 60.0 / bpm).coerceAtLeast(1.0)
                (gridOffsetSample + (Math.round((clamped - gridOffsetSample) / step) * step).roundToLong()).coerceIn(0L, tl)
            }
            SnapMode.EIGHTH -> {
                val step = (sampleRate * 60.0 / bpm / 2.0).coerceAtLeast(1.0)
                (gridOffsetSample + (Math.round((clamped - gridOffsetSample) / step) * step).roundToLong()).coerceIn(0L, tl)
            }
            SnapMode.SIXTEENTH -> {
                val step = (sampleRate * 60.0 / bpm / 4.0).coerceAtLeast(1.0)
                (gridOffsetSample + (Math.round((clamped - gridOffsetSample) / step) * step).roundToLong()).coerceIn(0L, tl)
            }
            SnapMode.TRIPLET -> {
                val step = (sampleRate * 60.0 / bpm / 3.0).coerceAtLeast(1.0)
                (gridOffsetSample + (Math.round((clamped - gridOffsetSample) / step) * step).roundToLong()).coerceIn(0L, tl)
            }
            SnapMode.SIXTH -> {
                val step = (sampleRate * 60.0 / bpm / 6.0).coerceAtLeast(1.0)
                (gridOffsetSample + (Math.round((clamped - gridOffsetSample) / step) * step).roundToLong()).coerceIn(0L, tl)
            }
            SnapMode.CUSTOM -> {
                val step = when (customSnapUnit) {
                    CustomSnapUnit.BEAT_DIV -> (sampleRate * 60.0 / bpm * 4.0 / customSnapDiv.coerceAtLeast(1)).coerceAtLeast(1.0)
                    CustomSnapUnit.SAMPLES -> customSnapSamples.coerceAtLeast(1L).toDouble()
                    CustomSnapUnit.MS -> (sampleRate * customSnapMs / 1000.0).coerceAtLeast(1.0)
                }
                val origin = if (customSnapUnit == CustomSnapUnit.BEAT_DIV) gridOffsetSample else 0L
                (origin + (Math.round((clamped - origin) / step) * step).roundToLong()).coerceIn(0L, tl)
            }
        }
    }

    fun syncRotationToNative() {
        WaveZNative.setRotationMode(isRotationMode, m1Sample, m2Sample, m3Sample, m4Sample)
    }

    fun autoComputeM1ToM4FromGrid() {
        val barFrames = (sampleRate * 60.0 / bpm * beatsPerBar).coerceAtLeast(1.0)
        val relStart = (selStart - gridOffsetSample).coerceAtLeast(0L)
        val kStart = kotlin.math.floor(relStart / barFrames).toLong()
        val downbeatStart = (gridOffsetSample + (kStart * barFrames).roundToLong()).coerceIn(0L, selStart)
        val relEnd = (selEnd - gridOffsetSample).coerceAtLeast(0L)
        val kEnd = kotlin.math.ceil(relEnd / barFrames).toLong()
        val barAfterStart = (gridOffsetSample + ((kStart + 1L) * barFrames).roundToLong()).coerceIn(selStart, selEnd)
        val downbeatEnd = (gridOffsetSample + ((kEnd - 1L).coerceAtLeast(kStart) * barFrames).roundToLong()).coerceIn(selStart, selEnd)
        m1Sample = downbeatStart
        m2Sample = selStart
        m3Sample = if (downbeatEnd > selStart) downbeatEnd else barAfterStart
        m4Sample = selEnd
        syncRotationToNative()
    }

    fun updateSelection(newStart: Long, newEnd: Long) {
        val tl = timelineFrames.coerceAtLeast(1L)
        val s = newStart.coerceIn(0L, (tl - 1L).coerceAtLeast(0L))
        val e = newEnd.coerceIn((s + 1L).coerceAtMost(tl), tl)
        selStart = s; selEnd = e
        WaveZNative.setLoopPoints(s, e)
        m2Sample = s
        m4Sample = e
        if (m3Sample < s || m3Sample > e) m3Sample = (s + e) / 2L
        if (isRotationMode) syncRotationToNative()
    }

    fun zoomBy(factor: Double, anchorRatio: Double = 0.5) {
        if (timelineFrames <= 0L) return
        val curSpan = (viewEnd - viewStart).coerceAtLeast(16L).toDouble()
        val newSpan = (curSpan * factor).roundToLong().coerceIn(24L, timelineFrames)
        val anchor = viewStart + (curSpan * anchorRatio).roundToLong()
        var ns = anchor - (newSpan * anchorRatio).roundToLong()
        var ne = ns + newSpan
        if (ns < 0L) { ns = 0L; ne = newSpan.coerceAtMost(timelineFrames) }
        if (ne > timelineFrames) { ne = timelineFrames; ns = (ne - newSpan).coerceAtLeast(0L) }
        viewStart = ns; viewEnd = ne
        refreshWaveform()
    }

    DisposableEffect(hasAudio, isPlaying, selStart, selEnd, viewStart, viewEnd, timelineFrames, activeTrackIdx, loopEnabled) {
        registerKeyHandler { ev ->
            val ctrl = ev.isCtrlPressed
            val shift = ev.isShiftPressed
            when (ev.keyCode) {
                KeyEvent.KEYCODE_SPACE -> if (hasAudio) {
                    if (isPlaying) { WaveZNative.pause(); isPlaying = false } else isPlaying = WaveZNative.play(); true
                } else false
                KeyEvent.KEYCODE_Z -> if (ctrl && shift) { if (WaveZNative.redo()) refreshWaveform(); true }
                    else if (ctrl) { if (WaveZNative.undo()) refreshWaveform(); true }
                    else if (hasAudio) {
                        val slope = WaveZNative.getZeroCrossingSlope(selEnd)
                        updateSelection(WaveZNative.findZeroCrossing(selStart, slope), WaveZNative.findZeroCrossing(selEnd, slope)); true
                    } else false
                KeyEvent.KEYCODE_Y -> if (ctrl) { if (WaveZNative.redo()) refreshWaveform(); true } else false
                KeyEvent.KEYCODE_X -> if (ctrl && hasAudio) { if (WaveZNative.cutSelection(selStart, selEnd)) { viewStart = 0L; viewEnd = WaveZNative.getTotalFrames(); refreshWaveform() }; true } else false
                KeyEvent.KEYCODE_C -> if (ctrl && hasAudio) { if (WaveZNative.copySelection(selStart, selEnd)) hasClipboard = true; true } else false
                KeyEvent.KEYCODE_V -> if (ctrl && hasAudio && hasClipboard) { if (WaveZNative.pasteAt(playheadSample)) { viewEnd = WaveZNative.getTotalFrames(); refreshWaveform() }; true } else false
                KeyEvent.KEYCODE_A -> if (ctrl && hasAudio) { updateSelection(0L, timelineFrames); true } else false
                KeyEvent.KEYCODE_L -> if (hasAudio) {
                    loopEnabled = !loopEnabled; WaveZNative.setLoopEnabled(loopEnabled)
                    Toast.makeText(context, if (loopEnabled) "Петля: ВКЛ" else "Петля: ВЫКЛ", Toast.LENGTH_SHORT).show(); true
                } else false
                KeyEvent.KEYCODE_R -> if (hasAudio && !ctrl) { isRotationMode = !isRotationMode; syncRotationToNative(); true } else false
                else -> false
            }
        }
        onDispose { registerKeyHandler { false } }
    }

    val exportDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null && hasAudio) {
            scope.launch {
                isExporting = true
                val fmt = pendingExportFormat
                val ok = withContext(Dispatchers.IO) {
                    context.contentResolver.openFileDescriptor(uri, "w")?.use { pfd ->
                        when (fmt) {
                            ExportFormat.WAV_16 -> WaveZNative.exportWavToFd(pfd.fd, selStart, selEnd, isRotationMode, m2Sample, m3Sample, m4Sample, pendingWriteSmpl, 16)
                            ExportFormat.WAV_24 -> WaveZNative.exportWavToFd(pfd.fd, selStart, selEnd, isRotationMode, m2Sample, m3Sample, m4Sample, pendingWriteSmpl, 24)
                            ExportFormat.WAV_32F -> WaveZNative.exportWavToFd(pfd.fd, selStart, selEnd, isRotationMode, m2Sample, m3Sample, m4Sample, pendingWriteSmpl, 32)
                            ExportFormat.FLAC_16 -> WaveZNative.exportFlacToFd(pfd.fd, selStart, selEnd, isRotationMode, m2Sample, m3Sample, m4Sample, 16)
                            ExportFormat.FLAC_24 -> WaveZNative.exportFlacToFd(pfd.fd, selStart, selEnd, isRotationMode, m2Sample, m3Sample, m4Sample, 24)
                            ExportFormat.AIFF_16 -> WaveZNative.exportAiffToFd(pfd.fd, selStart, selEnd, isRotationMode, m2Sample, m3Sample, m4Sample)
                            ExportFormat.M4A_AAC -> {
                                val shorts = WaveZNative.renderOutputShorts(selStart, selEnd, isRotationMode, m2Sample, m3Sample, m4Sample)
                                M4aExporter.encodeToFd(pfd.fileDescriptor, shorts, sampleRate, channels)
                            }
                        }
                    } ?: false
                }
                isExporting = false
                Toast.makeText(context, if (ok) "Экспорт ${fmt.label} готов!" else "Ошибка экспорта ${fmt.ext}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val exportMultipleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null && hasAudio && labels.isNotEmpty()) {
            scope.launch {
                isExporting = true
                val sorted = labels.sortedBy { it.startSample }
                val tl = timelineFrames.coerceAtLeast(1L)
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
                var exportedCount = 0
                withContext(Dispatchers.IO) {
                    sorted.forEachIndexed { i, lbl ->
                        val s = lbl.startSample.coerceIn(0L, tl - 1L)
                        val e = if (lbl.endSample > s) lbl.endSample.coerceAtMost(tl)
                        else (sorted.getOrNull(i + 1)?.startSample ?: tl).coerceIn(s + 1L, tl)
                        val safeName = lbl.text.replace(Regex("[^a-zA-Z0-9а-яА-Я_\\- ]"), "_").trim().ifBlank { "region" }
                        val fileName = String.format(Locale.US, "%02d_%s", i + 1, safeName)
                        val docUri = DocumentsContract.createDocument(context.contentResolver, dirUri, "audio/wav", fileName)
                        if (docUri != null) {
                            context.contentResolver.openFileDescriptor(docUri, "w")?.use { pfd ->
                                if (WaveZNative.exportWavToFd(pfd.fd, s, e, false, m2Sample, m3Sample, m4Sample, true, 16)) {
                                    exportedCount++
                                }
                            }
                        }
                    }
                }
                isExporting = false
                Toast.makeText(context, "Экспортировано регионов: $exportedCount из ${sorted.size}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val saveProjLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && hasAudio) {
            scope.launch(Dispatchers.IO) {
                val edl = WaveZNative.exportEdlState()
                val labelsArr = JSONArray().apply {
                    labels.forEach { l ->
                        put(JSONObject().apply {
                            put("id", l.id); put("start", l.startSample); put("end", l.endSample)
                            put("text", l.text); put("color", l.colorHex)
                        })
                    }
                }
                val json = JSONObject().apply {
                    put("sourceUri", sourceUriString)
                    put("trackName", tracks.firstOrNull()?.name ?: "Audio")
                    put("selStart", selStart); put("selEnd", selEnd)
                    put("m1", m1Sample); put("m2", m2Sample); put("m3", m3Sample); put("m4", m4Sample)
                    put("rotation", isRotationMode); put("loop", loopEnabled)
                    put("bpm", bpm); put("beatsPerBar", beatsPerBar); put("gridOffset", gridOffsetSample)
                    put("edl", JSONArray(edl.toList()))
                    put("labels", labelsArr)
                }
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray()) }
                withContext(Dispatchers.Main) { Toast.makeText(context, "Проект .wzproj сохранён", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    val openProjLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                isLoading = true
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }
                if (!text.isNullOrBlank()) {
                    runCatching {
                        val j = JSONObject(text)
                        val srcStr = j.optString("sourceUri", "")
                        if (srcStr.isNotBlank()) {
                            val srcUri = Uri.parse(srcStr)
                            val decoded = WaveZAudioDecoder.decodeUri(context, srcUri)
                            if (decoded != null) {
                                sourceUriString = srcStr
                                WaveZNative.loadPcmBuffer(decoded.samples, decoded.sampleRate, decoded.channels)
                                val edlArr = j.optJSONArray("edl")
                                if (edlArr != null && edlArr.length() > 0) {
                                    val longs = LongArray(edlArr.length()) { edlArr.getLong(it) }
                                    WaveZNative.importEdlState(longs)
                                }
                                tracks = listOf(TrackUi(name = j.optString("trackName", decoded.fileName)))
                                bpm = j.optDouble("bpm", 120.0)
                                beatsPerBar = j.optInt("beatsPerBar", 4)
                                gridOffsetSample = j.optLong("gridOffset", 0L)
                                m1Sample = j.optLong("m1", 0L); m2Sample = j.optLong("m2", 0L)
                                m3Sample = j.optLong("m3", 0L); m4Sample = j.optLong("m4", decoded.totalFrames)
                                isRotationMode = j.optBoolean("rotation", false)
                                loopEnabled = j.optBoolean("loop", false)
                                WaveZNative.setLoopEnabled(loopEnabled)
                                val lArr = j.optJSONArray("labels")
                                labels = if (lArr != null) {
                                    (0 until lArr.length()).map { idx ->
                                        val o = lArr.getJSONObject(idx)
                                        WaveZLabel(
                                            id = o.optLong("id", System.nanoTime() + idx),
                                            startSample = o.optLong("start", 0L),
                                            endSample = o.optLong("end", 0L),
                                            text = o.optString("text", "Метка"),
                                            colorHex = o.optLong("color", 0xFFFFB300L)
                                        )
                                    }
                                } else emptyList()
                                viewStart = 0L; viewEnd = WaveZNative.getTotalFrames()
                                updateSelection(j.optLong("selStart", 0L), j.optLong("selEnd", viewEnd))
                                syncRotationToNative()
                                refreshWaveform()
                            }
                        }
                    }.onFailure {
                        Toast.makeText(context, "Ошибка чтения .wzproj", Toast.LENGTH_SHORT).show()
                    }
                }
                isLoading = false
            }
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                isLoading = true
                WaveZNative.pause(); isPlaying = false
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val decoded = WaveZAudioDecoder.decodeUri(context, uri)
                if (decoded != null) {
                    sourceUriString = uri.toString()
                    WaveZNative.loadPcmBuffer(decoded.samples, decoded.sampleRate, decoded.channels)
                    tracks = listOf(TrackUi(name = decoded.fileName))
                    viewStart = 0L; viewEnd = decoded.totalFrames
                    selStart = 0L; selEnd = decoded.totalFrames
                    m1Sample = 0L; m2Sample = 0L; m3Sample = decoded.totalFrames / 2L; m4Sample = decoded.totalFrames
                    WaveZNative.setLoopPoints(0L, decoded.totalFrames)
                    isRotationMode = false; syncRotationToNative()
                    refreshWaveform()
                }
                isLoading = false
            }
        }
    }

    val addTrackLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                isLoading = true
                val decoded = WaveZAudioDecoder.decodeUri(context, uri)
                if (decoded != null && WaveZNative.addTrackPcm(decoded.samples, decoded.sampleRate, decoded.channels)) {
                    tracks = tracks + TrackUi(name = decoded.fileName)
                    viewStart = 0L; viewEnd = WaveZNative.getTotalFrames()
                    refreshWaveform()
                }
                isLoading = false
            }
        }
    }

    fun startMicRecording() {
        val sr = if (hasAudio) sampleRate else 44100
        if (recorder.start(scope, sr) { db -> meterLevels = floatArrayOf(db, db) }) {
            isRecording = true
            if (overdubEnabled && hasAudio) {
                isPlaying = WaveZNative.play()
            }
        } else {
            Toast.makeText(context, "Не удалось открыть микрофон", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopMicRecording() {
        val pcm = recorder.stop()
        isRecording = false
        if (isPlaying) { WaveZNative.pause(); isPlaying = false }
        if (pcm.isNotEmpty()) {
            val sr = if (hasAudio) sampleRate else 44100
            if (!hasAudio) {
                WaveZNative.loadPcmBuffer(pcm, sr, 2)
                tracks = listOf(TrackUi(name = "Запись 1"))
                viewStart = 0L; viewEnd = WaveZNative.getTotalFrames()
                selStart = 0L; selEnd = viewEnd
                refreshWaveform()
            } else if (WaveZNative.addTrackPcm(pcm, sr, 2)) {
                tracks = tracks + TrackUi(name = "Запись ${tracks.size + 1}")
                viewStart = 0L; viewEnd = WaveZNative.getTotalFrames()
                refreshWaveform()
            }
        }
    }

    val recordPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMicRecording()
        else Toast.makeText(context, "Нужно разрешение на микрофон", Toast.LENGTH_SHORT).show()
    }

    fun toggleRecording() {
        if (isRecording) stopMicRecording()
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startMicRecording()
        } else {
            recordPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(isPlaying, hasAudio) {
        while (isPlaying && hasAudio) {
            playheadSample = WaveZNative.getPlayheadSample()
            meterLevels = WaveZNative.getMeterLevelsDb()
            isPlaying = WaveZNative.isPlaying()
            delay(20L)
        }
        if (!isPlaying) meterLevels = floatArrayOf(-96f, -96f)
    }

    var showIntro by remember { mutableStateOf(!WaveZSession.introPlayed) }

    Box(modifier = Modifier.fillMaxSize()) {
    WaveZTheme(isDark = themeDark, isMaterial3 = themeM3) {
    Surface(modifier = Modifier.fillMaxSize(), color = StudioWorkspace, contentColor = StudioText) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // 1. COMPACT TOP MENU BAR + LIVE dB METER
            Row(
                modifier = Modifier.fillMaxWidth().background(StudioChrome).padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("WaveZ", color = StudioNeon, fontWeight = FontWeight.Black, fontSize = 11.sp, modifier = Modifier.padding(end = 3.dp))
                    listOf("Файл", "Правка", "Эффекты", "Анализ", "Справка").forEach { menuName ->
                        Box {
                            val menuShape = if (themeM3) RoundedCornerShape(16.dp) else RoundedCornerShape(5.dp)
                            val menuBg by animateColorAsState(
                                targetValue = if (openMenu == menuName) StudioNeon.copy(alpha = 0.18f) else StudioPanel,
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                label = "menuBg"
                            )
                            Surface(
                                color = menuBg, shape = menuShape, border = BorderStroke(1.dp, if (openMenu == menuName) StudioNeon else StudioBorder),
                                tonalElevation = if (themeM3) 2.dp else 0.dp,
                                modifier = Modifier.clip(menuShape).clickable {
                                    when (menuName) {
                                        "Эффекты" -> showEffectsSheet = true
                                        "Справка" -> showAboutDlg = true
                                        else -> openMenu = menuName
                                    }
                                }
                            ) {
                                Text(menuName, color = StudioText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = if (themeM3) 10.dp else 6.dp, vertical = 5.dp))
                            }
                            DropdownMenu(expanded = openMenu == menuName, onDismissRequest = { openMenu = null }, containerColor = StudioPanel) {
                                val base = tracks.firstOrNull()?.name?.substringBeforeLast(".") ?: "wavez"
                                when (menuName) {
                                    "Файл" -> {
                                        StudioMenuItem("Открыть аудио...") { openMenu = null; pickerLauncher.launch(arrayOf("audio/*")) }
                                        StudioMenuItem("Добавить дорожку...") { openMenu = null; addTrackLauncher.launch(arrayOf("audio/*")) }
                                        StudioMenuItem("Генерировать (Тон / Шум / Chirp / DTMF)...") { openMenu = null; showGenerateDlg = true }
                                        HorizontalDivider(color = StudioBorder)
                                        StudioMenuItem("Сохранить проект (.wzproj)...", enabled = hasAudio) { openMenu = null; saveProjLauncher.launch("${base}.wzproj") }
                                        StudioMenuItem("Открыть проект (.wzproj)...") { openMenu = null; openProjLauncher.launch(arrayOf("application/json", "*/*")) }
                                        HorizontalDivider(color = StudioBorder)
                                        StudioMenuItem("Экспорт аудио (WAV / FLAC / AIFF / M4A)...", enabled = hasAudio && !isExporting) { openMenu = null; showExportDlg = true }
                                        StudioMenuItem("Экспорт по меткам (Export Multiple)...", enabled = hasAudio && labels.isNotEmpty() && !isExporting) { openMenu = null; exportMultipleLauncher.launch(null) }
                                    }
                                    "Правка" -> {
                                        StudioMenuItem("Отменить (Undo)", enabled = canUndo) { openMenu = null; if (WaveZNative.undo()) refreshWaveform() }
                                        StudioMenuItem("Повторить (Redo)", enabled = canRedo) { openMenu = null; if (WaveZNative.redo()) refreshWaveform() }
                                        HorizontalDivider(color = StudioBorder)
                                        StudioMenuItem("Вырезать (Cut)", enabled = hasAudio) { openMenu = null; if (WaveZNative.cutSelection(selStart, selEnd)) { viewStart = 0L; viewEnd = WaveZNative.getTotalFrames(); refreshWaveform() } }
                                        StudioMenuItem("Split Cut (вырезать без сдвига)", enabled = hasAudio) { openMenu = null; if (WaveZNative.splitCutSelection(selStart, selEnd)) { hasClipboard = true; refreshWaveform() } }
                                        StudioMenuItem("Копировать (Copy)", enabled = hasAudio) { openMenu = null; if (WaveZNative.copySelection(selStart, selEnd)) hasClipboard = true }
                                        StudioMenuItem("Вставить (Paste)", enabled = hasAudio && hasClipboard) { openMenu = null; if (WaveZNative.pasteAt(playheadSample)) { viewEnd = WaveZNative.getTotalFrames(); refreshWaveform() } }
                                        StudioMenuItem("Удалить выделение", enabled = hasAudio) { openMenu = null; if (WaveZNative.deleteSelection(selStart, selEnd)) { viewStart = 0L; viewEnd = WaveZNative.getTotalFrames(); refreshWaveform() } }
                                        StudioMenuItem("Split Delete (удалить без сдвига)", enabled = hasAudio) { openMenu = null; if (WaveZNative.splitDeleteSelection(selStart, selEnd)) refreshWaveform() }
                                        StudioMenuItem("Разрезать клип на курсоре (Split)", enabled = hasAudio) { openMenu = null; if (WaveZNative.splitAt(playheadSample)) { refreshWaveform(); Toast.makeText(context, "Разрезано на $playheadSample", Toast.LENGTH_SHORT).show() } }
                                        StudioMenuItem("Дублировать выделение", enabled = hasAudio) { openMenu = null; if (WaveZNative.duplicateSelection(selStart, selEnd)) { viewEnd = WaveZNative.getTotalFrames(); refreshWaveform() } }
                                        StudioMenuItem("Обрезать (Trim)", enabled = hasAudio) { openMenu = null; if (WaveZNative.trimToSelection(selStart, selEnd)) { viewStart = 0L; viewEnd = WaveZNative.getTotalFrames(); refreshWaveform() } }
                                        StudioMenuItem("Заглушить (Silence)", enabled = hasAudio) { openMenu = null; if (WaveZNative.silenceSelection(selStart, selEnd)) refreshWaveform() }
                                        HorizontalDivider(color = StudioBorder)
                                        StudioMenuItem("Привязать к Zero-Crossing (Slope)", enabled = hasAudio) {
                                            openMenu = null
                                            val slope = WaveZNative.getZeroCrossingSlope(selEnd)
                                            updateSelection(WaveZNative.findZeroCrossing(selStart, slope), WaveZNative.findZeroCrossing(selEnd, slope))
                                        }
                                        StudioMenuItem("Сетка, BPM, Размер и Границы...", enabled = hasAudio) { openMenu = null; showSelectionDlg = true }
                                        StudioMenuItem(if (showClipping) "✓ Показывать клиппинг (≥ 0 dBFS)" else "Показывать клиппинг (≥ 0 dBFS)") { openMenu = null; showClipping = !showClipping }
                                        StudioMenuItem(if (showRms) "✓ Показывать RMS" else "Показывать RMS") { openMenu = null; showRms = !showRms }
                                        StudioMenuItem(if (waveformDbScale) "✓ Шкала волны в dB (Log)" else "Шкала волны в dB (Log)") { openMenu = null; waveformDbScale = !waveformDbScale }
                                        StudioMenuItem(if (overdubEnabled) "✓ Overdub при записи" else "Overdub при записи") { openMenu = null; overdubEnabled = !overdubEnabled }
                                        StudioMenuItem(if (showSpectrogram) "✓ Спектрограмма (FFT)" else "Спектрограмма (FFT)") { openMenu = null; showSpectrogram = !showSpectrogram; refreshWaveform() }
                                        StudioMenuItem(if (showBeatGrid) "✓ Сетка тактов (BPM)" else "Сетка тактов (BPM)") { openMenu = null; showBeatGrid = !showBeatGrid }
                                        StudioMenuItem("Тема оформления (Свет/Тём/M3)...") { openMenu = null; showThemeDlg = true }
                                    }
                                    "Эффекты" -> { }
                                    "Анализ" -> {
                                        StudioMenuItem("Измерить RMS / Пик / Шов Δ%...", enabled = hasAudio) {
                                            openMenu = null
                                            val st = WaveZNative.analyzeSelection(selStart, selEnd)
                                            val tail = WaveZNative.getRawSamples((selEnd - 24L).coerceAtLeast(0L), selEnd)
                                            val head = WaveZNative.getRawSamples(selStart, (selStart + 24L).coerceAtMost(timelineFrames))
                                            analysisData = st to (tail + head)
                                        }
                                        StudioMenuItem("График спектра (FFT)...", enabled = hasAudio) { openMenu = null; spectrumData = WaveZNative.computeSpectrum(selStart, selEnd, 64) }
                                        StudioMenuItem("Определить темп (BPM)", enabled = hasAudio) {
                                            openMenu = null; val est = WaveZNative.estimateBpm(selStart, selEnd); bpm = est
                                            Toast.makeText(context, String.format(Locale.US, "Темп: %.0f BPM", est), Toast.LENGTH_SHORT).show()
                                        }
                                        HorizontalDivider(color = StudioBorder)
                                        StudioMenuItem("Silence Finder (Метки пауз)...", enabled = hasAudio) { openMenu = null; finderMode = 0 }
                                        StudioMenuItem("Sound Finder (Метки регионов)...", enabled = hasAudio) { openMenu = null; finderMode = 1 }
                                        StudioMenuItem("Beat Finder (Метки ударов)...", enabled = hasAudio) { openMenu = null; finderMode = 2 }
                                        StudioMenuItem("Find Clipping (Метки клипов)...", enabled = hasAudio) { openMenu = null; finderMode = 3 }
                                        StudioMenuItem("Регулярный интервал меток...", enabled = hasAudio) { openMenu = null; showRegLabelsDlg = true }
                                        StudioMenuItem("Определить тональность (Key)", enabled = hasAudio) {
                                            openMenu = null
                                            val kIdx = WaveZNative.estimateKey(selStart, selEnd)
                                            val roots = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
                                            val name = if (kIdx < 12) "${roots[kIdx % 12]} Major" else "${roots[kIdx % 12]} Minor"
                                            Toast.makeText(context, "Тональность: $name", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                StereoDbMeter(leftDb = meterLevels[0], rightDb = meterLevels[1])
            }

            if (isLoading || isExporting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = StudioNeon, trackColor = StudioChrome)
            else HorizontalDivider(color = StudioBorder, thickness = 1.dp)

            // 2. MULTI-TRACK WORKSPACE (fills maximum screen height!)
            Box(modifier = Modifier.fillMaxWidth().weight(1f).background(StudioWorkspace)) {
                if (hasAudio) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Slim 26dp Top Time Ruler
                        Row(modifier = Modifier.fillMaxWidth().height(26.dp).background(StudioChrome)) {
                            Box(modifier = Modifier.width(52.dp).fillMaxHeight().background(StudioPanel), contentAlignment = Alignment.Center) {
                                Text("${tracks.size}т", color = StudioNeon, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            MarkerBarCanvas(
                                viewStart = viewStart, viewEnd = viewEnd, selStart = selStart, selEnd = selEnd,
                                m1 = m1Sample, m3 = m3Sample, showM1M4 = isRotationMode,
                                sampleRate = sampleRate, bpm = bpm, beatsPerBar = beatsPerBar, gridOffset = gridOffsetSample,
                                showBeatGrid = showBeatGrid, isTop = true,
                                onSelectionChange = { ns, ne -> updateSelection(applySnap(ns), applySnap(ne)) },
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                        HorizontalDivider(color = StudioBorder, thickness = 1.dp)
                        // Slim 24dp Label Track Bar
                        LabelTrackBar(
                            viewStart = viewStart, viewEnd = viewEnd,
                            labels = labels,
                            onAddLabel = {
                                val isReg = selStart > 0L || selEnd < timelineFrames
                                val s = if (isReg) selStart else playheadSample
                                val e = if (isReg) selEnd else 0L
                                val newLbl = WaveZLabel(
                                    id = System.nanoTime(),
                                    startSample = s,
                                    endSample = e,
                                    text = "Метка ${labels.size + 1}"
                                )
                                labels = labels + newLbl
                                editingLabel = newLbl
                            },
                            onTapLabel = { lbl ->
                                if (lbl.endSample > lbl.startSample) {
                                    updateSelection(lbl.startSample, lbl.endSample)
                                    WaveZNative.seek(lbl.startSample); playheadSample = lbl.startSample
                                } else {
                                    WaveZNative.seek(lbl.startSample); playheadSample = lbl.startSample
                                }
                            },
                            onEditLabel = { lbl -> editingLabel = lbl }
                        )
                        HorizontalDivider(color = StudioBorder, thickness = 1.dp)

                        // Scrollable tracks (single track fills full workspace height!)
                        val trackListMod = if (tracks.size == 1) {
                            Modifier.fillMaxWidth().weight(1f)
                        } else {
                            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                        }
                        Column(modifier = trackListMod) {
                            tracks.forEachIndexed { idx, trk ->
                                val isSelected = idx == activeTrackIdx
                                val rowMod = if (tracks.size == 1) {
                                    Modifier.fillMaxWidth().weight(1f)
                                } else {
                                    Modifier.fillMaxWidth().height(180.dp)
                                }
                                val cardShape = if (themeM3) RoundedCornerShape(20.dp) else RoundedCornerShape(0.dp)
                                val borderCol by animateColorAsState(
                                    targetValue = if (isSelected) StudioNeon else StudioBorder,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                    label = "trkBorder"
                                )
                                Row(
                                    modifier = rowMod
                                        .then(if (themeM3) Modifier.padding(horizontal = 6.dp, vertical = 3.dp).clip(cardShape) else Modifier)
                                        .border(if (isSelected) 1.5.dp else 0.5.dp, borderCol, cardShape)
                                ) {
                                    CompactTrackControlPanel(
                                        title = trk.name, isSelected = isSelected, isMuted = trk.muted, isSolo = trk.solo,
                                        onSelectAndOpenSheet = {
                                            if (WaveZNative.selectTrack(idx)) activeTrackIdx = idx
                                            showTrackSheet = true
                                        },
                                        onToggleMute = {
                                            val nextM = !trk.muted
                                            WaveZNative.setTrackMixAt(idx, trk.gain, trk.pan, nextM, trk.solo)
                                            tracks = tracks.mapIndexed { i, t -> if (i == idx) t.copy(muted = nextM) else t }
                                        },
                                        onToggleSolo = {
                                            val nextS = !trk.solo
                                            WaveZNative.setTrackMixAt(idx, trk.gain, trk.pan, trk.muted, nextS)
                                            tracks = tracks.mapIndexed { i, t -> if (i == idx) t.copy(solo = nextS) else t }
                                        }
                                    )

                                    UnifiedGestureWaveformCanvas(
                                        viewStart = viewStart, viewEnd = viewEnd, timelineFrames = timelineFrames,
                                        selStart = selStart, selEnd = selEnd, playheadSample = playheadSample,
                                        m1 = m1Sample, m3 = m3Sample, showM1M4 = isRotationMode,
                                        sampleRate = sampleRate, bpm = bpm, beatsPerBar = beatsPerBar, gridOffset = gridOffsetSample,
                                        showBeatGrid = showBeatGrid, showClipping = showClipping, showRms = showRms,
                                        showSpectrogram = showSpectrogram, waveformDbScale = waveformDbScale, verticalZoom = verticalZoom, toolMode = toolMode,
                                        offsetFrames = trk.offsetFrames, envelope = trk.envelope,
                                        stereoPeaks = trk.peaks, rawSamples = trk.rawSamples, spectrogram = trk.spectrogram,
                                        onSelectTrack = { if (WaveZNative.selectTrack(idx)) { activeTrackIdx = idx; refreshWaveform() } },
                                        onSeek = { frame -> val s = applySnap(frame); WaveZNative.seek(s); playheadSample = s },
                                        onSelectionChange = { ns, ne -> updateSelection(applySnap(ns), applySnap(ne)) },
                                        onOffsetChange = { newOff, commit ->
                                            val snapped = if (snapMode != SnapMode.OFF) applySnap(newOff.coerceAtLeast(0L)) else newOff.coerceAtLeast(0L)
                                            if (WaveZNative.setTrackOffset(idx, snapped, commit)) refreshWaveform()
                                        },
                                        onEnvelopeChange = { newEnv, commit ->
                                            val sorted = newEnv.sortedBy { it.first }
                                            WaveZNative.setTrackEnvelope(
                                                idx,
                                                sorted.map { it.first }.toLongArray(),
                                                sorted.map { it.second }.toFloatArray(),
                                                commit
                                            )
                                            tracks = tracks.mapIndexed { i, t -> if (i == idx) t.copy(envelope = sorted) else t }
                                            if (commit) canUndo = WaveZNative.canUndo()
                                        },
                                        onZoomPan = { zoomFactor, panFraction ->
                                            val span = (viewEnd - viewStart).coerceAtLeast(24L).toDouble()
                                            val newSpan = (span / zoomFactor).roundToLong().coerceIn(24L, timelineFrames)
                                            val shift = (-panFraction * span).roundToLong()
                                            var ns = (viewStart + (span - newSpan).roundToLong() / 2L + shift)
                                            ns = ns.coerceIn(0L, (timelineFrames - newSpan).coerceAtLeast(0L))
                                            viewStart = ns; viewEnd = (ns + newSpan).coerceAtMost(timelineFrames)
                                            refreshWaveform()
                                        },
                                        modifier = Modifier.weight(1f).fillMaxHeight()
                                    )
                                }
                            }
                        }

                        // Slim 26dp Bottom Ear Handles bar
                        HorizontalDivider(color = StudioBorder, thickness = 1.dp)
                        Row(modifier = Modifier.fillMaxWidth().height(26.dp).background(StudioChrome)) {
                            Box(modifier = Modifier.width(52.dp).fillMaxHeight().background(StudioPanel), contentAlignment = Alignment.Center) {
                                Text("Ушки", color = StudioText.copy(alpha = 0.6f), fontSize = 8.sp)
                            }
                            MarkerBarCanvas(
                                viewStart = viewStart, viewEnd = viewEnd, selStart = selStart, selEnd = selEnd,
                                m1 = m1Sample, m3 = m3Sample, showM1M4 = isRotationMode,
                                sampleRate = sampleRate, bpm = bpm, beatsPerBar = beatsPerBar, gridOffset = gridOffsetSample,
                                showBeatGrid = false, isTop = false,
                                onSelectionChange = { ns, ne -> updateSelection(applySnap(ns), applySnap(ne)) },
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
            }

            if (!themeM3) HorizontalDivider(color = StudioBorder, thickness = 1.dp)

            // 3. BOTTOM THUMB DOCK: Expressive Floating Dock in M3E / Classic Bar in Studio
            Surface(
                color = StudioPanel,
                shape = if (themeM3) RoundedCornerShape(26.dp) else RoundedCornerShape(0.dp),
                tonalElevation = if (themeM3) 6.dp else 0.dp,
                shadowElevation = if (themeM3) 4.dp else 0.dp,
                border = if (themeM3) BorderStroke(1.dp, StudioBorder.copy(alpha = 0.5f)) else null,
                modifier = if (themeM3) Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp) else Modifier.fillMaxWidth()
            ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = if (themeM3) 8.dp else 6.dp, vertical = if (themeM3) 5.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (themeM3) 6.dp else 4.dp)
            ) {
                ToolbarIconBtn(Icons.Default.SkipPrevious, "В начало", hasAudio) {
                    val target = if (playheadSample > selStart) selStart else 0L
                    WaveZNative.seek(target); playheadSample = target
                }
                if (themeM3) {
                    val playRadius by animateDpAsState(
                        targetValue = if (isPlaying) 12.dp else 22.dp,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                        label = "playMorph"
                    )
                    val playBg by animateColorAsState(
                        targetValue = if (!hasAudio) StudioChrome else if (isPlaying) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "playBg"
                    )
                    val playFg = if (!hasAudio) StudioText.copy(alpha = 0.25f) else if (isPlaying) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
                    Surface(
                        color = playBg,
                        shape = RoundedCornerShape(playRadius),
                        modifier = Modifier.width(46.dp).height(36.dp).clip(RoundedCornerShape(playRadius)).clickable(enabled = hasAudio) {
                            if (isPlaying) { WaveZNative.pause(); isPlaying = false } else isPlaying = WaveZNative.play()
                        }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = playFg, modifier = Modifier.size(20.dp))
                        }
                    }
                } else {
                    ToolbarIconBtn(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", hasAudio, if (isPlaying) StudioAmber else StudioNeon) {
                        if (isPlaying) { WaveZNative.pause(); isPlaying = false } else isPlaying = WaveZNative.play()
                    }
                }
                ToolbarIconBtn(Icons.Default.Stop, "Стоп", hasAudio || isRecording, Color(0xFFFF6B6B)) {
                    if (isRecording) stopMicRecording()
                    else { WaveZNative.pause(); isPlaying = false; WaveZNative.seek(selStart); playheadSample = selStart }
                }
                ToolbarIconBtn(Icons.Default.FiberManualRecord, "Запись (Mic)", true, if (isRecording) Color(0xFFFF1744) else Color(0xFFFF6B6B)) {
                    toggleRecording()
                }
                ToolbarIconBtn(Icons.Default.SkipNext, "В конец", hasAudio) {
                    val target = if (playheadSample < selEnd && selEnd < timelineFrames) selEnd else (timelineFrames - 1L).coerceAtLeast(0L)
                    WaveZNative.seek(target); playheadSample = target
                }
                ToolbarIconBtn(Icons.Default.Repeat, "Петля", hasAudio, if (loopEnabled) StudioNeon else StudioText.copy(alpha = 0.45f)) {
                    loopEnabled = !loopEnabled; WaveZNative.setLoopEnabled(loopEnabled)
                }
                VerticalDivider(Modifier.height(22.dp).padding(horizontal = 2.dp), color = StudioBorder)
                ToolbarIconBtn(Icons.AutoMirrored.Filled.Undo, "Отменить", canUndo, if (canUndo) StudioNeon else StudioText) { if (WaveZNative.undo()) refreshWaveform() }
                ToolbarIconBtn(Icons.AutoMirrored.Filled.Redo, "Повторить", canRedo, if (canRedo) StudioCyan else StudioText) { if (WaveZNative.redo()) refreshWaveform() }
                VerticalDivider(Modifier.height(22.dp).padding(horizontal = 2.dp), color = StudioBorder)
                Surface(
                    color = if (toolMode != WaveZToolMode.SELECT) StudioNeon.copy(alpha = 0.2f) else StudioChrome,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, if (toolMode != WaveZToolMode.SELECT) StudioNeon else StudioBorder),
                    modifier = Modifier.clickable(enabled = hasAudio) {
                        val all = WaveZToolMode.entries
                        toolMode = all[(all.indexOf(toolMode) + 1) % all.size]
                    }
                ) {
                    Text(toolMode.label, color = if (toolMode != WaveZToolMode.SELECT) StudioNeon else StudioText, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 7.dp))
                }
                ToolbarIconBtn(Icons.Default.Sync, "Ротация M1..M4", hasAudio, if (isRotationMode) StudioCyan else StudioText.copy(alpha = 0.45f)) {
                    isRotationMode = !isRotationMode; if (isRotationMode) autoComputeM1ToM4FromGrid() else syncRotationToNative()
                }
                ToolbarIconBtn(Icons.Default.GridOn, "Сетка тактов", hasAudio, if (showBeatGrid) StudioAmber else StudioText.copy(alpha = 0.45f)) {
                    showBeatGrid = !showBeatGrid
                }
                ToolbarIconBtn(Icons.Default.ZoomIn, "Приблизить", hasAudio) { zoomBy(0.5) }
                ToolbarIconBtn(Icons.Default.ZoomOut, "Отдалить", hasAudio) { zoomBy(2.0) }
                ToolbarIconBtn(Icons.Default.FitScreen, "Подогнать", hasAudio) { viewStart = 0L; viewEnd = timelineFrames; refreshWaveform() }
                Surface(
                    color = if (verticalZoom > 1.01f) StudioCyan.copy(alpha = 0.2f) else StudioChrome,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, if (verticalZoom > 1.01f) StudioCyan else StudioBorder),
                    modifier = Modifier.clickable(enabled = hasAudio) {
                        verticalZoom = when {
                            verticalZoom < 1.5f -> 2.0f
                            verticalZoom < 3.0f -> 4.0f
                            verticalZoom < 6.0f -> 8.0f
                            else -> 1.0f
                        }
                    }
                ) {
                    Text("V:${verticalZoom.toInt()}x", color = if (verticalZoom > 1.01f) StudioCyan else StudioText, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp))
                }
                Surface(
                    color = if (kotlin.math.abs(playbackSpeed - 1.0f) > 0.02f) StudioAmber.copy(alpha = 0.2f) else StudioChrome,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, if (kotlin.math.abs(playbackSpeed - 1.0f) > 0.02f) StudioAmber else StudioBorder),
                    modifier = Modifier.clickable(enabled = hasAudio) { showPlaybackSpeedDlg = true }
                ) {
                    Text(String.format(Locale.US, "%.2gx", playbackSpeed), color = if (kotlin.math.abs(playbackSpeed - 1.0f) > 0.02f) StudioAmber else StudioText, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp))
                }
            }
            }

            if (!themeM3) HorizontalDivider(color = StudioBorder, thickness = 1.dp)

            // 4. COMPACT BOTTOM STATUS & SNAP BAR
            Row(
                modifier = Modifier.fillMaxWidth().background(StudioChrome).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Surface(
                    color = StudioPanel, shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, if (snapMode != SnapMode.OFF) StudioNeon else StudioBorder),
                    modifier = Modifier.clickable { showSnapDlg = true }
                ) {
                    val snapText = if (snapMode == SnapMode.CUSTOM) {
                        when (customSnapUnit) {
                            CustomSnapUnit.BEAT_DIV -> "1/$customSnapDiv"
                            CustomSnapUnit.SAMPLES -> "${customSnapSamples}smp"
                            CustomSnapUnit.MS -> "${customSnapMs.toInt()}ms"
                        }
                    } else snapMode.label
                    Text("Снап: $snapText", color = if (snapMode != SnapMode.OFF) StudioNeon else StudioText, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp))
                }

                Surface(
                    color = StudioPanel, shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, if (showBeatGrid) StudioAmber else StudioBorder),
                    modifier = Modifier.clickable(enabled = hasAudio) { showSelectionDlg = true }
                ) {
                    val sig = if (beatsPerBar == 6 || beatsPerBar == 7) "$beatsPerBar/8" else "$beatsPerBar/4"
                    Text(String.format(Locale.US, "⚙ %.1f•%s", bpm, sig), color = if (showBeatGrid) StudioAmber else StudioText, fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.clickable(enabled = hasAudio) { showSelectionDlg = true }) {
                    Text("Н:${formatTime(selStart, sampleRate)}", color = StudioNeon, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    Text("К:${formatTime(selEnd, sampleRate)}", color = StudioCyan, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                }
                Text("П:${formatTime(playheadSample, sampleRate)}", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // Track Mix & 4-Marker Rotation BottomSheet
    if (showTrackSheet && hasAudio) {
        val trk = tracks.getOrNull(activeTrackIdx)
        if (trk != null) {
            ModalBottomSheet(onDismissRequest = { showTrackSheet = false }, containerColor = StudioPanel) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Дорожка ${activeTrackIdx + 1}: ${trk.name}", color = StudioNeon, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(String.format(Locale.US, "Громкость: %.0f%%", trk.gain * 100), color = StudioText, fontSize = 12.sp)
                    Slider(value = trk.gain, onValueChange = { g ->
                        WaveZNative.setTrackMixAt(activeTrackIdx, g, trk.pan, trk.muted, trk.solo)
                        tracks = tracks.mapIndexed { i, t -> if (i == activeTrackIdx) t.copy(gain = g) else t }
                    }, valueRange = 0f..2f)

                    Text(String.format(Locale.US, "Панорама: %+.2f", trk.pan), color = StudioText, fontSize = 12.sp)
                    Slider(value = trk.pan, onValueChange = { p ->
                        WaveZNative.setTrackMixAt(activeTrackIdx, trk.gain, p, trk.muted, trk.solo)
                        tracks = tracks.mapIndexed { i, t -> if (i == activeTrackIdx) t.copy(pan = p) else t }
                    }, valueRange = -1f..1f)

                    HorizontalDivider(color = StudioBorder)
                    Text("Ротация [M1..M4] (сильная доля такта)", color = StudioCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("M1=$m1Sample • M2=$m2Sample • M3=$m3Sample • M4=$m4Sample", color = StudioText.copy(alpha = 0.8f), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { isRotationMode = !isRotationMode; syncRotationToNative() }, colors = ButtonDefaults.buttonColors(containerColor = if (isRotationMode) StudioCyan else StudioChrome, contentColor = if (isRotationMode) Color.Black else StudioText)) {
                            Text(if (isRotationMode) "Ротация: ВКЛ" else "Ротация: ВЫКЛ", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = { autoComputeM1ToM4FromGrid() }, border = BorderStroke(1.dp, StudioCyan)) {
                            Text("Авто M1..M4 от сетки", color = StudioCyan, fontSize = 12.sp)
                        }
                    }
                    Text("Сдвиг M3 (граница Head/Body): $m3Sample", color = StudioText, fontSize = 11.sp)
                    Slider(
                        value = m3Sample.toFloat().coerceIn(m2Sample.toFloat(), m4Sample.coerceAtLeast(m2Sample + 1L).toFloat()),
                        onValueChange = { m3Sample = it.roundToLong(); if (isRotationMode) syncRotationToNative() },
                        valueRange = m2Sample.toFloat()..m4Sample.coerceAtLeast(m2Sample + 1L).toFloat()
                    )

                    HorizontalDivider(color = StudioBorder)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            if (WaveZNative.duplicateTrack()) { tracks = tracks + TrackUi("${trk.name} (копия)"); refreshWaveform() }
                            showTrackSheet = false
                        }) { Text("Дублировать", color = StudioText, fontSize = 12.sp) }
                        OutlinedButton(onClick = {
                            if (WaveZNative.splitStereoToMono(activeTrackIdx)) {
                                val mut = tracks.toMutableList()
                                val orig = mut[activeTrackIdx]
                                mut[activeTrackIdx] = orig.copy(name = "${orig.name} (L)", pan = -0.5f)
                                mut.add(activeTrackIdx + 1, orig.copy(name = "${orig.name} (R)", pan = 0.5f, envelope = emptyList()))
                                tracks = mut
                                refreshWaveform()
                            } else {
                                Toast.makeText(context, "Нужна стереодорожка", Toast.LENGTH_SHORT).show()
                            }
                            showTrackSheet = false
                        }) { Text("Split Stereo→2 Mono", color = StudioCyan, fontSize = 12.sp) }
                        OutlinedButton(onClick = {
                            if (WaveZNative.swapStereoChannels(activeTrackIdx)) refreshWaveform()
                            showTrackSheet = false
                        }) { Text("Swap L/R", color = StudioText, fontSize = 12.sp) }
                        if (trk.offsetFrames > 0L) {
                            OutlinedButton(onClick = {
                                if (WaveZNative.setTrackOffset(activeTrackIdx, 0L)) refreshWaveform()
                                showTrackSheet = false
                            }) { Text("Сдвиг = 0", color = StudioAmber, fontSize = 12.sp) }
                        }
                        if (trk.envelope.isNotEmpty()) {
                            OutlinedButton(onClick = {
                                WaveZNative.setTrackEnvelope(activeTrackIdx, LongArray(0), FloatArray(0))
                                tracks = tracks.mapIndexed { i, t -> if (i == activeTrackIdx) t.copy(envelope = emptyList()) else t }
                                showTrackSheet = false
                            }) { Text("Сбросить Envelope", color = StudioAmber, fontSize = 12.sp) }
                        }
                        if (tracks.size > 1) {
                            OutlinedButton(onClick = {
                                if (WaveZNative.mixDownTracks()) { tracks = listOf(TrackUi("Mixdown")); refreshWaveform() }
                                showTrackSheet = false
                            }) { Text("Свести (Mix)", color = StudioNeon, fontSize = 12.sp) }
                            OutlinedButton(onClick = {
                                val r = activeTrackIdx
                                if (WaveZNative.removeTrack(r)) { tracks = tracks.filterIndexed { i, _ -> i != r }; refreshWaveform() }
                                showTrackSheet = false
                            }) { Text("Удалить", color = Color(0xFFFF6B6B), fontSize = 12.sp) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    // 4-Tab Effects BottomSheet
    if (showEffectsSheet) {
        ModalBottomSheet(onDismissRequest = { showEffectsSheet = false }, containerColor = StudioPanel) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("DSP / Эффекты", color = StudioNeon, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { showEffectsSheet = false; showEffectRackDlg = true }, border = BorderStroke(1.dp, StudioNeon)) {
                            Text("Rack (${rackSteps.size})", color = StudioNeon, fontSize = 11.sp)
                        }
                        if (lastEffectAction != null && lastEffectLabel != null && hasAudio) {
                            OutlinedButton(onClick = { showEffectsSheet = false; lastEffectAction?.invoke() }, border = BorderStroke(1.dp, StudioCyan)) {
                                Text("Повторить: $lastEffectLabel", color = StudioCyan, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                val tabs = listOf("Швы", "Динамика", "Тон/Время", "Пространство")
                if (themeM3) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        tabs.forEachIndexed { i, t ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = tabs.size),
                                onClick = { effectsTab = i },
                                selected = effectsTab == i,
                                label = { Text(t, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tabs.forEachIndexed { i, t ->
                            FilterChip(selected = effectsTab == i, onClick = { effectsTab = i }, label = { Text(t, fontSize = 12.sp) })
                        }
                    }
                }
                HorizontalDivider(color = StudioBorder)

                fun runInstant(label: String, block: () -> Unit) {
                    lastEffectLabel = label; lastEffectAction = block; showEffectsSheet = false; block()
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (effectsTab) {
                        0 -> { // Швы и Правка
                            EffectRowBtn("LSAR Repair (до 128 сэмплов)", hasAudio) {
                                runInstant("LSAR Repair") { if (WaveZNative.repairSelection(selStart, selEnd)) refreshWaveform() }
                            }
                            EffectRowBtn("Границы сглаживания / Кроссфейд шва...", hasAudio) { showEffectsSheet = false; showSmoothDlg = true }
                            EffectRowBtn("Auto-Seam (автопоиск идеального стыка)", hasAudio) {
                                runInstant("Auto-Seam") {
                                    val barFrames = (sampleRate * 60.0 / bpm * beatsPerBar).roundToLong().coerceIn(2048L, 65536L)
                                    val bestEnd = WaveZNative.findBestLoopSeam(selStart, selEnd, barFrames / 2L)
                                    updateSelection(selStart, bestEnd)
                                    Toast.makeText(context, "Найден лучший стык: $bestEnd", Toast.LENGTH_SHORT).show()
                                }
                            }
                            EffectRowBtn("Инверсия фазы (Invert)", hasAudio) {
                                runInstant("Invert") { if (WaveZNative.invertSelection(selStart, selEnd)) refreshWaveform() }
                            }
                            EffectRowBtn("Реверс (Reverse)", hasAudio) {
                                runInstant("Reverse") { if (WaveZNative.reverseSelection(selStart, selEnd)) refreshWaveform() }
                            }
                            EffectRowBtn("Trim Digital Silence (-90 dB)", hasAudio) {
                                runInstant("Trim Silence") { if (WaveZNative.trimSilence(-90f)) { viewStart = 0L; viewEnd = WaveZNative.getTotalFrames(); refreshWaveform() } }
                            }
                            EffectRowBtn("Noise Reduction (Шумоподавление по профилю)...", hasAudio) { showEffectsSheet = false; showNoiseReductionDlg = true }
                            EffectRowBtn("Clip Fix (Устранение клиппинга)...", hasAudio) { showEffectsSheet = false; showClipFixDlg = true }
                            EffectRowBtn("Truncate Silence (сжать паузы)...", hasAudio) { showEffectsSheet = false; showTruncateSilDlg = true }
                            EffectRowBtn("Auto-Click Removal (удаление щелчков)", hasAudio) {
                                runInstant("Click Removal") { if (WaveZNative.clickRemoval(selStart, selEnd, 0.25f)) refreshWaveform() }
                            }
                            EffectRowBtn("Повтор выделения (Repeat N раз)...", hasAudio) { showEffectsSheet = false; showRepeatDlg = true }
                        }
                        1 -> { // Динамика
                            EffectRowBtn("Нормализация (Normalize dB + DC)...", hasAudio) { showEffectsSheet = false; showNormalizeDlg = true }
                            EffectRowBtn("Loudness Normalization (-16 LUFS)", hasAudio) {
                                runInstant("Loudness -16") { if (WaveZNative.loudnessNormalize(selStart, selEnd, -16f)) refreshWaveform() }
                            }
                            EffectRowBtn("Усиление (Amplify dB)...", hasAudio) { showEffectsSheet = false; showAmplifyDlg = true }
                            EffectRowBtn("Компрессор...", hasAudio) { showEffectsSheet = false; showCompressorDlg = true }
                            EffectRowBtn("3-Band Multiband Compressor (Pro-MB)...", hasAudio) { showEffectsSheet = false; showMultibandDlg = true }
                            EffectRowBtn("De-Esser (Подавление сибилянтов)...", hasAudio) { showEffectsSheet = false; showDeEsserDlg = true }
                            EffectRowBtn("Лимитер (Limiter dB)...", hasAudio) { showEffectsSheet = false; showLimiterDlg = true }
                            EffectRowBtn("Auto Duck (приглушение под голос)...", hasAudio && tracks.size > 1) { showEffectsSheet = false; showAutoDuckDlg = true }
                            EffectRowBtn("Noise Gate (Threshold / Attack / Release)...", hasAudio) { showEffectsSheet = false; showNoiseGateDlg = true }
                            EffectRowBtn("Fade In (плавное нарастание)", hasAudio) {
                                runInstant("Fade In") { if (WaveZNative.fadeInSelection(selStart, selEnd)) refreshWaveform() }
                            }
                            EffectRowBtn("Fade Out (плавное затухание)", hasAudio) {
                                runInstant("Fade Out") { if (WaveZNative.fadeOutSelection(selStart, selEnd)) refreshWaveform() }
                            }
                            EffectRowBtn("Adjustable / Studio Fade...", hasAudio) { showEffectsSheet = false; showAdjFadeDlg = true }
                        }
                        2 -> { // Тон и Время
                            EffectRowBtn("Скорость и Питч (Kaiser Sinc + Гц/Центы + Пресеты)...", hasAudio) { showEffectsSheet = false; showSpeedDlg = true }
                            EffectRowBtn("SoundTouch (Темп / Тональность)...", hasAudio) { showEffectsSheet = false; showSoundTouchDlg = true }
                            EffectRowBtn("Sliding Stretch (Разгон / Тормоз)...", hasAudio) { showEffectsSheet = false; showSlidingStretchDlg = true }
                            EffectRowBtn("Paulstretch (Ambient-растяжение)...", hasAudio) { showEffectsSheet = false; showPaulstretchDlg = true }
                        }
                        else -> { // Частоты и Пространство
                            EffectRowBtn("Графический Эквалайзер (5 полос)...", hasAudio) { showEffectsSheet = false; showEq5Dlg = true }
                            EffectRowBtn("Фильтр ФВЧ / ФНЧ (HPF / LPF)...", hasAudio) { showEffectsSheet = false; showFilterDlg = true }
                            EffectRowBtn("Notch Filter (Режекторный 50/60 Гц)...", hasAudio) { showEffectsSheet = false; showNotchDlg = true }
                            EffectRowBtn("Низкие и Высокие (Bass & Treble)...", hasAudio) { showEffectsSheet = false; showBassTrebleDlg = true }
                            EffectRowBtn("Vocal Reduction & Isolation (M/S)...", hasAudio) { showEffectsSheet = false; showVocalDlg = true }
                            EffectRowBtn("Phantom Center (LR4 M/S Кроссовер)...", hasAudio) { showEffectsSheet = false; showPhantomCenterDlg = true }
                            EffectRowBtn("Tape / Tube Saturator (Saturn)...", hasAudio) { showEffectsSheet = false; showSaturatorDlg = true }
                            EffectRowBtn("Vocoder (Вокодер)...", hasAudio) { showEffectsSheet = false; showVocoderDlg = true }
                            EffectRowBtn("Эхо (Echo + BPM Sync + Wrap Tail)...", hasAudio) { showEffectsSheet = false; echoReverbMode = false }
                            EffectRowBtn("Реверберация (Reverb + Wrap Tail)...", hasAudio) { showEffectsSheet = false; echoReverbMode = true }
                            EffectRowBtn("Тремоло (Tremolo LFO)...", hasAudio) { showEffectsSheet = false; showTremoloDlg = true }
                            EffectRowBtn("Фейзер (Phaser)...", hasAudio) { showEffectsSheet = false; showPhaserDlg = true }
                            EffectRowBtn("Вау-вау (Wahwah)...", hasAudio) { showEffectsSheet = false; showWahwahDlg = true }
                            EffectRowBtn("Дисторшн / Overdrive...", hasAudio) { showEffectsSheet = false; showDistortionDlg = true }
                            EffectRowBtn("Bitcrusher (Bit Depth + Downsample)...", hasAudio) { showEffectsSheet = false; showBitcrusherDlg = true }
                            EffectRowBtn("Генератор (Синус / Шум / Chirp / Метроном / DTMF / Pluck)...", true) { showEffectsSheet = false; showGenerateDlg = true }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // Dialogs
    if (showSmoothDlg) SmoothBoundaryDialog(
        onDismiss = { showSmoothDlg = false },
        onApplyMicroFade = { ms -> showSmoothDlg = false; lastEffectLabel = "Micro-Fade"; lastEffectAction = { if (WaveZNative.smoothBoundaries(selStart, selEnd, ms)) refreshWaveform() }; lastEffectAction?.invoke() },
        onApplyLoopCrossfade = { ms -> showSmoothDlg = false; lastEffectLabel = "Crossfade"; lastEffectAction = { if (WaveZNative.crossfadeLoopSeam(selStart, selEnd, ms)) refreshWaveform() }; lastEffectAction?.invoke() }
    )
    if (showAmplifyDlg) AmplifyDialog(
        onDismiss = { WaveZNative.stopPreview(); showAmplifyDlg = false },
        onPreview = { db -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 1, db.toDouble()) },
        onApply = { db -> WaveZNative.stopPreview(); showAmplifyDlg = false; lastEffectLabel = "Amplify"; lastEffectAction = { if (WaveZNative.amplifySelection(selStart, selEnd, db)) refreshWaveform() }; lastEffectAction?.invoke() }
    )
    if (showCompressorDlg) CompressorDialog(
        onDismiss = { WaveZNative.stopPreview(); showCompressorDlg = false },
        onPreview = { th, r -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 2, th.toDouble(), r.toDouble()) },
        onApply = { th, r -> WaveZNative.stopPreview(); showCompressorDlg = false; lastEffectLabel = "Compressor"; lastEffectAction = { if (WaveZNative.compressor(selStart, selEnd, th, r)) refreshWaveform() }; lastEffectAction?.invoke() }
    )
    if (showFilterDlg) FilterDialog(
        onDismiss = { WaveZNative.stopPreview(); showFilterDlg = false },
        onPreview = { type, hz -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 3, type.toDouble(), hz) },
        onApply = { type, hz -> WaveZNative.stopPreview(); showFilterDlg = false; lastEffectLabel = "Filter"; lastEffectAction = { if (WaveZNative.filter(selStart, selEnd, type, hz)) refreshWaveform() }; lastEffectAction?.invoke() }
    )
    if (showBassTrebleDlg) BassTrebleDialog(
        onDismiss = { WaveZNative.stopPreview(); showBassTrebleDlg = false },
        onPreview = { b, t -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 4, b.toDouble(), t.toDouble()) },
        onApply = { b, t -> WaveZNative.stopPreview(); showBassTrebleDlg = false; lastEffectLabel = "Bass/Treble"; lastEffectAction = { if (WaveZNative.bassTreble(selStart, selEnd, b, t)) refreshWaveform() }; lastEffectAction?.invoke() }
    )
    echoReverbMode?.let { isRev ->
        EchoReverbDialog(
            isReverb = isRev, bpm = bpm,
            onDismiss = { WaveZNative.stopPreview(); echoReverbMode = null },
            onPreview = { p1, p2, wrap -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, if (isRev) 6 else 5, p1.toDouble(), p2.toDouble(), if (wrap) 1.0 else 0.0) },
            onApply = { p1, p2, wrap ->
                WaveZNative.stopPreview(); echoReverbMode = null
                lastEffectLabel = if (isRev) "Reverb" else "Echo"
                lastEffectAction = {
                    val ok = if (isRev) WaveZNative.reverb(selStart, selEnd, p1, p2, wrap) else WaveZNative.echo(selStart, selEnd, p1, p2, wrap)
                    if (ok) refreshWaveform()
                }
                lastEffectAction?.invoke()
            }
        )
    }
    if (showNoiseGateDlg) NoiseGateDialog(
        onDismiss = { WaveZNative.stopPreview(); showNoiseGateDlg = false },
        onPreview = { th, att, rel -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 7, th.toDouble(), att.toDouble(), rel.toDouble()) },
        onApply = { th, att, rel -> WaveZNative.stopPreview(); showNoiseGateDlg = false; lastEffectLabel = "Noise Gate"; lastEffectAction = { if (WaveZNative.noiseGate(selStart, selEnd, th, att, rel)) refreshWaveform() }; lastEffectAction?.invoke() }
    )
    val selDurSec = (selEnd - selStart).coerceAtLeast(1L).toDouble() / sampleRate.coerceAtLeast(1)
    if (showPaulstretchDlg) PaulstretchDialog(
        onDismiss = { WaveZNative.stopPreview(); showPaulstretchDlg = false },
        onPreview = { f, tr -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 10, f, tr) },
        onApply = { f, tr ->
            WaveZNative.stopPreview(); showPaulstretchDlg = false
            lastEffectLabel = "Paulstretch"
            lastEffectAction = {
                scope.launch { isLoading = true; if (withContext(Dispatchers.Default) { WaveZNative.paulstretch(selStart, selEnd, f, tr) }) { viewStart = 0L; viewEnd = WaveZNative.getTotalFrames(); selStart = 0L; selEnd = viewEnd; refreshWaveform() }; isLoading = false }
            }
            lastEffectAction?.invoke()
        }
    )
    if (showSpeedDlg) ChangeSpeedDialog(
        currentDurationSec = selDurSec,
        onDismiss = { WaveZNative.stopPreview(); showSpeedDlg = false },
        onPreview = { ratio -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 8, ratio) },
        onApply = { ratio ->
            WaveZNative.stopPreview(); showSpeedDlg = false
            lastEffectLabel = "Speed/Pitch"
            lastEffectAction = {
                scope.launch { isLoading = true; if (withContext(Dispatchers.Default) { WaveZNative.changeSpeed(ratio) }) { viewStart = 0L; viewEnd = WaveZNative.getTotalFrames(); selStart = 0L; selEnd = viewEnd; refreshWaveform() }; isLoading = false }
            }
            lastEffectAction?.invoke()
        }
    )
    if (showSoundTouchDlg) SoundTouchDialog(
        currentDurationSec = selDurSec,
        currentBpm = bpm,
        onDismiss = { WaveZNative.stopPreview(); showSoundTouchDlg = false },
        onPreview = { tempo, pitch -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 9, tempo, pitch) },
        onApply = { tempo, pitch ->
            WaveZNative.stopPreview(); showSoundTouchDlg = false
            lastEffectLabel = "SoundTouch"
            lastEffectAction = {
                scope.launch { isLoading = true; if (withContext(Dispatchers.Default) { WaveZNative.soundTouch(tempo, pitch) }) { viewStart = 0L; viewEnd = WaveZNative.getTotalFrames(); selStart = 0L; selEnd = viewEnd; refreshWaveform() }; isLoading = false }
            }
            lastEffectAction?.invoke()
        }
    )
    if (showGenerateDlg) GenerateDialog(
        bpm = bpm,
        onDismiss = { showGenerateDlg = false },
        onGenerateSignal = { type, freq, amp, dur ->
            showGenerateDlg = false
            if (WaveZNative.generateSignal(type, freq, amp, dur, 44100, 2)) {
                tracks = listOf(TrackUi("Генератор (${freq.toInt()} Гц)")); viewStart = 0L; viewEnd = WaveZNative.getTotalFrames(); selStart = 0L; selEnd = viewEnd; refreshWaveform()
            }
        },
        onGenerateChirp = { f1, f2, dur ->
            showGenerateDlg = false
            if (WaveZNative.generateChirp(f1, f2, 0.7f, dur)) {
                tracks = listOf(TrackUi("Chirp ${f1.toInt()}-${f2.toInt()} Гц")); viewStart = 0L; viewEnd = WaveZNative.getTotalFrames(); selStart = 0L; selEnd = viewEnd; refreshWaveform()
            }
        },
        onGenerateRhythm = { b, dur ->
            showGenerateDlg = false
            if (WaveZNative.generateRhythm(b, beatsPerBar, dur)) {
                tracks = listOf(TrackUi("Метроном ${b.toInt()} BPM")); viewStart = 0L; viewEnd = WaveZNative.getTotalFrames(); selStart = 0L; selEnd = viewEnd; refreshWaveform()
            }
        },
        onGenerateDtmf = { digits, toneSec ->
            showGenerateDlg = false
            if (WaveZNative.generateDtmf(digits, toneSec)) {
                tracks = listOf(TrackUi("DTMF $digits")); viewStart = 0L; viewEnd = WaveZNative.getTotalFrames(); selStart = 0L; selEnd = viewEnd; refreshWaveform()
            }
        },
        onGeneratePluck = { freq, dur ->
            showGenerateDlg = false
            if (WaveZNative.generatePluck(freq, dur)) {
                tracks = listOf(TrackUi("Pluck ${freq.toInt()} Гц")); viewStart = 0L; viewEnd = WaveZNative.getTotalFrames(); selStart = 0L; selEnd = viewEnd; refreshWaveform()
            }
        },
        onGenerateRissetDrum = { freq, dur ->
            showGenerateDlg = false
            if (WaveZNative.generateRissetDrum(freq, 0.4, 1500.0, dur)) {
                tracks = listOf(TrackUi("Risset Drum ${freq.toInt()} Гц")); viewStart = 0L; viewEnd = WaveZNative.getTotalFrames(); selStart = 0L; selEnd = viewEnd; refreshWaveform()
            }
        }
    )
    if (showClipFixDlg) ClipFixDialog(
        onDismiss = { WaveZNative.stopPreview(); showClipFixDlg = false },
        onPreview = { th, red -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 23, th.toDouble(), red.toDouble()) },
        onApply = { th, red ->
            WaveZNative.stopPreview(); showClipFixDlg = false
            lastEffectLabel = "Clip Fix"; lastEffectAction = { if (WaveZNative.clipFix(selStart, selEnd, th, red)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showVocoderDlg) VocoderDialog(
        onDismiss = { WaveZNative.stopPreview(); showVocoderDlg = false },
        onPreview = { b, c, m -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 27, b.toDouble(), c, m.toDouble()) },
        onApply = { b, c, m ->
            WaveZNative.stopPreview(); showVocoderDlg = false
            lastEffectLabel = "Vocoder"; lastEffectAction = { if (WaveZNative.vocoder(selStart, selEnd, b, c, m)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showDeEsserDlg) DeEsserDialog(
        onDismiss = { WaveZNative.stopPreview(); showDeEsserDlg = false },
        onPreview = { f, th, r -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 24, f, th.toDouble(), r.toDouble()) },
        onApply = { f, th, r ->
            WaveZNative.stopPreview(); showDeEsserDlg = false
            lastEffectLabel = "De-Esser"; lastEffectAction = { if (WaveZNative.deEsser(selStart, selEnd, f, th, r)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showMultibandDlg) MultibandCompDialog(
        onDismiss = { WaveZNative.stopPreview(); showMultibandDlg = false },
        onPreview = { lo, hi, th, r, _ -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 25, lo, hi, th.toDouble(), r.toDouble()) },
        onApply = { lo, hi, th, r, mk ->
            WaveZNative.stopPreview(); showMultibandDlg = false
            lastEffectLabel = "Multiband Comp"; lastEffectAction = { if (WaveZNative.multibandCompressor(selStart, selEnd, lo, hi, th, r, mk)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showSaturatorDlg) SaturatorDialog(
        onDismiss = { WaveZNative.stopPreview(); showSaturatorDlg = false },
        onPreview = { m, d, w, mx -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 26, m.toDouble(), d.toDouble(), w.toDouble(), mx.toDouble()) },
        onApply = { m, d, w, mx ->
            WaveZNative.stopPreview(); showSaturatorDlg = false
            lastEffectLabel = "Saturator"; lastEffectAction = { if (WaveZNative.saturator(selStart, selEnd, m, d, w, mx)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showBitcrusherDlg) BitcrusherDialog(
        onDismiss = { WaveZNative.stopPreview(); showBitcrusherDlg = false },
        onPreview = { bits, ds, mx -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 28, bits.toDouble(), ds.toDouble(), mx.toDouble()) },
        onApply = { bits, ds, mx ->
            WaveZNative.stopPreview(); showBitcrusherDlg = false
            lastEffectLabel = "Bitcrusher"; lastEffectAction = { if (WaveZNative.bitcrusher(selStart, selEnd, bits, ds, mx)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showRegLabelsDlg) RegularIntervalLabelsDialog(
        onDismiss = { showRegLabelsDlg = false },
        onApplyCount = { cnt ->
            showRegLabelsDlg = false
            val span = (selEnd - selStart).coerceAtLeast(1L)
            val added = (0 until cnt).map { i ->
                val f = selStart + (span * i) / cnt
                WaveZLabel(System.nanoTime() + i, f, 0L, "M${i + 1}", 0xFF00E5FFL)
            }
            labels = labels + added
            Toast.makeText(context, "Добавлено меток: ${added.size}", Toast.LENGTH_SHORT).show()
        }
    )
    if (showEffectRackDlg) EffectRackDialog(
        rackSteps = rackSteps,
        availableEffects = listOf(
            "HPF 80Hz" to { WaveZNative.filter(selStart, selEnd, 1, 80.0) },
            "De-Esser" to { WaveZNative.deEsser(selStart, selEnd, 6500.0, -22f, 4f) },
            "Tape Saturator" to { WaveZNative.saturator(selStart, selEnd, 0, 5f, 0.5f, 100f) },
            "Multiband Comp" to { WaveZNative.multibandCompressor(selStart, selEnd, 200.0, 3000.0, -18f, 3.5f, 2f) },
            "Phantom -100%" to { WaveZNative.phantomCenter(selStart, selEnd, 120.0, 16000.0, -100f, 0f) },
            "Normalize -1dB" to { WaveZNative.normalizeSelection(selStart, selEnd, -1f, true) },
            "Limiter -0.5dB" to { WaveZNative.limiter(selStart, selEnd, -0.5f) }
        ),
        onAddStep = { rackSteps = rackSteps + it },
        onRemoveStep = { idx -> rackSteps = rackSteps.filterIndexed { i, _ -> i != idx } },
        onMoveUp = { idx ->
            if (idx > 0) {
                val m = rackSteps.toMutableList()
                val item = m.removeAt(idx)
                m.add(idx - 1, item)
                rackSteps = m
            }
        },
        onApplyRack = {
            showEffectRackDlg = false
            if (hasAudio && rackSteps.isNotEmpty()) {
                rackSteps.forEach { (_, fn) -> fn() }
                refreshWaveform()
                Toast.makeText(context, "Цепь из ${rackSteps.size} эффектов применена", Toast.LENGTH_SHORT).show()
            }
        },
        onDismiss = { showEffectRackDlg = false }
    )
    if (showAboutDlg) AboutSummaryDialog { showAboutDlg = false }
    if (showNoiseReductionDlg) NoiseReductionDialog(
        hasProfile = WaveZNative.hasNoiseProfile(),
        onCaptureProfile = {
            if (WaveZNative.captureNoiseProfile(selStart, selEnd)) {
                Toast.makeText(context, "Профиль шума снят!", Toast.LENGTH_SHORT).show()
            }
        },
        onDismiss = { WaveZNative.stopPreview(); showNoiseReductionDlg = false },
        onPreview = { r, s, sm, res -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 20, r.toDouble(), s.toDouble(), sm.toDouble(), if (res) 1.0 else 0.0) },
        onApply = { r, s, sm, res ->
            WaveZNative.stopPreview(); showNoiseReductionDlg = false
            lastEffectLabel = "Noise Reduction"; lastEffectAction = { if (WaveZNative.noiseReduction(selStart, selEnd, r, s, sm, res)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showNotchDlg) NotchFilterDialog(
        onDismiss = { WaveZNative.stopPreview(); showNotchDlg = false },
        onPreview = { f, q -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 16, f, q) },
        onApply = { f, q ->
            WaveZNative.stopPreview(); showNotchDlg = false
            lastEffectLabel = "Notch ${f.toInt()} Гц"; lastEffectAction = { if (WaveZNative.notchFilter(selStart, selEnd, f, q)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showVocalDlg) VocalReductionDialog(
        onDismiss = { WaveZNative.stopPreview(); showVocalDlg = false },
        onPreview = { m, st, lo, hi -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 17, m.toDouble(), st.toDouble(), lo, hi) },
        onApply = { m, st, lo, hi ->
            WaveZNative.stopPreview(); showVocalDlg = false
            lastEffectLabel = "Vocal Isolation"; lastEffectAction = { if (WaveZNative.vocalReduction(selStart, selEnd, m, st, lo, hi)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showPhantomCenterDlg) PhantomCenterDialog(
        onDismiss = { WaveZNative.stopPreview(); showPhantomCenterDlg = false },
        onPreview = { hpf, lpf, mix, outDb -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 22, hpf, lpf, mix.toDouble(), outDb.toDouble()) },
        onApply = { hpf, lpf, mix, outDb ->
            WaveZNative.stopPreview(); showPhantomCenterDlg = false
            lastEffectLabel = "Phantom Center"; lastEffectAction = { if (WaveZNative.phantomCenter(selStart, selEnd, hpf, lpf, mix, outDb)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showTremoloDlg) TremoloDialog(
        onDismiss = { WaveZNative.stopPreview(); showTremoloDlg = false },
        onPreview = { wf, ph, wet, f -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 18, wf.toDouble(), ph.toDouble(), wet.toDouble(), f.toDouble()) },
        onApply = { wf, ph, wet, f ->
            WaveZNative.stopPreview(); showTremoloDlg = false
            lastEffectLabel = "Tremolo"; lastEffectAction = { if (WaveZNative.tremolo(selStart, selEnd, wf, ph, wet, f)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showSlidingStretchDlg) SlidingStretchDialog(
        onDismiss = { WaveZNative.stopPreview(); showSlidingStretchDlg = false },
        onPreview = { it, ft, ip, fp -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 21, it, ft, ip, fp) },
        onApply = { it, ft, ip, fp ->
            WaveZNative.stopPreview(); showSlidingStretchDlg = false
            lastEffectLabel = "Sliding Stretch"; lastEffectAction = { if (WaveZNative.slidingStretch(it, ft, ip, fp)) { viewEnd = WaveZNative.getTotalFrames(); refreshWaveform() } }
            lastEffectAction?.invoke()
        }
    )
    if (showAdjFadeDlg) AdjustableFadeDialog(
        onDismiss = { WaveZNative.stopPreview(); showAdjFadeDlg = false },
        onPreview = { ft, mid, sp, ep, _ -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 19, ft.toDouble(), mid.toDouble(), sp.toDouble(), ep.toDouble()) },
        onApply = { ft, mid, sp, ep, c ->
            WaveZNative.stopPreview(); showAdjFadeDlg = false
            lastEffectLabel = "Adj. Fade"; lastEffectAction = { if (WaveZNative.adjustableFade(selStart, selEnd, ft, mid, sp, ep, c)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showAutoDuckDlg) AutoDuckDialog(
        trackNames = tracks.map { it.name },
        activeTrackIdx = activeTrackIdx,
        onDismiss = { showAutoDuckDlg = false },
        onApply = { ctrlIdx, duck, th, outS, inS ->
            showAutoDuckDlg = false
            lastEffectLabel = "Auto Duck"; lastEffectAction = { if (WaveZNative.autoDuck(ctrlIdx, duck, th, outS, inS)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showNormalizeDlg) NormalizeDialog(
        onDismiss = { showNormalizeDlg = false },
        onApply = { peakDb, remDc ->
            showNormalizeDlg = false
            lastEffectLabel = "Normalize"; lastEffectAction = { if (WaveZNative.normalizeSelection(selStart, selEnd, peakDb, remDc)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showLimiterDlg) LimiterDialog(
        onDismiss = { showLimiterDlg = false },
        onApply = { ceilDb ->
            showLimiterDlg = false
            lastEffectLabel = "Limiter"; lastEffectAction = { if (WaveZNative.limiter(selStart, selEnd, ceilDb)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showTruncateSilDlg) TruncateSilenceDialog(
        onDismiss = { showTruncateSilDlg = false },
        onApply = { thDb, minS ->
            showTruncateSilDlg = false
            lastEffectLabel = "Truncate Silence"; lastEffectAction = { if (WaveZNative.truncateSilence(thDb, minS)) { viewStart = 0L; viewEnd = WaveZNative.getTotalFrames(); refreshWaveform() } }
            lastEffectAction?.invoke()
        }
    )
    finderMode?.let { fm ->
        FinderDialog(
            mode = fm,
            onDismiss = { finderMode = null },
            onRun = { p1, p2, p3 ->
                finderMode = null
                val newLbls = mutableListOf<WaveZLabel>()
                when (fm) {
                    0 -> {
                        val arr = WaveZNative.findSilences(p1, p2)
                        var i = 0
                        while (i + 1 < arr.size) {
                            newLbls.add(WaveZLabel(System.nanoTime() + i, arr[i], arr[i + 1], "Пауза ${i / 2 + 1}", 0xFF00E5FFL))
                            i += 2
                        }
                    }
                    1 -> {
                        val arr = WaveZNative.findSounds(p1, p2, p3)
                        var i = 0
                        while (i + 1 < arr.size) {
                            newLbls.add(WaveZLabel(System.nanoTime() + i, arr[i], arr[i + 1], "Фрагмент ${i / 2 + 1}", 0xFF39FF14L))
                            i += 2
                        }
                    }
                    2 -> {
                        val arr = WaveZNative.findBeats(p1)
                        arr.forEachIndexed { idx, f ->
                            newLbls.add(WaveZLabel(System.nanoTime() + idx, f, 0L, "B${idx + 1}", 0xFFFFB300L))
                        }
                    }
                    else -> {
                        val arr = WaveZNative.findClippings(p1.toInt(), p2.toInt())
                        var i = 0
                        while (i + 1 < arr.size) {
                            newLbls.add(WaveZLabel(System.nanoTime() + i, arr[i], arr[i + 1], "Clip ${i / 2 + 1}", 0xFFFF5252L))
                            i += 2
                        }
                    }
                }
                if (newLbls.isEmpty()) {
                    Toast.makeText(context, "Ничего не найдено по заданному порогу", Toast.LENGTH_SHORT).show()
                } else {
                    labels = labels + newLbls
                    Toast.makeText(context, "Добавлено меток: ${newLbls.size}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
    if (showEq5Dlg) Eq5Dialog(
        onDismiss = { WaveZNative.stopPreview(); showEq5Dlg = false },
        onPreview = { g60, g250, g1k, g4k, _ -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 11, g60.toDouble(), g250.toDouble(), g1k.toDouble(), g4k.toDouble()) },
        onApply = { g60, g250, g1k, g4k, g12k ->
            WaveZNative.stopPreview(); showEq5Dlg = false
            lastEffectLabel = "EQ 5-band"; lastEffectAction = { if (WaveZNative.equalizer5Band(selStart, selEnd, g60, g250, g1k, g4k, g12k)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showPhaserDlg) PhaserDialog(
        onDismiss = { WaveZNative.stopPreview(); showPhaserDlg = false },
        onPreview = { r, d, fb -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 12, r.toDouble(), d.toDouble(), fb.toDouble()) },
        onApply = { r, d, fb ->
            WaveZNative.stopPreview(); showPhaserDlg = false
            lastEffectLabel = "Phaser"; lastEffectAction = { if (WaveZNative.phaser(selStart, selEnd, r, d, fb)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showWahwahDlg) WahwahDialog(
        onDismiss = { WaveZNative.stopPreview(); showWahwahDlg = false },
        onPreview = { f, d, res -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 13, f.toDouble(), d.toDouble(), res.toDouble()) },
        onApply = { f, d, res ->
            WaveZNative.stopPreview(); showWahwahDlg = false
            lastEffectLabel = "Wahwah"; lastEffectAction = { if (WaveZNative.wahwah(selStart, selEnd, f, d, res)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showDistortionDlg) DistortionDialog(
        onDismiss = { WaveZNative.stopPreview(); showDistortionDlg = false },
        onPreview = { m, drv, mx -> isPlaying = WaveZNative.previewEffect(selStart, selEnd, 14, m.toDouble(), drv.toDouble(), mx.toDouble()) },
        onApply = { m, drv, mx ->
            WaveZNative.stopPreview(); showDistortionDlg = false
            lastEffectLabel = "Distortion"; lastEffectAction = { if (WaveZNative.distortion(selStart, selEnd, m, drv, mx)) refreshWaveform() }
            lastEffectAction?.invoke()
        }
    )
    if (showRepeatDlg) RepeatDialog({ showRepeatDlg = false }) { cnt ->
        showRepeatDlg = false
        lastEffectLabel = "Repeat x$cnt"; lastEffectAction = { if (WaveZNative.repeatSelection(selStart, selEnd, cnt)) { viewEnd = WaveZNative.getTotalFrames(); refreshWaveform() } }
        lastEffectAction?.invoke()
    }
    if (showSnapDlg) SnapConfigDialog(
        currentMode = snapMode.label,
        currentCustomDiv = customSnapDiv,
        currentCustomSamples = customSnapSamples,
        currentCustomMs = customSnapMs,
        currentCustomUnit = customSnapUnit,
        onDismiss = { showSnapDlg = false },
        onSelectPreset = { lbl ->
            snapMode = SnapMode.entries.firstOrNull { it.label == lbl } ?: SnapMode.OFF
            showSnapDlg = false
        },
        onApplyCustom = { u, d, s, m ->
            customSnapUnit = u; customSnapDiv = d; customSnapSamples = s; customSnapMs = m
            snapMode = SnapMode.CUSTOM
            showSnapDlg = false
        }
    )
    if (showThemeDlg) ThemePickerDialog(
        isDark = themeDark,
        isMaterial3 = themeM3,
        onDismiss = { showThemeDlg = false },
        onSelect = { dark, m3 ->
            themeDark = dark; themeM3 = m3
            themePrefs.edit().putBoolean("theme_dark", dark).putBoolean("theme_m3", m3).apply()
            showThemeDlg = false
        }
    )
    if (showPlaybackSpeedDlg) PlaybackSpeedDialog(
        currentSpeed = playbackSpeed,
        onDismiss = { showPlaybackSpeedDlg = false },
        onApply = { spd ->
            playbackSpeed = spd
            WaveZNative.setPlaybackSpeed(spd)
        }
    )
    editingLabel?.let { lbl ->
        val rangeStr = if (lbl.endSample > lbl.startSample) {
            "${formatTime(lbl.startSample, sampleRate)} → ${formatTime(lbl.endSample, sampleRate)}"
        } else {
            "Позиция: ${formatTime(lbl.startSample, sampleRate)}"
        }
        LabelEditDialog(
            initialText = lbl.text,
            initialColorHex = lbl.colorHex,
            isRegion = lbl.endSample > lbl.startSample,
            rangeText = rangeStr,
            onDismiss = { editingLabel = null },
            onDelete = {
                labels = labels.filterNot { it.id == lbl.id }
                editingLabel = null
            },
            onSave = { newText, newColor ->
                labels = labels.map { if (it.id == lbl.id) it.copy(text = newText, colorHex = newColor) else it }
                editingLabel = null
            }
        )
    }
    if (showSelectionDlg) SelectionEditDialog(selStart, selEnd, timelineFrames.coerceAtLeast(1L), sampleRate, bpm, beatsPerBar, gridOffsetSample, showBeatGrid, playheadSample, { showSelectionDlg = false }) { s, e, newBpm, newBeats, newOff, newGrid ->
        showSelectionDlg = false; bpm = newBpm; beatsPerBar = newBeats; gridOffsetSample = newOff; showBeatGrid = newGrid; updateSelection(s, e)
    }
    if (showExportDlg) ExportFormatDialog({ showExportDlg = false }) { fmt, writeSmpl ->
        showExportDlg = false
        pendingExportFormat = fmt
        pendingWriteSmpl = writeSmpl
        val base = tracks.firstOrNull()?.name?.substringBeforeLast(".") ?: "wavez"
        exportDocLauncher.launch("${base}_wavez.${fmt.ext}")
    }
    spectrumData?.let { spec -> SpectrumDialog(spec, sampleRate) { spectrumData = null } }
    analysisData?.let { (stats, seam) ->
        AnalysisDialog(stats, seam, { analysisData = null }) {
            analysisData = null
            val rStart = (selStart - 32L).coerceAtLeast(0L)
            val rEnd = (rStart + 64L).coerceAtMost(timelineFrames)
            if (WaveZNative.repairSelection(rStart, rEnd)) { refreshWaveform(); Toast.makeText(context, "Шов сглажен (LSAR)", Toast.LENGTH_SHORT).show() }
        }
    }
    AnimatedVisibility(
        visible = showIntro,
        exit = fadeOut(tween(420))
    ) {
        WaveZIntroOverlay(
            onFinished = {
                WaveZSession.introPlayed = true
                showIntro = false
            }
        )
    }
    } // end WaveZTheme
    } // end Box
}

@Composable
private fun WaveZIntroOverlay(onFinished: () -> Unit) {
    val pal = LocalWaveZPalette.current
    val cs = MaterialTheme.colorScheme
    val bgCol = if (pal.isMaterial3) cs.background else pal.workspace
    val startCol = if (pal.isMaterial3) cs.primary else pal.neon
    val endCol = if (pal.isMaterial3) cs.tertiary else pal.cyan
    val peakCol = if (pal.isMaterial3) cs.secondary else pal.amber

    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            animationSpec = tween(
                durationMillis = 2600,
                easing = androidx.compose.animation.core.CubicBezierEasing(0.25f, 0.85f, 0.35f, 1.0f)
            )
        )
        onFinished()
    }
    val p = progress.value
    Box(
        modifier = Modifier.fillMaxSize().background(bgCol),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(220.dp)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val bars = 23
            val barAreaW = w * 0.68f
            val leftX = cx - barAreaW / 2f
            val stepX = barAreaW / (bars - 1)
            val strokeW = stepX * 0.56f
            val zTop = h * 0.22f
            val zBot = h * 0.78f
            val zH = zBot - zTop

            // Smooth S-curve bar appearance (no cursor sine wave)
            fun barAppear(strokeProgress: Float, indexFrac: Float): Float {
                val local = ((strokeProgress - indexFrac * 0.7f) / 0.3f).coerceIn(0f, 1f)
                return local * local * local * (local * (local * 6f - 15f) + 10f)
            }

            val s1 = (p / 0.32f).coerceIn(0f, 1f)
            val s2 = ((p - 0.26f) / 0.38f).coerceIn(0f, 1f)
            val s3 = ((p - 0.58f) / 0.34f).coerceIn(0f, 1f)

            for (i in 0 until bars) {
                val u = i / (bars - 1f)
                val x = leftX + i * stepX
                val col = androidx.compose.ui.graphics.lerp(startCol, endCol, u)

                // 1. Top bar (left -> right) with rounded top-right bend & spectrum contour
                val aTop = barAppear(s1, u)
                if (aTop > 0.01f) {
                    val roundTop = ((u - 0.70f) / 0.30f).coerceIn(0f, 1f)
                    val topBend = roundTop * roundTop * (zH * 0.10f)
                    val topThick = 0.085f + 0.11f * kotlin.math.exp(-((u - 0.20f) * (u - 0.20f)) / 0.016f) +
                        0.14f * kotlin.math.exp(-((u - 0.66f) * (u - 0.66f)) / 0.016f)
                    val pulse = 1f + 0.32f * kotlin.math.sin(aTop * kotlin.math.PI.toFloat()) * (1f - aTop)
                    val half = (topThick * zH * 0.45f) * aTop * pulse
                    val yCenter = zTop + topBend + kotlin.math.sin(u * kotlin.math.PI.toFloat() * 2f) * (zH * 0.014f)
                    val y1 = yCenter - half
                    val y2 = yCenter + half
                    val isPeak = kotlin.math.abs(u - 0.66f) < 0.09f
                    val brush = if (isPeak) Brush.verticalGradient(listOf(peakCol, col), y1, y2)
                    else Brush.verticalGradient(listOf(col, col), y1, y2)
                    drawLine(brush, Offset(x, y1), Offset(x, y2), strokeW, cap = StrokeCap.Round)
                }

                // 2. Diagonal (top-right -> bottom-left) with rounded S-curve & spectrum bars
                val aDiag = barAppear(s2, 1f - u)
                if (aDiag > 0.01f) {
                    val curvedU = u * u * (3f - 2f * u)
                    val smoothU = 0.55f * u + 0.45f * curvedU
                    val diagY = (zBot - zH * 0.09f) - smoothU * (zH * 0.82f)
                    val specMod = 0.07f + 0.045f * kotlin.math.exp(-((u - 0.5f) * (u - 0.5f)) / 0.05f)
                    val pulse = 1f + 0.32f * kotlin.math.sin(aDiag * kotlin.math.PI.toFloat()) * (1f - aDiag)
                    val half = (specMod * zH) * aDiag * pulse
                    drawLine(col, Offset(x, diagY - half), Offset(x, diagY + half), strokeW, cap = StrokeCap.Round)
                }

                // 3. Bottom bar (left -> right) with rounded bottom-left bend & spectrum contour
                val aBot = barAppear(s3, u)
                if (aBot > 0.01f) {
                    val roundBot = ((0.30f - u) / 0.30f).coerceIn(0f, 1f)
                    val botBend = roundBot * roundBot * (zH * 0.10f)
                    val botThick = 0.085f + 0.13f * kotlin.math.exp(-((u - 0.72f) * (u - 0.72f)) / 0.016f)
                    val pulse = 1f + 0.32f * kotlin.math.sin(aBot * kotlin.math.PI.toFloat()) * (1f - aBot)
                    val half = (botThick * zH * 0.45f) * aBot * pulse
                    val yCenter = zBot - botBend - kotlin.math.sin(u * kotlin.math.PI.toFloat() * 2f) * (zH * 0.014f)
                    val y1 = yCenter - half
                    val y2 = yCenter + half
                    val isPeak = kotlin.math.abs(u - 0.72f) < 0.09f
                    val brush = if (isPeak) Brush.verticalGradient(listOf(peakCol, col), y1, y2)
                    else Brush.verticalGradient(listOf(col, col), y1, y2)
                    drawLine(brush, Offset(x, y1), Offset(x, y2), strokeW, cap = StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun EffectRowBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    val pal = LocalWaveZPalette.current
    val bg = if (pal.isMaterial3) MaterialTheme.colorScheme.surfaceContainerHigh else StudioChrome
    val shape = if (pal.isMaterial3) MaterialTheme.shapes.medium else RoundedCornerShape(8.dp)
    Surface(
        color = bg, shape = shape,
        border = if (pal.isMaterial3) null else BorderStroke(1.dp, StudioBorder),
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            label,
            color = if (enabled) StudioText else StudioText.copy(alpha = 0.35f),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun StudioMenuItem(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, color = if (enabled) StudioText else StudioText.copy(alpha = 0.35f), fontSize = 13.sp) }, enabled = enabled, onClick = onClick)
}

@Composable
private fun ToolbarIconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean, tint: Color = StudioText, onClick: () -> Unit) {
    val pal = LocalWaveZPalette.current
    val shape = if (pal.isMaterial3) RoundedCornerShape(14.dp) else RoundedCornerShape(6.dp)
    val animatedTint by animateColorAsState(
        targetValue = if (enabled) tint else StudioText.copy(alpha = 0.25f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "btnTint"
    )
    Surface(
        color = StudioChrome, shape = shape, border = if (pal.isMaterial3) BorderStroke(0.5.dp, StudioBorder.copy(alpha = 0.45f)) else BorderStroke(1.dp, StudioBorder),
        modifier = Modifier.size(34.dp).clip(shape).clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, desc, tint = animatedTint, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun StereoDbMeter(leftDb: Float, rightDb: Float) {
    val pal = LocalWaveZPalette.current
    val animL by animateFloatAsState(targetValue = ((leftDb + 48f) / 48f).coerceIn(0f, 1f), animationSpec = spring(stiffness = Spring.StiffnessMedium), label = "dbL")
    val animR by animateFloatAsState(targetValue = ((rightDb + 48f) / 48f).coerceIn(0f, 1f), animationSpec = spring(stiffness = Spring.StiffnessMedium), label = "dbR")
    val boxShape = if (pal.isMaterial3) RoundedCornerShape(10.dp) else RoundedCornerShape(4.dp)
    Column(modifier = Modifier.width(64.dp).background(pal.lane, boxShape).padding(horizontal = 5.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf("L" to (leftDb to animL), "R" to (rightDb to animR)).forEach { (ch, pair) ->
            val (db, frac) = pair
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ch, color = pal.text.copy(alpha = 0.7f), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Canvas(modifier = Modifier.weight(1f).height(6.dp)) {
                    val cr = if (pal.isMaterial3) CornerRadius(3f, 3f) else CornerRadius.Zero
                    drawRoundRect(pal.chrome, cornerRadius = cr)
                    val w = size.width * frac
                    val col = when { db > -1.5f -> Color(0xFFFF5252); db > -12f -> pal.amber; else -> pal.neon }
                    if (w > 0f) drawRoundRect(col, size = Size(w, size.height), cornerRadius = cr)
                }
            }
        }
    }
}

@Composable
private fun CompactTrackControlPanel(
    title: String, isSelected: Boolean, isMuted: Boolean, isSolo: Boolean,
    onSelectAndOpenSheet: () -> Unit, onToggleMute: () -> Unit, onToggleSolo: () -> Unit
) {
    val pal = LocalWaveZPalette.current
    val btnShape = if (pal.isMaterial3) RoundedCornerShape(10.dp) else RoundedCornerShape(4.dp)
    val panelBg by animateColorAsState(
        targetValue = if (isSelected) (if (pal.isMaterial3) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f) else Color(0xFF313944)) else StudioPanel,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "trkPanelBg"
    )
    val muteBg by animateColorAsState(if (isMuted) Color(0xFFFF5252).copy(alpha = 0.25f) else StudioChrome, spring(stiffness = Spring.StiffnessMediumLow), label = "mBg")
    val soloBg by animateColorAsState(if (isSolo) StudioAmber.copy(alpha = 0.25f) else StudioChrome, spring(stiffness = Spring.StiffnessMediumLow), label = "sBg")
    Column(
        modifier = Modifier.width(52.dp).fillMaxHeight().background(panelBg).clickable(onClick = onSelectAndOpenSheet).padding(3.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = if (isSelected) StudioNeon else StudioText, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Surface(color = muteBg, shape = btnShape, border = BorderStroke(1.dp, if (isMuted) Color(0xFFFF5252) else StudioBorder), modifier = Modifier.fillMaxWidth().clip(btnShape).clickable(onClick = onToggleMute)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(4.dp)) { Text("M", color = if (isMuted) Color(0xFFFF5252) else StudioText, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
            Surface(color = soloBg, shape = btnShape, border = BorderStroke(1.dp, if (isSolo) StudioAmber else StudioBorder), modifier = Modifier.fillMaxWidth().clip(btnShape).clickable(onClick = onToggleSolo)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(4.dp)) { Text("S", color = if (isSolo) StudioAmber else StudioText, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Icon(Icons.Default.Tune, "Микшер и M1..M4", tint = StudioText.copy(alpha = 0.6f), modifier = Modifier.size(16.dp).align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun MarkerBarCanvas(
    viewStart: Long, viewEnd: Long, selStart: Long, selEnd: Long,
    m1: Long, m3: Long, showM1M4: Boolean,
    sampleRate: Int, bpm: Double, beatsPerBar: Int, gridOffset: Long, showBeatGrid: Boolean, isTop: Boolean,
    onSelectionChange: (Long, Long) -> Unit, modifier: Modifier = Modifier
) {
    val hitSlopPx = with(LocalDensity.current) { 48.dp.toPx() }
    val pal = LocalWaveZPalette.current
    val curViewStart by rememberUpdatedState(viewStart)
    val curViewEnd by rememberUpdatedState(viewEnd)
    val curSelStart by rememberUpdatedState(selStart)
    val curSelEnd by rememberUpdatedState(selEnd)
    val curOnSelChange by rememberUpdatedState(onSelectionChange)
    var activeHandle by remember { mutableIntStateOf(0) }
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { offset ->
                    val vs = curViewStart; val ve = curViewEnd
                    val ss = curSelStart; val se = curSelEnd
                    val span = (ve - vs).coerceAtLeast(1L).toFloat()
                    val sx = ((ss - vs) / span) * size.width
                    val ex = ((se - vs) / span) * size.width
                    val dS = abs(offset.x - sx)
                    val dE = abs(offset.x - ex)
                    activeHandle = if (dS <= hitSlopPx && dS <= dE) 1 else if (dE <= hitSlopPx) 2 else if (dS <= dE) 1 else 2
                },
                onHorizontalDrag = { change, _ ->
                    change.consume()
                    val vs = curViewStart; val ve = curViewEnd
                    val ss = curSelStart; val se = curSelEnd
                    val span = (ve - vs).coerceAtLeast(1L)
                    val frame = vs + ((change.position.x / size.width) * span).roundToLong()
                    if (activeHandle == 1) curOnSelChange(frame.coerceAtMost(se - 1L), se)
                    else curOnSelChange(ss, frame.coerceAtLeast(ss + 1L))
                }
            )
        }
    ) {
        val span = (viewEnd - viewStart).coerceAtLeast(1L).toFloat()
        val sFrac = ((selStart - viewStart) / span).coerceIn(0f, 1f)
        val eFrac = ((selEnd - viewStart) / span).coerceIn(0f, 1f)
        if (eFrac > sFrac) drawRect(pal.neon.copy(alpha = 0.2f), Offset(sFrac * size.width, 0f), Size((eFrac - sFrac) * size.width, size.height))
        if (isTop) {
            for (i in 0..10) { val x = (i / 10f) * size.width; drawLine(pal.text.copy(alpha = 0.4f), Offset(x, size.height * 0.65f), Offset(x, size.height), 1.5f) }
            if (showBeatGrid && bpm > 0.0) {
                val fpb = sampleRate * 60.0 / bpm
                val first = kotlin.math.ceil((viewStart - gridOffset) / fpb).toLong()
                val last = kotlin.math.floor((viewEnd - gridOffset) / fpb).toLong()
                val step = ((last - first) / 48L).coerceAtLeast(1L); var b = first
                while (b <= last) {
                    val samplePos = gridOffset + b * fpb
                    val bx = ((samplePos - viewStart) / span).toFloat() * size.width
                    val isBar = (beatsPerBar > 0) && (((b % beatsPerBar) + beatsPerBar) % beatsPerBar == 0L)
                    drawLine(if (isBar) pal.amber else pal.amber.copy(alpha = 0.45f), Offset(bx, if (isBar) 0f else size.height * 0.4f), Offset(bx, size.height), if (isBar) 2.5f else 1f)
                    b += step
                }
            }
        }
        if (showM1M4) {
            val m1x = (((m1 - viewStart) / span).coerceIn(0f, 1f)) * size.width
            val m3x = (((m3 - viewStart) / span).coerceIn(0f, 1f)) * size.width
            drawCircle(pal.amber, 6f, Offset(m1x, size.height / 2f))
            drawCircle(pal.magenta, 7f, Offset(m3x, size.height / 2f))
        }
        val sx = sFrac * size.width; val ex = eFrac * size.width; val r = 13f
        if (isTop) {
            drawPath(Path().apply { moveTo(sx, size.height); lineTo(sx - r, 2f); lineTo(sx + r, 2f); close() }, pal.neon)
            drawPath(Path().apply { moveTo(ex, size.height); lineTo(ex - r, 2f); lineTo(ex + r, 2f); close() }, pal.cyan)
        } else {
            drawPath(Path().apply { moveTo(sx, 0f); lineTo(sx - r, size.height - 2f); lineTo(sx + r, size.height - 2f); close() }, pal.neon)
            drawPath(Path().apply { moveTo(ex, 0f); lineTo(ex - r, size.height - 2f); lineTo(ex + r, size.height - 2f); close() }, pal.cyan)
        }
    }
}

@Composable
private fun LabelTrackBar(
    viewStart: Long,
    viewEnd: Long,
    labels: List<WaveZLabel>,
    onAddLabel: () -> Unit,
    onTapLabel: (WaveZLabel) -> Unit,
    onEditLabel: (WaveZLabel) -> Unit
) {
    val pal = LocalWaveZPalette.current
    val span = (viewEnd - viewStart).coerceAtLeast(1L).toFloat()
    Row(modifier = Modifier.fillMaxWidth().height(24.dp).background(pal.chrome)) {
        Surface(
            color = pal.panel,
            border = BorderStroke(1.dp, pal.amber.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(3.dp),
            modifier = Modifier.width(52.dp).fillMaxHeight().padding(1.dp).clickable(onClick = onAddLabel)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("+ Метка", color = pal.amber, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val totalW = maxWidth
            Canvas(modifier = Modifier.fillMaxSize()) {
                labels.forEach { lbl ->
                    val x1 = ((lbl.startSample - viewStart) / span) * size.width
                    val col = Color(lbl.colorHex)
                    if (lbl.endSample > lbl.startSample) {
                        val x2 = ((lbl.endSample - viewStart) / span) * size.width
                        val left = x1.coerceIn(0f, size.width)
                        val right = x2.coerceIn(0f, size.width)
                        if (right > left) {
                            drawRect(col.copy(alpha = 0.28f), Offset(left, 2f), Size(right - left, size.height - 4f))
                            drawLine(col, Offset(left, 0f), Offset(left, size.height), 2f)
                            drawLine(col, Offset(right, 0f), Offset(right, size.height), 2f)
                        }
                    } else if (x1 in 0f..size.width) {
                        drawLine(col, Offset(x1, 0f), Offset(x1, size.height), 2.5f)
                        drawCircle(col, 4.5f, Offset(x1, 6f))
                    }
                }
            }
            labels.forEach { lbl ->
                val frac = ((lbl.startSample - viewStart) / span).coerceIn(0f, 0.94f)
                if (lbl.startSample in viewStart..viewEnd || (lbl.endSample > viewStart && lbl.startSample < viewEnd)) {
                    val xDp = totalW * frac.coerceIn(0f, 0.9f)
                    Surface(
                        color = Color(lbl.colorHex).copy(alpha = 0.9f),
                        shape = RoundedCornerShape(3.dp),
                        modifier = Modifier
                            .offset(x = xDp, y = 2.dp)
                            .height(20.dp)
                            .pointerInput(lbl.id) {
                                detectTapGestures(
                                    onTap = { onTapLabel(lbl) },
                                    onLongPress = { onEditLabel(lbl) }
                                )
                            }
                    ) {
                        Text(
                            lbl.text,
                            color = Color.Black,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnifiedGestureWaveformCanvas(
    viewStart: Long, viewEnd: Long, timelineFrames: Long,
    selStart: Long, selEnd: Long, playheadSample: Long,
    m1: Long, m3: Long, showM1M4: Boolean,
    sampleRate: Int, bpm: Double, beatsPerBar: Int, gridOffset: Long, showBeatGrid: Boolean, showClipping: Boolean, showRms: Boolean,
    showSpectrogram: Boolean, waveformDbScale: Boolean, verticalZoom: Float, toolMode: WaveZToolMode,
    offsetFrames: Long, envelope: List<Pair<Long, Float>>,
    stereoPeaks: FloatArray, rawSamples: FloatArray, spectrogram: FloatArray,
    onSelectTrack: () -> Unit, onSeek: (Long) -> Unit, onSelectionChange: (Long, Long) -> Unit,
    onOffsetChange: (Long, Boolean) -> Unit, onEnvelopeChange: (List<Pair<Long, Float>>, Boolean) -> Unit,
    onZoomPan: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val hitSlopPx = with(LocalDensity.current) { 48.dp.toPx() }
    val pal = LocalWaveZPalette.current
    val curViewStart by rememberUpdatedState(viewStart)
    val curViewEnd by rememberUpdatedState(viewEnd)
    val curSelStart by rememberUpdatedState(selStart)
    val curSelEnd by rememberUpdatedState(selEnd)
    val curToolMode by rememberUpdatedState(toolMode)
    val curOffsetFrames by rememberUpdatedState(offsetFrames)
    val curEnvelope by rememberUpdatedState(envelope)
    val curOnSelectTrack by rememberUpdatedState(onSelectTrack)
    val curOnSeek by rememberUpdatedState(onSeek)
    val curOnSelChange by rememberUpdatedState(onSelectionChange)
    val curOnOffsetChange by rememberUpdatedState(onOffsetChange)
    val curOnEnvelopeChange by rememberUpdatedState(onEnvelopeChange)
    val curOnZoomPan by rememberUpdatedState(onZoomPan)
    var longPressAnchor by remember { mutableLongStateOf(0L) }
    var longPressCur by remember { mutableLongStateOf(0L) }
    var shiftStartOffset by remember { mutableLongStateOf(0L) }
    var shiftAccumDx by remember { mutableFloatStateOf(0f) }
    var envDragIdx by remember { mutableIntStateOf(-1) }

    Box(
        modifier = modifier
            .clipToBounds()
            .background(StudioLane)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { offset ->
                        if (curToolMode == WaveZToolMode.ENVELOPE) {
                            curOnSelectTrack()
                            val vs = curViewStart; val ve = curViewEnd
                            val span = (ve - vs).coerceAtLeast(1L).toFloat()
                            val nearIdx = curEnvelope.indexOfFirst {
                                val px = (((it.first + curOffsetFrames) - vs) / span) * size.width
                                abs(px - offset.x) < 44f
                            }
                            if (nearIdx >= 0) {
                                val next = curEnvelope.toMutableList()
                                next.removeAt(nearIdx)
                                curOnEnvelopeChange(next, true)
                            }
                        }
                    },
                    onTap = { offset ->
                        curOnSelectTrack()
                        val vs = curViewStart; val ve = curViewEnd
                        val span = (ve - vs).coerceAtLeast(1L).toFloat()
                        if (curToolMode == WaveZToolMode.ENVELOPE) {
                            val timelinePos = vs + ((offset.x / size.width) * span).roundToLong()
                            val localF = (timelinePos - curOffsetFrames).coerceAtLeast(0L)
                            val newGain = (2.0f * (1f - (offset.y / size.height).coerceIn(0f, 1f))).coerceIn(0f, 2f)
                            val nearIdx = curEnvelope.indexOfFirst {
                                val px = (((it.first + curOffsetFrames) - vs) / span) * size.width
                                abs(px - offset.x) < 32f
                            }
                            val next = curEnvelope.toMutableList()
                            if (nearIdx >= 0) next[nearIdx] = localF to newGain else next.add(localF to newGain)
                            curOnEnvelopeChange(next, true)
                        } else {
                            val sx = ((curSelStart - vs) / span) * size.width
                            val ex = ((curSelEnd - vs) / span) * size.width
                            if (abs(offset.x - sx) > hitSlopPx && abs(offset.x - ex) > hitSlopPx) {
                                curOnSeek(vs + ((offset.x / size.width) * span).roundToLong())
                            }
                        }
                    }
                )
            }
            .pointerInput(toolMode) {
                if (toolMode == WaveZToolMode.SELECT) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        if (curToolMode == WaveZToolMode.TIME_SHIFT) {
                            curOnSelectTrack()
                            shiftStartOffset = curOffsetFrames
                            shiftAccumDx = 0f
                            curOnOffsetChange(shiftStartOffset, true)
                        } else if (curToolMode == WaveZToolMode.ENVELOPE) {
                            curOnSelectTrack()
                            val vs = curViewStart; val ve = curViewEnd
                            val span = (ve - vs).coerceAtLeast(1L).toFloat()
                            val nearIdx = curEnvelope.indexOfFirst {
                                val px = (((it.first + curOffsetFrames) - vs) / span) * size.width
                                abs(px - offset.x) < 40f
                            }
                            val timelinePos = vs + ((offset.x / size.width) * span).roundToLong()
                            val localF = (timelinePos - curOffsetFrames).coerceAtLeast(0L)
                            val newGain = (2.0f * (1f - (offset.y / size.height).coerceIn(0f, 1f))).coerceIn(0f, 2f)
                            val next = curEnvelope.toMutableList()
                            if (nearIdx >= 0) {
                                envDragIdx = nearIdx
                                next[nearIdx] = localF to newGain
                            } else {
                                next.add(localF to newGain)
                                envDragIdx = next.lastIndex
                            }
                            curOnEnvelopeChange(next, true)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        if (curToolMode == WaveZToolMode.TIME_SHIFT) {
                            change.consume()
                            shiftAccumDx += dragAmount.x
                            val span = (curViewEnd - curViewStart).coerceAtLeast(1L).toFloat()
                            val deltaFrames = ((shiftAccumDx / size.width) * span).roundToLong()
                            curOnOffsetChange((shiftStartOffset + deltaFrames).coerceAtLeast(0L), false)
                        } else if (curToolMode == WaveZToolMode.ENVELOPE && envDragIdx in curEnvelope.indices) {
                            change.consume()
                            val vs = curViewStart; val ve = curViewEnd
                            val span = (ve - vs).coerceAtLeast(1L).toFloat()
                            val timelinePos = vs + ((change.position.x / size.width) * span).roundToLong()
                            val localF = (timelinePos - curOffsetFrames).coerceAtLeast(0L)
                            val newGain = (2.0f * (1f - (change.position.y / size.height).coerceIn(0f, 1f))).coerceIn(0f, 2f)
                            val next = curEnvelope.toMutableList()
                            next[envDragIdx] = localF to newGain
                            curOnEnvelopeChange(next, false)
                        }
                    },
                    onDragEnd = { envDragIdx = -1 },
                    onDragCancel = { envDragIdx = -1 }
                )
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        if (curToolMode != WaveZToolMode.SELECT) return@detectDragGesturesAfterLongPress
                        curOnSelectTrack()
                        val vs = curViewStart; val ve = curViewEnd
                        val span = (ve - vs).coerceAtLeast(1L)
                        longPressAnchor = vs + ((offset.x / size.width) * span).roundToLong()
                        longPressCur = longPressAnchor
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val vs = curViewStart; val ve = curViewEnd
                        val span = (ve - vs).coerceAtLeast(1L)
                        longPressCur = vs + ((change.position.x / size.width) * span).roundToLong()
                        val a = minOf(longPressAnchor, longPressCur)
                        val b = maxOf(longPressAnchor, longPressCur).coerceAtLeast(a + 1L)
                        curOnSelChange(a, b)
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (curToolMode != WaveZToolMode.TIME_SHIFT && (abs(zoom - 1f) > 0.01f || abs(pan.x) > 1.5f)) {
                        curOnZoomPan(zoom, pan.x / size.width.coerceAtLeast(1))
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val span = (viewEnd - viewStart).coerceAtLeast(1L).toFloat()
            val midL = size.height * 0.25f; val midR = size.height * 0.75f; val halfCh = size.height * 0.23f * verticalZoom
            fun scaleAmp(v: Float): Float {
                val a = v * verticalZoom
                if (!waveformDbScale) return a.coerceIn(-1f, 1f)
                val mag = abs(v).coerceIn(1e-4f, 1f)
                val dbNorm = ((20f * kotlin.math.log10(mag) + 60f) / 60f).coerceIn(0f, 1f)
                return if (v >= 0f) dbNorm else -dbNorm
            }

            val sRatio = ((selStart - viewStart) / span).coerceIn(0f, 1f)
            val eRatio = ((selEnd - viewStart) / span).coerceIn(0f, 1f)
            if (eRatio > sRatio) drawRect(pal.selectionBg, Offset(sRatio * size.width, 0f), Size((eRatio - sRatio) * size.width, size.height))

            drawLine(pal.border, Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), 1.5f)
            drawLine(pal.border.copy(alpha = 0.5f), Offset(0f, midL), Offset(size.width, midL), 1f)
            drawLine(pal.border.copy(alpha = 0.5f), Offset(0f, midR), Offset(size.width, midR), 1f)

            if (showBeatGrid && bpm > 0.0) {
                val fpb = sampleRate * 60.0 / bpm
                val first = kotlin.math.ceil((viewStart - gridOffset) / fpb).toLong()
                val last = kotlin.math.floor((viewEnd - gridOffset) / fpb).toLong()
                val step = ((last - first) / 48L).coerceAtLeast(1L); var b = first
                while (b <= last) {
                    val samplePos = gridOffset + b * fpb
                    val x = ((samplePos - viewStart) / span).toFloat() * size.width
                    val isBar = (beatsPerBar > 0) && (((b % beatsPerBar) + beatsPerBar) % beatsPerBar == 0L)
                    drawLine(if (isBar) pal.amber.copy(alpha = 0.55f) else pal.amber.copy(alpha = 0.2f), Offset(x, 0f), Offset(x, size.height), if (isBar) 2f else 1f)
                    b += step
                }
            }

            if (showSpectrogram && spectrogram.isNotEmpty()) {
                val tBins = 96
                val fBins = 24
                val cellW = size.width / tBins
                val cellH = size.height / fBins
                for (tb in 0 until tBins) {
                    for (fb in 0 until fBins) {
                        val v = spectrogram[tb * fBins + fb].coerceIn(0f, 1f)
                        val col = when {
                            v < 0.25f -> pal.lane.copy(alpha = 0.9f)
                            v < 0.55f -> pal.wave.copy(alpha = 0.85f)
                            v < 0.8f -> pal.neon.copy(alpha = 0.85f)
                            else -> pal.amber
                        }
                        drawRect(col, Offset(tb * cellW, size.height - (fb + 1) * cellH), Size(cellW + 1f, cellH + 1f))
                    }
                }
            } else if (rawSamples.size >= 4) {
                val count = rawSamples.size / 2; val stepX = size.width / (count - 1).coerceAtLeast(1)
                for (i in 0 until count) {
                    val x = i * stepX
                    val lVal = rawSamples[i * 2]
                    val rVal = rawSamples[i * 2 + 1]
                    val yL = midL - scaleAmp(lVal) * (size.height * 0.23f); val yR = midR - scaleAmp(rVal) * (size.height * 0.23f)
                    val cL = if (showClipping && abs(lVal) >= 0.995f) Color(0xFFFF5252) else pal.waveSel
                    val cR = if (showClipping && abs(rVal) >= 0.995f) Color(0xFFFF5252) else pal.waveSel
                    drawLine(cL, Offset(x, midL), Offset(x, yL), 2f); drawLine(cR, Offset(x, midR), Offset(x, yR), 2f)
                    drawCircle(pal.text, 3.5f, Offset(x, yL)); drawCircle(pal.text, 3.5f, Offset(x, yR))
                }
            } else if (stereoPeaks.size >= 6) {
                val bins = stereoPeaks.size / 6; val barW = size.width / bins
                for (b in 0 until bins) {
                    val x = b * barW + barW / 2f
                    val minL = stereoPeaks[b * 6 + 0]; val maxL = stereoPeaks[b * 6 + 1]; val rmsL = stereoPeaks[b * 6 + 2]
                    val minR = stereoPeaks[b * 6 + 3]; val maxR = stereoPeaks[b * 6 + 4]; val rmsR = stereoPeaks[b * 6 + 5]
                    val inSel = (b.toFloat() / bins) in sRatio..eRatio
                    val clipL = showClipping && (abs(minL) >= 0.995f || abs(maxL) >= 0.995f)
                    val clipR = showClipping && (abs(minR) >= 0.995f || abs(maxR) >= 0.995f)
                    val colL = if (clipL) Color(0xFFFF5252) else if (inSel) pal.waveSel else pal.wave
                    val colR = if (clipR) Color(0xFFFF5252) else if (inSel) pal.waveSel else pal.wave
                    val stroke = (barW * 0.85f).coerceAtLeast(1.5f)
                    val baseH = size.height * 0.23f
                    drawLine(colL, Offset(x, midL - scaleAmp(maxL) * baseH), Offset(x, midL - scaleAmp(minL) * baseH), stroke)
                    drawLine(colR, Offset(x, midR - scaleAmp(maxR) * baseH), Offset(x, midR - scaleAmp(minR) * baseH), stroke)
                    if (showRms) {
                        drawLine(pal.waveRms.copy(alpha = 0.7f), Offset(x, midL - rmsL * halfCh), Offset(x, midL + rmsL * halfCh), stroke)
                        drawLine(pal.waveRms.copy(alpha = 0.7f), Offset(x, midR - rmsR * halfCh), Offset(x, midR + rmsR * halfCh), stroke)
                    }
                }
            }

            if (envelope.isNotEmpty() || toolMode == WaveZToolMode.ENVELOPE) {
                val pts = envelope.map { (f, g) ->
                    val x = (((f + offsetFrames) - viewStart) / span) * size.width
                    val y = size.height * (1f - (g / 2.0f).coerceIn(0f, 1f))
                    Offset(x, y)
                }
                if (pts.size >= 2) {
                    for (i in 0 until pts.size - 1) {
                        drawLine(pal.cyan, pts[i], pts[i + 1], 2.5f)
                    }
                }
                pts.forEach { pt ->
                    drawCircle(pal.cyan, 6f, pt)
                    drawCircle(pal.text, 3f, pt)
                }
            }

            if (showM1M4) {
                val m1x = ((m1 - viewStart) / span) * size.width
                val m3x = ((m3 - viewStart) / span) * size.width
                if (m1x in 0f..size.width) drawLine(pal.amber, Offset(m1x, 0f), Offset(m1x, size.height), 2f)
                if (m3x in 0f..size.width) drawLine(pal.magenta, Offset(m3x, 0f), Offset(m3x, size.height), 2.5f)
            }
            drawLine(pal.neon, Offset(sRatio * size.width, 0f), Offset(sRatio * size.width, size.height), 2.5f)
            drawLine(pal.cyan, Offset(eRatio * size.width, 0f), Offset(eRatio * size.width, size.height), 2.5f)

            val pRatio = ((playheadSample - viewStart) / span)
            if (pRatio in 0f..1f) drawLine(pal.text, Offset(pRatio * size.width, 0f), Offset(pRatio * size.width, size.height), 2f)
        }
    }
}

private fun formatTime(frame: Long, sampleRate: Int): String {
    val totalSec = frame.toDouble() / sampleRate.coerceAtLeast(1)
    val mins = (totalSec / 60).toInt()
    val secs = totalSec % 60.0
    return String.format(Locale.US, "%02d:%06.3f", mins, secs)
}
