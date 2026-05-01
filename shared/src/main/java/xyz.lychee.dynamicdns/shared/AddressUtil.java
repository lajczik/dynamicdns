package xyz.lychee.dynamicdns.shared;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;

public abstract class AddressUtil {
    public static URI parseAddressAsUri(String ip) {
        if (ip == null) {
            throw new NullPointerException("ip");
        }

        URI uri = URI.create("tcp://" + ip);
        if (uri.getHost() == null) {
            throw new IllegalStateException("Invalid hostname/IP " + ip);
        }

        return uri;
    }

    public static InetSocketAddress parseAddressFromUri(URI uri) {
        int port = uri.getPort() == -1 ? 25565 : uri.getPort();
        try {
            InetAddress ia = InetAddress.getByName(uri.getHost());
            return new InetSocketAddress(ia, port);
        } catch (UnknownHostException e) {
            return InetSocketAddress.createUnresolved(uri.getHost(), port);
        }
    }

    public static InetSocketAddress parseAddress(String ip) {
        return parseAddressFromUri(parseAddressAsUri(ip));
    }

    public static InetSocketAddress parseAndResolveAddress(String ip) {
        URI uri = parseAddressAsUri(ip);
        int port = uri.getPort() == -1 ? 25565 : uri.getPort();
        return new InetSocketAddress(uri.getHost(), port);
    }

    public static InetAddress checkPublicIp() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                URI.create("https://checkip.amazonaws.com").toURL().openStream()))) {
            return InetAddress.getByName(in.readLine());
        } catch (IOException e) {
            return InetAddress.getLoopbackAddress();
        }
    }
}
