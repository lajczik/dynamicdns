![dynamicdns](https://i.imgur.com/Q5TXoQp.png)

**DynamicDNS** is a lightweight, cross-platform Minecraft plugin and proxy extension that keeps your subservers connected and reachable even when hosted on dynamic IP addresses (such as home servers or VPS nodes without static IPs).

No separate port forward, custom daemons or DNS propagation delays are required — address changes are synchronized in real-time directly through Minecraft's native networking layer.

## Features

- **Port-Free Subserver Synchronization:** Subservers ping the proxy using native Minecraft Handshake packets (`sync.<token>.<name>.<port>`). No extra open ports or firewall adjustments are needed on the proxy.
- **Proxy Configuration Persistence:** Automatically registers, updates, and persists subserver addresses directly into `velocity.toml` (Velocity) or `config.yml` (BungeeCord).
- **Built-in ngrok Tunneling:** Automatically exposes local servers via ngrok TCP tunnels and propagates the assigned public tunnel endpoint to your proxy or Dynu.
- **Dynu DDNS Hook:** Updates Dynu Dynamic DNS domains and SRV records automatically with your current public IP address or ngrok hostname.
- **Integrated Modrinth Update Checker:** Checks for new releases against Modrinth API asynchronously in the background.
- **All-in-One Universal Jar:** One unified artifact supports all platforms.

## Supported Platforms

| Platform | Type | Minimum Version |
| :--- | :--- | :--- |
| **Velocity** | Proxy | 3.x / 4.x |
| **BungeeCord / Waterfall** | Proxy | 1.8.8 – 26.4 |
| **Paper / Purpur / Spigot / Folia** | Subserver | 1.8.8 – 26.4 |
| **Sponge** | Subserver | SpongeAPI 8+ (Minecraft 1.16.5+) |

## How It Works

```
+-------------------------------------------------------------------+
|               Subserver (Spigot / Paper / Sponge)                 |
|                                                                   |
|   1. Periodically sends handshake ping:                           |
|      virtualHost = "sync.<token>.<server_name>.<port>"            |
+---------------------------------+---------------------------------+
                                  |
               (Standard Minecraft Port, e.g. 25565)
                                  v
+-------------------------------------------------------------------+
|                  Proxy (Velocity / BungeeCord)                    |
|                                                                   |
|   2. Intercepts incoming handshake/ping connection                |
|   3. Validates secret token                                       |
|   4. Updates subserver address in proxy server registry           |
|   5. Saves updated address to velocity.toml or config.yml         |
|   6. Closes the sync connection immediately                       |
+-------------------------------------------------------------------+
```

1. The subserver runs a repeating background task sending a handshake ping to the proxy's regular Minecraft address and port.
2. The payload contains the shared secret token, subserver identifier, and target port/IP.
3. The proxy verifies the token, updates the subserver route dynamically in memory, persists it to file, and terminates the sync ping handshake without proxying player traffic.

---

## Installation & Setup

### 1. Proxy Setup (Velocity or BungeeCord)
1. Drop `DynamicDNS.jar` into your proxy's `plugins/` directory.
2. Start or restart the proxy to generate `plugins/DynamicDNS/config.yml`.
3. Open `plugins/DynamicDNS/config.yml` and enable synchronization:
   ```yaml
   server_sync:
     enabled: true
     token: "YOUR_SECRET_TOKEN" # If left blank, a UUID token is automatically generated on startup
   ```
4. Copy the secret `token` for use on your subservers.

### 2. Subserver Setup (Paper, Spigot or Sponge)
1. Drop `DynamicDNS.jar` into your subserver's `plugins/` (or `mods/` on Sponge) directory.
2. Start or restart the server to generate `config.yml`.
3. Configure `plugins/DynamicDNS/config.yml`:
   ```yaml
   server_sync:
     enabled: true
     token: "YOUR_SECRET_TOKEN"          # Must match the token set on your proxy
     name: "survival"                    # The server ID registered on the proxy
     target_address: "proxy.host:25565"  # Host and port of your proxy server
     interval: 30                        # Sync interval in seconds
   ```
4. Reload the configuration with `/dynamicdns reload` or restart the subserver. The proxy will automatically register the subserver's current IP and port.

## Configuration Reference (`config.yml`)

```yaml
# Global debug logging - logs every sync ping sent and heartbeat received
debug: false

# Automatically check for updates on startup via Modrinth
check_updates: true

# Configuration for ngrok - creates secure tunnels to local servers
ngrok:
  enabled: false
  token: ""
  region: "US"

# Configuration for Dynu Dynamic DNS - updates a domain record with current IP address
dynu:
  enabled: false
  domain: "example.dynu.com"
  client: ""
  secret: ""

# Subserver address synchronization via Minecraft Handshake ping
server_sync:
  # Enable synchronization (true on proxy to accept pings; true on subserver to send pings)
  enabled: false
  # Shared secret token for authenticating subservers with the proxy
  token: ""
  # Subserver identifier configured on the proxy (Subserver only, e.g. "survival")
  name: "survival"
  # Target proxy address and port (Subserver only, e.g. "123.123.123.123:25565")
  target_address: "123.123.123.123:25565"
  # Interval in seconds between sync pings sent to proxy (Subserver only)
  interval: 30
```

## Commands & Permissions

| Command | Aliases | Description | Permission |
| :--- | :--- | :--- | :--- |
| `/dynamicdns reload` | `/ddns reload` | Reloads `config.yml` and restarts active hooks | `dynamicdns.reload` or `dynamicdns.admin` |
| `/dynamicdns update` | `/ddns update` | Checks Modrinth for available plugin updates | `dynamicdns.reload` or `dynamicdns.admin` |

*By default, permissions are granted to server operators (`op`).*

## Building from Source

Requirements:
- JDK 17 or newer (Gradle toolchain manages Java 25 / multi-release compilation)

```bash
git clone https://github.com/lychee/dynamicdns.git
cd dynamicdns
./gradlew build
```

The compiled universal JAR will be located at:
`build/libs/DynamicDNS-2.X.jar`

## License

This project is licensed under the **GNU General Public License v3.0** (GPLv3). See the [LICENSE](LICENSE) file for details.
