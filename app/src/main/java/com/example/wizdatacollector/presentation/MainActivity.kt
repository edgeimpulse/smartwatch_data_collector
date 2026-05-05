package com.example.eidatacollector.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Colour palette ────────────────────────────────────────────────────────────
private val Cyan        = Color(0xFF4DD0E1)
private val Surface     = Color(0xFF1A1A2E)
private val Background  = Color.Black
private val TextPrimary = Color.White
private val TextMuted   = Color(0xFF9E9E9E)
private val Red         = Color(0xFFEF5350)
private val Green       = Color(0xFF66BB6A)
private val Amber       = Color(0xFFFFCA28)

private const val PREFS_NAME  = "ei_collector_prefs"
private const val KEY_API_KEY = "api_key"

// Android 16+ replaces BODY_SENSORS with this Samsung-specific permission for biometric sensors
private const val PERM_ADDITIONAL_HEALTH =
    "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"

enum class Screen { MAIN, SENSOR_SELECT, SETTINGS }

fun sensorLabel(type: Int): String = when (type) {
    Sensor.TYPE_ACCELEROMETER       -> "Accelerometer"
    Sensor.TYPE_GYROSCOPE           -> "Gyroscope"
    Sensor.TYPE_HEART_RATE          -> "Heart Rate"
    Sensor.TYPE_LINEAR_ACCELERATION -> "Linear Accel."
    Sensor.TYPE_GRAVITY             -> "Gravity"
    Sensor.TYPE_ROTATION_VECTOR     -> "Rotation Vector"
    Sensor.TYPE_MAGNETIC_FIELD      -> "Magnetometer"
    Sensor.TYPE_PRESSURE            -> "Barometer"
    Sensor.TYPE_LIGHT               -> "Light"
    Sensor.TYPE_PROXIMITY           -> "Proximity"
    Sensor.TYPE_AMBIENT_TEMPERATURE -> "Temperature"
    Sensor.TYPE_RELATIVE_HUMIDITY   -> "Humidity"
    else                            -> "Sensor ($type)"
}

// ── Activity ──────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    private lateinit var sensorCollector: SensorDataCollector
    private lateinit var ppgCollector: PpgDataCollector
    private val audioCollector = AudioDataCollector()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorCollector = SensorDataCollector(this)
        ppgCollector    = PpgDataCollector(this)
        setContent { AppRoot(sensorCollector, ppgCollector, audioCollector) }
    }

    override fun onDestroy() {
        super.onDestroy()
        ppgCollector.disconnect()
    }
}

// ── Root: permission + navigation ────────────────────────────────────────────
@Composable
fun AppRoot(collector: SensorDataCollector, ppgCollector: PpgDataCollector, audioCollector: AudioDataCollector) {
    val context = LocalContext.current

    val healthPerm = if (Build.VERSION.SDK_INT >= 36) PERM_ADDITIONAL_HEALTH
                     else Manifest.permission.BODY_SENSORS

    var bodySensorsGranted by remember { mutableStateOf(false) }
    val healthPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> bodySensorsGranted = granted }

    var audioPermGranted by remember { mutableStateOf(false) }
    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> audioPermGranted = granted }

    LaunchedEffect(Unit) {
        bodySensorsGranted = ContextCompat.checkSelfPermission(
            context, healthPerm
        ) == PackageManager.PERMISSION_GRANTED
        if (!bodySensorsGranted) healthPermLauncher.launch(healthPerm)

        audioPermGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!audioPermGranted) audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString(KEY_API_KEY, "") ?: "") }

    val availableSensors = remember { collector.getAvailableSensors() }
    var currentScreen by remember { mutableStateOf(Screen.MAIN) }

    var selectedSensors by remember {
        mutableStateOf(
            availableSensors.filter {
                it.type == Sensor.TYPE_ACCELEROMETER || it.type == Sensor.TYPE_GYROSCOPE
            }.toSet()
        )
    }

    var ppgSelected   by remember { mutableStateOf(false) }
    var ppgStatus     by remember { mutableStateOf("disconnected") }
    var audioSelected by remember { mutableStateOf(false) }

    LaunchedEffect(ppgSelected) {
        if (ppgSelected) {
            ppgStatus = "connecting"
            ppgCollector.onTrackerError = { error -> ppgStatus = "error:${error.name}" }
            ppgCollector.connect { connected, _ ->
                ppgStatus = if (connected) "connected" else "error"
            }
        } else {
            ppgCollector.disconnect()
            ppgStatus = "disconnected"
        }
    }

    var label        by remember { mutableStateOf("") }
    var durationSec  by remember { mutableStateOf(5) }
    var status       by remember { mutableStateOf("Ready") }
    var statusColor  by remember { mutableStateOf(TextPrimary) }
    var isRecording  by remember { mutableStateOf(false) }
    val scope        = rememberCoroutineScope()

    when (currentScreen) {
        Screen.MAIN -> MainScreen(
            availableSensors   = availableSensors,
            selectedSensors    = selectedSensors,
            bodySensorsGranted = bodySensorsGranted,
            audioPermGranted   = audioPermGranted,
            apiKey             = apiKey,
            label              = label,
            onLabelChange      = { label = it },
            durationSec        = durationSec,
            onDurationChange   = { durationSec = it },
            status             = status,
            statusColor        = statusColor,
            isRecording        = isRecording,
            ppgSelected        = ppgSelected,
            ppgStatus          = ppgStatus,
            audioSelected      = audioSelected,
            onSensorsClick     = { currentScreen = Screen.SENSOR_SELECT },
            onSettingsClick    = { currentScreen = Screen.SETTINGS },
            onRecord           = {
                val anythingSelected = selectedSensors.isNotEmpty() || ppgSelected || audioSelected
                if (!isRecording && anythingSelected && label.isNotBlank()) {
                    isRecording = true
                    scope.launch {
                        for (i in 3 downTo 1) {
                            status = "Get ready…  $i"
                            statusColor = Amber
                            delay(1000)
                        }
                        if (selectedSensors.isNotEmpty()) collector.start(selectedSensors.toList())
                        if (ppgSelected && ppgStatus == "connected") ppgCollector.start()
                        if (audioSelected && audioPermGranted) audioCollector.start()
                        for (i in durationSec downTo 1) {
                            status = "Recording…  $i"
                            statusColor = Red
                            delay(1000)
                        }
                        collector.stop()
                        ppgCollector.stop()
                        audioCollector.stop()

                        status = "Uploading…"
                        statusColor = Cyan
                        var ok = true
                        if (selectedSensors.isNotEmpty()) {
                            ok = EdgeImpulseUploader.upload(
                                label      = label,
                                sensorData = collector.sensorData,
                                sensors    = selectedSensors.toList(),
                                apiKey     = apiKey
                            ) && ok
                        }
                        if (ppgSelected && ppgCollector.ppgGreen.isNotEmpty()) {
                            ok = EdgeImpulseUploader.uploadPpg(
                                label    = label,
                                ppgGreen = ppgCollector.ppgGreen,
                                ppgIr    = ppgCollector.ppgIr,
                                ppgRed   = ppgCollector.ppgRed,
                                apiKey   = apiKey
                            ) && ok
                        }
                        if (audioSelected && audioCollector.samples.isNotEmpty()) {
                            ok = EdgeImpulseUploader.uploadAudio(
                                label   = label,
                                samples = audioCollector.samples,
                                apiKey  = apiKey
                            ) && ok
                        }
                        status      = if (ok) "Done ✓" else "Failed ✗"
                        statusColor = if (ok) Green else Red
                        delay(2000)
                        status      = "Ready"
                        statusColor = TextPrimary
                        isRecording = false
                    }
                }
            }
        )
        Screen.SENSOR_SELECT -> SensorSelectScreen(
            availableSensors  = availableSensors,
            selectedSensors   = selectedSensors,
            onToggle          = { s ->
                selectedSensors =
                    if (s in selectedSensors) selectedSensors - s else selectedSensors + s
            },
            ppgSelected   = ppgSelected,
            onPpgToggle   = { ppgSelected = !ppgSelected },
            audioSelected = audioSelected,
            onAudioToggle = { audioSelected = !audioSelected },
            onDone        = { currentScreen = Screen.MAIN }
        )
        Screen.SETTINGS -> SettingsScreen(
            currentApiKey = apiKey,
            onSave        = { newKey ->
                apiKey = newKey
                prefs.edit().putString(KEY_API_KEY, newKey).apply()
                currentScreen = Screen.MAIN
            },
            onBack = { currentScreen = Screen.MAIN }
        )
    }
}

// ── Main screen ───────────────────────────────────────────────────────────────
@Composable
fun MainScreen(
    availableSensors: List<Sensor>,
    selectedSensors: Set<Sensor>,
    bodySensorsGranted: Boolean,
    audioPermGranted: Boolean,
    apiKey: String,
    label: String,
    onLabelChange: (String) -> Unit,
    durationSec: Int,
    onDurationChange: (Int) -> Unit,
    status: String,
    statusColor: Color,
    isRecording: Boolean,
    ppgSelected: Boolean,
    ppgStatus: String,
    audioSelected: Boolean,
    onSensorsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRecord: () -> Unit
) {
    val durations = listOf(listOf(2, 5, 10), listOf(30, 60))
    val totalSelected = selectedSensors.size + (if (ppgSelected) 1 else 0) + (if (audioSelected) 1 else 0)
    val totalAvailable = availableSensors.size + 2  // +1 PPG, +1 Mic
    val canRecord = !isRecording && totalSelected > 0 && label.isNotBlank()
    val apiKeySet = apiKey.isNotBlank()

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 28.dp)
    ) {

        // ── Title + Settings ───────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text          = "DATA COLLECTOR",
                    fontSize      = 11.sp,
                    fontWeight    = FontWeight.Bold,
                    color         = Cyan,
                    letterSpacing = 1.5.sp
                )
                CompactChip(
                    onClick = onSettingsClick,
                    label   = {
                        Text(
                            text      = "⚙",
                            fontSize  = 12.sp,
                            color     = if (apiKeySet) TextMuted else Amber
                        )
                    },
                    colors = ChipDefaults.chipColors(backgroundColor = Surface)
                )
            }
        }

        // ── API key warning ────────────────────────────────────────────────
        item {
            if (!apiKeySet) {
                Text(
                    text      = "⚠ No API key set — tap ⚙ to configure",
                    fontSize  = 10.sp,
                    color     = Amber,
                    textAlign = TextAlign.Center
                )
            }
        }

        // ── SENSORS ────────────────────────────────────────────────────────
        item { SectionLabel("SENSORS") }
        item {
            val sensorLabels = selectedSensors.map { sensorLabel(it.type) }.toMutableList()
            if (ppgSelected) sensorLabels.add("PPG")
            if (audioSelected) sensorLabels.add("Mic")
            val summary = if (sensorLabels.isEmpty()) "None selected"
                          else sensorLabels.joinToString(", ")
            Chip(
                onClick        = onSensorsClick,
                modifier       = Modifier.fillMaxWidth(),
                colors         = ChipDefaults.chipColors(backgroundColor = Surface),
                label          = {
                    Text(
                        text     = summary,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color    = if (totalSelected == 0) TextMuted else TextPrimary
                    )
                },
                secondaryLabel = {
                    Text(
                        text     = "$totalSelected of $totalAvailable selected",
                        fontSize = 10.sp,
                        color    = Cyan
                    )
                }
            )
        }

        // ── PPG connection status ──────────────────────────────────────────
        item {
            if (ppgSelected) {
                Text(
                    text = when {
                        ppgStatus == "connecting"       -> "PPG: connecting…"
                        ppgStatus == "connected"        -> "PPG: connected ✓"
                        ppgStatus.startsWith("error:")  -> "PPG: ${ppgStatus.removePrefix("error:")}"
                        ppgStatus == "error"            -> "PPG: connection failed ✗"
                        else                            -> ""
                    },
                    fontSize  = 10.sp,
                    color     = when {
                        ppgStatus == "connected"       -> Green
                        ppgStatus.startsWith("error")  -> Red
                        else                           -> Amber
                    },
                    textAlign = TextAlign.Center
                )
            }
        }

        // ── LABEL ──────────────────────────────────────────────────────────
        item { SectionLabel("LABEL") }
        item {
            InputField(
                value         = label,
                onValueChange = onLabelChange,
                placeholder   = "e.g. walking"
            )
        }

        // ── DURATION ───────────────────────────────────────────────────────
        item { SectionLabel("DURATION") }
        items(durations) { row ->
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { d ->
                    val selected = durationSec == d
                    CompactChip(
                        onClick  = { onDurationChange(d) },
                        modifier = Modifier.weight(1f),
                        label    = {
                            Text(
                                text       = "${d}s",
                                fontSize   = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color      = if (selected) Background else TextPrimary
                            )
                        },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = if (selected) Cyan else Surface
                        )
                    )
                }
            }
        }

        // ── Status ─────────────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(6.dp))
            Text(
                text       = status,
                fontSize   = 13.sp,
                fontWeight = FontWeight.Medium,
                color      = statusColor,
                textAlign  = TextAlign.Center
            )
        }

        // ── Record button ──────────────────────────────────────────────────
        item {
            Button(
                onClick  = onRecord,
                enabled  = canRecord,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                colors   = ButtonDefaults.buttonColors(
                    backgroundColor         = if (isRecording) Red else Cyan,
                    disabledBackgroundColor = Surface
                )
            ) {
                Text(
                    text       = if (isRecording) "● REC" else "RECORD",
                    fontWeight = FontWeight.Bold,
                    fontSize   = 14.sp,
                    color      = if (canRecord) Background else TextMuted
                )
            }
        }

        // ── Hints ──────────────────────────────────────────────────────────
        item {
            if (!isRecording && !canRecord) {
                Text(
                    text      = when {
                        totalSelected == 0 -> "Tap SENSORS to select"
                        label.isBlank()    -> "Enter a label above"
                        else               -> ""
                    },
                    fontSize  = 10.sp,
                    color     = TextMuted,
                    textAlign = TextAlign.Center
                )
            }
            if (selectedSensors.any { it.type == Sensor.TYPE_HEART_RATE } && !bodySensorsGranted) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text      = "⚠ Body Sensors permission denied",
                    fontSize  = 10.sp,
                    color     = Amber,
                    textAlign = TextAlign.Center
                )
            }
            if (ppgSelected && ppgStatus == "error") {
                Spacer(Modifier.height(4.dp))
                Text(
                    text      = "⚠ PPG unavailable — Samsung Health required",
                    fontSize  = 10.sp,
                    color     = Red,
                    textAlign = TextAlign.Center
                )
            }
            if (audioSelected && !audioPermGranted) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text      = "⚠ Microphone permission denied",
                    fontSize  = 10.sp,
                    color     = Amber,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ── Settings screen ───────────────────────────────────────────────────────────
@Composable
fun SettingsScreen(
    currentApiKey: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit
) {
    var draftKey by remember { mutableStateOf(currentApiKey) }
    var showKey  by remember { mutableStateOf(false) }
    val isValid  = draftKey.startsWith("ei_") && draftKey.length > 10

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 28.dp)
    ) {
        item {
            Text(
                text          = "SETTINGS",
                fontSize      = 11.sp,
                fontWeight    = FontWeight.Bold,
                color         = Cyan,
                letterSpacing = 1.5.sp
            )
        }
        item { SectionLabel("EDGE IMPULSE API KEY") }
        item {
            InputField(
                value         = draftKey,
                onValueChange = { draftKey = it },
                placeholder   = "ei_xxxxxxxx…",
                isPassword    = !showKey
            )
        }
        item {
            CompactChip(
                onClick  = { showKey = !showKey },
                modifier = Modifier.fillMaxWidth(),
                label    = {
                    Text(
                        text     = if (showKey) "Hide key" else "Show key",
                        fontSize = 11.sp,
                        color    = TextMuted
                    )
                },
                colors = ChipDefaults.chipColors(backgroundColor = Surface)
            )
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text       = "Tip: tap the field above,\nthen run on your computer:\n\nadb shell input text \"ei_...\"",
                fontSize   = 10.sp,
                color      = TextMuted,
                textAlign  = TextAlign.Center,
                lineHeight = 14.sp
            )
        }
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Button(
                onClick  = { if (isValid) onSave(draftKey) },
                enabled  = isValid,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                colors   = ButtonDefaults.buttonColors(
                    backgroundColor         = Cyan,
                    disabledBackgroundColor = Surface
                )
            ) {
                Text(
                    text       = "SAVE",
                    fontWeight = FontWeight.Bold,
                    fontSize   = 14.sp,
                    color      = if (isValid) Background else TextMuted
                )
            }
        }
        item {
            CompactChip(
                onClick  = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                label    = { Text("BACK", fontSize = 12.sp, color = TextMuted) },
                colors   = ChipDefaults.chipColors(backgroundColor = Surface)
            )
        }
    }
}

// ── Sensor selection screen ───────────────────────────────────────────────────
@Composable
fun SensorSelectScreen(
    availableSensors: List<Sensor>,
    selectedSensors: Set<Sensor>,
    onToggle: (Sensor) -> Unit,
    ppgSelected: Boolean,
    onPpgToggle: () -> Unit,
    audioSelected: Boolean,
    onAudioToggle: () -> Unit,
    onDone: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 28.dp)
    ) {
        item {
            Text(
                text          = "SELECT SENSORS",
                fontSize      = 11.sp,
                fontWeight    = FontWeight.Bold,
                color         = Cyan,
                letterSpacing = 1.5.sp
            )
        }

        // ── PPG (Samsung Health SDK) ───────────────────────────────────────
        item {
            Chip(
                onClick  = onPpgToggle,
                modifier = Modifier.fillMaxWidth(),
                colors   = ChipDefaults.chipColors(
                    backgroundColor = if (ppgSelected) Cyan.copy(alpha = 0.25f) else Surface
                ),
                label = {
                    Text(
                        text       = "PPG Sensor",
                        fontSize   = 12.sp,
                        fontWeight = if (ppgSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color      = if (ppgSelected) Cyan else TextPrimary
                    )
                },
                secondaryLabel = {
                    Text(
                        text     = "Samsung Health · 100 Hz",
                        fontSize = 10.sp,
                        color    = TextMuted
                    )
                }
            )
        }

        // ── Microphone ────────────────────────────────────────────────────
        item {
            Chip(
                onClick  = onAudioToggle,
                modifier = Modifier.fillMaxWidth(),
                colors   = ChipDefaults.chipColors(
                    backgroundColor = if (audioSelected) Cyan.copy(alpha = 0.25f) else Surface
                ),
                label = {
                    Text(
                        text       = "Microphone",
                        fontSize   = 12.sp,
                        fontWeight = if (audioSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color      = if (audioSelected) Cyan else TextPrimary
                    )
                },
                secondaryLabel = {
                    Text(
                        text     = "16 kHz · Mono · WAV",
                        fontSize = 10.sp,
                        color    = TextMuted
                    )
                }
            )
        }

        items(availableSensors) { sensor ->
            val isSelected = sensor in selectedSensors
            Chip(
                onClick  = { onToggle(sensor) },
                modifier = Modifier.fillMaxWidth(),
                colors   = ChipDefaults.chipColors(
                    backgroundColor = if (isSelected) Cyan.copy(alpha = 0.25f) else Surface
                ),
                label = {
                    Text(
                        text       = sensorLabel(sensor.type),
                        fontSize   = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color      = if (isSelected) Cyan else TextPrimary
                    )
                },
                secondaryLabel = {
                    Text(
                        text     = sensor.name,
                        fontSize = 10.sp,
                        color    = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }

        item { Spacer(Modifier.height(4.dp)) }
        item {
            Button(
                onClick  = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                colors   = ButtonDefaults.buttonColors(backgroundColor = Cyan)
            ) {
                Text("DONE", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Background)
            }
        }
    }
}

// ── Shared composables ────────────────────────────────────────────────────────
@Composable
private fun SectionLabel(text: String) {
    Text(
        text          = text,
        fontSize      = 10.sp,
        fontWeight    = FontWeight.Medium,
        color         = TextMuted,
        letterSpacing = 1.sp,
        modifier      = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false
) {
    val borderColor = if (value.isBlank()) Color(0xFF424242) else Cyan
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(24.dp))
            .border(1.dp, borderColor, RoundedCornerShape(24.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory  = { context ->
                EditText(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    hint         = placeholder
                    textSize     = 13f
                    isSingleLine = true
                    background   = null
                    setPadding(24, 16, 24, 16)
                    setTextColor(android.graphics.Color.WHITE)
                    setHintTextColor(android.graphics.Color.GRAY)
                    inputType = if (isPassword)
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else
                        InputType.TYPE_CLASS_TEXT
                    setText(value)
                    setSelection(value.length)
                    addTextChangedListener(object : TextWatcher {
                        override fun afterTextChanged(s: Editable?) {
                            val new = s?.toString() ?: ""
                            if (new != value) onValueChange(new)
                        }
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    })
                }
            },
            update   = { editText ->
                val newType = if (isPassword)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else
                    InputType.TYPE_CLASS_TEXT
                if (editText.inputType != newType) editText.inputType = newType
                if (editText.text.toString() != value) {
                    editText.setText(value)
                    editText.setSelection(value.length)
                }
            }
        )
    }
}
