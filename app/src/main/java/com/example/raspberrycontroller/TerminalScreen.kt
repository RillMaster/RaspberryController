package com.example.raspberrycontroller

import android.app.Activity
import android.content.Context
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

// ── Palette de couleurs pour les raccourcis ───────────────────────────────────
private val SHORTCUT_COLORS = listOf(
    Color(0xFF39FF14), Color(0xFF00BFFF), Color(0xFFFF6B6B), Color(0xFFFFD93D),
    Color(0xFFFF8C42), Color(0xFFB388FF), Color(0xFF4DD0E1), Color(0xFFFF80AB),
    Color(0xFF69F0AE), Color(0xFFFFAB40),
)
private fun shortcutColor(index: Int): Color = SHORTCUT_COLORS[index % SHORTCUT_COLORS.size]

// ══════════════════════════════════════════════════════════════════════════════
//  Coloration syntaxique de l'output
// ══════════════════════════════════════════════════════════════════════════════
private val ERROR_REGEX   = Regex("""(?i)\b(error|fail|failed|failure|denied|fatal|not found|permission denied|command not found|no such file|traceback|exception|critical)\b""")
private val SUCCESS_REGEX = Regex("""(?i)\b(success|succeeded|ok|done|complete|completed|saved|installed|started|active|running|enabled)\b""")
private val WARNING_REGEX = Regex("""(?i)\b(warn|warning|deprecated|caution|notice)\b""")

private val OUTPUT_ERROR_COLOR   = Color(0xFFFF5555)
private val OUTPUT_SUCCESS_COLOR = Color(0xFF50FA7B)
private val OUTPUT_WARNING_COLOR = Color(0xFFFFD93D)

/** Retourne une couleur de teinte si la ligne brute correspond à un pattern, sinon null */
fun syntaxTintForLine(rawText: String): Color? = when {
    ERROR_REGEX.containsMatchIn(rawText)   -> OUTPUT_ERROR_COLOR
    SUCCESS_REGEX.containsMatchIn(rawText) -> OUTPUT_SUCCESS_COLOR
    WARNING_REGEX.containsMatchIn(rawText) -> OUTPUT_WARNING_COLOR
    else                                   -> null
}

// ══════════════════════════════════════════════════════════════════════════════
//  Historique des commandes
// ══════════════════════════════════════════════════════════════════════════════
class CommandHistory(private val maxSize: Int = 100) {
    private val _entries = mutableListOf<String>()
    val entries: List<String> get() = _entries.toList()

    private var browseIndex = -1  // -1 = position courante (pas en navigation)

    fun add(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isBlank()) return
        _entries.remove(trimmed)          // déduplique
        _entries.add(0, trimmed)          // plus récent en tête
        if (_entries.size > maxSize) _entries.removeAt(_entries.size - 1)
        browseIndex = -1
    }

    fun reset() { browseIndex = -1 }

    /** Remonte dans l'historique (touche ↑). Retourne la commande ou null. */
    fun goUp(): String? {
        if (_entries.isEmpty()) return null
        browseIndex = (browseIndex + 1).coerceAtMost(_entries.size - 1)
        return _entries[browseIndex]
    }

    /** Descend dans l'historique (touche ↓). Retourne la commande, ou chaîne vide si retour au présent. */
    fun goDown(): String {
        if (browseIndex <= 0) { browseIndex = -1; return "" }
        browseIndex--
        return _entries[browseIndex]
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Autocomplétion
// ══════════════════════════════════════════════════════════════════════════════
private val COMMON_COMMANDS = listOf(
    "ls", "ls -la", "ls -lh", "cd", "cd ..", "cd ~", "pwd",
    "cat", "nano", "vi", "vim", "less", "more", "tail -f", "tail -n",
    "grep", "grep -r", "grep -i", "find", "find . -name",
    "sudo", "sudo apt update", "sudo apt upgrade", "sudo apt install",
    "apt list --installed", "dpkg -l",
    "systemctl status", "systemctl start", "systemctl stop", "systemctl restart",
    "systemctl enable", "systemctl disable", "journalctl -u", "journalctl -f",
    "ps aux", "ps aux | grep", "kill", "killall", "top", "htop",
    "df -h", "du -sh", "free -h", "uptime", "uname -a",
    "ip addr", "ip route", "ping", "curl", "wget",
    "git status", "git log", "git pull", "git push", "git add", "git commit -m",
    "git diff", "git branch", "git checkout", "git stash",
    "python3", "python3 -m", "pip3 install", "pip3 list",
    "chmod", "chown", "mkdir", "mkdir -p", "rm", "rm -rf", "mv", "cp", "cp -r",
    "echo", "export", "source", "which", "man", "history", "clear",
    "tar -czf", "tar -xzf", "zip", "unzip",
    "ssh", "scp", "rsync",
    "docker ps", "docker images", "docker run", "docker stop", "docker logs",
    "docker-compose up", "docker-compose down",
    "crontab -e", "crontab -l",
    "passwd", "whoami", "id", "groups",
)

fun computeSuggestions(partial: String, history: List<String>): List<String> {
    if (partial.length < 2) return emptyList()
    val lower = partial.lowercase()
    val fromHistory = history.filter { it.lowercase().startsWith(lower) }
    val fromCommon  = COMMON_COMMANDS.filter { it.lowercase().startsWith(lower) && !fromHistory.contains(it) }
    return (fromHistory + fromCommon).take(8)
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

    // Raw text scrollback for syntax tinting
    private val _scrollbackRaw = mutableListOf<String>()
    val scrollbackRaw: List<String> get() = _scrollbackRaw

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

    fun toScreenLinesRaw(): List<String> =
        grid.map { row -> row.map { it.char }.joinToString("") }

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
            val rawLine = grid[scrollTop].map { it.char }.joinToString("")
            _scrollback.add(renderRow(scrollTop))
            _scrollbackRaw.add(rawLine)
            if (_scrollback.size > 5000) { _scrollback.removeAt(0); _scrollbackRaw.removeAt(0) }
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
                if (cursorRow <= safeEnd) {
                    for (i in cursorRow..safeEnd) grid[i] = grid[i + cnt]
                }
                val clearFrom = maxOf(cursorRow, scrollBot - cnt + 1)
                if (clearFrom <= scrollBot) {
                    for (i in clearFrom..scrollBot) clearRow(i)
                }
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
private val EDITOR_CMD_REGEX = Regex("""^\s*(?:sudo\s+)?(?:nano|vi|vim)\s+(.+?)\s*$""")

// ══════════════════════════════════════════════════════════════════════════════
//  Constantes de connexion
// ══════════════════════════════════════════════════════════════════════════════
private const val KEEP_ALIVE_INTERVAL_MS = 30_000L
private const val RECONNECT_DELAY_MS     = 3_000L
private const val MAX_RECONNECT_ATTEMPTS = 10

// ══════════════════════════════════════════════════════════════════════════════
//  État de l'éditeur intégré
// ══════════════════════════════════════════════════════════════════════════════
private data class EditorState(
    val filePath      : String,
    val initialContent: String  = "",
    val isLoading     : Boolean = true,
    val sessionId     : Long    = System.currentTimeMillis()
)

// ══════════════════════════════════════════════════════════════════════════════
//  Dialog : ajouter / éditer un snippet
// ══════════════════════════════════════════════════════════════════════════════
@Composable
private fun SnippetDialog(
    initialLabel  : String = "",
    initialCommand: String = "",
    title         : String = "Nouveau snippet",
    onConfirm     : (label: String, command: String) -> Unit,
    onDelete      : (() -> Unit)? = null,
    onDismiss     : () -> Unit
) {
    var label   by remember { mutableStateOf(initialLabel) }
    var command by remember { mutableStateOf(initialCommand) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1A1A1A),
        titleContentColor= TerminalGreen,
        title = { Text(title, fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value          = label,
                    onValueChange  = { label = it },
                    label          = { Text("Nom affiché", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    singleLine     = true,
                    colors         = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = TerminalGreen,
                        unfocusedBorderColor = Color(0xFF444444),
                        focusedTextColor     = TerminalGreen,
                        unfocusedTextColor   = TERM_DEFAULT_FG,
                        cursorColor          = TerminalGreen,
                        focusedLabelColor    = TerminalGreen,
                        unfocusedLabelColor  = Color(0xFF888888)
                    ),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )
                OutlinedTextField(
                    value          = command,
                    onValueChange  = { command = it },
                    label          = { Text("Commande", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    singleLine     = false,
                    minLines       = 2,
                    colors         = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = TerminalGreen,
                        unfocusedBorderColor = Color(0xFF444444),
                        focusedTextColor     = TerminalGreen,
                        unfocusedTextColor   = TERM_DEFAULT_FG,
                        cursorColor          = TerminalGreen,
                        focusedLabelColor    = TerminalGreen,
                        unfocusedLabelColor  = Color(0xFF888888)
                    ),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Supprimer", color = Color(0xFFFF5555), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Annuler", color = Color(0xFF888888), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                TextButton(
                    onClick  = { if (label.isNotBlank() && command.isNotBlank()) onConfirm(label.trim(), command.trim()) },
                    enabled  = label.isNotBlank() && command.isNotBlank()
                ) {
                    Text("OK", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    )
}

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
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    var rawInput    by remember { mutableStateOf(TextFieldValue(ghost)) }
    var session     by remember { mutableStateOf<ShellSession?>(null) }
    var status      by remember { mutableStateOf("Connexion...") }
    var isConnected by remember { mutableStateOf(false) }

    var userClosedManually by remember { mutableStateOf(false) }
    var reconnectAttempt   by remember { mutableIntStateOf(0) }
    var isReconnecting     by remember { mutableStateOf(false) }

    var ctrlActive  by remember { mutableStateOf(false) }
    var altActive   by remember { mutableStateOf(false) }
    var isLandscape by remember { mutableStateOf(false) }
    var showBars    by remember { mutableStateOf(true) }
    var fontSize    by remember { mutableFloatStateOf(13f) }

    val emulator   = remember { TerminalEmulator(80, 24) }
    var renderTick by remember { mutableIntStateOf(0) }
    var termCols   by remember { mutableIntStateOf(80) }
    var termRows   by remember { mutableIntStateOf(24) }

    // ── Nouveautés ─────────────────────────────────────────────────────────────
    val commandHistory   = remember { CommandHistory() }
    val localShortcuts   = remember { settings.shortcuts.toMutableStateList() }

    // Dialog état
    var showAddSnippet       by remember { mutableStateOf(false) }
    var editSnippetIndex     by remember { mutableStateOf<Int?>(null) }

    // Suggestions d'autocomplétion
    var suggestions          by remember { mutableStateOf<List<String>>(emptyList()) }
    // ── Fin nouveautés ─────────────────────────────────────────────────────────

    var cursorVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(530); cursorVisible = !cursorVisible } }

    val screenLines        = remember(renderTick) { emulator.toScreenLines(showCursor = false) }
    val screenLinesRaw     = remember(renderTick) { emulator.toScreenLinesRaw() }
    val cursorRow          = emulator.cursorRow
    val cursorCol          = emulator.cursorCol
    val scrollbackSnapshot = remember(renderTick) { emulator.scrollback.toList() }
    val scrollbackRawSnapshot = remember(renderTick) { emulator.scrollbackRaw.toList() }
    val totalLines         = scrollbackSnapshot.size + screenLines.size
    val listState          = rememberLazyListState()

    LaunchedEffect(totalLines) {
        if (totalLines > 0) try { listState.scrollToItem(totalLines - 1) } catch (_: Exception) {}
    }

    LaunchedEffect(termCols, termRows) {
        emulator.resize(termCols, termRows)
        renderTick++
    }

    var editorState by remember { mutableStateOf<EditorState?>(null) }
    var typedLine   by remember { mutableStateOf("") }

    // Recalcule les suggestions à chaque changement de typedLine
    LaunchedEffect(typedLine) {
        suggestions = if (typedLine.isNotEmpty())
            computeSuggestions(typedLine, commandHistory.entries)
        else
            emptyList()
    }

    fun toggleRotation() {
        val a = context as? Activity ?: return
        isLandscape = !isLandscape
        a.requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    DisposableEffect(Unit) {
        onDispose { (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        if (text.isNotEmpty()) {
            typedLine += text
            scope.launch { session?.sendRaw(text) }
        }
    }

    fun sendRaw(bytes: String) {
        when {
            bytes == "\r" -> {
                val cmd   = typedLine.trim()
                // Ajoute à l'historique avant de réinitialiser
                if (cmd.isNotEmpty()) commandHistory.add(cmd)
                suggestions = emptyList()
                typedLine = ""
                val match = EDITOR_CMD_REGEX.find(cmd)
                if (match != null && isConnected) {
                    val rawPath  = match.groupValues[1].trim()
                    scope.launch { session?.sendRaw("\u0015") }
                    val openedAt = System.currentTimeMillis()
                    editorState  = EditorState(filePath = rawPath, isLoading = true, sessionId = openedAt)
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
                suggestions = emptyList()
                commandHistory.reset()
                scope.launch { session?.sendRaw(bytes) }
            }
            // ↑ : navigation historique
            bytes == "\u001B[A" -> {
                val prev = commandHistory.goUp()
                if (prev != null) {
                    // Efface la ligne courante et tape la commande historique
                    val clearLine = "\u0015"          // Ctrl+U
                    scope.launch {
                        session?.sendRaw(clearLine)
                        session?.sendRaw(prev)
                    }
                    typedLine = prev
                } else {
                    scope.launch { session?.sendRaw(bytes) }
                }
            }
            // ↓ : navigation historique
            bytes == "\u001B[B" -> {
                val next = commandHistory.goDown()
                val clearLine = "\u0015"
                scope.launch {
                    session?.sendRaw(clearLine)
                    session?.sendRaw(next)
                }
                typedLine = next
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

    fun sendCommand(cmd: String) {
        if (cmd.isBlank() || !isConnected) return
        commandHistory.add(cmd)
        scope.launch { session?.sendRaw("$cmd\r") }
    }

    /** Insère une suggestion d'autocomplétion dans la ligne en cours */
    fun applySuggestion(suggestion: String) {
        val clearLine = "\u0015"   // Ctrl+U
        scope.launch {
            session?.sendRaw(clearLine)
            session?.sendRaw(suggestion)
        }
        typedLine   = suggestion
        suggestions = emptyList()
        commandHistory.reset()
    }

    suspend fun connectSsh() {
        val result = SshClient.openShell(
            host     = settings.host,
            port     = settings.port,
            user     = settings.username,
            password = settings.password
        )
        result.onSuccess { sh ->
            session          = sh
            status           = "Connecté"
            isConnected      = true
            isReconnecting   = false
            reconnectAttempt = 0

            scope.launch(Dispatchers.IO) {
                while (sh.isConnected) {
                    delay(KEEP_ALIVE_INTERVAL_MS)
                    try { sh.sendRaw("\u0000") } catch (_: Exception) { break }
                }
            }

            scope.launch(Dispatchers.IO) {
                val reader = sh.inputStream.bufferedReader(Charsets.UTF_8)
                val buffer = CharArray(4096)
                while (sh.isConnected) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    val text = String(buffer, 0, n)
                    withContext(Dispatchers.Main) { emulator.process(text); renderTick++ }
                }

                withContext(Dispatchers.Main) {
                    isConnected = false
                    session     = null

                    if (!userClosedManually) {
                        if (reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
                            reconnectAttempt++
                            isReconnecting = true
                            status = "Reconnexion ($reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)..."
                            emulator.process("\r\n\u001B[33m⚠ Connexion perdue — tentative $reconnectAttempt/$MAX_RECONNECT_ATTEMPTS dans ${RECONNECT_DELAY_MS / 1000} s...\u001B[0m\r\n")
                            renderTick++
                            scope.launch {
                                delay(RECONNECT_DELAY_MS)
                                if (!userClosedManually) connectSsh()
                            }
                        } else {
                            isReconnecting = false
                            status = "Déconnecté"
                            emulator.process("\r\n\u001B[31m✗ Reconnexion abandonnée après $MAX_RECONNECT_ATTEMPTS tentatives.\u001B[0m\r\n")
                            renderTick++
                        }
                    } else {
                        status = "Déconnecté"
                        onClose()
                    }
                }
            }
        }.onFailure { err ->
            isConnected = false
            session     = null

            if (!userClosedManually && reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempt++
                isReconnecting = true
                status = "Reconnexion ($reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)..."
                emulator.process("\r\n\u001B[33m⚠ Échec — ${SshClient.parseError(err)}\u001B[0m\r\n")
                emulator.process("\u001B[33m  Nouvelle tentative dans ${RECONNECT_DELAY_MS / 1000} s...\u001B[0m\r\n")
                renderTick++
                delay(RECONNECT_DELAY_MS)
                if (!userClosedManually) connectSsh()
            } else if (!userClosedManually) {
                isReconnecting = false
                status = "Erreur"
                emulator.process(SshClient.parseError(err) + "\r\n")
                emulator.process("Vérifiez les paramètres SSH.\r\n")
                renderTick++
            }
        }
    }

    LaunchedEffect(Unit) {
        emulator.process("Connexion à ${settings.host}:${settings.port}...\r\n")
        renderTick++
        connectSsh()
    }

    LaunchedEffect(Unit) { forceShowKeyboard() }

    DisposableEffect(Unit) {
        onDispose {
            userClosedManually = true
            session?.close()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Dialogs snippets
    // ══════════════════════════════════════════════════════════════════════════
    if (showAddSnippet) {
        SnippetDialog(
            initialCommand = typedLine,
            title          = "Ajouter un snippet",
            onConfirm = { label, command ->
                localShortcuts.add(label to command)
                settings.shortcuts = localShortcuts.toList()
                showAddSnippet = false
            },
            onDismiss = { showAddSnippet = false }
        )
    }

    val editIdx = editSnippetIndex
    if (editIdx != null && editIdx < localShortcuts.size) {
        val (el, ec) = localShortcuts[editIdx]
        SnippetDialog(
            initialLabel   = el,
            initialCommand = ec,
            title          = "Modifier le snippet",
            onConfirm = { label, command ->
                localShortcuts[editIdx] = label to command
                settings.shortcuts = localShortcuts.toList()
                editSnippetIndex = null
            },
            onDelete = {
                localShortcuts.removeAt(editIdx)
                settings.shortcuts = localShortcuts.toList()
                editSnippetIndex = null
            },
            onDismiss = { editSnippetIndex = null }
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Affichage
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
                onClose = {
                    editorState = null
                    scope.launch { forceShowKeyboard() }
                }
            )
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Terminal SSH", fontFamily = FontFamily.Monospace)
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (isReconnecting) {
                                    CircularProgressIndicator(
                                        modifier    = Modifier.size(8.dp),
                                        strokeWidth = 1.5.dp,
                                        color       = Color.Yellow
                                    )
                                }
                                Text(
                                    text  = status,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        isConnected                        -> TerminalGreen
                                        isReconnecting                     -> Color.Yellow
                                        status.startsWith("Connexion")     -> Color.Yellow
                                        else                               -> Color.Red
                                    }
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { if (fontSize > 9f)  fontSize -= 1f }) {
                            Text("A−", color = TerminalGreen.copy(0.75f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        IconButton(onClick = { if (fontSize < 22f) fontSize += 1f }) {
                            Text("A+", color = TerminalGreen.copy(0.75f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        IconButton(
                            onClick = { if (isConnected) pasteFromClipboard() },
                            enabled = isConnected
                        ) {
                            Icon(
                                Icons.Default.ContentPaste,
                                contentDescription = "Coller",
                                tint = if (isConnected) TerminalGreen.copy(0.75f) else Color(0xFF3A3A3A)
                            )
                        }
                        if (!isConnected && !isReconnecting) {
                            IconButton(onClick = {
                                reconnectAttempt   = 0
                                userClosedManually = false
                                status             = "Reconnexion..."
                                isReconnecting     = true
                                emulator.process("\r\n\u001B[33m↺ Reconnexion manuelle...\u001B[0m\r\n")
                                renderTick++
                                scope.launch { connectSsh() }
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reconnecter", tint = Color.Yellow)
                            }
                        }
                        if (isLandscape) {
                            IconButton(onClick = { showBars = !showBars }) {
                                Icon(
                                    if (showBars) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Barres",
                                    tint = TerminalGreen.copy(0.75f)
                                )
                            }
                        }
                        IconButton(onClick = { toggleRotation() }) {
                            Icon(
                                Icons.Default.ScreenRotation,
                                contentDescription = "Rotation",
                                tint = if (isLandscape) TerminalGreen else TerminalGreen.copy(0.45f)
                            )
                        }
                        IconButton(onClick = {
                            userClosedManually = true
                            session?.close()
                            onClose()
                        }) {
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
                if (isReconnecting) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2A1F00))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = Color(0xFFFFD93D))
                            Text(text = status, color = Color(0xFFFFD93D), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                // ── Zone terminal ─────────────────────────────────────────────
                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(TerminalBg)) {
                    val density = LocalDensity.current
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { size ->
                                val charW   = fontSize * 0.6f
                                val charH   = fontSize * 1.27f
                                val boxW    = size.width / density.density
                                val boxH    = size.height / density.density
                                val newCols = (boxW / charW).toInt().coerceIn(20, 250)
                                val newRows = (boxH / charH).toInt().coerceIn(5, 80)
                                termCols    = newCols
                                termRows    = newRows
                            }
                    ) {
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
                                                added.length > 1 -> {
                                                    typedLine += added
                                                    scope.launch { session?.sendRaw(added) }
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
                            singleLine = false
                        )

                        val hScroll = rememberScrollState()
                        SelectionContainer(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event       = awaitPointerEvent()
                                            val isUp        = event.changes.all { !it.pressed }
                                            val isLongPress = event.changes.any {
                                                it.uptimeMillis - it.previousUptimeMillis > 400
                                            }
                                            if (isUp && !isLongPress) forceShowKeyboard()
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
                                // ── Scrollback avec coloration syntaxique ─────
                                scrollbackSnapshot.forEachIndexed { idx, line ->
                                    item(key = "sb_$idx") {
                                        val rawText = scrollbackRawSnapshot.getOrElse(idx) { "" }
                                        val tint    = syntaxTintForLine(rawText)
                                        if (tint != null) {
                                            // Applique un overlay de couleur sur toute la ligne
                                            Text(
                                                buildAnnotatedString {
                                                    withStyle(SpanStyle(color = tint.copy(alpha = 0.85f))) {
                                                        append(line.text)
                                                    }
                                                },
                                                fontFamily = FontFamily.Monospace,
                                                fontSize   = fontSize.sp,
                                                lineHeight = (fontSize * 1.27f).sp,
                                                softWrap   = false
                                            )
                                        } else {
                                            Text(
                                                line,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize   = fontSize.sp,
                                                lineHeight = (fontSize * 1.27f).sp,
                                                softWrap   = false
                                            )
                                        }
                                    }
                                }

                                // ── Screen lines avec curseur & coloration ────
                                screenLines.forEachIndexed { idx, line ->
                                    item(key = "sr_$idx") {
                                        val rawText = screenLinesRaw.getOrElse(idx) { "" }
                                        val tint    = if (idx != cursorRow) syntaxTintForLine(rawText) else null

                                        val displayLine: AnnotatedString = when {
                                            isConnected && idx == cursorRow -> {
                                                remember(line, cursorCol, cursorVisible) {
                                                    if (!cursorVisible) line
                                                    else buildAnnotatedString {
                                                        line.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
                                                        append(line.text)
                                                        val ci = cursorCol.coerceAtMost(line.length)
                                                        addStyle(
                                                            SpanStyle(color = TerminalBg, background = TERM_DEFAULT_FG),
                                                            ci, (ci + 1).coerceAtMost(line.length)
                                                        )
                                                    }
                                                }
                                            }
                                            tint != null -> buildAnnotatedString {
                                                withStyle(SpanStyle(color = tint.copy(alpha = 0.85f))) { append(line.text) }
                                            }
                                            else -> line
                                        }

                                        Text(
                                            displayLine,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize   = fontSize.sp,
                                            lineHeight = (fontSize * 1.27f).sp,
                                            softWrap   = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ══════════════════════════════════════════════════════════════
                //  Barre d'autocomplétion
                // ══════════════════════════════════════════════════════════════
                if (suggestions.isNotEmpty() && isConnected) {
                    HorizontalDivider(color = Color(0xFF1E3A1E), thickness = 0.5.dp)
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0A1A0A))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        // Bouton "enregistrer comme snippet" si typedLine non vide
                        item {
                            Box(
                                modifier = Modifier
                                    .height(24.dp)
                                    .background(Color(0xFF1A3A1A), RoundedCornerShape(4.dp))
                                    .clickable { showAddSnippet = true }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("＋ snippet", color = TerminalGreen.copy(0.6f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        items(suggestions) { suggestion ->
                            Box(
                                modifier = Modifier
                                    .height(24.dp)
                                    .background(Color(0xFF1A2A1A), RoundedCornerShape(4.dp))
                                    .clickable { applySuggestion(suggestion) }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                // Surligne la partie déjà tapée
                                val prefix = typedLine.length.coerceAtMost(suggestion.length)
                                Text(
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(color = TerminalGreen, fontWeight = FontWeight.Bold)) {
                                            append(suggestion.take(prefix))
                                        }
                                        withStyle(SpanStyle(color = TerminalGreen.copy(0.55f))) {
                                            append(suggestion.drop(prefix))
                                        }
                                    },
                                    fontSize   = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    softWrap   = false
                                )
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

                        Box(modifier = Modifier.width(1.dp).height(20.dp).background(Color(0xFF333333)))

                        CompactKey(label = "Paste", enabled = isConnected) { pasteFromClipboard() }

                        SPECIAL_KEYS.forEach { (label, bytes) ->
                            CompactKey(label = label, enabled = isConnected) { sendRaw(bytes) }
                        }
                    }

                    // ── Barre des snippets avec bouton "+" ────────────────────
                    HorizontalDivider(color = Color(0xFF222222), thickness = 0.5.dp)
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0A0A0A))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        // Bouton "+"
                        item {
                            Box(
                                modifier = Modifier
                                    .height(26.dp)
                                    .background(Color(0xFF1A1A2A), RoundedCornerShape(6.dp))
                                    .clickable { showAddSnippet = true }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("+", color = Color(0xFF888888), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        items(localShortcuts.size) { idx ->
                            val (label, cmd) = localShortcuts[idx]
                            val color        = shortcutColor(idx)
                            ShortcutChip(
                                label   = label,
                                color   = color,
                                enabled = isConnected,
                                onLongClick = { editSnippetIndex = idx },
                                onClick = { sendCommand(cmd) }
                            )
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
                                text       = if (ctrlActive) "^ Ctrl actif — tapez une lettre" else "Alt actif — tapez une touche",
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

// ── Chip de raccourci coloré avec support long-press ─────────────────────────
@Composable
private fun ShortcutChip(
    label      : String,
    color      : Color,
    enabled    : Boolean,
    onLongClick: () -> Unit = {},
    onClick    : () -> Unit
) {
    val bgColor     = color.copy(alpha = 0.10f)
    val borderColor = color.copy(alpha = if (enabled) 0.55f else 0.20f)
    val textColor   = color.copy(alpha = if (enabled) 1.00f else 0.35f)

    SuggestionChip(
        onClick = { if (enabled) onClick() },
        label   = {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val isLong = event.changes.any {
                                !it.pressed && it.uptimeMillis - it.previousUptimeMillis > 400
                            }
                            if (isLong) onLongClick()
                        }
                    }
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            color = color.copy(alpha = if (enabled) 0.9f else 0.3f),
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                )
                Text(text = label, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = textColor)
            }
        },
        colors  = SuggestionChipDefaults.suggestionChipColors(containerColor = bgColor),
        border  = SuggestionChipDefaults.suggestionChipBorder(enabled = true, borderColor = borderColor),
        modifier = Modifier.height(26.dp)
    )
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
            colors         = ButtonDefaults.buttonColors(containerColor = TerminalGreen, contentColor = Color.Black)
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