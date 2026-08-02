package xyz.lychee.dynamicdns.shared

import com.github.alexdlaird.ngrok.NgrokClient
import com.github.alexdlaird.ngrok.conf.JavaNgrokConfig
import com.github.alexdlaird.ngrok.installer.NgrokInstaller
import com.github.alexdlaird.ngrok.installer.NgrokVersion
import com.github.alexdlaird.ngrok.protocol.CreateTunnel
import com.github.alexdlaird.ngrok.protocol.Proto
import com.github.alexdlaird.ngrok.protocol.Region
import com.github.alexdlaird.ngrok.protocol.Tunnel
import dev.dejvokep.boostedyaml.YamlDocument
import java.net.InetSocketAddress
import java.net.URI
import java.util.Locale
import java.util.logging.Logger

class NgrokHook(
    val logger: Logger,
    val address: InetSocketAddress,
    val config: YamlDocument
) {
    var ngrokClient: NgrokClient? = null
        private set
    var tunnel: Tunnel? = null
        private set

    @Throws(IllegalArgumentException::class)
    fun load(): URI {
        val regionString = this.config.getString("ngrok.region", "US").uppercase(Locale.getDefault())
        val javaNgrokConfig = JavaNgrokConfig.Builder()
            .withNgrokVersion(NgrokVersion.V3)
            .withRegion(Region.valueOf(regionString))
            .withAuthToken(this.config.getString("ngrok.token"))
            .build()

        this.ngrokClient = NgrokClient.Builder()
            .withNgrokInstaller(NgrokInstaller())
            .withJavaNgrokConfig(javaNgrokConfig)
            .build()

        val createTunnel = CreateTunnel.Builder()
            .withProto(Proto.TCP)
            .withAddr("${this.address.hostString}:${this.address.port}")
            .build()

        val activeTunnel = this.ngrokClient!!.connect(createTunnel)
        this.tunnel = activeTunnel

        this.logger.info("Listening server on address ${activeTunnel.publicUrl}")
        return URI.create(activeTunnel.publicUrl)
    }

    fun unload() {
        val client = this.ngrokClient
        if (client != null) {
            val activeTunnel = this.tunnel
            if (activeTunnel != null) {
                client.disconnect(activeTunnel.publicUrl)
            }
            client.kill()
        }
    }
}
