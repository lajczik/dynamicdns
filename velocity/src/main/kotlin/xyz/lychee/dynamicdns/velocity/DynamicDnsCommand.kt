package xyz.lychee.dynamicdns.velocity

import com.velocitypowered.api.command.SimpleCommand
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

class DynamicDnsCommand(private val plugin: VelocityMain) : SimpleCommand {

    companion object {
        private val SERIALIZER: LegacyComponentSerializer = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build()

        fun color(message: String): Component {
            return SERIALIZER.deserialize(message)
        }
    }

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments()

        if (!hasPermission(invocation)) {
            source.sendMessage(color("&cYou do not have permission to execute this command!"))
            return
        }

        if (args.isNotEmpty() && args[0].equals("reload", ignoreCase = true)) {
            try {
                plugin.reload()
                source.sendMessage(color("&a[DynamicDNS] Configuration reloaded successfully!"))
            } catch (e: Exception) {
                source.sendMessage(color("&c[DynamicDNS] Failed to reload configuration: ${e.message}"))
                plugin.logger.warning("Failed to reload configuration: ${e.message}")
            }
            return
        }

        source.sendMessage(color("&e[DynamicDNS] Usage: /dynamicdns reload"))
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        val args = invocation.arguments()
        if (args.size <= 1 && hasPermission(invocation)) {
            val prefix = if (args.isEmpty()) "" else args[0].lowercase()
            if ("reload".startsWith(prefix)) {
                return listOf("reload")
            }
        }
        return emptyList()
    }

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean {
        val source = invocation.source()
        return source.hasPermission("dynamicdns.reload") || source.hasPermission("dynamicdns.admin")
    }
}
