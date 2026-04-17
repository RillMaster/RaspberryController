@file:Suppress("UNUSED_VALUE")

package com.example.raspberrycontroller

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager
import android.app.AlertDialog
import android.os.Bundle
import android.os.Build
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.isSystemInDarkTheme
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
import org.json.JSONObject
import sh.calvin.reorderable.ReorderableColumn
import sh.calvin.reorderable.ReorderableItem
import java.net.URL

// ══════════════════════════════════════════════════════════════════════════════
//  Modèle de données pour les stats système
// ══════════════════════════════════════════════════════════════════════════════
data class SystemStats(
    val tempCelsius: Double,
    val cpuPercent: Int,
    val ramUsedMb: Int,
    val ramTotalMb: Int
)

suspend fun fetchSystemStats(settings: SettingsManager): SystemStats? {
    val cmd = """python3 -c "
import os
temp = int(open('/sys/class/thermal/thermal_zone0/temp').read()) / 1000.0
cpu = float(open('/proc/loadavg').read().split()[0])
mem = open('/proc/meminfo').read()
def mi(k): return int([l for l in mem.split('\n') if l.startswith(k)][0].split()[1])
total=mi('MemTotal'); free=mi('MemAvailable')
used=total-free
print(f'{temp:.1f},{cpu:.2f},{used//1024},{total//1024}')
""""
    val raw = SshClient.execute(
        settings.host, settings.port, settings.username, settings.password,
        cmd, settings.sshTimeoutMs
    )
    val parts = raw.trim().split(",")
    if (parts.size < 4) return null
    return try {
        SystemStats(
            tempCelsius = parts[0].toDouble(),
            cpuPercent  = (parts[1].toDouble() * 100).toInt().coerceIn(0, 100),
            ramUsedMb   = parts[2].toInt(),
            ramTotalMb  = parts[3].toInt()
        )
    } catch (_: Exception) { null }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MainActivity
// ══════════════════════════════════════════════════════════════════════════════
class MainActivity : FragmentActivity() {

    private val versionUrl =
        "https://raw.githubusercontent.com/RillMaster/RaspberryController/main/version.json"
    private val changelogUrl =
        "https://raw.githubusercontent.com/RillMaster/RaspberryController/main/changelog.txt"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current
            val settings = remember { SettingsManager(context) }
            var themePref by remember { mutableStateOf(settings.theme) }

            val darkTheme = when (themePref) {
                "light" -> false
                "dark"  -> true
                else    -> isSystemInDarkTheme()
            }

            RaspberryControllerTheme(darkTheme = darkTheme) {
                AppEntryPoint(
                    activity        = this@MainActivity,
                    settings        = settings,
                    onThemeChanged  = { newTheme ->
                        settings.theme = newTheme
                        themePref = newTheme
                    },
                    onAppReady = { checkForUpdates() }
                )
            }
        }
    }

    // ── Vérification des mises à jour ─────────────────────────────────────────
    private fun checkForUpdates() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val timestamp  = System.currentTimeMillis()
                val jsonRaw    = URL("$versionUrl?t=$timestamp").readText().trim()
                val jsonObject = JSONObject(jsonRaw)

                val latestVersionCode = jsonObject.getLong("versionCode")
                val latestVersionName = jsonObject.optString("versionName", "Inconnue")
                val apkUrl            = jsonObject.getString("url")

                val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageManager.getPackageInfo(packageName, 0).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
                }

                if (latestVersionCode > currentVersionCode) {
                    val changelog = try {
                        URL("$changelogUrl?t=$timestamp").readText().trim()
                    } catch (_: Exception) { "" }
                    withContext(Dispatchers.Main) {
                        showUpdateDialog(changelog, apkUrl, latestVersionName)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Vous êtes à jour (v$latestVersionName)",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Erreur mise à jour: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showUpdateDialog(changelog: String, downloadUrl: String, latestVersion: String) {
        val message = buildString {
            append("Une nouvelle version est disponible : v$latestVersion")
            if (changelog.isNotEmpty()) {
                append("\n\n📋 Nouveautés :\n$changelog")
            }
            append("\n\nVoulez-vous l'installer ?")
        }
        AlertDialog.Builder(this)
            .setTitle("Mise à jour disponible")
            .setMessage(message)
            .setPositiveButton("Mettre à jour") { _, _ ->
                UpdateManager(this).downloadAndInstall(downloadUrl)
            }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Point d'entrée de l'app
    // ══════════════════════════════════════════════════════════════════════════
    @Composable
    fun AppEntryPoint(
        activity       : FragmentActivity,
        settings       : SettingsManager,
        onThemeChanged : (String) -> Unit,
        onAppReady     : () -> Unit
    ) {
        var onboardingDone by remember { mutableStateOf(!settings.isFirstLaunch) }

        if (!onboardingDone) {
            OnboardingScreen(
                activity  = activity,
                settings  = settings,
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
                    onSuccess = { isAuthenticated = true },
                    onError   = { authError = it }
                )
            }
            BiometricLockScreen(
                error   = authError,
                onRetry = {
                    authError = null
                    BiometricHelper.authenticate(
                        activity  = activity,
                        onSuccess = { isAuthenticated = true },
                        onError   = { authError = it }
                    )
                }
            )
        } else {
            LaunchedEffect(Unit) { onAppReady() }
            MainApp(
                activity          = activity,
                settings          = settings,
                onThemeChanged    = onThemeChanged,
                onBiometricEnabled = {
                    isAuthenticated = false
                    authError = null
                }
            )
        }
    }

    // ── Écran de verrouillage biométrique ─────────────────────────────────────
    @Composable
    fun BiometricLockScreen(error: String?, onRetry: () -> Unit) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color    = MaterialTheme.colorScheme.background
        ) {
            Box(
                modifier        = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment  = Alignment.CenterHorizontally,
                    verticalArrangement  = Arrangement.spacedBy(16.dp),
                    modifier             = Modifier.padding(40.dp)
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

    // ── Navigation principale ─────────────────────────────────────────────────
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
                settings      = settings,
                onOpenSettings = { showSettings = true },
                onOpenTerminal = { showTerminal = true },
                onSshCommand   = { cmd, s, callback ->
                    activity.lifecycleScope.launch {
                        callback(
                            SshClient.execute(
                                s.host, s.port, s.username, s.password, cmd, s.sshTimeoutMs
                            )
                        )
                    }
                },
                onLed = { state, s, callback ->
                    activity.lifecycleScope.launch {
                        @Suppress("SpellCheckingInspection")
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

    // ── Helpers UI ────────────────────────────────────────────────────────────
    @Composable
    fun SectionTitle(text: String) {
        Text(
            text     = text,
            style    = MaterialTheme.typography.titleMedium,
            color    = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
        )
    }

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

    // ══════════════════════════════════════════════════════════════════════════
    //  Barre de statut système (temp + CPU + RAM)
    // ══════════════════════════════════════════════════════════════════════════
    @Composable
    fun SystemStatusBar(
        settings: SettingsManager,
        stats   : SystemStats?,
        loading : Boolean
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {

                Text(
                    text     = "${settings.username}@${settings.host}:${settings.port}",
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(10.dp))

                when {
                    loading -> Row(
                        verticalAlignment    = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            "Chargement...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    stats == null -> Text(
                        "⚠️ Impossible de lire les statistiques",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    else -> {
                        // ── Blocs de stats ────────────────────────────────────
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // Température
                            StatBlock(
                                emoji = tempEmoji(stats.tempCelsius),
                                value = "%.1f°C".format(stats.tempCelsius),
                                label = "Temp CPU",
                                color = tempColor(stats.tempCelsius)
                            )

                            VerticalDivider(
                                modifier = Modifier.height(48.dp),
                                color    = MaterialTheme.colorScheme.outlineVariant
                            )

                            // Charge CPU
                            val cpuColor = when {
                                stats.cpuPercent >= 80 -> Color(0xFFEF5350)
                                stats.cpuPercent >= 50 -> Color(0xFFFF9800)
                                else                   -> Color(0xFF66BB6A)
                            }
                            StatBlock(
                                emoji = "🖥️",
                                value = "${stats.cpuPercent}%",
                                label = "Charge CPU",
                                color = cpuColor
                            )

                            VerticalDivider(
                                modifier = Modifier.height(48.dp),
                                color    = MaterialTheme.colorScheme.outlineVariant
                            )

                            // RAM
                            val ramPercent = if (stats.ramTotalMb > 0)
                                (stats.ramUsedMb * 100 / stats.ramTotalMb) else 0
                            val ramColor = when {
                                ramPercent >= 85 -> Color(0xFFEF5350)
                                ramPercent >= 65 -> Color(0xFFFF9800)
                                else             -> Color(0xFF66BB6A)
                            }
                            StatBlock(
                                emoji = "🧠",
                                value = "${stats.ramUsedMb}/${stats.ramTotalMb} Mo",
                                label = "RAM ($ramPercent%)",
                                color = ramColor
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // ── Barres de progression ─────────────────────────────
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val cpuColor = when {
                                stats.cpuPercent >= 80 -> Color(0xFFEF5350)
                                stats.cpuPercent >= 50 -> Color(0xFFFF9800)
                                else                   -> Color(0xFF66BB6A)
                            }
                            LinearProgressIndicator(
                                progress   = { (stats.cpuPercent / 100f).coerceIn(0f, 1f) },
                                modifier   = Modifier.weight(1f).height(4.dp),
                                color      = cpuColor,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            val ramPct = if (stats.ramTotalMb > 0)
                                stats.ramUsedMb.toFloat() / stats.ramTotalMb else 0f
                            val ramBarColor = when {
                                (ramPct * 100).toInt() >= 85 -> Color(0xFFEF5350)
                                (ramPct * 100).toInt() >= 65 -> Color(0xFFFF9800)
                                else                         -> Color(0xFF66BB6A)
                            }
                            LinearProgressIndicator(
                                progress   = { ramPct.coerceIn(0f, 1f) },
                                modifier   = Modifier.weight(1f).height(4.dp),
                                color      = ramBarColor,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text  = "Mise à jour toutes les ${settings.tempRefreshMs / 1000} s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun StatBlock(emoji: String, value: String, label: String, color: Color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 20.sp)
            Text(
                text       = value,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color      = color
            )
            Text(
                text  = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Écran Paramètres
    // ══════════════════════════════════════════════════════════════════════════
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
        var port     by remember { mutableIntStateOf(settings.port) }
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
        var selectedRefresh by remember { mutableIntStateOf(settings.tempRefreshMs) }

        val timeoutOptions  = listOf(5000 to "5 s", 8000 to "8 s", 15000 to "15 s", 30000 to "30 s")
        var selectedTimeout by remember { mutableIntStateOf(settings.sshTimeoutMs) }

        var shortcuts      by remember { mutableStateOf(settings.shortcuts) }
        var showAddDialog  by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Paramètres") },
                    navigationIcon = {
                        if (settings.isConfigured()) {
                            IconButton(onClick = onSave) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Ajouter un raccourci")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── Connexion SSH ─────────────────────────────────────────────
                SectionTitle("Connexion SSH")
                OutlinedTextField(
                    value         = host,
                    onValueChange = { host = it },
                    label         = { Text("Adresse IP") },
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = port.toString(),
                    onValueChange = { port = it.toIntOrNull() ?: 22 },
                    label         = { Text("Port SSH") },
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = username,
                    onValueChange = { username = it },
                    label         = { Text("Nom d'utilisateur") },
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value                  = password,
                    onValueChange          = { password = it },
                    label                  = { Text("Mot de passe") },
                    visualTransformation   = PasswordVisualTransformation(),
                    modifier               = Modifier.fillMaxWidth()
                )

                // ── Timeout ───────────────────────────────────────────────────
                SectionTitle("Timeout SSH")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    timeoutOptions.forEach { (ms, label) ->
                        FilterChip(
                            selected  = selectedTimeout == ms,
                            onClick   = { selectedTimeout = ms; settings.sshTimeoutMs = ms },
                            label     = { Text(label) },
                            modifier  = Modifier.weight(1f)
                        )
                    }
                }

                // ── Thème ─────────────────────────────────────────────────────
                SectionTitle("Thème")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    themeOptions.forEach { (value, label) ->
                        FilterChip(
                            selected  = selectedTheme == value,
                            onClick   = { selectedTheme = value; onThemeChanged(value) },
                            label     = { Text(label) },
                            modifier  = Modifier.weight(1f)
                        )
                    }
                }

                // ── Sécurité ──────────────────────────────────────────────────
                SectionTitle("Sécurité")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Biométrie", modifier = Modifier.weight(1f))
                    Switch(
                        checked         = biometricEnabled,
                        onCheckedChange = {
                            biometricEnabled        = it
                            settings.biometricEnabled = it
                            if (it) onBiometricEnabled()
                        },
                        enabled = biometricAvailable
                    )
                }

                // ── Rafraîchissement ──────────────────────────────────────────
                SectionTitle("Rafraîchissement température")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    refreshOptions.forEach { (ms, label) ->
                        FilterChip(
                            selected  = selectedRefresh == ms,
                            onClick   = { selectedRefresh = ms; settings.tempRefreshMs = ms },
                            label     = { Text(label) },
                            modifier  = Modifier.weight(1f)
                        )
                    }
                }

                // ── Raccourcis avec Drag & Drop ───────────────────────────────
                SectionTitle("Raccourcis terminal")

                ReorderableColumn(
                    list                = shortcuts,
                    onSettle            = { fromIndex, toIndex ->
                        val updated = shortcuts.toMutableList().apply {
                            add(toIndex, removeAt(fromIndex))
                        }
                        shortcuts         = updated
                        settings.shortcuts = updated
                    },
                    modifier            = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) { index, shortcut, isDragging ->

                    key(shortcut.first) {
                        ReorderableItem {
                            val elevation by animateDpAsState(
                                targetValue = if (isDragging) 8.dp else 0.dp,
                                label       = "shortcut_elevation"
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors   = CardDefaults.cardColors(
                                    containerColor = if (isDragging)
                                        MaterialTheme.colorScheme.surface
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = elevation)
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
                                        modifier           = Modifier
                                            .draggableHandle()
                                            .padding(end = 12.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text       = shortcut.first,
                                            style      = MaterialTheme.typography.bodyMedium,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text       = shortcut.second,
                                            style      = MaterialTheme.typography.bodySmall,
                                            color      = MaterialTheme.colorScheme.primary,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    IconButton(onClick = {
                                        val updated = shortcuts.toMutableList().apply { removeAt(index) }
                                        shortcuts         = updated
                                        settings.shortcuts = updated
                                    }) {
                                        Icon(
                                            imageVector        = Icons.Default.Delete,
                                            contentDescription = "Supprimer",
                                            tint               = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Bouton Enregistrer ────────────────────────────────────────
                Button(
                    onClick  = {
                        settings.host     = host
                        settings.port     = port
                        settings.username = username
                        settings.password = password
                        onSave()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                ) {
                    Text("Enregistrer la configuration")
                }
            }
        }

        if (showAddDialog) {
            ShortcutDialog(
                initialLabel   = "",
                initialCommand = "",
                title          = "Ajouter",
                onConfirm      = { label, cmd ->
                    val updated        = shortcuts + Pair(label, cmd)
                    shortcuts          = updated
                    settings.shortcuts = updated
                    showAddDialog      = false
                },
                onDismiss = { showAddDialog = false }
            )
        }
    }

    // ── Dialog d'ajout de raccourci ───────────────────────────────────────────
    @Composable
    fun ShortcutDialog(
        initialLabel  : String,
        initialCommand: String,
        title         : String,
        onConfirm     : (String, String) -> Unit,
        onDismiss     : () -> Unit
    ) {
        var label   by remember { mutableStateOf(initialLabel) }
        var command by remember { mutableStateOf(initialCommand) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title            = { Text(title) },
            text             = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value         = label,
                        onValueChange = { label = it },
                        label         = { Text("Libellé du bouton") },
                        placeholder   = { Text("ex : reboot") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value         = command,
                        onValueChange = { command = it },
                        label         = { Text("Commande SSH") },
                        placeholder   = { Text("ex : sudo reboot") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        textStyle     = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick  = {
                        if (label.isNotEmpty() && command.isNotEmpty()) onConfirm(label, command)
                    },
                    enabled  = label.isNotEmpty() && command.isNotEmpty()
                ) { Text("Ajouter") }
            },
            dismissButton = {
                OutlinedButton(onClick = onDismiss) { Text("Annuler") }
            }
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Écran principal de contrôle
    // ══════════════════════════════════════════════════════════════════════════
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ControlScreen(
        settings      : SettingsManager,
        onOpenSettings: () -> Unit,
        onOpenTerminal: () -> Unit,
        onSshCommand  : (String, SettingsManager, (String) -> Unit) -> Unit,
        onLed         : (Boolean, SettingsManager, (String) -> Unit) -> Unit
    ) {
        var result  by remember { mutableStateOf("Prêt.") }
        var command by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }

        // ── Stats système (temp + CPU + RAM) ──────────────────────────────────
        var systemStats  by remember { mutableStateOf<SystemStats?>(null) }
        var statsLoading by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            while (true) {
                statsLoading = systemStats == null
                val stats    = fetchSystemStats(settings)
                systemStats  = stats
                statsLoading = false
                delay(settings.tempRefreshMs.toLong())
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title   = { Text("Raspberry Controller") },
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
                // ── Barre de statut système ───────────────────────────────────
                SystemStatusBar(
                    settings = settings,
                    stats    = systemStats,
                    loading  = statsLoading
                )

                // ── GPIO LED ──────────────────────────────────────────────────
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier            = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("GPIO - LED (pin 17)", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick  = {
                                    loading = true
                                    onLed(true, settings) { result = it; loading = false }
                                },
                                modifier = Modifier.weight(1f),
                                enabled  = !loading
                            ) { Text("Allumer") }
                            OutlinedButton(
                                onClick  = {
                                    loading = true
                                    onLed(false, settings) { result = it; loading = false }
                                },
                                modifier = Modifier.weight(1f),
                                enabled  = !loading
                            ) { Text("Éteindre") }
                        }
                    }
                }

                // ── Commande rapide ───────────────────────────────────────────
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier            = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Commande rapide", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value         = command,
                            onValueChange = { command = it },
                            label         = { Text("Ex : ls /home/pi") },
                            modifier      = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick  = {
                                loading = true
                                onSshCommand(command, settings) { result = it; loading = false }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled  = !loading && command.isNotEmpty()
                        ) { Text("Exécuter") }
                    }
                }

                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                // ── Résultat ──────────────────────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        text     = result,
                        modifier = Modifier.padding(16.dp),
                        style    = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}