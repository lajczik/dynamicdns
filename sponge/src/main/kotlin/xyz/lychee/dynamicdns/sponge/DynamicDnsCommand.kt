package xyz.lychee.dynamicdns.sponge

import net.kyori.adventure.identity.Identity
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.spongepowered.api.command.Command
import org.spongepowered.api.command.CommandResult
import org.spongepowered.api.command.parameter.CommandContext
import xyz.lychee.dynamicdns.shared.ModrinthUpdater

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

        val updateSubcommand = Command.builder()
            .permission("dynamicdns.reload")
            .executionRequirements { cause ->
                cause.hasPermission("dynamicdns.reload") || cause.hasPermission("dynamicdns.admin")
            }
            .executor { context: CommandContext ->
                context.sendMessage(Identity.nil(), color("&e[DynamicDNS] Checking for updates..."))
                val version = plugin.pluginContainer.metadata().version().toString()
                val updater = plugin.updater ?: ModrinthUpdater(plugin.julLogger, version)
                updater.checkForUpdatesAsync(debug = true, force = true) { result ->
                    if (result.isUpdateAvailable) {
                        context.sendMessage(Identity.nil(), color("&a[DynamicDNS] A new version is available: &fv${result.latestVersion} &a(Current: &fv${result.currentVersion}&a)!"))
                        context.sendMessage(Identity.nil(), color("&a[DynamicDNS] Download it at: &f${result.projectUrl}"))
                    } else {
                        context.sendMessage(Identity.nil(), color("&a[DynamicDNS] You are running the latest version: &fv${result.currentVersion}&a."))
                    }
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
            .addChild(updateSubcommand, "update")
            .executor { context: CommandContext ->
                context.sendMessage(Identity.nil(), color("&e[DynamicDNS] Usage: /dynamicdns <reload|update>"))
                CommandResult.success()
            }
            .build()
    }
}
