package xyz.lychee.dynamicdns.shared;

import dev.dejvokep.boostedyaml.YamlDocument;
import lombok.Getter;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

@Getter
public abstract class MessageReceiver {
    private final Map<String, InetSocketAddress> servers = new ConcurrentHashMap<>();
    private final YamlDocument config;
    private final Logger logger;
    private Cipher cipher;

    public MessageReceiver(Logger logger, YamlDocument config) {
        this.config = config;
        this.logger = logger;
    }

    public static Cipher createCipher(String token, int mode) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException {
        byte[] keyBytes = new byte[16];
        byte[] tokenBytes = token.getBytes();
        System.arraycopy(tokenBytes, 0, keyBytes, 0, Math.min(tokenBytes.length, keyBytes.length));

        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(mode, new SecretKeySpec(keyBytes, "AES"));
        return cipher;
    }

    public void loadServers(String token) {
        try {
            this.cipher = MessageReceiver.createCipher(token, Cipher.DECRYPT_MODE);
        } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException e) {
            this.logger.log(Level.SEVERE, "Failed to load config", e);
        }

        List<String> servers = this.config.getStringList("servers");
        if (servers != null && !servers.isEmpty()) {
            for (String server : servers) {
                String[] split = server.split("-");
                this.servers.put(split[0], AddressUtil.parseAddress(split[1]));
            }
        }
    }

    private void saveServers() throws IOException {
        List<String> list = new ArrayList<>();
        for (Map.Entry<String, InetSocketAddress> entry : this.servers.entrySet()) {
            String name = entry.getKey();
            InetSocketAddress address = entry.getValue();
            list.add(name + '-' + address.getHostString() + ':' + address.getPort());
        }

        this.config.set("servers", list);
        this.config.save();
    }

    public abstract void updateServers();

    public void start(InetSocketAddress address) {
        Thread t = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(address.getPort())) {
                this.logger.info("Started listening " + address + " for ip changes!");

                while (true) {
                    try (Socket clientSocket = serverSocket.accept()) {
                        clientSocket.setSoTimeout(5000);

                        byte[] decrypted = this.cipher.doFinal(this.readAllBytes(clientSocket.getInputStream()));
                        String message = new String(decrypted, StandardCharsets.UTF_8);

                        String[] data = message.split(":");

                        if (data.length >= 2) {
                            String serverName = data[0];
                            int serverPort = Integer.parseInt(data[1]);
                            InetSocketAddress serverAddress = new InetSocketAddress(clientSocket.getInetAddress(), serverPort);

                            this.servers.put(serverName, serverAddress);

                            this.updateServers();
                            this.saveServers();
                        } else {
                            this.logger.warning("Invalid message format: " + message);
                        }
                    } catch (Exception ex) {
                        this.logger.warning("An error occurred while reading the message: " + ex.getMessage());
                    }
                }
            } catch (IOException ex) {
                this.logger.log(Level.SEVERE, "Failed to start UDP server", ex);
            }
        });

        t.setName("DynamicDNS-Server");
        t.start();
    }

    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int bytesRead;

        while ((bytesRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }

        return buffer.toByteArray();
    }
}
