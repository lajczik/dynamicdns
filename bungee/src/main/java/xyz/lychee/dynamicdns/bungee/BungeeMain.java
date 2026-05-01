package xyz.lychee.dynamicdns.bungee;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import lombok.Getter;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.plugin.Plugin;
import org.bstats.bungeecord.Metrics;
import xyz.lychee.dynamicdns.shared.*;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Getter
public class BungeeMain extends Plugin {
    private final Map<String, InetSocketAddress> servers = new ConcurrentHashMap<>();
    private YamlDocument config;
    private MessageReceiver receiver;
    private Metrics metrics;
    private NgrokHook ngrok;
    private DynuHook dynu;

    @Override
    public void onEnable() {
        this.metrics = new Metrics(this, 27000);
        try {
            this.config = YamlDocument.create(
                    new File(this.getDataFolder(), "config.yml"),
                    this.getResourceAsStream("config.yml"),
                    GeneralSettings.DEFAULT,
                    LoaderSettings.builder().setCreateFileIfAbsent(true).setAutoUpdate(true).build(),
                    DumperSettings.DEFAULT
            );
            this.config.save();

            this.reload();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onDisable() {
        if (this.metrics != null) this.metrics.shutdown();
        if (this.ngrok != null) this.ngrok.unload();
    }

    public void reload() throws IOException {
        this.config.reload();

        InetSocketAddress bind = this.getProxy().getConfig().getListeners()
                .stream()
                .findFirst()
                .map(info -> (InetSocketAddress) info.getSocketAddress())
                .orElse(new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565));

        this.ngrok = new NgrokHook(this.getLogger(), bind, this.config);
        this.dynu = new DynuHook(this.getLogger(), this.config);
        this.receiver = new BungeeMessageReceiver(this.getLogger(), this.config);

        InetSocketAddress address = this.config.getBoolean("ngrok.enabled", false)
                ? AddressUtil.parseAddressFromUri(this.ngrok.load())
                : new InetSocketAddress(AddressUtil.checkPublicIp(), bind.getPort());

        if (this.config.getBoolean("dynu.enabled", false)) {
            this.dynu.load(address);
        }

        this.loadSocketServers();
    }

    public void loadSocketServers() throws IOException {
        String token = this.config.getString("socket_server.token", null);

        if (token == null) {
            token = UUID.randomUUID().toString();
            this.config.set("socket_server.token", token);
            this.config.save();
        }

        if (!this.config.getBoolean("socket_server.enabled", false)) {
            return;
        }

        String address = this.config.getString("socket_server.address");
        if (address == null) {
            this.getLogger().warning("Value socket_client.address has not been set!");
            return;
        }

        String[] split = address.split(":");
        if (split.length != 2) {
            this.getLogger().warning("Invalid socket_server address format. Expected format: host:port");
            return;
        }

        this.receiver.loadServers(token);
        this.receiver.updateServers();
        this.receiver.start(new InetSocketAddress(split[0], Integer.parseInt(split[1])));
    }

    public class BungeeMessageReceiver extends MessageReceiver {
        public BungeeMessageReceiver(Logger logger, YamlDocument config) {
            super(logger, config);
        }

        @Override
        public void updateServers() {
            Map<String, ServerInfo> current = getProxy().getServers();
            for (Map.Entry<String, InetSocketAddress> entry : this.getServers().entrySet()) {
                String name = entry.getKey();
                InetSocketAddress address = entry.getValue();

                ServerInfo existing = current.get(name);
                if (existing != null && existing.getSocketAddress().equals(address)) continue;

                ServerInfo oldInfo = current.get(name);

                ServerInfo newInfo = getProxy().constructServerInfo(
                        name, address, "DynamicDNS updated server", false
                );

                if (oldInfo != null) {
                    oldInfo.getPlayers().forEach(p -> p.connect(newInfo));
                }

                current.put(name, newInfo);
                this.getLogger().info("Loaded server " + name + " with address " + address + "!");
            }
        }
    }
}