package xyz.lychee.dynamicdns.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import lombok.Getter;
import org.bstats.velocity.Metrics;
import xyz.lychee.dynamicdns.shared.AddressUtil;
import xyz.lychee.dynamicdns.shared.DynuHook;
import xyz.lychee.dynamicdns.shared.MessageReceiver;
import xyz.lychee.dynamicdns.shared.NgrokHook;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Getter
public class VelocityMain {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Map<String, InetSocketAddress> servers = new ConcurrentHashMap<>();
    private final YamlDocument config;
    private final MessageReceiver receiver;
    private final Metrics.Factory metricsFactory;
    private final NgrokHook ngrok;
    private final DynuHook dynu;
    private Metrics metrics;

    @Inject
    public VelocityMain(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory, Metrics.Factory metricsFactory) throws IOException {
        this.config = YamlDocument.create(
                dataDirectory.resolve("config.yml").toFile(),
                this.getClass().getClassLoader().getResourceAsStream("config.yml"),
                GeneralSettings.DEFAULT,
                LoaderSettings.builder().setCreateFileIfAbsent(true).build(),
                DumperSettings.DEFAULT
        );

        this.receiver = new VelocityMessageReceiver(logger, this.config);
        this.ngrok = new NgrokHook(logger, proxy.getBoundAddress(), this.config);
        this.dynu = new DynuHook(logger, this.config);

        this.proxy = proxy;
        this.metricsFactory = metricsFactory;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) throws IOException {
        this.metrics = this.metricsFactory.make(this, 27000);

        this.reload();
    }

    public void reload() throws IOException {
        this.config.reload();

        InetSocketAddress address = this.config.getBoolean("ngrok.enabled", false)
                ? AddressUtil.parseAddressFromUri(this.ngrok.load())
                : new InetSocketAddress(AddressUtil.checkPublicIp(), proxy.getBoundAddress().getPort());

        if (this.config.getBoolean("dynu.enabled", false)) {
            this.dynu.load(address);
        }

        this.loadSocketServers();
    }

    public void loadSocketServers() throws IOException {
        String token = this.config.getString("socket_server.token");

        if (token == null) {
            token = UUID.randomUUID().toString();
            this.config.set("socket_server.token", token);
            this.config.save();
        }

        if (!this.config.getBoolean("socket_server.enabled", false)) {
            return;
        }

        String address = this.config.getString("socket_server.bind");
        if (address == null) {
            this.getLogger().warning("Value socket_server.bind has not been set!");
            return;
        }

        String[] split = address.split(":");
        if (split.length != 2) {
            this.getLogger().warning("Invalid socket_server bind format. Expected format: host:port");
            return;
        }

        this.receiver.loadServers(token);
        this.receiver.updateServers();
        this.receiver.start(new InetSocketAddress(split[0], Integer.parseInt(split[1])));
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (this.metrics != null) this.metrics.shutdown();
        if (this.ngrok != null) this.ngrok.unload();
    }

    public class VelocityMessageReceiver extends MessageReceiver {
        public VelocityMessageReceiver(Logger logger, YamlDocument config) {
            super(logger, config);
        }

        @Override
        public void updateServers() {
            for (Map.Entry<String, InetSocketAddress> entry : this.getServers().entrySet()) {
                String name = entry.getKey();
                InetSocketAddress address = entry.getValue();

                ServerInfo info = proxy.getServer(name).map(RegisteredServer::getServerInfo).orElse(null);
                if (info != null && !info.getAddress().equals(address)) {
                    proxy.unregisterServer(info);
                    proxy.registerServer(new ServerInfo(name, address));
                    this.getLogger().info("Loaded server " + name + " with address " + address + "!");
                }
            }
        }
    }
}