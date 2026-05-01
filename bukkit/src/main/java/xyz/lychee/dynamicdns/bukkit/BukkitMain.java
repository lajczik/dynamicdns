package xyz.lychee.dynamicdns.bukkit;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import lombok.Getter;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import xyz.lychee.dynamicdns.shared.AddressUtil;
import xyz.lychee.dynamicdns.shared.DynuHook;
import xyz.lychee.dynamicdns.shared.MessageReceiver;
import xyz.lychee.dynamicdns.shared.NgrokHook;

import javax.crypto.Cipher;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class BukkitMain extends JavaPlugin {
    private static @Getter BukkitMain instance;
    private YamlDocument config;
    private BukkitTask task;
    private Metrics metrics;
    private NgrokHook ngrok;
    private DynuHook dynu;

    public void onEnable() {
        instance = this;
        this.metrics = new Metrics(this, 27000);

        try {
            this.config = YamlDocument.create(
                    new File(this.getDataFolder(), "config.yml"),
                    this.getResource("config.yml"),
                    GeneralSettings.DEFAULT,
                    LoaderSettings.builder().setCreateFileIfAbsent(true).setAutoUpdate(true).build(),
                    DumperSettings.DEFAULT
            );
            this.config.save();

            this.reload();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void reload() throws Exception {
        this.config.reload();

        this.ngrok = new NgrokHook(this.getLogger(), new InetSocketAddress(Bukkit.getIp(), Bukkit.getPort()), this.config);
        this.dynu = new DynuHook(this.getLogger(), this.config);

        InetSocketAddress address = this.config.getBoolean("ngrok.enabled", false)
                ? AddressUtil.parseAddressFromUri(this.ngrok.load())
                : new InetSocketAddress(AddressUtil.checkPublicIp(), Bukkit.getPort());

        if (this.config.getBoolean("dynu.enabled", false)) {
            this.dynu.load(address);
        }

        this.loadSocketClient();
    }

    public void loadSocketClient() throws Exception {
        if (!this.config.getBoolean("socket_client.enabled", false)) {
            return;
        }

        String token = this.config.getString("socket_client.token");
        if (token == null) {
            this.getLogger().warning("Value socket_client.token has not been set!");
            return;
        }

        String address = this.config.getString("socket_client.target_address");
        if (address == null) {
            this.getLogger().warning("Value socket_client.target_address has not been set!");
            return;
        }

        String[] split = address.split(":");
        if (split.length != 2) {
            this.getLogger().warning("Invalid socket_client target address format. Expected format: host:port");
            return;
        }

        if (this.task != null) {
            this.task.cancel();
        }

        Cipher cipher = MessageReceiver.createCipher(token, Cipher.ENCRYPT_MODE);

        String message = this.config.getString("socket_client.name") + ":" + getServer().getPort();
        byte[] encrypted = cipher.doFinal(message.getBytes(StandardCharsets.UTF_8));

        InetSocketAddress socketAddress = new InetSocketAddress(split[0], Integer.parseInt(split[1]));

        int interval = 20 * this.config.getInt("socket_client.interval", 30);
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try (Socket socket = new Socket()) {
                socket.setSoTimeout(5000);
                socket.connect(socketAddress, 5000);

                socket.getOutputStream().write(encrypted);
            } catch (IOException e) {
                this.getLogger().warning("Failed to send update: " + e.getMessage());
            }
        }, interval, interval);
    }

    public void onDisable() {
        if (this.task != null) this.task.cancel();
        if (this.metrics != null) this.metrics.shutdown();
        if (this.ngrok != null) this.ngrok.unload();
    }
}
