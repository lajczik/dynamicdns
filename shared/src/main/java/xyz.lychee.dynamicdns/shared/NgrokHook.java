package xyz.lychee.dynamicdns.shared;

import com.github.alexdlaird.ngrok.NgrokClient;
import com.github.alexdlaird.ngrok.conf.JavaNgrokConfig;
import com.github.alexdlaird.ngrok.installer.NgrokInstaller;
import com.github.alexdlaird.ngrok.installer.NgrokVersion;
import com.github.alexdlaird.ngrok.protocol.CreateTunnel;
import com.github.alexdlaird.ngrok.protocol.Proto;
import com.github.alexdlaird.ngrok.protocol.Region;
import com.github.alexdlaird.ngrok.protocol.Tunnel;
import dev.dejvokep.boostedyaml.YamlDocument;
import lombok.Getter;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.logging.Logger;

@Getter
public class NgrokHook {
    private final Logger logger;
    private final InetSocketAddress address;
    private final YamlDocument config;
    private NgrokClient ngrokClient;
    private Tunnel tunnel;

    public NgrokHook(Logger logger, InetSocketAddress address, YamlDocument config) {
        this.logger = logger;
        this.address = address;
        this.config = config;
    }

    public URI load() throws IllegalArgumentException {
        final JavaNgrokConfig javaNgrokConfig = new JavaNgrokConfig.Builder()
                .withNgrokVersion(NgrokVersion.V3)
                .withRegion(Region.valueOf(this.config.getString("ngrok.region", "US").toUpperCase()))
                .build();

        this.ngrokClient = new NgrokClient.Builder()
                .withNgrokInstaller(new NgrokInstaller())
                .withJavaNgrokConfig(javaNgrokConfig)
                .build();

        this.ngrokClient.getNgrokProcess().setAuthToken(this.config.getString("ngrok.token"));

        final CreateTunnel createTunnel = new CreateTunnel.Builder()
                .withProto(Proto.TCP)
                .withAddr(this.address.getHostString() + ":" + this.address.getPort())
                .build();

        this.tunnel = this.ngrokClient.connect(createTunnel);

        this.getLogger().info("Listening server on address " + this.getTunnel().getPublicUrl());
        return URI.create(this.getTunnel().getPublicUrl());
    }

    public void unload() {
        if (this.ngrokClient != null) {
            if (this.tunnel != null) {
                this.ngrokClient.disconnect(this.tunnel.getPublicUrl());
            }
            this.ngrokClient.kill();
        }
    }
}