package com.example.raspberrycontroller

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Palette éditeur (cohérente avec le terminal) ──────────────────────────────
private val EditorBg       = Color(0xFF0D0D0D)
private val EditorGutter   = Color(0xFF1A1A1A)
private val EditorLineNum  = Color(0xFF4A4A4A)
private val EditorCaret    = Color(0xFF39FF14)
private val EditorText     = Color(0xFFCCCCCC)
private val EditorBar      = Color(0xFF111111)
private val EditorDivider  = Color(0xFF2A2A2A)
private val EditorDirty    = Color(0xFFFCE94F)
private val EditorSaved    = Color(0xFF39FF14)
private val EditorError    = Color(0xFFEF2929)

// ══════════════════════════════════════════════════════════════════════════════
//  TextEditorScreen
//
//  filePath       — chemin absolu du fichier sur le serveur
//  initialContent — contenu lu via SSH (peut être vide si fichier nouveau)
//  isLoading      — true pendant le chargement du fichier
//  onSave         — suspend lambda : reçoit le contenu, retourne true si succès
//  onClose        — ferme l'éditeur et revient au terminal
// ══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    filePath      : String,
    initialContent: String,
    isLoading     : Boolean,
    onSave        : suspend (String) -> Boolean,
    onClose       : () -> Unit
) {
    val scope    = rememberCoroutineScope()
    val fileName = filePath.substringAfterLast('/')

    var textValue by remember(initialContent) {
        mutableStateOf(TextFieldValue(initialContent))
    }
    var isDirty          by remember { mutableStateOf(false) }
    var isSaving         by remember { mutableStateOf(false) }
    var saveMessage      by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var showDiscardDlg   by remember { mutableStateOf(false) }

    // ── Stats curseur ──────────────────────────────────────────────────────────
    val cursorPos = textValue.selection.start.coerceAtMost(textValue.text.length)
    val currentLine = remember(cursorPos, textValue.text) {
        textValue.text.substring(0, cursorPos).count { it == '\n' } + 1
    }
    val currentCol = remember(cursorPos, textValue.text) {
        cursorPos - (textValue.text.lastIndexOf('\n', cursorPos - 1) + 1) + 1
    }
    val lineCount = remember(textValue.text) {
        textValue.text.count { it == '\n' } + 1
    }

    // ── Sauvegarde ─────────────────────────────────────────────────────────────
    fun save() {
        if (isSaving) return
        scope.launch {
            isSaving = true
            val ok = onSave(textValue.text)
            isSaving = false
            saveMessage = if (ok) "✓ Sauvegardé" to true else "✗ Erreur de sauvegarde" to false
            if (ok) isDirty = false
            delay(2500)
            saveMessage = null
        }
    }

    // ── Dialog abandon ─────────────────────────────────────────────────────────
    if (showDiscardDlg) {
        AlertDialog(
            onDismissRequest = { showDiscardDlg = false },
            title            = { Text("Modifications non sauvegardées", fontFamily = FontFamily.Monospace) },
            text             = { Text("Fermer sans sauvegarder les modifications de « $fileName » ?", fontFamily = FontFamily.Monospace) },
            confirmButton    = {
                TextButton(onClick = { showDiscardDlg = false; onClose() }) {
                    Text("Fermer quand même", color = EditorError, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDlg = false }) {
                    Text("Annuler", color = EditorCaret, fontFamily = FontFamily.Monospace)
                }
            },
            containerColor = Color(0xFF1A1A1A),
            titleContentColor = EditorText,
            textContentColor  = EditorText.copy(0.8f)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (isDirty) showDiscardDlg = true else onClose() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = EditorCaret)
                    }
                },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text       = fileName,
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 15.sp,
                                color      = EditorText
                            )
                            if (isDirty) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "●",
                                    color      = EditorDirty,
                                    fontSize   = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        Text(
                            text       = filePath,
                            style      = MaterialTheme.typography.labelSmall,
                            color      = EditorLineNum,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                actions = {
                    // Message save/erreur
                    saveMessage?.let { (msg, ok) ->
                        Text(
                            text       = msg,
                            color      = if (ok) EditorSaved else EditorError,
                            fontSize   = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier   = Modifier.padding(end = 8.dp)
                        )
                    }
                    // Bouton sauvegarder
                    IconButton(
                        onClick  = ::save,
                        enabled  = !isSaving && !isLoading && isDirty
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier  = Modifier.size(18.dp),
                                color     = EditorCaret,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = "Sauvegarder",
                                tint = if (isDirty) EditorCaret else EditorLineNum
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = EditorBar)
            )
        },
        bottomBar = {
            // Barre de statut — ligne / col / total lignes
            HorizontalDivider(color = EditorDivider, thickness = 0.5.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(EditorBar)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    text       = "Ln $currentLine, Col $currentCol",
                    color      = EditorLineNum,
                    fontSize   = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text       = "$lineCount ligne${if (lineCount > 1) "s" else ""}  •  UTF-8",
                    color      = EditorLineNum,
                    fontSize   = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        containerColor = EditorBg
    ) { padding ->

        if (isLoading) {
            // ── Chargement ───────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(EditorBg),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = EditorCaret)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Chargement de $fileName…",
                        color      = EditorLineNum,
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 12.sp
                    )
                }
            }
        } else {
            // ── Éditeur ──────────────────────────────────────────────────────
            val hScroll = rememberScrollState()
            val vScroll = rememberScrollState()

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(EditorBg)
                    .imePadding()
            ) {
                // ── Gouttière numéros de lignes ───────────────────────────────
                val lines = remember(textValue.text) {
                    textValue.text.split('\n')
                }
                val gutterWidth = when {
                    lineCount < 100   -> 36.dp
                    lineCount < 1000  -> 46.dp
                    else              -> 56.dp
                }

                Box(
                    modifier = Modifier
                        .width(gutterWidth)
                        .fillMaxHeight()
                        .background(EditorGutter)
                        .verticalScroll(vScroll)
                        .padding(end = 6.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                        lines.forEachIndexed { idx, _ ->
                            val lineNum = idx + 1
                            Text(
                                text       = "$lineNum",
                                color      = if (lineNum == currentLine) EditorCaret.copy(0.7f) else EditorLineNum,
                                fontSize   = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = (12 * 1.6f).sp
                            )
                        }
                    }
                }

                // Séparateur gouttière / éditeur
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(EditorDivider))

                // ── Champ de texte ────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(vScroll)
                        .horizontalScroll(hScroll)
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    BasicTextField(
                        value         = textValue,
                        onValueChange = { nv ->
                            textValue = nv
                            if (!isDirty && nv.text != initialContent) isDirty = true
                        },
                        textStyle     = TextStyle(
                            color      = EditorText,
                            fontSize   = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight  = (13 * 1.6f).sp
                        ),
                        cursorBrush = SolidColor(EditorCaret),
                        modifier    = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            if (textValue.text.isEmpty()) {
                                Text(
                                    "Fichier vide — commencez à taper…",
                                    color      = EditorLineNum,
                                    fontSize   = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Helpers SSH pour lire / écrire un fichier distant
// ══════════════════════════════════════════════════════════════════════════════
object RemoteFileHelper {

    /** Lit un fichier distant via SSH exec, retourne le contenu ou "" si absent. */
    suspend fun readFile(settings: SettingsManager, filePath: String): String =
        SshClient.execute(
            host     = settings.host,
            port     = settings.port,
            user     = settings.username,
            password = settings.password,
            command  = "cat \"$filePath\" 2>/dev/null || true"
        )

    /**
     * Écrit un fichier distant en encodant le contenu en base64 pour éviter
     * tout problème de caractères spéciaux / apostrophes / sauts de ligne.
     * Retourne true si succès.
     */
    suspend fun writeFile(settings: SettingsManager, filePath: String, content: String): Boolean {
        val encoded = Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        // Écriture atomique : on écrit dans un temp puis on déplace
        val tmpPath = "/tmp/.editor_${System.currentTimeMillis()}"
        val cmd = "printf '%s' '$encoded' | base64 -d > \"$tmpPath\" && mv \"$tmpPath\" \"$filePath\""
        val result = SshClient.execute(
            host     = settings.host,
            port     = settings.port,
            user     = settings.username,
            password = settings.password,
            command  = cmd
        )
        return !result.startsWith("[err]")
    }
}