package xyz.lychee.dynamicdns.bungee

import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.TabExecutor
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

        sender.sendMessage(*color("&e[DynamicDNS] Usage: /dynamicdns reload"))
    }

    override fun onTabComplete(sender: CommandSender, args: Array<out String>): Iterable<String> {
        if (args.size == 1 && (sender.hasPermission("dynamicdns.reload") || sender.hasPermission("dynamicdns.admin"))) {
            if ("reload".startsWith(args[0].lowercase())) {
                return listOf("reload")
            }
        }
        return emptyList()
    }
}
