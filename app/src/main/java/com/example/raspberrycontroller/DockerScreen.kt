package com.example.raspberrycontroller

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ══════════════════════════════════════════════════════════════════════════════
//  Modèle de données
// ══════════════════════════════════════════════════════════════════════════════
data class DockerContainer(
    val id    : String,
    val name  : String,
    val status: String,
    val image : String
) {
    val isRunning: Boolean get() = status.startsWith("Up", ignoreCase = true)
}

// ══════════════════════════════════════════════════════════════════════════════
//  Récupération des containers via SSH
// ══════════════════════════════════════════════════════════════════════════════
suspend fun fetchDockerContainers(settings: SettingsManager): Result<List<DockerContainer>> {
    val raw = SshClient.execute(
        host      = settings.host,
        port      = settings.port,
        user      = settings.username,
        password  = settings.password,
        // sudo permet d'accéder à /var/run/docker.sock sans être dans le groupe docker
        command   = "sudo docker ps -a --format \"{{.ID}}\t{{.Names}}\t{{.Status}}\t{{.Image}}\"",
        timeoutMs = settings.sshTimeoutMs
    )
    if (raw.startsWith("❌") || raw.startsWith("⚠️") || raw.startsWith("🌐") ||
        raw.startsWith("⏱️") || raw.startsWith("🚫") || raw.startsWith("📡") ||
        raw.startsWith("🔌") || raw.startsWith("[err]")) {
        return Result.failure(Exception(raw))
    }
    if (raw.isBlank()) return Result.success(emptyList())

    val containers = raw.lines().mapNotNull { line ->
        val parts = line.split("\t")
        if (parts.size >= 4) DockerContainer(
            id     = parts[0].take(12),
            name   = parts[1],
            status = parts[2],
            image  = parts[3]
        ) else null
    }
    return Result.success(containers)
}

// ══════════════════════════════════════════════════════════════════════════════
//  Écran Docker
// ══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockerScreen(
    settings: SettingsManager,
    onClose : () -> Unit
) {
    val scope  = rememberCoroutineScope()

    var containers    by remember { mutableStateOf<List<DockerContainer>>(emptyList()) }
    var fetchError    by remember { mutableStateOf<String?>(null) }
    var isLoading     by remember { mutableStateOf(true) }
    var actionLoading by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var actionResult  by remember { mutableStateOf<String?>(null) }

    // ── Chargement ────────────────────────────────────────────────────────────
    fun refresh() {
        scope.launch {
            isLoading  = true
            fetchError = null
            val result = fetchDockerContainers(settings)
            result.fold(
                onSuccess = { containers = it; fetchError = null },
                onFailure = { fetchError = it.message }
            )
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // ── Action sur un container ───────────────────────────────────────────────
    fun runAction(container: DockerContainer, action: String) {
        scope.launch {
            actionLoading = actionLoading + (container.name to action)
            actionResult  = null

            // sudo devant chaque commande docker pour éviter le permission denied
            val cmd = when (action) {
                "start"   -> "sudo docker start ${container.name}"
                "stop"    -> "sudo docker stop ${container.name}"
                "restart" -> "sudo docker restart ${container.name}"
                else      -> return@launch
            }
            val raw = SshClient.execute(
                settings.host, settings.port, settings.username, settings.password,
                cmd, settings.sshTimeoutMs
            )
            val emoji = when (action) {
                "start"   -> "▶️"
                "stop"    -> "⏹️"
                "restart" -> "🔄"
                else      -> "✅"
            }
            actionResult  = "$emoji ${container.name} : $raw"
            actionLoading = actionLoading - container.name
            refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Docker") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualiser")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // ── Résumé ────────────────────────────────────────────────────────
            if (!isLoading && fetchError == null) {
                val running = containers.count { it.isRunning }
                val stopped = containers.size - running
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryChip(
                        label    = "$running actif${if (running > 1) "s" else ""}",
                        color    = Color(0xFF66BB6A),
                        modifier = Modifier.weight(1f)
                    )
                    SummaryChip(
                        label    = "$stopped arrêté${if (stopped > 1) "s" else ""}",
                        color    = Color(0xFFEF5350),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Résultat de la dernière action ────────────────────────────────
            actionResult?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier              = Modifier.padding(12.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text       = msg,
                            style      = MaterialTheme.typography.bodySmall,
                            modifier   = Modifier.weight(1f),
                            fontFamily = FontFamily.Monospace
                        )
                        IconButton(
                            onClick  = { actionResult = null },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Fermer",
                                modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // ── Chargement ────────────────────────────────────────────────────
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier            = Modifier.padding(32.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Récupération des containers...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                return@Column
            }

            // ── Erreur ────────────────────────────────────────────────────────
            fetchError?.let { err ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(
                        modifier            = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text       = "Impossible de récupérer les containers",
                            style      = MaterialTheme.typography.titleSmall,
                            color      = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text       = err,
                            style      = MaterialTheme.typography.bodySmall,
                            color      = MaterialTheme.colorScheme.onErrorContainer,
                            fontFamily = FontFamily.Monospace
                        )
                        // Conseil affiché si l'erreur est liée aux permissions
                        if (err.contains("permission denied", ignoreCase = true) ||
                            err.contains("docker.sock", ignoreCase = true)) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.3f)
                            )
                            Row(
                                verticalAlignment     = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector        = Icons.Default.Info,
                                    contentDescription = null,
                                    tint               = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier           = Modifier.size(16.dp).padding(top = 2.dp)
                                )
                                Text(
                                    text       = "Conseil : autorisez sudo docker sans mot de passe " +
                                            "en ajoutant cette ligne dans /etc/sudoers :\n" +
                                            "${settings.username} ALL=(ALL) NOPASSWD: /usr/bin/docker",
                                    style      = MaterialTheme.typography.labelSmall,
                                    color      = MaterialTheme.colorScheme.onErrorContainer,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        OutlinedButton(
                            onClick  = { refresh() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Réessayer") }
                    }
                }
                return@Column
            }

            // ── Aucun container ───────────────────────────────────────────────
            if (containers.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier            = Modifier.padding(32.dp)
                    ) {
                        Text("🐳", fontSize = 48.sp)
                        Text("Aucun container Docker trouvé",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                return@Column
            }

            // ── Liste des containers ──────────────────────────────────────────
            containers.forEach { container ->
                val loadingAction = actionLoading[container.name]
                ContainerCard(
                    container     = container,
                    loadingAction = loadingAction,
                    onStart       = { runAction(container, "start") },
                    onStop        = { runAction(container, "stop") },
                    onRestart     = { runAction(container, "restart") }
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Carte d'un container
// ══════════════════════════════════════════════════════════════════════════════
@Composable
private fun ContainerCard(
    container    : DockerContainer,
    loadingAction: String?,
    onStart      : () -> Unit,
    onStop       : () -> Unit,
    onRestart    : () -> Unit
) {
    val isRunning = container.isRunning
    val busy      = loadingAction != null

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier            = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── En-tête ───────────────────────────────────────────────────────
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = container.name,
                        style      = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text       = container.image,
                        style      = MaterialTheme.typography.labelSmall,
                        color      = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
                StatusBadge(isRunning = isRunning)
            }

            // ── ID ────────────────────────────────────────────────────────────
            Text(
                text       = "ID : ${container.id}",
                style      = MaterialTheme.typography.labelSmall,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )

            // ── Indicateur de chargement ──────────────────────────────────────
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text  = when (loadingAction) {
                        "start"   -> "Démarrage..."
                        "stop"    -> "Arrêt..."
                        "restart" -> "Redémarrage..."
                        else      -> "En cours..."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // ── Boutons d'action ──────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isRunning) {
                    Button(
                        onClick  = onStart,
                        modifier = Modifier.weight(1f),
                        enabled  = !busy,
                        colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF66BB6A))
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Démarrer")
                    }
                } else {
                    OutlinedButton(
                        onClick  = onStop,
                        modifier = Modifier.weight(1f),
                        enabled  = !busy
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Arrêter")
                    }
                    Button(
                        onClick  = onRestart,
                        modifier = Modifier.weight(1f),
                        enabled  = !busy
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Redémarrer")
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Composants utilitaires
// ══════════════════════════════════════════════════════════════════════════════
@Composable
private fun StatusBadge(isRunning: Boolean) {
    val color = if (isRunning) Color(0xFF66BB6A) else Color(0xFFEF5350)
    val label = if (isRunning) "● Actif" else "● Arrêté"
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text       = label,
            color      = color,
            style      = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier   = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SummaryChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape    = MaterialTheme.shapes.medium,
        color    = color.copy(alpha = 0.12f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text       = label,
                color      = color,
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}