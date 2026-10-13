package dev.a2wp.wallet

import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * a2wpctl: small admin tool for the wallet store. Never prints credentials or keys.
 *
 *   a2wpctl list  [config]   credentials (handle, owning agent, vct, issuer, agent_id/agent_name claims, stored_at) and approvals
 *   a2wpctl reset [config]   backs up the store file, then removes it (all credentials, holder keys and approvals).
 *                            API keys, the store key and the config are kept. The wallet must be stopped first.
 */
fun main(args: Array<String>) {
    val cmd = args.firstOrNull()
    val configPath = Path.of(args.getOrNull(1) ?: System.getenv("A2WP_CONFIG") ?: "config/wallet.json")
    when (cmd) {
        "list" -> Ctl.list(ConfigLoader.load(configPath))
        "reset" -> Ctl.reset(ConfigLoader.load(configPath))
        else -> {
            System.err.println("usage: a2wpctl list|reset [config/wallet.json]")
            exitProcess(2)
        }
    }
}

object Ctl {
    fun list(cfg: LoadedConfig) {
        val path = cfg.resolve(cfg.config.store.path)
        if (!Files.exists(path)) {
            println("store ${path}: empty (no file)")
            return
        }
        val data = EncryptedStore(path, EncryptedStore.loadKey(cfg)).snapshot()
        println("store $path: ${data.credentials.size} credential(s), ${data.approvals.size} approval record(s)")
        data.credentials.sortedBy { Instant.parse(it.storedAt) }.forEach { c ->
            val claims = runCatching { PresentService.claimValues(c).first }.getOrDefault(emptyMap())
            println(
                "  ${c.handle}  agent=${c.agentId}  stored=${c.storedAt}  agent_id=${claims["agent_id"]}  " +
                    "agent_name=${claims["agent_name"]}  vct=${c.vct}  issuer=${c.issuer}",
            )
        }
        data.approvals.forEach { (k, v) ->
            val (agent, verifier) = k.split('\u0000', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            println("  approval agent=$agent verifier=$verifier claims=${v.joinToString(",")}")
        }
    }

    fun reset(cfg: LoadedConfig) {
        val listen = cfg.config.listen
        if (isListening(listen.host, listen.port)) {
            System.err.println("The wallet is running on ${listen.host}:${listen.port}. Stop it first, then reset, then start it again.")
            exitProcess(1)
        }
        val path = cfg.resolve(cfg.config.store.path)
        if (!Files.exists(path)) {
            println("Nothing to reset: $path does not exist.")
            return
        }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        val backupDir = path.toAbsolutePath().parent.resolve("backups")
        Files.createDirectories(backupDir)
        val backup = backupDir.resolve("${path.fileName}.$stamp")
        Files.copy(path, backup, StandardCopyOption.COPY_ATTRIBUTES)
        Files.delete(path)
        println("Backed up $path to $backup (still encrypted with the same store key).")
        println("Removed all credentials, holder keys and approvals. API keys, store key and config are unchanged.")
    }

    private fun isListening(host: String, port: Int): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 300) } }.isSuccess
}
