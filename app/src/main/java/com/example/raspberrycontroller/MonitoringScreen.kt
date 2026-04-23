package com.example.raspberrycontroller

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ══════════════════════════════════════════════════════════════════════════════
//  Constantes
// ══════════════════════════════════════════════════════════════════════════════

private const val MAX_HISTORY = 60   // 60 points → ~10 min à 10 s/point

// ══════════════════════════════════════════════════════════════════════════════
//  Modèles de données
// ══════════════════════════════════════════════════════════════════════════════

data class DiskPartition(
    val mountPoint : String,
    val totalMb    : Long,
    val usedMb     : Long,
    val availMb    : Long,
    val usedPercent: Int
)

data class ExtendedStats(
    val base           : SystemStats,
    val gpuTempCelsius : Double?,
    val disks          : List<DiskPartition>
)

// ══════════════════════════════════════════════════════════════════════════════
//  Script Python — parsing robuste via regex
//  - RAM  : re.search sur /proc/meminfo (évite les bugs startswith)
//  - CPU  : moyenne sur 0.5 s via /proc/stat (plus fiable que loadavg)
//  - Temp : /sys/class/thermal
//  - GPU  : vcgencmd measure_temp
// ══════════════════════════════════════════════════════════════════════════════

private val STATS_SCRIPT = """
import re, subprocess, time

# ── RAM via regex sur /proc/meminfo ──────────────────────────────────────────
meminfo = open('/proc/meminfo').read()
def mi(k):
    m = re.search(r'^' + k + r':\s+(\d+)', meminfo, re.MULTILINE)
    return int(m.group(1)) if m else 0

mem_total = mi('MemTotal')
mem_avail = mi('MemAvailable')
mem_used  = mem_total - mem_avail

# ── CPU via /proc/stat (delta sur 0.5 s) ─────────────────────────────────────
def read_cpu():
    line = open('/proc/stat').readline()
    vals = list(map(int, line.split()[1:]))
    idle  = vals[3]
    total = sum(vals)
    return idle, total

idle1, total1 = read_cpu()
time.sleep(0.5)
idle2, total2 = read_cpu()
d_total = total2 - total1
d_idle  = idle2  - idle1
cpu_pct = round((1.0 - d_idle / d_total) * 100.0, 1) if d_total > 0 else 0.0

# ── Température CPU ───────────────────────────────────────────────────────────
temp = int(open('/sys/class/thermal/thermal_zone0/temp').read()) / 1000.0

# ── GPU VideoCore ─────────────────────────────────────────────────────────────
try:
    r   = subprocess.check_output(['vcgencmd', 'measure_temp'], timeout=2).decode()
    gpu = float(r.strip().replace('temp=', '').replace("'C", ''))
except Exception:
    gpu = -1.0

print(
    str(round(temp, 1)) + ',' +
    str(round(cpu_pct, 1)) + ',' +
    str(mem_used  // 1024) + ',' +
    str(mem_total // 1024) + ',' +
    str(round(gpu, 1))
)
""".trimIndent()

// ══════════════════════════════════════════════════════════════════════════════
//  Fetch SSH (withContext IO + script passé via base64)
// ══════════════════════════════════════════════════════════════════════════════

suspend fun fetchExtendedStats(settings: SettingsManager): ExtendedStats? =
    withContext(Dispatchers.IO) {
        try {
            val b64 = android.util.Base64.encodeToString(
                STATS_SCRIPT.toByteArray(), android.util.Base64.NO_WRAP
            )
            val cmd = "echo '$b64' | base64 -d | python3" +
                    " && echo '---DISK---'" +
                    " && df -BM --output=target,size,used,avail,pcent 2>/dev/null | grep '^/'"

            val raw = SshClient.execute(
                settings.host, settings.port, settings.username, settings.password,
                cmd, settings.sshTimeoutMs
            )

            val sections  = raw.split("---DISK---")
            val statLine  = sections.getOrNull(0)?.trim() ?: return@withContext null
            val statParts = statLine.split(",")
            if (statParts.size < 5) return@withContext null

            val base = SystemStats(
                tempCelsius = statParts[0].toDouble(),
                // CPU : maintenant en % réel (0–100), plus besoin de × 100
                cpuPercent  = statParts[1].toDouble().toInt().coerceIn(0, 100),
                ramUsedMb   = statParts[2].toInt(),
                ramTotalMb  = statParts[3].toInt()
            )
            val gpuTemp = statParts[4].toDoubleOrNull()?.takeIf { it >= 0 }

            val disks = sections.getOrNull(1)
                ?.lines()
                ?.filter { it.isNotBlank() }
                ?.mapNotNull { line ->
                    val p = line.trim().split(Regex("\\s+"))
                    if (p.size >= 5) {
                        fun mb(s: String) = s.trimEnd('M').toLongOrNull() ?: 0L
                        DiskPartition(
                            mountPoint  = p[0],
                            totalMb     = mb(p[1]),
                            usedMb      = mb(p[2]),
                            availMb     = mb(p[3]),
                            usedPercent = p[4].trimEnd('%').toIntOrNull() ?: 0
                        )
                    } else null
                } ?: emptyList()

            ExtendedStats(base, gpuTemp, disks)
        } catch (_: Exception) {
            null
        }
    }

// ══════════════════════════════════════════════════════════════════════════════
//  Composable : Sparkline Canvas
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun SparklineChart(
    values  : List<Float>,
    color   : Color,
    maxValue: Float = 100f,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val w    = size.width
        val h    = size.height
        val step = w / (MAX_HISTORY - 1).toFloat()

        fun xAt(i: Int) = (MAX_HISTORY - values.size + i) * step
        fun yAt(v: Float) = h - (v / maxValue).coerceIn(0f, 1f) * h

        val fillPath = Path().apply {
            moveTo(xAt(0), h)
            lineTo(xAt(0), yAt(values[0]))
            for (i in 1 until values.size) lineTo(xAt(i), yAt(values[i]))
            lineTo(xAt(values.size - 1), h)
            close()
        }
        drawPath(
            path  = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.35f), Color.Transparent),
                startY = 0f,
                endY   = h
            )
        )

        val linePath = Path().apply {
            moveTo(xAt(0), yAt(values[0]))
            for (i in 1 until values.size) lineTo(xAt(i), yAt(values[i]))
        }
        drawPath(
            path  = linePath,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        drawCircle(
            color  = color,
            radius = 3.dp.toPx(),
            center = Offset(xAt(values.size - 1), yAt(values.last()))
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Composable : Carte graphique avec stats Min/Moy/Max
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun ChartCard(
    title   : String,
    values  : List<Float>,
    color   : Color,
    maxValue: Float = 100f,
    unit    : String = "%"
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier            = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
            ) {
                Column(
                    modifier            = Modifier
                        .width(34.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        "${maxValue.toInt()}$unit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "0$unit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(6.dp))
                SparklineChart(
                    values   = values,
                    color    = color,
                    maxValue = maxValue,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }

            // FIX : formater le nombre séparément puis concaténer l'unité
            // pour éviter UnknownFormatConversionException quand unit="%"
            if (values.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MiniStat("Min", "${"%.0f".format(values.min())}$unit",              Color(0xFF66BB6A))
                    MiniStat("Moy", "${"%.0f".format(values.average().toFloat())}$unit", color)
                    MiniStat("Max", "${"%.0f".format(values.max())}$unit",              Color(0xFFEF5350))
                }
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.labelLarge,
            color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Composable : Barre de partition disque
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun DiskBar(disk: DiskPartition) {
    val barColor = when {
        disk.usedPercent >= 90 -> Color(0xFFEF5350)
        disk.usedPercent >= 70 -> Color(0xFFFF9800)
        else                   -> Color(0xFF66BB6A)
    }
    fun Long.fmt() = if (this >= 1024) "${"%.1f".format(this / 1024.0)} Go" else "$this Mo"

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                disk.mountPoint,
                fontFamily = FontFamily.Monospace,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${disk.usedMb.fmt()} / ${disk.totalMb.fmt()} — ${disk.usedPercent}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress   = { (disk.usedPercent / 100f).coerceIn(0f, 1f) },
            modifier   = Modifier.fillMaxWidth().height(6.dp),
            color      = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Écran principal : Monitoring avancé
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringScreen(settings: SettingsManager, onClose: () -> Unit) {

    val cpuHistory  = remember { mutableStateListOf<Float>() }
    val ramHistory  = remember { mutableStateListOf<Float>() }
    val tempHistory = remember { mutableStateListOf<Float>() }

    var current by remember { mutableStateOf<ExtendedStats?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error   by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            val stats = fetchExtendedStats(settings)

            if (stats != null) {
                current = stats
                loading = false
                error   = false

                fun <T> MutableList<T>.push(v: T) { add(v); if (size > MAX_HISTORY) removeAt(0) }

                cpuHistory .push(stats.base.cpuPercent.toFloat())
                ramHistory .push(
                    if (stats.base.ramTotalMb > 0)
                        stats.base.ramUsedMb.toFloat() / stats.base.ramTotalMb * 100f
                    else 0f
                )
                tempHistory.push(stats.base.tempCelsius.toFloat())
            } else {
                loading = false
                error   = (current == null)
            }
            delay(settings.tempRefreshMs.toLong())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text("Monitoring avancé") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier            = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Première lecture en cours…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (error) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "⚠️ Impossible de récupérer les statistiques. Vérifiez la connexion SSH.",
                        modifier = Modifier.padding(16.dp),
                        color    = MaterialTheme.colorScheme.onErrorContainer,
                        style    = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // ── CPU ──────────────────────────────────────────────────────
            if (cpuHistory.isNotEmpty()) {
                ChartCard(
                    title    = "🖥️ CPU — ${current?.base?.cpuPercent ?: 0}%",
                    values   = cpuHistory.toList(),
                    color    = Color(0xFF42A5F5),
                    maxValue = 100f,
                    unit     = "%"
                )
            }

            // ── RAM ──────────────────────────────────────────────────────
            if (ramHistory.isNotEmpty()) {
                val ramPct = current?.let {
                    if (it.base.ramTotalMb > 0) it.base.ramUsedMb * 100 / it.base.ramTotalMb else 0
                } ?: 0
                ChartCard(
                    title    = "🧠 RAM — ${current?.base?.ramUsedMb ?: 0}/${current?.base?.ramTotalMb ?: 0} Mo ($ramPct%)",
                    values   = ramHistory.toList(),
                    color    = Color(0xFFAB47BC),
                    maxValue = 100f,
                    unit     = "%"
                )
            }

            // ── Température CPU ──────────────────────────────────────────
            if (tempHistory.isNotEmpty()) {
                ChartCard(
                    title    = "🌡️ Temp CPU — ${"%.1f".format(current?.base?.tempCelsius ?: 0.0)}°C",
                    values   = tempHistory.toList(),
                    color    = Color(0xFFEF5350),
                    maxValue = 90f,
                    unit     = "°C"
                )
            }

            // ── GPU VideoCore ────────────────────────────────────────────
            current?.gpuTempCelsius?.let { gpuTemp ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier              = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("🎮 GPU VideoCore", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    gpuTemp >= 75 -> "🔥 Surchauffe"
                                    gpuTemp >= 60 -> "♨️ Chaud"
                                    gpuTemp >= 45 -> "🌡️ Tiède"
                                    else          -> "✅ Normal"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "${"%.1f".format(gpuTemp)}°C",
                            style      = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color      = monitorTempColor(gpuTemp)
                        )
                    }
                }
            }

            // ── Disques ──────────────────────────────────────────────────
            val disks = current?.disks
            if (!disks.isNullOrEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier            = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("💾 Espace disque", style = MaterialTheme.typography.titleMedium)
                        disks.forEach { DiskBar(it) }
                    }
                }
            }

            // ── Légende refresh ──────────────────────────────────────────
            if (!loading) {
                val intervalSec = settings.tempRefreshMs / 1000
                val durationMin = MAX_HISTORY * intervalSec / 60
                Text(
                    "⏱ Rafraîchissement toutes les $intervalSec s · Historique $durationMin min (${cpuHistory.size}/$MAX_HISTORY points)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Helper couleur température
// ══════════════════════════════════════════════════════════════════════════════

private fun monitorTempColor(celsius: Double): Color = when {
    celsius >= 75.0 -> Color(0xFFEF5350)
    celsius >= 60.0 -> Color(0xFFFF9800)
    celsius >= 45.0 -> Color(0xFFFFEB3B)
    else            -> Color(0xFF66BB6A)
}