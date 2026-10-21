package xyz.lychee.dynamicdns.bungee

import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.TabExecutor
import xyz.lychee.dynamicdns.shared.ModrinthUpdater
import java.util.regex.Pattern

class DynamicDnsCommand(private val plugin: BungeeMain) :
    Command("dynamicdns", "dynamicdns.reload", "ddns"), TabExecutor {

    companion object {
        private val HEX_PATTERN: Pattern = Pattern.compile("&#([A-Fa-f0-9]{6})")

        fun color(message: String): Array<BaseComponent> {
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
            val translated = ChatColor.translateAlternateColorCodes('&', sb.toString())
            return TextComponent.fromLegacyText(translated)
        }
    }

    override fun execute(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("dynamicdns.reload") && !sender.hasPermission("dynamicdns.admin")) {
            sender.sendMessage(*color("&cYou do not have permission to execute this command!"))
            return
        }

        if (args.isNotEmpty() && args[0].equals("reload", ignoreCase = true)) {
            try {
                plugin.reload()
                sender.sendMessage(*color("&a[DynamicDNS] Configuration reloaded successfully!"))
            } catch (e: Exception) {
                sender.sendMessage(*color("&c[DynamicDNS] Failed to reload configuration: ${e.message}"))
                plugin.logger.warning("Failed to reload configuration: ${e.message}")
            }
            return
        }

        if (args.isNotEmpty() && args[0].equals("update", ignoreCase = true)) {
            sender.sendMessage(*color("&e[DynamicDNS] Checking for updates..."))
            val updater = plugin.updater ?: ModrinthUpdater(plugin.logger, plugin.description.version)
            updater.checkForUpdatesAsync(debug = true, force = true) { result ->
                if (result.isUpdateAvailable) {
                    sender.sendMessage(*color("&a[DynamicDNS] A new version is available: &fv${result.latestVersion} &a(Current: &fv${result.currentVersion}&a)!"))
                    sender.sendMessage(*color("&a[DynamicDNS] Download it at: &f${result.projectUrl}"))
                } else {
                    sender.sendMessage(*color("&a[DynamicDNS] You are running the latest version: &fv${result.currentVersion}&a."))
                }
            }
            return
        }

        if (args.isNotEmpty() && args[0].equals("status", ignoreCase = true)) {
            sender.sendMessage(*color("&e[DynamicDNS] --- BungeeCord Proxy Status ---"))
            sender.sendMessage(*color("&7Registered subservers: &f${plugin.servers.size}"))
            val now = System.currentTimeMillis()
            plugin.servers.forEach { (name, addr) ->
                val lastUpdate = plugin.serverLastUpdate[name]
                val lastUpdateStr = if (lastUpdate != null) {
                    val seconds = (now - lastUpdate) / 1000L
                    if (seconds < 60) "${seconds}s ago" else "${seconds / 60}m ${seconds % 60}s ago"
                } else "never"
                sender.sendMessage(*color(" &8- &a$name&7: &f${addr.hostString}:${addr.port} &7(last update: &e$lastUpdateStr&7)"))
            }
            return
        }

        sender.sendMessage(*color("&e[DynamicDNS] Usage: /dynamicdns <reload|update|status>"))
    }

    override fun onTabComplete(sender: CommandSender, args: Array<out String>): Iterable<String> {
        if (args.size == 1 && (sender.hasPermission("dynamicdns.reload") || sender.hasPermission("dynamicdns.admin"))) {
            return listOf("reload", "update", "status").filter { it.startsWith(args[0].lowercase()) }
        }
        return emptyList()
    }
}
