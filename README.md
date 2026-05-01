![dynamicdns](https://i.imgur.com/Q5TXoQp.png)

This plugin keeps your subserver always reachable, even if it's hosted on a dynamic IP address. It's perfect for setups where a server (like a Minecraft subserver) runs on a home computer or any machine without a static IP. You don’t have to manually update IPs anymore – it just works.
No need for external DNS tricks or restarts – the IP stays in sync automatically, in real-time.

## How it works?
The proxy server listens for incoming connections using a ServerSocket. When a new connection attempt is detected, the plugin checks if the subserver’s IP address has changed. If it has, the plugin updates the subserver’s address automatically before forwarding the connection. This way, the proxy always routes traffic to the correct, current IP of the subserver.

## How to install?
1. Upload the plugin to your Velocity/BungeeCord proxy server.
2. Start the proxy and adjust the configuration to fit your setup.
3. Copy the secret key from the token.txt file generated on the proxy server.
4. Upload the plugin to your subserver (e.g. a Spigot-based survival server).
5. In the subserver config, set the server ID that should have its IP updated.
6. Paste the secret key from the proxy into the Spigot plugin’s configuration.

