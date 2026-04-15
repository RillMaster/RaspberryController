package com.example.raspberrycontroller

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.raspberrycontroller.ui.theme.RaspberryControllerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.net.URL

class MainActivity : FragmentActivity() {

    private lateinit var easterEgg: EasterEggManager
    // URL du fichier texte contenant uniquement le numéro de version (ex: 3)
    private val versionUrl = "https://raw.githubusercontent.com/RillMaster/RaspberryController/main/version.txt"
    private val apkUrl = "https://github.com/RillMaster/RaspberryController/releases/download/RaspbayriPi/app-debug.apk"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        easterEgg = EasterEggManager(this) {
            startActivity(Intent(this, FakeCrashActivity::class.java))
        }

        setContent {
            val context   = LocalContext.current
            val settings  = remember { SettingsManager(context) }
            var themePref by remember { mutableStateOf(settings.theme) }

            val darkTheme = when (themePref) {
                "light" -> false
                "dark"  -> true
                else    -> isSystemInDarkTheme()
            }

            RaspberryControllerTheme(darkTheme = darkTheme) {
                AppEntryPoint(
                    activity       = this@MainActivity,
                    settings       = settings,
                    onThemeChanged = { newTheme ->
                        settings.theme = newTheme
                        themePref      = newTheme
                    },
                    onAuthSuccess = {
                        // ✅ On vérifie la mise à jour seulement après succès biométrique
                        checkForUpdates()
                    }
                )
            }
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Récupère le code de version distant sur GitHub
                val latestVersionCode = URL(versionUrl).readText().trim().toInt()
                // Récupère le code de version actuel de l'application
                val currentVersionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode

                // Si la version distante est supérieure, on propose la mise à jour
                if (latestVersionCode > currentVersionCode) {
                    withContext(Dispatchers.Main) {
                        showUpdateDialog()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showUpdateDialog() {
        AlertDialog.Builder(this)
            .setTitle("Mise à jour disponible")
            .setMessage("Une nouvelle version de l'application est disponible. Voulez-vous l'installer ?")
            .setPositiveButton("Mettre à jour") { _, _ ->
                val updateManager = UpdateManager(this)
                updateManager.downloadAndInstall(apkUrl)
            }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        easterEgg.start()
    }

    override fun onPause() {
        super.onPause()
        easterEgg.stop()
    }
}

@Composable
fun AppEntryPoint(
    activity      : FragmentActivity,
    settings      : SettingsManager,
    onThemeChanged: (String) -> Unit,
    onAuthSuccess : () -> Unit
) {
    var onboardingDone by remember { mutableStateOf(!settings.isFirstLaunch) }

    if (!onboardingDone) {
        OnboardingScreen(
            activity   = activity,
            settings   = settings,
            onFinished = { onboardingDone = true }
        )
        return
    }

    var isAuthenticated by remember { mutableStateOf(!settings.biometricEnabled) }
    var authError       by remember { mutableStateOf<String?>(null) }

    if (!isAuthenticated) {
        LaunchedEffect(Unit) {
            BiometricHelper.authenticate(
                activity  = activity,
                onSuccess = {
                    isAuthenticated = true
                    onAuthSuccess() // ✅ Signalement du succès pour la mise à jour
                },
                onError   = { authError = it }
            )
        }
        BiometricLockScreen(
            error   = authError,
            onRetry = {
                authError = null
                BiometricHelper.authenticate(
                    activity  = activity,
                    onSuccess = {
                        isAuthenticated = true
                        onAuthSuccess()
                    },
                    onError   = { authError = it }
                )
            }
        )
    } else {
        MainApp(
            activity           = activity,
            settings           = settings,
            onThemeChanged     = onThemeChanged,
            onBiometricEnabled = {
                isAuthenticated = false
                authError       = null
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Écran de verrouillage biométrique
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun BiometricLockScreen(error: String?, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color    = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier         = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier            = Modifier.padding(40.dp)
            ) {
                Text("🔒", fontSize = 56.sp)
                Text("RaspberryController", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text  = "Authentification requise pour accéder à l'application.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (error != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text     = error,
                            modifier = Modifier.padding(12.dp),
                            color    = MaterialTheme.colorScheme.onErrorContainer,
                            style    = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text("Réessayer")
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Application principale
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MainApp(
    activity          : FragmentActivity,
    settings          : SettingsManager,
    onThemeChanged    : (String) -> Unit,
    onBiometricEnabled: () -> Unit
) {
    var showSettings by remember { mutableStateOf(!settings.isConfigured()) }
    var showTerminal by remember { mutableStateOf(false) }

    when {
        showSettings -> SettingsScreen(
            settings           = settings,
            activity           = activity,
            onThemeChanged     = onThemeChanged,
            onBiometricEnabled = onBiometricEnabled,
            onSave             = { showSettings = false }
        )
        showTerminal -> TerminalScreen(
            settings = settings,
            onClose  = { showTerminal = false }
        )
        else -> ControlScreen(
            settings       = settings,
            onOpenSettings = { showSettings = true },
            onOpenTerminal = { showTerminal = true },
            onSshCommand   = { cmd, s, callback ->
                activity.lifecycleScope.launch {
                    callback(SshClient.execute(s.host, s.port, s.username, s.password, cmd, s.sshTimeoutMs))
                }
            },
            onGetTempLive = { s ->
                val raw = SshClient.execute(
                    s.host, s.port, s.username, s.password,
                    "cat /sys/class/thermal/thermal_zone0/temp",
                    s.sshTimeoutMs
                )
                val isSshError = raw.startsWith("❌") || raw.startsWith("🌐") ||
                        raw.startsWith("⏱️") || raw.startsWith("🚫") ||
                        raw.startsWith("📡") || raw.startsWith("🔌") ||
                        raw.startsWith("⚠️") || raw.startsWith("[err]")
                if (isSshError || raw.isEmpty()) null
                else {
                    val millis = raw.trim().toIntOrNull()
                    if (millis != null) (millis / 1000.0).toString() else null
                }
            },
            onLed = { state, s, callback ->
                activity.lifecycleScope.launch {
                    val cmd = if (state)
                        "python3 -c \"import RPi.GPIO as G; G.setmode(G.BCM); G.setup(17,G.OUT); G.output(17,True)\""
                    else
                        "python3 -c \"import RPi.GPIO as G; G.setmode(G.BCM); G.setup(17,G.OUT); G.output(17,False)\""
                    SshClient.execute(s.host, s.port, s.username, s.password, cmd)
                    callback(if (state) "💡 LED allumée" else "💡 LED éteinte")
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Composants communs
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SectionTitle(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.titleMedium,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Paramètres
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings          : SettingsManager,
    activity          : FragmentActivity,
    onThemeChanged    : (String) -> Unit,
    onBiometricEnabled: () -> Unit,
    onSave            : () -> Unit
) {
    var host     by remember { mutableStateOf(settings.host) }
    var port     by remember { mutableStateOf(settings.port.toString()) }
    var username by remember { mutableStateOf(settings.username) }
    var password by remember { mutableStateOf(settings.password) }

    var biometricEnabled by remember { mutableStateOf(settings.biometricEnabled) }
    val biometricAvailable = remember {
        BiometricManager.from(activity)
            .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }

    val themeOptions   = listOf("system" to "Système", "light" to "Clair", "dark" to "Sombre")
    var selectedTheme  by remember { mutableStateOf(settings.theme) }

    val refreshOptions  = listOf(1000 to "1 s", 2000 to "2 s", 5000 to "5 s", 10000 to "10 s")
    var selectedRefresh by remember { mutableStateOf(settings.tempRefreshMs) }

    val timeoutOptions  = listOf(5000 to "5 s", 8000 to "8 s", 15000 to "15 s", 30000 to "30 s")
    var selectedTimeout by remember { mutableStateOf(settings.sshTimeoutMs) }

    var shortcuts     by remember { mutableStateOf(settings.shortcuts) }
    var showAddDialog by remember { mutableStateOf(false) }

    val lazyListState    = androidx.compose.foundation.lazy.rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIdx = from.index - SHORTCUTS_START_INDEX
        val toIdx   = to.index   - SHORTCUTS_START_INDEX
        if (fromIdx in shortcuts.indices && toIdx in shortcuts.indices) {
            val updated = shortcuts.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
            shortcuts          = updated
            settings.shortcuts = updated
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = {
                    if (settings.isConfigured()) {
                        IconButton(onClick = onSave) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                        }
                    }
                }
            )
        }
    ) { padding ->
        androidx.compose.foundation.lazy.LazyColumn(
            state   = lazyListState,
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { SectionTitle("Connexion SSH") }
            item {
                OutlinedTextField(value = host, onValueChange = { host = it },
                    label = { Text("Adresse IP du Raspberry Pi") },
                    placeholder = { Text("ex : 192.168.1.42") },
                    modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(value = port, onValueChange = { port = it },
                    label = { Text("Port SSH") }, placeholder = { Text("22") },
                    modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(value = username, onValueChange = { username = it },
                    label = { Text("Nom d'utilisateur") }, placeholder = { Text("pi") },
                    modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(value = password, onValueChange = { password = it },
                    label = { Text("Mot de passe") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            item { SectionTitle("Timeout de connexion SSH") }
            item {
                Text("Temps maximum d'attente avant d'abandonner la connexion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    timeoutOptions.forEach { (ms, label) ->
                        FilterChip(
                            selected = selectedTimeout == ms,
                            onClick  = { selectedTimeout = ms; settings.sshTimeoutMs = ms },
                            label    = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            item { SectionTitle("Thème") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    themeOptions.forEach { (value, label) ->
                        FilterChip(
                            selected = selectedTheme == value,
                            onClick  = { selectedTheme = value; onThemeChanged(value) },
                            label    = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            item { SectionTitle("Sécurité") }
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Déverrouillage biométrique", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text  = if (biometricAvailable) "Empreinte digitale / reconnaissance faciale"
                            else "Non disponible sur cet appareil",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (biometricAvailable) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                        )
                    }
                    Switch(
                        checked         = biometricEnabled,
                        onCheckedChange = { enabled ->
                            if (!biometricAvailable && enabled) return@Switch
                            biometricEnabled = enabled
                            settings.biometricEnabled = enabled
                            if (enabled) onBiometricEnabled()
                        },
                        enabled = biometricAvailable
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            item { SectionTitle("Rafraîchissement de la température") }
            item {
                Text("Fréquence de mise à jour de la température CPU.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    refreshOptions.forEach { (ms, label) ->
                        FilterChip(
                            selected = selectedRefresh == ms,
                            onClick  = { selectedRefresh = ms; settings.tempRefreshMs = ms },
                            label    = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            item { SectionTitle("Raccourcis terminal") }
            item {
                Text("Ces boutons apparaissent dans la barre du terminal pour envoyer une commande en un tap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text("Maintenez ≡ pour réordonner.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            items(
                count = shortcuts.size,
                key   = { i -> "sc_${shortcuts[i].first}_${shortcuts[i].second}" }
            ) { index ->
                val (label, cmd) = shortcuts[index]
                ReorderableItem(reorderableState, key = "sc_${label}_${cmd}") { isDragging ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { shadowElevation = if (isDragging) 12f else 0f },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDragging)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier          = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector        = Icons.Default.DragHandle,
                                contentDescription = "Déplacer",
                                tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier           = Modifier
                                    .size(24.dp)
                                    .draggableHandle()
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace)
                                Text(cmd, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace)
                            }
                            IconButton(onClick = {
                                val updated = shortcuts.toMutableList().also { it.removeAt(index) }
                                shortcuts = updated; settings.shortcuts = updated
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Supprimer",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            item {
                OutlinedButton(onClick = { showAddDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ajouter un raccourci")
                }
            }
            item {
                TextButton(
                    onClick  = { val d = settings.defaultShortcuts(); shortcuts = d; settings.shortcuts = d },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Restaurer les raccourcis par défaut") }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            item {
                Button(
                    onClick = {
                        settings.host     = host
                        settings.port     = port.toIntOrNull() ?: 22
                        settings.username = username
                        settings.password = password
                        onSave()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled  = host.isNotEmpty() && username.isNotEmpty()
                ) { Text("Enregistrer") }
            }
        }
    }

    if (showAddDialog) {
        ShortcutDialog(
            initialLabel   = "",
            initialCommand = "",
            title          = "Nouveau raccourci",
            onConfirm      = { label, cmd ->
                val updated = shortcuts + Pair(label, cmd)
                shortcuts = updated; settings.shortcuts = updated; showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

private const val SHORTCUTS_START_INDEX = 23

@Composable
fun ShortcutDialog(
    initialLabel: String,
    initialCommand: String,
    title: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var label   by remember { mutableStateOf(initialLabel) }
    var command by remember { mutableStateOf(initialCommand) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text(title) },
        text    = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it },
                    label = { Text("Libellé du bouton") }, placeholder = { Text("ex : reboot") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = command, onValueChange = { command = it },
                    label = { Text("Commande SSH") }, placeholder = { Text("ex : sudo reboot") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
            }
        },
        confirmButton = {
            Button(
                onClick = { if (label.isNotEmpty() && command.isNotEmpty()) onConfirm(label, command) },
                enabled = label.isNotEmpty() && command.isNotEmpty()
            ) { Text("Ajouter") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Couleur / emoji de la température
// ─────────────────────────────────────────────────────────────────────────────
private fun tempColor(celsius: Double): Color = when {
    celsius >= 75.0 -> Color(0xFFEF5350)
    celsius >= 60.0 -> Color(0xFFFF9800)
    celsius >= 45.0 -> Color(0xFFFFEB3B)
    else            -> Color(0xFF66BB6A)
}

private fun tempEmoji(celsius: Double): String = when {
    celsius >= 75.0 -> "🔥"
    celsius >= 60.0 -> "♨️"
    celsius >= 45.0 -> "🌡️"
    else            -> "❄️"
}

// ─────────────────────────────────────────────────────────────────────────────
// Écran principal de contrôle
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(
    settings      : SettingsManager,
    onOpenSettings: () -> Unit,
    onOpenTerminal: () -> Unit,
    onSshCommand  : (String, SettingsManager, (String) -> Unit) -> Unit,
    onGetTempLive : suspend (SettingsManager) -> String?,
    onLed         : (Boolean, SettingsManager, (String) -> Unit) -> Unit
) {
    var result  by remember { mutableStateOf("Prêt.") }
    var command by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    var cpuTempStr  by remember { mutableStateOf<String?>(null) }
    var tempLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            tempLoading = cpuTempStr == null
            val value  = onGetTempLive(settings)
            cpuTempStr = value ?: ""
            tempLoading = false
            delay(settings.tempRefreshMs.toLong())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Raspberry Controller") },
                actions = {
                    IconButton(onClick = onOpenTerminal) {
                        Icon(Icons.Default.Terminal, contentDescription = "Terminal")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Paramètres")
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text     = "Connecté à ${settings.host}:${settings.port} (${settings.username})",
                    modifier = Modifier.padding(12.dp),
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.primary
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier            = Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Température CPU", style = MaterialTheme.typography.titleMedium)
                    when {
                        tempLoading -> Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier              = Modifier.padding(vertical = 6.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text("Lecture en cours…", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        cpuTempStr == "" -> Text("⚠️ Impossible de lire la température",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error)
                        else -> {
                            val celsius = cpuTempStr!!.replace(",", ".").toDoubleOrNull() ?: 0.0
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(text = tempEmoji(celsius), fontSize = 28.sp)
                                Text(text = "%.1f".format(celsius), fontSize = 42.sp,
                                    fontWeight = FontWeight.Bold, color = tempColor(celsius), lineHeight = 44.sp)
                                Text(text = "°C", fontSize = 22.sp,
                                    color = tempColor(celsius).copy(alpha = 0.75f),
                                    modifier = Modifier.padding(bottom = 5.dp))
                            }
                            Text(
                                text  = "Mise à jour toutes les ${settings.tempRefreshMs / 1000} s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("GPIO - LED (pin 17)", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { loading = true; onLed(true, settings) { result = it; loading = false } },
                            modifier = Modifier.weight(1f), enabled = !loading) { Text("Allumer") }
                        OutlinedButton(onClick = { loading = true; onLed(false, settings) { result = it; loading = false } },
                            modifier = Modifier.weight(1f), enabled = !loading) { Text("Éteindre") }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Commande rapide", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = command, onValueChange = { command = it },
                        label = { Text("Ex : ls /home/pi") }, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick  = { loading = true; onSshCommand(command, settings) { result = it; loading = false } },
                        modifier = Modifier.fillMaxWidth(),
                        enabled  = !loading && command.isNotEmpty()
                    ) { Text("Exécuter") }
                }
            }

            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Text(text = result, modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}