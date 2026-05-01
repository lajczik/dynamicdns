package xyz.lychee.dynamicdns.shared;

import dev.dejvokep.boostedyaml.YamlDocument;
import lombok.Getter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

@Getter
public class DynuHook {
    private static final String API_BASE = "https://api.dynu.com/v2";
    private static final String JSON_CONTENT = "application/json";

    private final Logger logger;
    private final YamlDocument config;
    private int port;
    private String domain;

    public DynuHook(Logger logger, YamlDocument config) {
        this.logger = logger;
        this.config = config;
    }

    public void load(InetSocketAddress address) {
        this.domain = this.config.getString("dynu.domain");

        this.update(address);
    }

    public boolean update(InetSocketAddress address) {
        try {
            String credentials = this.config.getString("dynu.client") + ":" + this.config.getString("dynu.secret");
            String token = getToken(credentials);

            if (token == null) {
                logger.severe("Failed to obtain authentication token");
                return false;
            }

            String domainId = getDomainInfo(token);
            if (domainId == null) {
                logger.severe("Failed to retrieve domain information");
                return false;
            }

            String dnsRecordId = getDNSRecords(token, domainId);
            if (dnsRecordId == null) {
                logger.severe("Failed to retrieve DNS records");
                return false;
            }

            return updateDNS(token, domainId, dnsRecordId) && updateIP(token, domainId, address.getHostString());

        } catch (Exception e) {
            logger.severe("Error during DNS update: " + e.getMessage());
            return false;
        }
    }

    private String getToken(String credentials) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_BASE + "/oauth2/token");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", JSON_CONTENT);
            conn.setRequestProperty("Authorization", "Basic " +
                    java.util.Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String response = reader.readLine();
                return response.split("\"")[3];
            }
        } catch (Exception e) {
            logger.severe("Failed to get token: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String getDomainInfo(String token) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_BASE + "/dns");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", JSON_CONTENT);
            conn.setRequestProperty("Authorization", "Bearer " + token);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String response = reader.readLine();
                String[] parts = response.split("\"");
                this.domain = parts[9];
                return parts[6].replace(":", "").replace(",", "");
            }
        } catch (Exception e) {
            logger.severe("Failed to get domain info: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String getDNSRecords(String token, String domainId) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_BASE + "/dns/" + domainId + "/record");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", JSON_CONTENT);
            conn.setRequestProperty("Authorization", "Bearer " + token);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String response = reader.readLine();
                String[] records = response.split("\"");

                for (int i = 0; i < records.length; i++) {
                    if (records[i].equals("recordType") &&
                            records[i + 2].equals("SRV") &&
                            records[i - 15] != null) {
                        return records[i - 15].replaceAll("[^0-9]", "");
                    }
                }
            }
        } catch (Exception e) {
            logger.severe("Failed to get DNS records: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private boolean updateIP(String token, String domainId, String ip) {
        HttpURLConnection conn = null;
        try {
            URL url = URI.create(API_BASE + "/dns/" + domainId).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Accept", JSON_CONTENT);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", JSON_CONTENT);
            conn.setDoOutput(true);

            String jsonPayload = String.format("{" +
                    "\"name\":\"%s\"," +
                    "\"group\":\"default\"," +
                    "\"ipv4Address\":\"%s\"," +
                    "\"ttl\":120," +
                    "\"ipv4\":true," +
                    "\"ipv6\":false," +
                    "\"ipv4WildcardAlias\":false," +
                    "\"ipv6WildcardAlias\":false," +
                    "\"allowZoneTransfer\":false," +
                    "\"dnssec\":false" +
                    "}", domain, ip);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                logger.info("Successfully updated IP address");
                return true;
            }
        } catch (Exception e) {
            logger.severe("Failed to update IP: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return false;
    }

    private boolean updateDNS(String token, String domainId, String dnsRecordId) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_BASE + "/dns/" + domainId + "/record/" + dnsRecordId);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Accept", JSON_CONTENT);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", JSON_CONTENT);
            conn.setDoOutput(true);

            String jsonPayload = String.format(
                    "{\"nodeName\":\"_minecraft._tcp\",\"recordType\":\"SRV\",\"ttl\":120," +
                            "\"state\":true,\"group\":\"\",\"host\":\"%s\",\"priority\":10," +
                            "\"weight\":5,\"port\":%d}", domain, port);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            }

            return conn.getResponseCode() == 200;

        } catch (Exception e) {
            logger.severe("Failed to update DNS: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}