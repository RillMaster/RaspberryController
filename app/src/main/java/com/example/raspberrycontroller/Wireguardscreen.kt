package com.example.raspberrycontroller

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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

// ── Couleurs WireGuard ────────────────────────────────────────────────────────
private val WgGreen  = Color(0xFF4CAF50)
private val WgBlue   = Color(0xFF2196F3)
private val WgOrange = Color(0xFFFF9800)
private val WgGrey   = Color(0xFF9E9E9E)
private val WgRed    = Color(0xFFEF5350)

// ── Modèles de données ────────────────────────────────────────────────────────
data class WgPeer(
    val name         : String,   // publicKey tronquée ou alias si dispo
    val publicKey    : String,
    val endpoint     : String,   // IP:port de la dernière connexion
    val allowedIPs   : String,
    val lastHandshake: String,   // ex: "2 minutes ago"
    val rxBytes      : Long,
    val txBytes      : Long,
    val isOnline     : Boolean   // handshake < 3 min
)

data class WgStatus(
    val interfaceName: String,
    val isUp         : Boolean,
    val publicKey    : String,
    val listenPort   : Int,
    val peers        : List<WgPeer>
)

// ── Script SSH pour récupérer le statut WireGuard ─────────────────────────────
private val WG_SCRIPT = """
python3 -c "
import subprocess, re, time

try:
    out = subprocess.check_output(['sudo', 'wg', 'show', 'all', 'dump'], text=True, stderr=subprocess.DEVNULL)
except Exception as e:
    print('ERROR:' + str(e))
    exit()

lines = [l.strip() for l in out.strip().split('\n') if l.strip()]
if not lines:
    print('NO_INTERFACE')
    exit()

iface_name = ''
pub_key = ''
port = 0
peers = []
now = int(time.time())

for line in lines:
    parts = line.split('\t')
    if len(parts) == 5 and parts[0] != 'off':
        # Ligne d'interface: interface  privatekey  publickey  port  fwmark
        iface_name = parts[0]
        pub_key = parts[2]
        try: port = int(parts[3])
        except: port = 0
    elif len(parts) == 9:
        # Ligne peer: interface  pubkey  preshared  endpoint  allowedIPs  latest_handshake  rx  tx  keepalive
        peer_pub = parts[1]
        endpoint = parts[3] if parts[3] != '(none)' else ''
        allowed  = parts[4]
        try:
            hs = int(parts[5])
            age_s = now - hs
            if hs == 0:
                hs_str = 'Jamais'
                online = False
            elif age_s < 60:
                hs_str = str(age_s) + 's'
                online = True
            elif age_s < 3600:
                hs_str = str(age_s // 60) + 'min'
                online = age_s < 180
            else:
                hs_str = str(age_s // 3600) + 'h'
                online = False
        except:
            hs_str = '?'
            online = False
        try: rx = int(parts[6])
        except: rx = 0
        try: tx = int(parts[7])
        except: tx = 0
        peers.append(peer_pub[:8] + '|' + peer_pub + '|' + endpoint + '|' + allowed + '|' + hs_str + '|' + str(rx) + '|' + str(tx) + '|' + str(1 if online else 0))

print('IFACE:' + iface_name + ':' + pub_key + ':' + str(port))
for p in peers:
    print('PEER:' + p)
"
""".trimIndent()

private suspend fun fetchWgStatus(settings: SettingsManager): WgStatus? {
    return try {
        val raw = SshClient.execute(
            settings.host, settings.port, settings.username, settings.password,
            WG_SCRIPT, settings.sshTimeoutMs
        ).trim()

        if (raw.startsWith("ERROR") || raw == "NO_INTERFACE") return null

        val lines = raw.lines()
        var ifaceName = "wg0"
        var pubKey    = ""
        var port      = 51820
        val peers     = mutableListOf<WgPeer>()

        for (line in lines) {
            when {
                line.startsWith("IFACE:") -> {
                    val parts = line.removePrefix("IFACE:").split(":")
                    ifaceName = parts.getOrElse(0) { "wg0" }
                    pubKey    = parts.getOrElse(1) { "" }
                    port      = parts.getOrElse(2) { "51820" }.toIntOrNull() ?: 51820
                }
                line.startsWith("PEER:") -> {
                    val p = line.removePrefix("PEER:").split("|")
                    if (p.size >= 8) {
                        peers.add(WgPeer(
                            name          = p[0] + "…",
                            publicKey     = p[1],
                            endpoint      = p[2].ifBlank { "Jamais connecté" },
                            allowedIPs    = p[3],
                            lastHandshake = p[4],
                            rxBytes       = p[5].toLongOrNull() ?: 0L,
                            txBytes       = p[6].toLongOrNull() ?: 0L,
                            isOnline      = p[7] == "1"
                        ))
                    }
                }
            }
        }

        // Vérifie si l'interface est up
        val isUp = SshClient.execute(
            settings.host, settings.port, settings.username, settings.password,
            "ip link show ${ifaceName} 2>/dev/null | grep -c 'state UP' || echo 0",
            settings.sshTimeoutMs
        ).trim().toIntOrNull() ?: 0 > 0

        WgStatus(ifaceName, isUp || peers.isNotEmpty(), pubKey, port, peers)
    } catch (_: Exception) { null }
}

private suspend fun toggleWireGuard(settings: SettingsManager, ifaceName: String, enable: Boolean): Boolean {
    return try {
        val cmd = if (enable) "sudo wg-quick up $ifaceName" else "sudo wg-quick down $ifaceName"
        val result = SshClient.execute(
            settings.host, settings.port, settings.username, settings.password,
            cmd, settings.sshTimeoutMs
        )
        result.isNotBlank() && !result.contains("Error", ignoreCase = true)
    } catch (_: Exception) { false }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Composable principal
// ══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WireGuardScreen(settings: SettingsManager, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()

    var wgStatus   by remember { mutableStateOf<WgStatus?>(null) }
    var loading    by remember { mutableStateOf(true) }
    var toggling   by remember { mutableStateOf(false) }
    var errorMsg   by remember { mutableStateOf<String?>(null) }
    val snackState = remember { SnackbarHostState() }
    var snackMsg   by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading  = true
            errorMsg = null
            val result = fetchWgStatus(settings)
            if (result == null) errorMsg = "WireGuard introuvable ou inaccessible.\nVérifiez que wg-quick est installé et que sudo est autorisé."
            wgStatus = result
            loading  = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            if (!toggling) {
                val r = fetchWgStatus(settings)
                if (r != null) wgStatus = r
            }
        }
    }

    LaunchedEffect(snackMsg) {
        snackMsg?.let { snackState.showSnackbar(it); snackMsg = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("WireGuard", fontWeight = FontWeight.Bold)
                        wgStatus?.let { s ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (s.isUp) WgGreen.copy(0.15f) else WgGrey.copy(0.15f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text       = if (s.isUp) "VPN UP" else "VPN DOWN",
                                    fontSize   = 10.sp,
                                    color      = if (s.isUp) WgGreen else WgGrey,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }, enabled = !loading && !toggling) {
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
                loading && wgStatus == null -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text("Connexion à WireGuard...", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Button(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) { Text("Réessayer") }
                }
                wgStatus != null -> {
                    val s = wgStatus!!

                    // ── Carte statut interface ────────────────────────────────
                    WgInterfaceCard(
                        status   = s,
                        toggling = toggling,
                        onToggle = {
                            scope.launch {
                                toggling = true
                                val ok = toggleWireGuard(settings, s.interfaceName, !s.isUp)
                                if (ok) {
                                    delay(2000)
                                    val fresh = fetchWgStatus(settings)
                                    if (fresh != null) wgStatus = fresh
                                    snackMsg = if (!s.isUp) "WireGuard démarré ✓" else "WireGuard arrêté"
                                } else {
                                    snackMsg = "Erreur lors du changement d'état"
                                }
                                toggling = false
                            }
                        }
                    )

                    // ── Résumé pairs ──────────────────────────────────────────
                    val onlineCount = s.peers.count { it.isOnline }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WgStatCard("Pairs configurés", s.peers.size.toString(), Icons.Default.Group, WgBlue, Modifier.weight(1f))
                        WgStatCard("Clients connectés", onlineCount.toString(), Icons.Default.Wifi, WgGreen, Modifier.weight(1f))
                    }

                    // ── Liste des pairs ───────────────────────────────────────
                    if (s.peers.isNotEmpty()) {
                        Text(
                            "Clients (${s.peers.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        s.peers.forEach { peer ->
                            WgPeerCard(peer)
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier          = Modifier.padding(20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PersonOff, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Aucun pair configuré", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Carte interface WireGuard ─────────────────────────────────────────────────
@Composable
private fun WgInterfaceCard(status: WgStatus, toggling: Boolean, onToggle: () -> Unit) {
    val bgColor = animateColorAsState(
        if (status.isUp) WgGreen.copy(0.10f) else WgGrey.copy(0.08f), label = "bg"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = bgColor.value),
        border   = androidx.compose.foundation.BorderStroke(1.dp, if (status.isUp) WgGreen.copy(0.35f) else WgGrey.copy(0.2f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape)
                        .background(if (status.isUp) WgGreen else WgGrey))
                    Column {
                        Text(status.interfaceName, fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                        Text("Port : ${status.listenPort}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                    }
                }
                if (toggling) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                } else {
                    Switch(
                        checked         = status.isUp,
                        onCheckedChange = { onToggle() },
                        colors          = SwitchDefaults.colors(
                            checkedTrackColor   = WgGreen,
                            uncheckedTrackColor = WgGrey.copy(0.4f)
                        )
                    )
                }
            }
            if (status.publicKey.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.5f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Key, contentDescription = null,
                        modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text       = status.publicKey.take(20) + "…",
                        style      = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color      = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Carte pair ────────────────────────────────────────────────────────────────
@Composable
private fun WgPeerCard(peer: WgPeer) {
    val statusColor = if (peer.isOnline) WgGreen else WgGrey

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // En-tête
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor))
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(peer.name, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(
                        if (peer.isOnline) "Connecté · dernière activité ${peer.lastHandshake}"
                        else "Hors ligne · dernière activité ${peer.lastHandshake}",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusColor.copy(0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(if (peer.isOnline) "ONLINE" else "OFFLINE",
                        fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        color = statusColor, fontFamily = FontFamily.Monospace)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.4f))

            // Infos réseau
            WgInfoRow(Icons.Default.Router, "Endpoint", peer.endpoint.ifBlank { "—" })
            WgInfoRow(Icons.Default.AccountTree, "IPs autorisées", peer.allowedIPs)

            // Trafic
            if (peer.rxBytes > 0 || peer.txBytes > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null,
                            modifier = Modifier.size(14.dp), tint = WgBlue)
                        Text(formatBytes(peer.rxBytes), style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace, color = WgBlue)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null,
                            modifier = Modifier.size(14.dp), tint = WgOrange)
                        Text(formatBytes(peer.txBytes), style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace, color = WgOrange)
                    }
                }
            }
        }
    }
}

@Composable
private fun WgInfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null,
            modifier = Modifier.size(14.dp).padding(top = 1.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$label : ", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun WgStatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f Go".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f Mo".format(bytes / 1_048_576.0)
    bytes >= 1_024L         -> "%.1f Ko".format(bytes / 1_024.0)
    else                    -> "$bytes o"
}