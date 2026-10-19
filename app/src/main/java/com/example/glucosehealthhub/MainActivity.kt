package com.example.glucosehealthhub

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.io.File
import kotlin.math.roundToInt

data class Reading(val time: Instant, val mgdl: Double, val tag: String = "")

private object EncryptedData {
    private const val ALIAS = "glucosehealthhub_preview_v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    fun read(context: Context): List<Reading> = runCatching {
        val file = File(context.filesDir, "glucose.bin")
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes()
        if (bytes.size < 13) return emptyList()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            .lineSequence().mapNotNull { line ->
                val parts = line.split('|', limit = 3)
                if (parts.size < 2) null else runCatching {
                    Reading(Instant.ofEpochMilli(parts[0].toLong()), parts[1].toDouble(), parts.getOrElse(2) { "" })
                }.getOrNull()
            }.toList()
    }.getOrDefault(emptyList())
    fun write(context: Context, readings: List<Reading>) {
        val plain = readings.joinToString("\n") {
            "${it.time.toEpochMilli()}|${it.mgdl}|${it.tag.replace('|', ' ')}"
        }.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        File(context.filesDir, "glucose.bin").writeBytes(cipher.iv + cipher.doFinal(plain))
    }
}

private fun splitCsv(line: String): List<String> {
    val fields = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        when (line[i]) {
            '"' -> if (quoted && i + 1 < line.length && line[i + 1] == '"') { current.append('"'); i++ } else quoted = !quoted
            ',' -> if (!quoted) { fields += current.toString(); current.clear() } else current.append(',')
            else -> current.append(line[i])
        }
        i++
    }
    fields += current.toString()
    return fields
}

private fun parseMySugr(csv: String): List<Reading> {
    val lines = csv.lineSequence().filter { it.isNotBlank() }.iterator()
    if (!lines.hasNext()) return emptyList()
    val header = splitCsv(lines.next()).map { it.trim().removePrefix("\uFEFF") }
    fun find(name: String) = header.indexOfFirst { it.equals(name, ignoreCase = true) }
    val dateCol = find("Date")
    val timeCol = find("Time")
    val glucoseCol = header.indexOfFirst { it.startsWith("Blood Sugar Measurement", ignoreCase = true) }
    val tagCol = find("Tags")
    val zoneCol = find("Timezone")
    if (dateCol < 0 || glucoseCol < 0) throw IllegalArgumentException("CSV does not match expected mySugr export columns")
    val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    val readings = mutableListOf<Reading>()
    while (lines.hasNext()) {
        val parts = splitCsv(lines.next())
        fun field(index: Int) = parts.getOrNull(index)?.trim().orEmpty()
        val glucose = field(glucoseCol).replace(",", ".").toDoubleOrNull() ?: continue
        if (glucose <= 0 || glucose > 1000) continue
        val dateStr = field(dateCol).replace("Sept ", "Sep ")
        val date = runCatching { LocalDate.parse(dateStr, dateFormat) }.getOrNull() ?: continue
        val time = runCatching { LocalTime.parse(field(timeCol)) }.getOrDefault(LocalTime.MIDNIGHT)
        val zoneStr = field(zoneCol).removePrefix("GMT")
        val offset = runCatching { ZoneOffset.of(zoneStr) }.getOrDefault(ZoneOffset.ofHours(3))
        val instant = OffsetDateTime.of(date, time, offset).toInstant()
        readings += Reading(instant, glucose, field(tagCol))
    }
    return readings.distinctBy { it.time to it.mgdl }.sortedBy { it.time }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { HealthScreen() } }
    }
}

@Composable
private fun HealthScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permissions = remember {
        setOf(HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class))
    }
    var readings by remember { mutableStateOf(EncryptedData.read(context)) }
    var steps by remember { mutableStateOf<Long?>(null) }
    var bpm by remember { mutableStateOf<Long?>(null) }
    var sleepHours by remember { mutableStateOf<Double?>(null) }
    var status by remember { mutableStateOf("Import a mySugr CSV, then connect Health Connect.") }
    var loading by remember { mutableStateOf(false) }
    val client = remember {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE)
            HealthConnectClient.getOrCreate(context) else null
    }

    suspend fun refresh() {
        val c = client ?: run { status = "Health Connect is unavailable on this device."; return }
        try {
            val granted = c.permissionController.getGrantedPermissions()
            if (!granted.containsAll(permissions)) { status = "Allow all three read permissions to view Samsung data."; return }
            val now = Instant.now()
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val result = c.aggregate(AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL, HeartRateRecord.BPM_AVG, SleepSessionRecord.SLEEP_DURATION_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(now.minus(Duration.ofDays(1)), now)))
            bpm = result[HeartRateRecord.BPM_AVG]
            sleepHours = result[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes()?.div(60.0)
            val today = c.aggregate(AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, now)))
            steps = today[StepsRecord.COUNT_TOTAL]
            status = "Health Connect updated. Results depend on Samsung Health sync settings."
        } catch (e: Exception) { status = "Health Connect read failed: ${e.localizedMessage}" }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        scope.launch { if (granted.containsAll(permissions)) refresh() else status = "Permissions declined." }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val csv = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("File cannot be opened")
                    parseMySugr(csv)
                }
                val merged = (readings + parsed).distinctBy { it.time to it.mgdl }.sortedBy { it.time }
                withContext(Dispatchers.IO) { EncryptedData.write(context, merged) }
                readings = merged
                status = "Imported ${parsed.size} glucose readings; stored ${merged.size} unique readings, encrypted on device."
            } catch (e: Exception) { status = "Import failed: ${e.localizedMessage}" }
            loading = false
        }
    }

    LaunchedEffect(client) {
        if (client != null) refresh()
    }

    val recent = readings.filter { it.time >= Instant.now().minus(Duration.ofDays(30)) }
    val average = recent.map { it.mgdl }.average().takeUnless { it.isNaN() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Glucose & Health Hub", style = MaterialTheme.typography.headlineMedium)
        Text("Accu-Chek mySugr + Samsung Health", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { csvLauncher.launch(arrayOf("text/*", "text/csv", "application/csv", "application/octet-stream")) }, enabled = !loading) {
            Text("Import mySugr CSV")
        }
        Button(onClick = {
            if (client == null) status = "Health Connect not available."
            else permissionLauncher.launch(permissions)
        }) { Text("Connect Samsung Health") }
        OutlinedButton(onClick = { scope.launch { refresh() } }, enabled = client != null) {
            Text("Refresh Health Connect")
        }
        Text(status)
        HorizontalDivider()
        Text("Glucose", style = MaterialTheme.typography.titleLarge)
        Text("Total readings: ${readings.size}")
        Text("Latest: ${readings.lastOrNull()?.mgdl?.roundToInt()?.toString() ?: "—"} mg/dL")
        Text("Last 30 days: ${average?.roundToInt()?.toString() ?: "—"} mg/dL average (${recent.size} readings)")
        GlucosePlot(recent)
        HorizontalDivider()
        Text("Samsung Health · latest available", style = MaterialTheme.typography.titleLarge)
        Text("Steps today: ${steps ?: "—"}")
        Text("Average heart rate, last 24h: ${bpm ?: "—"} bpm")
        Text("Sleep duration recorded over last 24h: ${sleepHours?.let { "%.1f".format(it) } ?: "—"} hours")
        HorizontalDivider()
        Text("This app displays records for information only and is not a medical device.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GlucosePlot(readings: List<Reading>) {
    val values = readings.sortedBy { it.time }
    if (values.size < 2) { Text("Import at least two measurements to see a trend."); return }
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val first = values.first().time.toEpochMilli().toDouble()
        val span = (values.last().time.toEpochMilli().toDouble() - first).coerceAtLeast(1.0)
        val minValue = (values.minOf { it.mgdl } - 20).coerceAtLeast(0.0)
        val maxValue = values.maxOf { it.mgdl } + 20
        val range = (maxValue - minValue).coerceAtLeast(1.0)
        val points = values.map {
            Offset(((it.time.toEpochMilli() - first) / span * size.width).toFloat(),
                (size.height - ((it.mgdl - minValue) / range * size.height)).toFloat())
        }
        points.zipWithNext().forEach { (a, b) ->
            drawLine(color = Color(0xFF1976D2), start = a, end = b, strokeWidth = 3.dp.toPx())
        }
    }
}
