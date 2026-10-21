package xyz.lychee.dynamicdns.bukkit

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import xyz.lychee.dynamicdns.shared.ModrinthUpdater
import java.util.regex.Pattern

class DynamicDnsCommand(private val plugin: BukkitMain) : CommandExecutor, TabCompleter {

    companion object {
        private val HEX_PATTERN: Pattern = Pattern.compile("&#([A-Fa-f0-9]{6})")

        fun color(message: String): String {
            val matcher = HEX_PATTERN.matcher(message)
            val sb = StringBuffer()
            while (matcher.find()) {
                val hex = matcher.group(1)
                val replacement = buildString {
                    append('§').append('x')
                    for (c in hex) {
                        append('§').append(c)
                    }
                }
                matcher.appendReplacement(sb, replacement)
            }
            matcher.appendTail(sb)
            return ChatColor.translateAlternateColorCodes('&', sb.toString())
        }
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (!sender.hasPermission("dynamicdns.reload") && !sender.hasPermission("dynamicdns.admin")) {
            sender.sendMessage(color("&cYou do not have permission to execute this command!"))
            return true
        }

        if (args.isNotEmpty()) {
            when (args[0].lowercase()) {
                "reload" -> {
                    try {
                        plugin.reload()
                        sender.sendMessage(color("&a[DynamicDNS] Configuration reloaded successfully!"))
                    } catch (e: Exception) {
                        sender.sendMessage(color("&c[DynamicDNS] Failed to reload configuration: ${e.message}"))
                        plugin.logger.warning("Failed to reload configuration: ${e.message}")
                    }
                    return true
                }
                "update" -> {
                    sender.sendMessage(color("&e[DynamicDNS] Checking for updates..."))
                    val updater = plugin.updater ?: ModrinthUpdater(plugin.logger, plugin.description.version)
                    updater.checkForUpdatesAsync(debug = true, force = true) { result ->
                        if (result.isUpdateAvailable) {
                            sender.sendMessage(color("&a[DynamicDNS] A new version is available: &fv${result.latestVersion} &a(Current: &fv${result.currentVersion}&a)!"))
                            sender.sendMessage(color("&a[DynamicDNS] Download it at: &f${result.projectUrl}"))
                        } else {
                            sender.sendMessage(color("&a[DynamicDNS] You are running the latest version: &fv${result.currentVersion}&a."))
                        }
                    }
                    return true
                }
                "sync" -> {
                    if (!plugin.config.serverSync.enabled) {
                        sender.sendMessage(color("&c[DynamicDNS] server_sync is disabled in config.yml!"))
                        return true
                    }
                    sender.sendMessage(color("&e[DynamicDNS] Sending sync ping to proxy..."))
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                        try {
                            val ok = plugin.sendSyncPing()
                            if (ok) {
                                sender.sendMessage(color("&a[DynamicDNS] Sync ping sent successfully to &f${plugin.config.serverSync.targetAddress}&a!"))
                            } else {
                                sender.sendMessage(color("&c[DynamicDNS] Failed to send sync ping. Check console for details."))
                            }
                        } catch (e: Exception) {
                            sender.sendMessage(color("&c[DynamicDNS] Error sending sync ping: ${e.message}"))
                        }
                    })
                    return true
                }
                "status" -> {
                    sender.sendMessage(color("&e[DynamicDNS] --- Subserver Status ---"))
                    val sync = plugin.config.serverSync
                    sender.sendMessage(color("&7Server Sync: " + if (sync.enabled) "&aEnabled" else "&cDisabled"))
                    if (sync.enabled) {
                        val now = System.currentTimeMillis()
                        val lastSync = plugin.lastSyncSentTime
                        val lastSyncStr = if (lastSync > 0) {
                            val seconds = (now - lastSync) / 1000L
                            if (seconds < 60) "${seconds}s ago" else "${seconds / 60}m ${seconds % 60}s ago"
                        } else "never"
                        sender.sendMessage(color(" &8- &7Target Proxy: &f${sync.targetAddress} &7(ID: &a${sync.name}&7)"))
                        sender.sendMessage(color(" &8- &7Last Update Sent: &e$lastSyncStr"))
                        sender.sendMessage(color(" &8- &7Interval: &f${sync.interval}s"))
                    }
                    val publicAddr = plugin.lastResolvedAddress
                    sender.sendMessage(color("&7Local Port: &f${plugin.server.port}"))
                    sender.sendMessage(color("&7Public Address: &f${publicAddr?.hostString ?: "unknown"}"))

                    val ngrokStatus = if (plugin.config.ngrok.enabled) "&aEnabled" else "&cDisabled"
                    sender.sendMessage(color("&7ngrok Tunnel: $ngrokStatus"))

                    val ddnsActive = buildList {
                        if (plugin.config.dynu.enabled) add("Dynu")
                        if (plugin.config.cloudflare.enabled) add("Cloudflare")
                        if (plugin.config.duckdns.enabled) add("DuckDNS")
                        if (plugin.config.noip.enabled) add("No-IP")
                    }
                    sender.sendMessage(color("&7Active DDNS: &f${if (ddnsActive.isEmpty()) "None" else ddnsActive.joinToString(", ")}"))
                    return true
                }
            }
        }

        sender.sendMessage(color("&e[DynamicDNS] Usage: /$label <reload|update|sync|status>"))
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (args.size == 1 && (sender.hasPermission("dynamicdns.reload") || sender.hasPermission("dynamicdns.admin"))) {
            return listOf("reload", "update", "sync", "status").filter { it.startsWith(args[0].lowercase()) }
        }
        return emptyList()
    }
}
