package dev.agenvas.shared.http;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Local synthetic proxy protocol checks; no Internet requests or production endpoint overrides. */
class PublicDownloadSocketFactoryTest {
    @Test
    void httpConnectAcceptsNumericFakeIpAndDoesNotForwardCredentials() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newSingleThreadExecutor()) {
            var request = executor.submit(() -> {
                try (var peer = server.accept()) {
                    peer.setSoTimeout(2000);
                    String headers = headers(peer.getInputStream());
                    peer.getOutputStream()
                            .write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    return headers;
                }
            });
            var factory = factory(PublicDownloadProperties.ProxyType.HTTP, server.getLocalPort());
            try (var socket = factory.createSocket()) {
                socket.connect(new InetSocketAddress(InetAddress.getByName("198.18.1.3"), 443), 2000);
            }
            assertThat(request.get(3, TimeUnit.SECONDS))
                    .isEqualTo("CONNECT 198.18.1.3:443 HTTP/1.1\r\nHost: 198.18.1.3:443\r\n\r\n");
        }
    }
    @Test
    void socksUsesNumericIpv4WireTypeAndNeverSendsOriginDomain() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newSingleThreadExecutor()) {
            var request = executor.submit(() -> {
                try (var peer = server.accept()) {
                    peer.setSoTimeout(2000);
                    var in = peer.getInputStream();
                    var out = peer.getOutputStream();
                    int version = in.read(), count = in.read();
                    assertThat(count).isEqualTo(1);
                    assertThat(in.readNBytes(count)).containsExactly(0);
                    assertThat(version).isEqualTo(5);
                    out.write(new byte[]{5, 0});
                    byte[] connect = in.readNBytes(10);
                    out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
                    return connect;
                }
            });
            try (var socket = factory(PublicDownloadProperties.ProxyType.SOCKS5, server.getLocalPort())
                    .createSocket()) {
                socket.connect(new InetSocketAddress(InetAddress.getByName("185.199.108.133"), 443), 2000);
            }
            assertThat(request.get(3, TimeUnit.SECONDS)).containsExactly(5, 1, 0, 1, (byte) 185, (byte) 199, 108,
                    (byte) 133, 1, (byte) 187);
        }
    }
    @Test
    void proxy407IsStableAndCannotTriggerAuthenticationOrOriginBytes() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newSingleThreadExecutor()) {
            var served = executor.submit(() -> {
                try (var peer = server.accept()) {
                    peer.setSoTimeout(2000);
                    headers(peer.getInputStream());
                    peer.getOutputStream().write(
                            "HTTP/1.1 407 Private upstream detail\r\nProxy-Authenticate: Basic realm=secret\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                    return peer.getInputStream().read();
                }
            });
            try (var socket = factory(PublicDownloadProperties.ProxyType.HTTP, server.getLocalPort()).createSocket()) {
                assertThatThrownBy(() -> socket
                        .connect(new InetSocketAddress(InetAddress.getByName("185.199.108.133"), 443), 2000))
                        .isInstanceOf(IOException.class).hasMessage("PUBLIC_DOWNLOAD_PROXY_AUTH_REQUIRED")
                        .satisfies(e -> assertThat(PublicDownloadException.reason(e))
                                .isEqualTo(PublicDownloadException.Reason.PROXY_AUTH_REQUIRED));
            }
            assertThat(served.get(3, TimeUnit.SECONDS)).isEqualTo(-1);
        }
    }
    @Test
    void boundedTunnelHeadersAndUnresolvedDestinationsFailClosed() throws Exception {
        for (var target : List.of(InetSocketAddress.createUnresolved("raw.githubusercontent.com", 443)))
            try (var socket = factory(PublicDownloadProperties.ProxyType.HTTP, 9).createSocket()) {
                assertThatThrownBy(() -> socket.connect(target, 200)).isInstanceOf(IOException.class)
                        .satisfies(e -> assertThat(PublicDownloadException.reason(e))
                                .isEqualTo(PublicDownloadException.Reason.DNS_FAILED));
            }
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newSingleThreadExecutor()) {
            var served = executor.submit(() -> {
                try (var peer = server.accept()) {
                    headers(peer.getInputStream());
                    peer.getOutputStream().write(("HTTP/1.1 200 OK\r\nX: " + "x".repeat(9000) + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                }
                return true;
            });
            try (var socket = factory(PublicDownloadProperties.ProxyType.HTTP, server.getLocalPort()).createSocket()) {
                assertThatThrownBy(() -> socket
                        .connect(new InetSocketAddress(InetAddress.getByName("185.199.108.133"), 443), 2000))
                        .isInstanceOf(IOException.class)
                        .satisfies(e -> assertThat(PublicDownloadException.reason(e))
                                .isEqualTo(PublicDownloadException.Reason.PROXY_ERROR));
            }
            served.get(3, TimeUnit.SECONDS);
        }
    }
    @Test
    void socksAuthenticationRequestCannotSendUsernameOrGlobalAuthenticatorCredentials() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newSingleThreadExecutor()) {
            var served = executor.submit(() -> {
                try (var peer = server.accept()) {
                    peer.setSoTimeout(2000);
                    assertThat(peer.getInputStream().readNBytes(3)).containsExactly(5, 1, 0);
                    peer.getOutputStream().write(new byte[]{5, 2});
                    return peer.getInputStream().read();
                }
            });
            try (var socket = factory(PublicDownloadProperties.ProxyType.SOCKS5, server.getLocalPort())
                    .createSocket()) {
                assertThatThrownBy(() -> socket
                        .connect(new InetSocketAddress(InetAddress.getByName("185.199.108.133"), 443), 2000))
                        .isInstanceOf(PublicDownloadException.class).hasMessage("PUBLIC_DOWNLOAD_PROXY_AUTH_REQUIRED");
            }
            assertThat(served.get(3, TimeUnit.SECONDS)).isEqualTo(-1);
        }
    }
    @Test
    void ipv6ProxyDestinationsUseNumericWireBytesAndBracketedConnectAuthority() throws Exception {
        var destination = InetAddress.getByName("2606:50c0:8000::154");
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newSingleThreadExecutor()) {
            var served = executor.submit(() -> {
                try (var peer = server.accept()) {
                    peer.setSoTimeout(2000);
                    var in = peer.getInputStream();
                    var out = peer.getOutputStream();
                    in.readNBytes(3);
                    out.write(new byte[]{5, 0});
                    assertThat(in.readNBytes(4)).containsExactly(5, 1, 0, 4);
                    byte[] numeric = in.readNBytes(16);
                    in.readNBytes(2);
                    out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
                    return numeric;
                }
            });
            try (var socket = factory(PublicDownloadProperties.ProxyType.SOCKS5, server.getLocalPort())
                    .createSocket()) {
                socket.connect(new InetSocketAddress(destination, 443), 2000);
            }
            assertThat(served.get(3, TimeUnit.SECONDS)).containsExactly(destination.getAddress());
        }
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newSingleThreadExecutor()) {
            var served = executor.submit(() -> {
                try (var peer = server.accept()) {
                    peer.setSoTimeout(2000);
                    String request = headers(peer.getInputStream());
                    peer.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    return request;
                }
            });
            try (var socket = factory(PublicDownloadProperties.ProxyType.HTTP, server.getLocalPort()).createSocket()) {
                socket.connect(new InetSocketAddress(destination, 443), 2000);
            }
            assertThat(served.get(3, TimeUnit.SECONDS)).startsWith("CONNECT [2606:50c0:8000:0:0:0:0:154]:443 HTTP/1.1");
        }
    }
    @Test
    void closingTheSocketInterruptsAnUnfinishedProxyHandshake() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newFixedThreadPool(2)) {
            var received = new CountDownLatch(1);
            var served = executor.submit(() -> {
                try (var peer = server.accept()) {
                    peer.setSoTimeout(2000);
                    headers(peer.getInputStream());
                    received.countDown();
                    return peer.getInputStream().read();
                }
            });
            var socket = factory(PublicDownloadProperties.ProxyType.HTTP, server.getLocalPort()).createSocket();
            var connecting = executor.submit(() -> {
                try {
                    socket.connect(new InetSocketAddress(InetAddress.getByName("185.199.108.133"), 443), 2000);
                    return false;
                } catch (IOException expected) {
                    return true;
                }
            });
            assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
            socket.close();
            assertThat(connecting.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(served.get(1, TimeUnit.SECONDS)).isEqualTo(-1);
        }
    }
    private PublicDownloadSocketFactory factory(PublicDownloadProperties.ProxyType type, int port) {
        return new PublicDownloadSocketFactory(new PublicDownloadProperties(null, List.of(), type, "127.0.0.1", port));
    }
    static String headers(InputStream in) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            bytes.write(c);
            if (bytes.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))
                return bytes.toString(StandardCharsets.US_ASCII);
            if (bytes.size() > 10000)
                throw new IOException();
        }
        throw new EOFException();
    }
}
