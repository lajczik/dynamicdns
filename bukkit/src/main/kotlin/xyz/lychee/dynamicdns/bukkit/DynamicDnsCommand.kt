package xyz.lychee.dynamicdns.bukkit

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
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

        if (args.isNotEmpty() && args[0].equals("reload", ignoreCase = true)) {
            try {
                plugin.reload()
                sender.sendMessage(color("&a[DynamicDNS] Configuration reloaded successfully!"))
            } catch (e: Exception) {
                sender.sendMessage(color("&c[DynamicDNS] Failed to reload configuration: ${e.message}"))
                plugin.logger.warning("Failed to reload configuration: ${e.message}")
            }
            return true
        }

        sender.sendMessage(color("&e[DynamicDNS] Usage: /$label reload"))
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (args.size == 1 && (sender.hasPermission("dynamicdns.reload") || sender.hasPermission("dynamicdns.admin"))) {
            if ("reload".startsWith(args[0].lowercase())) {
                return listOf("reload")
            }
        }
        return emptyList()
    }
}
