package xyz.lychee.dynamicdns.sponge

import net.kyori.adventure.identity.Identity
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.spongepowered.api.Sponge
import org.spongepowered.api.command.Command
import org.spongepowered.api.command.CommandResult
import org.spongepowered.api.command.parameter.CommandContext
import org.spongepowered.api.scheduler.Task
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
                cause.hasPermission("dynamicdns.admin") || cause.hasPermission("dynamicdns.reload")
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
            .permission("dynamicdns.update")
            .executionRequirements { cause ->
                cause.hasPermission("dynamicdns.admin") || cause.hasPermission("dynamicdns.update")
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

        val statusSubcommand = Command.builder()
            .permission("dynamicdns.status")
            .executionRequirements { cause ->
                cause.hasPermission("dynamicdns.admin") || cause.hasPermission("dynamicdns.status")
            }
            .executor { context: CommandContext ->
                val ip = plugin.lastResolvedAddress?.address?.hostAddress ?: "Unknown"
                val port = plugin.lastResolvedAddress?.port ?: Sponge.server().boundAddress().map { it.port }.orElse(25565)
                context.sendMessage(Identity.nil(), color("&6[DynamicDNS] &eBackend Server Status:"))
                context.sendMessage(Identity.nil(), color("&7- Current Public Address: &f$ip:$port"))
                context.sendMessage(Identity.nil(), color("&7- Ngrok: &f${if (plugin.config.ngrok.enabled) "&aEnabled" else "&cDisabled"}"))
                context.sendMessage(Identity.nil(), color("&7- Dynu: &f${if (plugin.config.dynu.enabled) "&aEnabled" else "&cDisabled"}"))
                context.sendMessage(Identity.nil(), color("&7- Cloudflare: &f${if (plugin.config.cloudflare.enabled) "&aEnabled" else "&cDisabled"}"))
                context.sendMessage(Identity.nil(), color("&7- DuckDNS: &f${if (plugin.config.duckdns.enabled) "&aEnabled" else "&cDisabled"}"))
                context.sendMessage(Identity.nil(), color("&7- No-IP: &f${if (plugin.config.noip.enabled) "&aEnabled" else "&cDisabled"}"))
                context.sendMessage(Identity.nil(), color("&7- Server Sync: &f${if (plugin.config.serverSync.enabled) "&aEnabled" else "&cDisabled"}"))
                if (plugin.config.serverSync.enabled) {
                    val lastSync = if (plugin.lastSyncSentTime > 0) {
                        "${(System.currentTimeMillis() - plugin.lastSyncSentTime) / 1000}s ago"
                    } else "Never"
                    context.sendMessage(Identity.nil(), color("&7- Last Sync Ping: &f$lastSync"))
                }
                CommandResult.success()
            }
            .build()

        val syncSubcommand = Command.builder()
            .permission("dynamicdns.sync")
            .executionRequirements { cause ->
                cause.hasPermission("dynamicdns.admin") || cause.hasPermission("dynamicdns.sync")
            }
            .executor { context: CommandContext ->
                if (!plugin.config.serverSync.enabled) {
                    context.sendMessage(Identity.nil(), color("&c[DynamicDNS] Server sync is not enabled in config.yml!"))
                    return@executor CommandResult.success()
                }

                Sponge.asyncScheduler().submit(Task.builder().execute(Runnable {
                    try {
                        val sent = plugin.sendSyncPing()
                        if (sent) {
                            context.sendMessage(Identity.nil(), color("&a[DynamicDNS] Forced sync ping sent successfully!"))
                        } else {
                            context.sendMessage(Identity.nil(), color("&c[DynamicDNS] Failed to send sync ping. Check configuration!"))
                        }
                    } catch (e: Exception) {
                        context.sendMessage(Identity.nil(), color("&c[DynamicDNS] Error during forced sync: ${e.message}"))
                    }
                }).build())

                CommandResult.success()
            }
            .build()

        return Command.builder()
            .executionRequirements { cause ->
                cause.hasPermission("dynamicdns.admin") ||
                        cause.hasPermission("dynamicdns.reload") ||
                        cause.hasPermission("dynamicdns.update") ||
                        cause.hasPermission("dynamicdns.status") ||
                        cause.hasPermission("dynamicdns.sync")
            }
            .addChild(reloadSubcommand, "reload")
            .addChild(updateSubcommand, "update")
            .addChild(statusSubcommand, "status")
            .addChild(syncSubcommand, "sync")
            .executor { context: CommandContext ->
                val cause = context.cause()
                val available = listOf("reload", "update", "status", "sync").filter {
                    cause.hasPermission("dynamicdns.admin") || cause.hasPermission("dynamicdns.$it")
                }
                if (available.isEmpty()) {
                    context.sendMessage(Identity.nil(), color("&cYou do not have permission to execute this command!"))
                } else {
                    context.sendMessage(Identity.nil(), color("&e[DynamicDNS] Usage: /dynamicdns <${available.joinToString("|")}>"))
                }
                CommandResult.success()
            }
            .build()
    }
}
