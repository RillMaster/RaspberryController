package com.example.raspberrycontroller

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val CfgRed   = Color(0xFFEF5350)
private val CfgGreen = Color(0xFF66BB6A)
private val CfgBlue  = Color(0xFF42A5F5)
private val CfgAmber = Color(0xFFFFA726)

sealed class PiHoleTestState {
    object Idle                                : PiHoleTestState()
    object Testing                             : PiHoleTestState()
    data class Success(val stats: PiHoleStats) : PiHoleTestState()
    data class Failure(val reason: String)     : PiHoleTestState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiHoleConfigScreen(
    settings : SettingsManager,
    onClose  : () -> Unit,
    onSaved  : () -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    // Toutes les valeurs lues depuis settings au démarrage
    var piPassword   by remember { mutableStateOf(settings.piHolePassword) }
    var showPassword by remember { mutableStateOf(false) }
    var autoRefresh  by remember { mutableStateOf(settings.piHoleAutoRefresh) }
    var refreshDelay by remember { mutableStateOf(settings.piHoleRefreshDelaySec) }
    var testState    by remember { mutableStateOf<PiHoleTestState>(PiHoleTestState.Idle) }
    var debugInfo    by remember { mutableStateOf<String?>(null) }

    val snackState = remember { SnackbarHostState() }
    val sshOk      = settings.isConfigured()
    val formOk     = piPassword.isNotBlank() && sshOk

    fun saveConfig() {
        settings.piHolePassword        = piPassword
        settings.piHoleAutoRefresh     = autoRefresh
        settings.piHoleRefreshDelaySec = refreshDelay
        scope.launch {
            snackState.showSnackbar("Configuration Pi-hole sauvegardee")
            onSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null,
                            tint = CfgRed, modifier = Modifier.size(22.dp))
                        Text("Configuration Pi-hole", fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            if (!sshOk) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CfgAmber.copy(alpha = 0.12f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CfgAmber.copy(0.4f))
                ) {
                    Row(modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = CfgAmber)
                        Column {
                            Text("Configuration SSH requise", fontWeight = FontWeight.Bold, color = CfgAmber)
                            Text("Pi-hole communique via SSH. Configurez d'abord la connexion SSH dans les parametres principaux.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            CfgSectionLabel(icon = Icons.Default.Terminal, title = "Connexion SSH")

            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Row(modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Computer, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (sshOk) "${settings.username}@${settings.host}:${settings.port}" else "Non configure",
                            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                        Text(
                            text = if (sshOk) "Pi-hole sera contacte via localhost" else "Veuillez d'abord configurer le SSH",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        imageVector = if (sshOk) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (sshOk) CfgGreen else CfgRed)
                }
            }

            CfgSectionLabel(icon = Icons.Default.Key, title = "Authentification Pi-hole")

            OutlinedTextField(
                value         = piPassword,
                onValueChange = { piPassword = it; testState = PiHoleTestState.Idle; debugInfo = null },
                label         = { Text("Mot de passe de l'interface web Pi-hole") },
                placeholder   = { Text("Mot de passe Pi-hole v6") },
                leadingIcon   = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon  = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Masquer" else "Afficher")
                    }
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine      = true,
                modifier        = Modifier.fillMaxWidth()
            )

            // Indicateur du mot de passe actuellement sauvegarde
            if (settings.piHolePassword.isNotBlank()) {
                Text(
                    "Mot de passe sauvegarde : ${"*".repeat(settings.piHolePassword.length)} (${settings.piHolePassword.length} car.)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Aucun mot de passe sauvegarde",
                    style = MaterialTheme.typography.labelSmall,
                    color = CfgAmber
                )
            }

            CfgInfoCard(
                icon  = Icons.Default.Info,
                color = CfgBlue,
                text  = "Pi-hole v6 utilise l'API REST sur http://localhost/api. " +
                        "Le mot de passe est celui configure lors de l'installation " +
                        "ou via : pihole setpassword"
            )

            CfgSectionLabel(icon = Icons.Default.Tune, title = "Options avancees")

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Actualisation automatique",
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("Mise a jour periodique des statistiques",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = autoRefresh, onCheckedChange = { autoRefresh = it })
                    }

                    AnimatedVisibility(visible = autoRefresh) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Intervalle : ${refreshDelay}s",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    when {
                                        refreshDelay <= 15 -> "Frequent"
                                        refreshDelay <= 45 -> "Normal"
                                        else               -> "Economique"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        refreshDelay <= 15 -> CfgAmber
                                        refreshDelay <= 45 -> CfgGreen
                                        else               -> CfgBlue
                                    }
                                )
                            }
                            Slider(
                                value         = refreshDelay.toFloat(),
                                onValueChange = { refreshDelay = it.toInt() },
                                valueRange    = 10f..120f,
                                steps         = 10,
                                modifier      = Modifier.fillMaxWidth()
                            )
                            Row(modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("10s", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("120s", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Endpoint API", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("http://localhost/api  (via SSH)",
                                fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Reponse brute du Pi (debug mot de passe)
            debugInfo?.let { info ->
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Reponse brute du Pi :",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(info, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }

            AnimatedVisibility(
                visible = testState !is PiHoleTestState.Idle,
                enter   = expandVertically() + fadeIn(),
                exit    = shrinkVertically() + fadeOut()
            ) {
                CfgTestResultCard(state = testState)
            }

            Spacer(Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            testState = PiHoleTestState.Testing
                            debugInfo = null
                            val raw = try {
                                SshClient.execute(
                                    settings.host, settings.port,
                                    settings.username, settings.password,
                                    buildFetchScript(piPassword),
                                    settings.sshTimeoutMs
                                ).trim()
                            } catch (e: Exception) {
                                "Exception SSH : ${e.message}"
                            }
                            debugInfo = raw
                            testState = when {
                                raw.startsWith("auth_error") || raw.startsWith("no_sid") ->
                                    PiHoleTestState.Failure("Mot de passe incorrect.\nReponse : $raw")
                                raw.startsWith("stats_error") ->
                                    PiHoleTestState.Failure("Erreur stats : ${raw.substringAfter("|")}")
                                raw.startsWith("Exception") ->
                                    PiHoleTestState.Failure(raw)
                                else -> {
                                    val parsed = parseStatsRaw(raw)
                                    if (parsed != null) PiHoleTestState.Success(parsed)
                                    else PiHoleTestState.Failure("Reponse inattendue :\n$raw")
                                }
                            }
                        }
                    },
                    enabled  = formOk && testState !is PiHoleTestState.Testing,
                    modifier = Modifier.weight(1f)
                ) {
                    if (testState is PiHoleTestState.Testing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Test...")
                    } else {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Tester")
                    }
                }

                Button(
                    onClick  = { saveConfig() },
                    enabled  = formOk,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sauvegarder")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun parseStatsRaw(raw: String): PiHoleStats? {
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

@Composable
private fun CfgSectionLabel(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
    }
}

@Composable
private fun CfgInfoCard(icon: ImageVector, color: Color, text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(10.dp)) {
        Row(modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null,
                tint = color, modifier = Modifier.size(18.dp).padding(top = 1.dp))
            Text(text, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CfgTestResultCard(state: PiHoleTestState) {
    val containerColor: Color
    val borderColor   : Color
    val icon          : ImageVector
    val tint          : Color
    val title         : String
    val body          : String

    when (state) {
        is PiHoleTestState.Testing -> {
            containerColor = MaterialTheme.colorScheme.surfaceVariant
            borderColor    = MaterialTheme.colorScheme.outline.copy(0.3f)
            icon           = Icons.Default.HourglassEmpty
            tint           = MaterialTheme.colorScheme.onSurfaceVariant
            title          = "Test en cours..."
            body           = "Connexion a Pi-hole via SSH..."
        }
        is PiHoleTestState.Success -> {
            containerColor = CfgGreen.copy(alpha = 0.10f)
            borderColor    = CfgGreen.copy(alpha = 0.35f)
            icon           = Icons.Default.CheckCircle
            tint           = CfgGreen
            title          = "Connexion reussie"
            body           = buildString {
                append("Pi-hole ${if (state.stats.enabled) "actif" else "inactif"}")
                append(" * ${state.stats.dnsQueriesToday} requetes DNS aujourd'hui")
                append(" * ${state.stats.adsPercentage}% bloquees")
                append(" * ${state.stats.domainsBlocked} domaines dans la liste")
            }
        }
        is PiHoleTestState.Failure -> {
            containerColor = CfgRed.copy(alpha = 0.10f)
            borderColor    = CfgRed.copy(alpha  = 0.35f)
            icon           = Icons.Default.Error
            tint           = CfgRed
            title          = "Echec de la connexion"
            body           = state.reason
        }
        PiHoleTestState.Idle -> return
    }

    Card(colors = CardDefaults.cardColors(containerColor = containerColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)) {
        Row(modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                if (body.isNotBlank()) {
                    Text(body, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}