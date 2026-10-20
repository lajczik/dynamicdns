package xyz.lychee.dynamicdns.sponge

import net.kyori.adventure.identity.Identity
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.spongepowered.api.command.Command
import org.spongepowered.api.command.CommandResult
import org.spongepowered.api.command.parameter.CommandContext

class DynamicDnsCommand(private val plugin: SpongeMain) {

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

    fun build(): Command.Parameterized {
        val reloadSubcommand = Command.builder()
            .permission("dynamicdns.reload")
            .executionRequirements { cause ->
                cause.hasPermission("dynamicdns.reload") || cause.hasPermission("dynamicdns.admin")
            }
            .executor { context: CommandContext ->
                try {
                    plugin.reload()
                    context.sendMessage(Identity.nil(), color("&a[DynamicDNS] Configuration reloaded successfully!"))
                } catch (e: Exception) {
                    context.sendMessage(Identity.nil(), color("&c[DynamicDNS] Failed to reload configuration: ${e.message}"))
                    plugin.julLogger.warning("Failed to reload configuration: ${e.message}")
                }
                CommandResult.success()
            }
            .build()

        return Command.builder()
            .permission("dynamicdns.reload")
            .executionRequirements { cause ->
                cause.hasPermission("dynamicdns.reload") || cause.hasPermission("dynamicdns.admin")
            }
            .addChild(reloadSubcommand, "reload")
            .executor { context: CommandContext ->
                context.sendMessage(Identity.nil(), color("&e[DynamicDNS] Usage: /dynamicdns reload"))
                CommandResult.success()
            }
            .build()
    }
}
