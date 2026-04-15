package com.example.raspberrycontroller

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Couleurs ──────────────────────────────────────────────────────────────────
val TerminalGreen = Color(0xFF39FF14)
val TerminalBg    = Color(0xFF0D0D0D)

private val ANSI_COLORS = arrayOf(
    Color(0xFF2E3436), Color(0xFFCC0000), Color(0xFF4E9A06), Color(0xFFC4A000),
    Color(0xFF3465A4), Color(0xFF75507B), Color(0xFF06989A), Color(0xFFD3D7CF),
    Color(0xFF555753), Color(0xFFEF2929), Color(0xFF8AE234), Color(0xFFFCE94F),
    Color(0xFF729FCF), Color(0xFFAD7FA8), Color(0xFF34E2E2), Color(0xFFEEEEEC)
)
private val TERM_DEFAULT_FG = Color(0xFFCCCCCC)
private fun ansiIndex(n: Int): Color = ANSI_COLORS.getOrNull(n) ?: TERM_DEFAULT_FG
private fun ansi256(n: Int): Color = when {
    n < 16  -> ansiIndex(n)
    n < 232 -> {
        val i = n - 16
        Color(
            if (i / 36 == 0) 0 else 55 + (i / 36) * 40,
            if (i % 36 / 6 == 0) 0 else 55 + (i % 36 / 6) * 40,
            if (i % 6 == 0) 0 else 55 + (i % 6) * 40
        )
    }
    else -> (8 + (n - 232) * 10).let { Color(it, it, it) }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Émulateur VT100 / xterm-256color
// ══════════════════════════════════════════════════════════════════════════════
class TerminalEmulator(initialCols: Int = 80, initialRows: Int = 24) {

    data class Cell(
        val char: Char    = ' ',
        val fg  : Color   = TERM_DEFAULT_FG,
        val bg  : Color   = Color.Transparent,
        val bold: Boolean = false
    )

    var cols = initialCols; private set
    var rows = initialRows; private set
    private var grid = Array(rows) { Array(cols) { Cell() } }

    private val _scrollback = mutableListOf<AnnotatedString>()
    val scrollback: List<AnnotatedString> get() = _scrollback

    var cursorRow = 0; private set
    var cursorCol = 0; private set
    private var savedRow  = 0
    private var savedCol  = 0
    private var savedFg   : Color? = null
    private var savedBg   : Color? = null
    private var savedBold = false

    private var scrollTop = 0
    private var scrollBot = rows - 1

    private var currentFg: Color? = null
    private var currentBg: Color? = null
    private var bold      = false
    private var underline = false
    private var reverse   = false

    private var inEscape = false
    private var inCSI    = false
    private var inOSC    = false
    private val buf      = StringBuilder()

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        val newGrid = Array(newRows) { r ->
            Array(newCols) { c -> if (r < rows && c < cols) grid[r][c] else Cell() }
        }
        cols = newCols; rows = newRows; grid = newGrid
        scrollTop = 0; scrollBot = rows - 1
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
    }

    fun process(data: String) { for (c in data) processChar(c) }

    fun toScreenLines(showCursor: Boolean): List<AnnotatedString> =
        grid.mapIndexed { ri, row ->
            buildAnnotatedString {
                row.forEachIndexed { ci, cell ->
                    val isCur = showCursor && ri == cursorRow && ci == cursorCol
                    val fg = if (isCur) (if (cell.bg != Color.Transparent) cell.bg else TerminalBg) else cell.fg
                    val bg = if (isCur) cell.fg else (if (cell.bg == Color.Transparent) Color.Unspecified else cell.bg)
                    withStyle(SpanStyle(color = fg, background = bg,
                        fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal)) {
                        append(cell.char)
                    }
                }
            }
        }

    private fun processChar(c: Char) {
        when {
            inOSC    -> { if (c == '\u0007' || c == '\u001B') { inOSC = false; buf.clear() } }
            inCSI    -> {
                buf.append(c)
                if (c in '\u0040'..'\u007E') { handleCSI(buf.toString()); inCSI = false; buf.clear() }
            }
            inEscape -> {
                inEscape = false
                when (c) {
                    '[' -> { inCSI = true; buf.clear() }
                    ']' -> { inOSC = true; buf.clear() }
                    '7' -> saveCursor()
                    '8' -> restoreCursor()
                    'M' -> reverseIndex()
                    'c' -> fullReset()
                    'D' -> lineFeed()
                    'E' -> { cursorCol = 0; lineFeed() }
                    else -> {}
                }
            }
            c == '\u001B' -> inEscape = true
            c == '\r'     -> cursorCol = 0
            c == '\n'     -> lineFeed()
            c == '\u0008' -> if (cursorCol > 0) cursorCol--
            c == '\u007F' -> if (cursorCol > 0) cursorCol--
            c == '\u0009' -> {
                cursorCol = ((cursorCol / 8) + 1) * 8
                if (cursorCol >= cols) cursorCol = cols - 1
            }
            c == '\u0007' || c == '\u000E' || c == '\u000F' -> {}
            c.code >= 32  -> putChar(c)
            else          -> {}
        }
    }

    private fun lineFeed() { if (cursorRow >= scrollBot) scrollUp() else cursorRow++ }

    private fun putChar(c: Char) {
        if (cursorCol >= cols) { cursorCol = 0; lineFeed() }
        val fg = if (reverse) currentBg ?: Color.Transparent else currentFg ?: TERM_DEFAULT_FG
        val bg = if (reverse) currentFg ?: TERM_DEFAULT_FG   else currentBg ?: Color.Transparent
        grid[cursorRow][cursorCol] = Cell(c, fg, bg, bold)
        cursorCol++
    }

    private fun scrollUp() {
        if (scrollTop == 0) {
            _scrollback.add(renderRow(scrollTop))
            if (_scrollback.size > 5000) _scrollback.removeAt(0)
        }
        for (i in scrollTop until scrollBot) grid[i] = grid[i + 1]
        grid[scrollBot] = Array(cols) { Cell() }
    }

    private fun renderRow(ri: Int): AnnotatedString = buildAnnotatedString {
        grid[ri].forEach { cell ->
            withStyle(SpanStyle(color = cell.fg,
                background = if (cell.bg == Color.Transparent) Color.Unspecified else cell.bg,
                fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal)) {
                append(cell.char)
            }
        }
    }

    private fun reverseIndex() {
        if (cursorRow > scrollTop) cursorRow--
        else {
            for (i in scrollBot downTo scrollTop + 1) grid[i] = grid[i - 1]
            grid[scrollTop] = Array(cols) { Cell() }
        }
    }

    private fun clearRow(r: Int) { grid[r] = Array(cols) { Cell() } }
    private fun fillLine(r: Int, from: Int, to: Int) {
        for (i in from until minOf(to, cols)) grid[r][i] = Cell()
    }

    private fun handleCSI(seq: String) {
        if (seq.isEmpty()) return
        val cmd    = seq.last()
        val raw    = seq.dropLast(1)
        val pStr   = if (raw.firstOrNull() in listOf('?', '!', '>')) raw.drop(1) else raw
        val params = pStr.split(";").mapNotNull { it.toIntOrNull() }
        fun p(i: Int, d: Int = 0)  = params.getOrElse(i) { d }
        fun p1(i: Int, d: Int = 1) = params.getOrElse(i) { d }.let { if (it == 0) d else it }

        when (cmd) {
            'A'       -> cursorRow = maxOf(scrollTop, cursorRow - p1(0))
            'B'       -> cursorRow = minOf(scrollBot, cursorRow + p1(0))
            'C'       -> cursorCol = minOf(cols - 1, cursorCol + p1(0))
            'D'       -> cursorCol = maxOf(0, cursorCol - p1(0))
            'E'       -> { cursorCol = 0; cursorRow = minOf(rows - 1, cursorRow + p1(0)) }
            'F'       -> { cursorCol = 0; cursorRow = maxOf(0, cursorRow - p1(0)) }
            'G', '`'  -> cursorCol = (p1(0) - 1).coerceIn(0, cols - 1)
            'H', 'f'  -> { cursorRow = (p1(0) - 1).coerceIn(0, rows - 1); cursorCol = (p1(1) - 1).coerceIn(0, cols - 1) }
            'd'       -> cursorRow = (p1(0) - 1).coerceIn(0, rows - 1)
            'J'       -> when (p(0)) {
                0     -> { fillLine(cursorRow, cursorCol, cols); for (r in cursorRow + 1 until rows) clearRow(r) }
                1     -> { for (r in 0 until cursorRow) clearRow(r); fillLine(cursorRow, 0, cursorCol + 1) }
                2, 3  -> { for (r in 0 until rows) clearRow(r); cursorRow = 0; cursorCol = 0 }
            }
            'K'       -> when (p(0)) {
                0     -> fillLine(cursorRow, cursorCol, cols)
                1     -> fillLine(cursorRow, 0, cursorCol + 1)
                2     -> clearRow(cursorRow)
            }
            'L'       -> {
                val cnt = p1(0)
                for (i in scrollBot downTo cursorRow + cnt) grid[i] = grid[i - cnt]
                for (r in cursorRow until minOf(cursorRow + cnt, scrollBot + 1)) clearRow(r)
            }
            'M'       -> {
                val cnt     = p1(0)
                val safeEnd = (scrollBot - cnt).coerceAtLeast(cursorRow)
                for (i in cursorRow..safeEnd) grid[i] = grid[i + cnt]
                for (i in maxOf(cursorRow, scrollBot - cnt + 1)..scrollBot) clearRow(i)
            }
            'P'       -> {
                val cnt = p1(0); val row = grid[cursorRow]
                for (i in cursorCol until cols) row[i] = if (i + cnt < cols) row[i + cnt] else Cell()
            }
            '@'       -> {
                val cnt = p1(0); val row = grid[cursorRow]
                for (i in cols - 1 downTo cursorCol + cnt) row[i] = row[i - cnt]
                for (i in cursorCol until minOf(cursorCol + cnt, cols)) row[i] = Cell()
            }
            'X'       -> fillLine(cursorRow, cursorCol, cursorCol + p1(0))
            'S'       -> repeat(p1(0)) { scrollUp() }
            'T'       -> repeat(p1(0)) { reverseIndex() }
            'r'       -> {
                scrollTop = (p1(0) - 1).coerceIn(0, rows - 1)
                scrollBot = (p1(1, rows) - 1).coerceIn(scrollTop, rows - 1)
                cursorRow = 0; cursorCol = 0
            }
            'm'           -> handleSGR(params)
            's'           -> saveCursor()
            'u'           -> restoreCursor()
            'n', 'h', 'l' -> {}
            else          -> {}
        }
    }

    private fun handleSGR(codes: List<Int>) {
        if (codes.isEmpty()) { currentFg = null; currentBg = null; bold = false; underline = false; reverse = false; return }
        var i = 0
        while (i < codes.size) {
            when (val c = codes[i]) {
                0           -> { currentFg = null; currentBg = null; bold = false; underline = false; reverse = false }
                1           -> bold      = true
                2, 22       -> bold      = false
                4           -> underline = true
                24          -> underline = false
                7           -> reverse   = true
                27          -> reverse   = false
                in 30..37   -> currentFg = ansiIndex(c - 30 + if (bold) 8 else 0)
                in 90..97   -> currentFg = ansiIndex(c - 90 + 8)
                39          -> currentFg = null
                in 40..47   -> currentBg = ansiIndex(c - 40)
                in 100..107 -> currentBg = ansiIndex(c - 100 + 8)
                49          -> currentBg = null
                38          -> when {
                    i + 2 < codes.size && codes[i + 1] == 5 -> { currentFg = ansi256(codes[i + 2]); i += 2 }
                    i + 4 < codes.size && codes[i + 1] == 2 -> { currentFg = Color(codes[i + 2], codes[i + 3], codes[i + 4]); i += 4 }
                }
                48          -> when {
                    i + 2 < codes.size && codes[i + 1] == 5 -> { currentBg = ansi256(codes[i + 2]); i += 2 }
                    i + 4 < codes.size && codes[i + 1] == 2 -> { currentBg = Color(codes[i + 2], codes[i + 3], codes[i + 4]); i += 4 }
                }
            }
            i++
        }
    }

    private fun saveCursor()    { savedRow = cursorRow; savedCol = cursorCol; savedFg = currentFg; savedBg = currentBg; savedBold = bold }
    private fun restoreCursor() { cursorRow = savedRow; cursorCol = savedCol; currentFg = savedFg; currentBg = savedBg; bold = savedBold }
    private fun fullReset() {
        for (r in 0 until rows) clearRow(r)
        cursorRow = 0; cursorCol = 0; currentFg = null; currentBg = null
        bold = false; underline = false; reverse = false
        scrollTop = 0; scrollBot = rows - 1
        buf.clear(); inEscape = false; inCSI = false; inOSC = false
    }
}

// ── Touches spéciales ─────────────────────────────────────────────────────────
private val SPECIAL_KEYS = listOf(
    "Tab"   to "\u0009",
    "Esc"   to "\u001B",
    "↑"     to "\u001B[A",
    "↓"     to "\u001B[B",
    "←"     to "\u001B[D",
    "→"     to "\u001B[C",
    "Home"  to "\u001B[H",
    "End"   to "\u001B[F",
    "PgUp"  to "\u001B[5~",
    "PgDn"  to "\u001B[6~",
    "Ins"   to "\u001B[2~",
    "Del"   to "\u001B[3~",
    "F1"    to "\u001BOP",
    "F2"    to "\u001BOQ",
    "F3"    to "\u001BOR",
    "F4"    to "\u001BOS",
    "F5"    to "\u001B[15~",
    "F6"    to "\u001B[17~",
    "F7"    to "\u001B[18~",
    "F8"    to "\u001B[19~",
    "F9"    to "\u001B[20~",
    "F10"   to "\u001B[21~",
    "F11"   to "\u001B[23~",
    "F12"   to "\u001B[24~",
    "Enter" to "\r"
)

// ── Regex d'interception des éditeurs texte ───────────────────────────────────
private val EDITOR_CMD_REGEX = Regex(
    """^\s*(?:sudo\s+)?(?:nano|vi|vim)\s+(.+?)\s*$"""
)

// ══════════════════════════════════════════════════════════════════════════════
//  État de l'éditeur intégré
// ══════════════════════════════════════════════════════════════════════════════
private data class EditorState(
    val filePath      : String,
    val initialContent: String = "",
    val isLoading     : Boolean = true,
    val sessionId     : Long = System.currentTimeMillis()
)

// ══════════════════════════════════════════════════════════════════════════════
//  Composable principal
// ══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(settings: SettingsManager, onClose: () -> Unit) {
    val scope          = rememberCoroutineScope()
    val context        = LocalContext.current
    val view           = LocalView.current
    val focusRequester = remember { FocusRequester() }

    val ghost = "\u200B"

    fun forceShowKeyboard() {
        focusRequester.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view.post {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    var rawInput    by remember { mutableStateOf(TextFieldValue(ghost)) }
    var session     by remember { mutableStateOf<ShellSession?>(null) }
    var status      by remember { mutableStateOf("Connexion...") }
    var isConnected by remember { mutableStateOf(false) }

    var ctrlActive  by remember { mutableStateOf(false) }
    var altActive   by remember { mutableStateOf(false) }
    var isLandscape by remember { mutableStateOf(false) }
    var showBars    by remember { mutableStateOf(true) }
    var fontSize    by remember { mutableFloatStateOf(13f) }

    val emulator      = remember { TerminalEmulator(80, 24) }
    var renderTick    by remember { mutableIntStateOf(0) }
    var cursorVisible by remember { mutableStateOf(true) }
    var termCols      by remember { mutableIntStateOf(80) }
    var termRows      by remember { mutableIntStateOf(24) }

    // ── État éditeur intégré ──────────────────────────────────────────────────
    var editorState by remember { mutableStateOf<EditorState?>(null) }
    var typedLine   by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { while (true) { delay(530); cursorVisible = !cursorVisible } }

    val screenLines        = remember(renderTick, cursorVisible) {
        emulator.toScreenLines(showCursor = cursorVisible && isConnected)
    }
    val scrollbackSnapshot = remember(renderTick) { emulator.scrollback.toList() }
    val totalLines         = scrollbackSnapshot.size + screenLines.size
    val listState          = rememberLazyListState()

    LaunchedEffect(renderTick) {
        if (totalLines > 0) try { listState.animateScrollToItem(totalLines - 1) } catch (_: Exception) {}
    }

    LaunchedEffect(termCols, termRows) {
        emulator.resize(termCols, termRows)
        renderTick++
    }

    val shortcuts = remember { settings.shortcuts }

    fun toggleRotation() {
        val a = context as? Activity ?: return
        isLandscape = !isLandscape
        a.requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else             ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    DisposableEffect(Unit) {
        onDispose { (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // ── sendRaw avec interception éditeur ─────────────────────────────────────
    fun sendRaw(bytes: String) {
        when {
            bytes == "\r" -> {
                val cmd = typedLine.trim()
                typedLine = ""
                val match = EDITOR_CMD_REGEX.find(cmd)
                if (match != null && isConnected) {
                    val rawPath = match.groupValues[1].trim()
                    scope.launch { session?.sendRaw("\u0015") }
                    val openedAt = System.currentTimeMillis()
                    editorState = EditorState(filePath = rawPath, isLoading = true, sessionId = openedAt)
                    scope.launch {
                        val content = RemoteFileHelper.readFile(settings, rawPath)
                        editorState = EditorState(
                            filePath       = rawPath,
                            initialContent = content,
                            isLoading      = false,
                            sessionId      = openedAt
                        )
                    }
                } else {
                    scope.launch { session?.sendRaw(bytes) }
                }
            }
            bytes == "\u0008" || bytes == "\u007F" -> {
                if (typedLine.isNotEmpty()) typedLine = typedLine.dropLast(1)
                scope.launch { session?.sendRaw(bytes) }
            }
            bytes == "\u0015" || bytes == "\u0003" -> {
                typedLine = ""
                scope.launch { session?.sendRaw(bytes) }
            }
            bytes.startsWith("\u001B") -> {
                typedLine = ""
                scope.launch { session?.sendRaw(bytes) }
            }
            else -> {
                typedLine += bytes
                scope.launch { session?.sendRaw(bytes) }
            }
        }
    }

    // ── CORRECTION : sendCommand envoie directement sans passer par typedLine ─
    fun sendCommand(cmd: String) {
        if (cmd.isBlank() || !isConnected) return
        scope.launch { session?.sendRaw("$cmd\r") }
    }

    // Connexion SSH
    LaunchedEffect(Unit) {
        emulator.process("Connexion à ${settings.host}:${settings.port}...\r\n")
        renderTick++
        val result = SshClient.openShell(
            host = settings.host, port = settings.port,
            user = settings.username, password = settings.password
        )
        result.onSuccess { sh ->
            session = sh; status = "Connecté"; isConnected = true
            scope.launch(Dispatchers.IO) {
                val reader = sh.inputStream.bufferedReader(Charsets.UTF_8)
                val buffer = CharArray(4096)
                while (sh.isConnected) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    val text = String(buffer, 0, n)
                    withContext(Dispatchers.Main) { emulator.process(text); renderTick++ }
                }
                withContext(Dispatchers.Main) { status = "Déconnecté"; isConnected = false; onClose() }
            }
        }.onFailure { err ->
            emulator.process(SshClient.parseError(err) + "\r\n")
            emulator.process("Vérifiez les paramètres SSH.\r\n")
            renderTick++; status = "Erreur"; isConnected = false
        }
    }

    LaunchedEffect(Unit)   { delay(300); forceShowKeyboard() }
    DisposableEffect(Unit) { onDispose { session?.close() } }

    // ══════════════════════════════════════════════════════════════════════════
    //  Affichage : éditeur ou terminal
    // ══════════════════════════════════════════════════════════════════════════
    val currentEditorState = editorState
    if (currentEditorState != null) {
        key(currentEditorState.sessionId) {
            TextEditorScreen(
                filePath       = currentEditorState.filePath,
                initialContent = currentEditorState.initialContent,
                isLoading      = currentEditorState.isLoading,
                onSave         = { content ->
                    RemoteFileHelper.writeFile(settings, currentEditorState.filePath, content)
                },
                onClose        = {
                    editorState = null
                    scope.launch { delay(100); forceShowKeyboard() }
                }
            )
        }
    } else {
        // ── Terminal ──────────────────────────────────────────────────────────
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Terminal SSH", fontFamily = FontFamily.Monospace)
                            Text(status, style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    isConnected              -> TerminalGreen
                                    status == "Connexion..." -> Color.Yellow
                                    else                     -> Color.Red
                                })
                        }
                    },
                    actions = {
                        IconButton(onClick = { if (fontSize > 9f)  fontSize -= 1f }) {
                            Text("A−", color = TerminalGreen.copy(0.75f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        IconButton(onClick = { if (fontSize < 22f) fontSize += 1f }) {
                            Text("A+", color = TerminalGreen.copy(0.75f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        if (isLandscape) {
                            IconButton(onClick = { showBars = !showBars }) {
                                Icon(
                                    if (showBars) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Barres", tint = TerminalGreen.copy(0.75f)
                                )
                            }
                        }
                        IconButton(onClick = { toggleRotation() }) {
                            Icon(Icons.Default.ScreenRotation, contentDescription = "Rotation",
                                tint = if (isLandscape) TerminalGreen else TerminalGreen.copy(0.45f))
                        }
                        IconButton(onClick = { session?.close(); onClose() }) {
                            Icon(Icons.Default.Close, contentDescription = "Fermer", tint = TerminalGreen)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
                )
            },
            containerColor = TerminalBg
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(TerminalBg)
                    .imePadding()
            ) {
                // ── Zone terminal ─────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(TerminalBg)
                ) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val boxW    = maxWidth.value
                        val boxH    = maxHeight.value
                        val charW   = fontSize * 0.6f
                        val charH   = fontSize * 1.27f
                        val newCols = (boxW / charW).toInt().coerceIn(20, 250)
                        val newRows = (boxH / charH).toInt().coerceIn(5, 80)
                        LaunchedEffect(newCols, newRows) { termCols = newCols; termRows = newRows }
                    }

                    val hScroll = rememberScrollState()

                    BasicTextField(
                        value         = rawInput,
                        onValueChange = { nv ->
                            val old = rawInput.text
                            val new = nv.text
                            when {
                                new.length > old.length -> {
                                    val added = new.replace(ghost, "")
                                    if (added.isNotEmpty()) {
                                        when {
                                            ctrlActive -> {
                                                val ch   = added.last().lowercaseChar()
                                                val code = ch.code - 'a'.code + 1
                                                sendRaw(if (code in 1..26) code.toChar().toString() else ch.toString())
                                                ctrlActive = false
                                            }
                                            altActive -> {
                                                sendRaw("\u001B${added.last()}")
                                                altActive = false
                                            }
                                            else -> sendRaw(added)
                                        }
                                    }
                                    rawInput = TextFieldValue(ghost, selection = TextRange(ghost.length))
                                }
                                new.length < old.length -> {
                                    val count = (old.length - new.length).coerceAtLeast(1)
                                    repeat(count) { sendRaw("\u0008") }
                                    rawInput = TextFieldValue(ghost, selection = TextRange(ghost.length))
                                }
                            }
                        },
                        textStyle       = TextStyle(color = Color.Transparent, fontSize = 1.sp),
                        cursorBrush     = SolidColor(Color.Transparent),
                        modifier        = Modifier
                            .size(1.dp)
                            .align(Alignment.BottomStart)
                            .alpha(0f)
                            .focusRequester(focusRequester),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            sendRaw("\r")
                            rawInput = TextFieldValue(ghost, selection = TextRange(ghost.length))
                        }),
                        singleLine = true
                    )

                    SelectionContainer(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val isUp = event.changes.all { !it.pressed }
                                        val isLongPress = event.changes.any {
                                            it.uptimeMillis - it.previousUptimeMillis > 400
                                        }
                                        if (isUp && !isLongPress) {
                                            forceShowKeyboard()
                                        }
                                    }
                                }
                            }
                    ) {
                        LazyColumn(
                            state    = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(hScroll)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            items(scrollbackSnapshot) { line ->
                                Text(line, fontFamily = FontFamily.Monospace,
                                    fontSize = fontSize.sp, lineHeight = (fontSize * 1.27f).sp, softWrap = false)
                            }
                            items(screenLines) { line ->
                                Text(line, fontFamily = FontFamily.Monospace,
                                    fontSize = fontSize.sp, lineHeight = (fontSize * 1.27f).sp, softWrap = false)
                            }
                        }
                    }
                }

                // ── Barres de touches ─────────────────────────────────────────
                if (!isLandscape || showBars) {

                    HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 0.5.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF111111))
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        CompactModifierKey("Ctrl", ctrlActive, isConnected) {
                            ctrlActive = !ctrlActive; if (ctrlActive) altActive = false
                        }
                        CompactModifierKey("Alt", altActive, isConnected) {
                            altActive = !altActive; if (altActive) ctrlActive = false
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(20.dp)
                                .background(Color(0xFF333333))
                        )

                        SPECIAL_KEYS.forEach { (label, bytes) ->
                            CompactKey(label = label, enabled = isConnected) { sendRaw(bytes) }
                        }
                    }

                    if (shortcuts.isNotEmpty()) {
                        HorizontalDivider(color = Color(0xFF222222), thickness = 0.5.dp)
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0E0E0E))
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            items(shortcuts) { (label, cmd) ->
                                SuggestionChip(
                                    onClick  = { if (isConnected) sendCommand(cmd) },
                                    label    = { Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                                    colors   = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = Color(0xFF1A2A1A), labelColor = TerminalGreen),
                                    border   = SuggestionChipDefaults.suggestionChipBorder(
                                        enabled = true, borderColor = TerminalGreen.copy(0.3f)),
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                        }
                    }

                    if (ctrlActive || altActive) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1A1500))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text       = if (ctrlActive) "^ Ctrl actif — tapez une lettre"
                                else            "Alt actif — tapez une touche",
                                color      = Color(0xFFFCE94F),
                                fontSize   = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Touche compacte ───────────────────────────────────────────────────────────
@Composable
private fun CompactKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick        = { if (enabled) onClick() },
        enabled        = enabled,
        modifier       = Modifier.height(28.dp),
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
        colors         = ButtonDefaults.outlinedButtonColors(
            contentColor         = TerminalGreen,
            disabledContentColor = Color(0xFF3A3A3A)
        )
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

// ── Bouton modificateur collant compact ──────────────────────────────────────
@Composable
private fun CompactModifierKey(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (active) {
        Button(
            onClick        = onClick,
            enabled        = enabled,
            modifier       = Modifier.height(28.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            colors         = ButtonDefaults.buttonColors(
                containerColor = TerminalGreen,
                contentColor   = Color.Black
            )
        ) {
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(
            onClick        = onClick,
            enabled        = enabled,
            modifier       = Modifier.height(28.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            colors         = ButtonDefaults.outlinedButtonColors(
                contentColor         = TerminalGreen.copy(0.7f),
                disabledContentColor = Color(0xFF3A3A3A)
            )
        ) {
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}