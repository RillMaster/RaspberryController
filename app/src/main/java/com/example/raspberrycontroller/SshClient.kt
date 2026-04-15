package com.example.raspberrycontroller

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream

class ShellSession(
    private val session: Session,
    private val channel: ChannelShell
) {
    private val writer: BufferedWriter = channel.outputStream.bufferedWriter(Charsets.UTF_8)

    // Stream brut pour les caractères de contrôle (Ctrl+C, flèches, etc.)
    private val rawOut: OutputStream = channel.outputStream

    val inputStream: InputStream = channel.inputStream
    val isConnected get() = channel.isConnected && !channel.isClosed

    /** Envoie une commande texte suivie d'un retour à la ligne. */
    suspend fun send(command: String) = withContext(Dispatchers.IO) {
        runCatching {
            writer.write(command + "\n")
            writer.flush()
        }.onFailure { it.printStackTrace() }
    }

    /**
     * Envoie des octets bruts sans newline — pour les séquences de contrôle
     * (Ctrl+C = \u0003, flèche haut = \u001B[A, Tab = \u0009, etc.)
     */
    suspend fun sendRaw(bytes: String) = withContext(Dispatchers.IO) {
        runCatching {
            rawOut.write(bytes.toByteArray(Charsets.UTF_8))
            rawOut.flush()
        }.onFailure { it.printStackTrace() }
    }

    fun close() {
        runCatching { writer.close() }
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }
}

object SshClient {

    /**
     * Traduit une exception JSch en message lisible par l'utilisateur.
     */
    fun parseError(e: Throwable): String {
        val msg = e.message ?: "Erreur inconnue"
        return when {
            e is JSchException && (msg.contains("Auth fail", ignoreCase = true)
                    || msg.contains("auth cancel", ignoreCase = true)) ->
                "❌ Authentification échouée — vérifiez identifiant / mot de passe"

            e is JSchException && (msg.contains("UnknownHost", ignoreCase = true)
                    || msg.contains("unable to resolve", ignoreCase = true)
                    || msg.contains("nodename nor servname", ignoreCase = true)) ->
                "🌐 Hôte introuvable — vérifiez l'adresse IP (${msg})"

            e is JSchException && (msg.contains("timeout", ignoreCase = true)
                    || msg.contains("timed out", ignoreCase = true)) ->
                "⏱️ Délai dépassé — hôte éteint ou port fermé ?"

            e is JSchException && msg.contains("Connection refused", ignoreCase = true) ->
                "🚫 Connexion refusée — SSH est-il activé sur le Raspberry Pi ?"

            e is JSchException && msg.contains("No route to host", ignoreCase = true) ->
                "📡 Hôte inaccessible — êtes-vous sur le même réseau Wi-Fi ?"

            e is JSchException && (msg.contains("Connection reset", ignoreCase = true)
                    || msg.contains("Broken pipe", ignoreCase = true)) ->
                "🔌 Connexion interrompue — le Raspberry Pi a fermé la session"

            e is JSchException && msg.contains("channel is not opened", ignoreCase = true) ->
                "⚠️ Canal fermé — la session a expiré, reconnectez-vous"

            msg.contains("ECONNREFUSED", ignoreCase = true) ->
                "🚫 Port SSH refusé (ECONNREFUSED)"
            msg.contains("ETIMEDOUT", ignoreCase = true) ->
                "⏱️ Réseau lent ou hôte éteint (ETIMEDOUT)"
            msg.contains("ENETUNREACH", ignoreCase = true) ->
                "📡 Réseau inaccessible (ENETUNREACH)"

            else -> "⚠️ Erreur SSH : $msg"
        }
    }

    // ─── Exécution d'une commande unique ─────────────────────────────────────────
    suspend fun execute(
        host: String,
        port: Int = 22,
        user: String,
        password: String,
        command: String,
        timeoutMs: Int = 8000
    ): String = withContext(Dispatchers.IO) {
        try {
            val jsch = JSch()
            val session = jsch.getSession(user, host, port)
            session.setPassword(password)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(timeoutMs)

            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val stdout = channel.inputStream
            val stderr = channel.errStream
            channel.connect()

            val output = StringBuilder()
            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 10_000

            while (System.currentTimeMillis() < deadline) {
                while (stdout.available() > 0) {
                    val n = stdout.read(buffer)
                    if (n > 0) output.append(String(buffer, 0, n, Charsets.UTF_8))
                }
                while (stderr.available() > 0) {
                    val n = stderr.read(buffer)
                    if (n > 0) output.append("[err] ${String(buffer, 0, n, Charsets.UTF_8)}")
                }
                if (channel.isClosed && stdout.available() == 0) break
                Thread.sleep(100)
            }

            channel.disconnect()
            session.disconnect()

            // ✅ CORRIGÉ : on retourne "" si vide, MainActivity gère ce cas
            output.toString().trim()

        } catch (e: Exception) {
            parseError(e)
        }
    }

    // ─── Ouverture d'un shell interactif ─────────────────────────────────────────
    suspend fun openShell(
        host: String,
        port: Int = 22,
        user: String,
        password: String
    ): Result<ShellSession> = withContext(Dispatchers.IO) {
        runCatching {
            val jsch = JSch()
            val session = jsch.getSession(user, host, port)
            session.setPassword(password)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(8000)

            val channel = session.openChannel("shell") as ChannelShell
            channel.setPtyType("vt100")
            channel.setPtySize(200, 50, 1000, 500)
            channel.connect()

            ShellSession(session, channel)
        }
    }
}