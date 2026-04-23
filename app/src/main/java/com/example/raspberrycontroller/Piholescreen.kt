package com.example.raspberrycontroller

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PiHoleRed   = Color(0xFFEF5350)
private val PiHoleGreen = Color(0xFF66BB6A)
private val PiHoleBlue  = Color(0xFF42A5F5)
private val PiHoleAmber = Color(0xFFFFA726)

data class PiHoleStats(
    val enabled         : Boolean,
    val domainsBlocked  : Int,
    val dnsQueriesToday : Int,
    val adsBlockedToday : Int,
    val adsPercentage   : Double,
    val uniqueDomains   : Int,
    val queriesCached   : Int,
    val clientsEverSeen : Int
)

@Suppress("SpellCheckingInspection")
fun buildFetchScript(password: String): String {
    val esc = password.replace("\\", "\\\\").replace("'", "\\'")

    return """python3 << 'PYEOF'
import urllib.request, json

base = 'http://localhost/api'
pwd  = '$esc'

def do_post(path, data):
    body = json.dumps(data).encode()
    req  = urllib.request.Request(
        base + path,
        data=body,
        headers={'Content-Type': 'application/json'}
    )
    return json.loads(urllib.request.urlopen(req, timeout=5).read())

def do_get(path, sid, csrf):
    req = urllib.request.Request(base + path)
    req.add_header('X-FTL-SID', sid)
    req.add_header('X-FTL-CSRF', csrf)
    return json.loads(urllib.request.urlopen(req, timeout=5).read())

try:
    auth = do_post('/auth', {'password': pwd})
    session = auth.get('session', {})

    sid   = session.get('sid', '')
    csrf  = session.get('csrf', '')
    valid = session.get('valid', False)

    if not valid or not sid or not csrf:
        print('auth_error|invalid_credentials')
        exit(1)

    stats = do_get('/stats/summary', sid, csrf)
    block = do_get('/dns/blocking', sid, csrf)

    enabled = block.get('blocking', 'unknown')

    queries = stats.get('queries', {})
    gravity = stats.get('gravity', {})
    clients = stats.get('clients', {})

    print(
        str(enabled) + '|' +
        str(gravity.get('domains_being_blocked', 0)) + '|' +
        str(queries.get('total', 0)) + '|' +
        str(queries.get('blocked', 0)) + '|' +
        str(queries.get('percent_blocked', 0.0)) + '|' +
        str(queries.get('unique_domains', 0)) + '|' +
        str(queries.get('cached', 0)) + '|' +
        str(clients.get('total', 0))
    )

except Exception as e:
    print('error|' + str(e))
PYEOF
"""
}

@Suppress("SpellCheckingInspection")
private fun buildToggleScript(password: String, enable: Boolean): String {
    val esc = password.replace("\\", "\\\\").replace("'", "\\'")
    val action = if (enable) "true" else "false"

    return """python3 << 'PYEOF'
import urllib.request, json

base = 'http://localhost/api'
pwd  = '$esc'

def post(path, data):
    req = urllib.request.Request(
        base + path,
        data=json.dumps(data).encode(),
        headers={'Content-Type': 'application/json'}
    )
    return json.loads(urllib.request.urlopen(req, timeout=5).read())

try:
    auth = post('/auth', {'password': pwd})
    session = auth.get('session', {})

    sid  = session.get('sid')
    csrf = session.get('csrf')
    valid = session.get('valid', False)

    if not valid or not sid or not csrf:
        print('error|auth_failed')
        exit(1)

    req = urllib.request.Request(
        base + '/dns/blocking',
        data=json.dumps({'blocking': $action}).encode(),
        headers={
            'Content-Type': 'application/json',
            'X-FTL-SID': sid,
            'X-FTL-CSRF': csrf
        }
    )

    res = urllib.request.urlopen(req, timeout=5).read().decode()
    print('ok|' + res)

except Exception as e:
    print('error|' + str(e))
PYEOF
"""
}

suspend fun fetchPiHoleStatus(settings: SettingsManager, password: String): PiHoleStats? {
    return try {
        val raw = SshClient.execute(
            settings.host, settings.port, settings.username, settings.password,
            buildFetchScript(password), settings.sshTimeoutMs
        ).trim()
        if (raw.startsWith("auth_error") || raw.startsWith("no_sid") || raw.startsWith("stats_error"))
            return null
        parseStatsOrNull(raw)
    } catch (_: Exception) { null }
}

suspend fun togglePiHole(settings: SettingsManager, password: String, enable: Boolean): Boolean {
    return try {
        val result = SshClient.execute(
            settings.host, settings.port, settings.username, settings.password,
            buildToggleScript(password, enable), settings.sshTimeoutMs
        ).trim()
        result.startsWith("ok")
    } catch (_: Exception) { false }
}

private fun parseStatsOrNull(raw: String): PiHoleStats? {
    val parts = raw.split("|")
    if (parts.size < 8) return null
    return PiHoleStats(
        enabled         = parts[0].trim() == "true",
        domainsBlocked  = parts[1].trim().toIntOrNull() ?: 0,
        dnsQueriesToday = parts[2].trim().toIntOrNull() ?: 0,
        adsBlockedToday = parts[3].trim().toIntOrNull() ?: 0,
        adsPercentage   = parts[4].trim().replace(",", ".").toDoubleOrNull() ?: 0.0,
        uniqueDomains   = parts[5].trim().toIntOrNull() ?: 0,
        queriesCached   = parts[6].trim().toIntOrNull() ?: 0,
        clientsEverSeen = parts[7].trim().toIntOrNull() ?: 0
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiHoleScreen(
    settings     : SettingsManager,
    onClose      : () -> Unit,
    onOpenConfig : () -> Unit
) {
    val scope = rememberCoroutineScope()

    var stats      by remember { mutableStateOf<PiHoleStats?>(null) }
    var loading    by remember { mutableStateOf(true) }
    var toggling   by remember { mutableStateOf(false) }
    var errorMsg   by remember { mutableStateOf<String?>(null) }
    var authError  by remember { mutableStateOf(false) }
    var snackMsg   by remember { mutableStateOf<String?>(null) }
    val snackState = remember { SnackbarHostState() }

    // 🔥 ANTI-SPAM GLOBAL (IMPORTANT)
    var lastRequestTime by remember { mutableStateOf(0L) }
    val MIN_DELAY = 2500L

    fun refresh() {
        scope.launch {

            val now = System.currentTimeMillis()
            if (now - lastRequestTime < MIN_DELAY) return@launch
            lastRequestTime = now

            loading = true
            errorMsg = null
            authError = false

            val pwd = settings.piHolePassword

            val raw = try {
                SshClient.execute(
                    settings.host,
                    settings.port,
                    settings.username,
                    settings.password,
                    buildFetchScript(pwd),
                    settings.sshTimeoutMs
                ).trim()
            } catch (_: Exception) {
                null
            }

            when {
                raw == null ->
                    errorMsg = "Impossible de se connecter au Raspberry Pi."

                raw.startsWith("auth_error") || raw.startsWith("no_sid") -> {
                    authError = true
                    errorMsg = "Mot de passe Pi-hole incorrect.\nConfigurez-le via l'icone."
                }

                raw.startsWith("stats_error") ->
                    errorMsg = "Erreur stats :\n${raw.substringAfter("|")}"

                else -> {
                    val parsed = parseStatsOrNull(raw)
                    if (parsed != null) {
                        stats = parsed
                        errorMsg = null
                        authError = false
                    } else {
                        errorMsg = "Reponse inattendue :\n$raw"
                    }
                }
            }

            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // 🔥 AUTO REFRESH PROTÉGÉ
    LaunchedEffect(Unit) {
        while (true) {
            val delaySec = settings.piHoleRefreshDelaySec.toLong().coerceAtLeast(15L)
            delay(delaySec * 1000L)

            val now = System.currentTimeMillis()

            if (settings.piHoleAutoRefresh &&
                !toggling &&
                now - lastRequestTime > MIN_DELAY) {

                lastRequestTime = now

                val result = fetchPiHoleStatus(settings, settings.piHolePassword)
                if (result != null) {
                    stats = result
                    errorMsg = null
                }
            }
        }
    }

    LaunchedEffect(snackMsg) {
        snackMsg?.let {
            snackState.showSnackbar(it)
            snackMsg = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackState) }
    ) { padding ->

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Pi-hole", fontWeight = FontWeight.Bold)
                        stats?.let { s ->
                            Box(modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (s.enabled) PiHoleGreen.copy(0.15f) else PiHoleRed.copy(0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (s.enabled) "ACTIF" else "INACTIF",
                                    fontSize = 10.sp,
                                    color = if (s.enabled) PiHoleGreen else PiHoleRed,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenConfig) {
                        Icon(Icons.Default.Settings, contentDescription = "Configuration Pi-hole")
                    }
                    IconButton(onClick = { refresh() }, enabled = !loading) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = "Actualiser")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                loading && stats == null -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(40.dp),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text("Connexion a Pi-hole...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                errorMsg != null -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Column {
                                Text("Erreur", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text(errorMsg!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    if (authError) {
                        Button(onClick = onOpenConfig, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Configurer le mot de passe Pi-hole")
                        }
                    } else {
                        Button(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Reessayer")
                        }
                    }
                }

                stats != null -> {
                    val s = stats!!

                    PiHoleToggleCard(
                        enabled  = s.enabled,
                        toggling = toggling,
                        onToggle = {
                            scope.launch {
                                toggling = true
                                val success = togglePiHole(settings, settings.piHolePassword, !s.enabled)
                                if (success) {
                                    delay(1500)
                                    val newStats = fetchPiHoleStatus(settings, settings.piHolePassword)
                                    if (newStats != null) stats = newStats
                                    snackMsg = if (!s.enabled) "Pi-hole active" else "Pi-hole desactive"
                                } else {
                                    snackMsg = "Erreur lors du changement d'etat."
                                }
                                toggling = false
                            }
                        }
                    )

                    PiHoleBlockingCard(s)

                    Text("Statistiques aujourd'hui",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PiHoleStatCard("Requetes DNS",        formatNumber(s.dnsQueriesToday), Icons.Default.Dns,    PiHoleBlue,  Modifier.weight(1f))
                        PiHoleStatCard("Publicites bloquees", formatNumber(s.adsBlockedToday), Icons.Default.Block,  PiHoleRed,   Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PiHoleStatCard("Domaines en cache", formatNumber(s.queriesCached),  Icons.Default.Memory,  PiHoleAmber, Modifier.weight(1f))
                        PiHoleStatCard("Clients vus",       s.clientsEverSeen.toString(),   Icons.Default.Devices, PiHoleGreen, Modifier.weight(1f))
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Shield, contentDescription = null,
                                tint = PiHoleRed, modifier = Modifier.size(28.dp))
                            Column {
                                Text("Liste de blocage", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatNumber(s.domainsBlocked) + " domaines bloques",
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Domaines uniques vus : ${formatNumber(s.uniqueDomains)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PiHoleToggleCard(enabled: Boolean, toggling: Boolean, onToggle: () -> Unit) {
    val bgColor     by animateColorAsState(if (enabled) PiHoleGreen.copy(0.12f) else PiHoleRed.copy(0.08f), label = "bg")
    val borderColor by animateColorAsState(if (enabled) PiHoleGreen.copy(0.4f)  else PiHoleRed.copy(0.25f), label = "border")

    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)) {
        Row(modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                    initialValue = 0.6f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "alpha")
                Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(
                    (if (enabled) PiHoleGreen else PiHoleRed).copy(alpha = if (enabled) pulse else 1f)))
                Column {
                    Text(if (enabled) "Pi-hole actif" else "Pi-hole inactif",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(if (enabled) "Le filtrage DNS est en cours" else "Tout le trafic passe sans filtrage",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (toggling) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            } else {
                Switch(checked = enabled, onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor   = Color.White, checkedTrackColor   = PiHoleGreen,
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = PiHoleRed.copy(0.4f)))
            }
        }
    }
}

@Composable
private fun PiHoleBlockingCard(s: PiHoleStats) {
    val pct   = (s.adsPercentage / 100.0).toFloat().coerceIn(0f, 1f)
    val color = when {
        s.adsPercentage >= 30 -> PiHoleGreen
        s.adsPercentage >= 10 -> PiHoleAmber
        else                  -> PiHoleRed
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Taux de blocage", style = MaterialTheme.typography.titleSmall)
                Text("%.1f%%".format(s.adsPercentage), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = color)
            }
            LinearProgressIndicator(
                progress   = { pct },
                modifier   = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color      = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Text("${formatNumber(s.adsBlockedToday)} requetes bloquees sur ${formatNumber(s.dnsQueriesToday)} aujourd'hui",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PiHoleStatCard(
    label   : String,
    value   : String,
    icon    : androidx.compose.ui.graphics.vector.ImageVector,
    color   : Color,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatNumber(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000     -> "%.1fk".format(n / 1_000.0)
    else           -> n.toString()
}