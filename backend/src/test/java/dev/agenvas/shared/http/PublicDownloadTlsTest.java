package dev.agenvas.shared.http;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic CA and loopback proxies verify independent origin/proxy TLS identities. */
class PublicDownloadTlsTest {
    @TempDir static Path temporary;
    static SSLContext tls;
    static X509TrustManager trust;
    @BeforeAll static void certificate()throws Exception {
        Path store=temporary.resolve("synthetic.p12");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-alias","synthetic","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=origin.test","-ext","SAN=dns:origin.test,ip:127.0.0.1","-storetype","PKCS12","-keystore",store.toString(),"-storepass","synthetic-test-only","-noprompt").redirectErrorStream(true).start();
        String result=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(result).isZero();
        var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store)){keys.load(input,"synthetic-test-only".toCharArray());}
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"synthetic-test-only".toCharArray());
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);trust=(X509TrustManager)tm.getTrustManagers()[0];
        tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),null);
    }
    @Test void httpAndHttpsProxyPreserveOriginTlsAndHttpHostWhileConnectingNumericIp()throws Exception {
        for(var type:List.of(PublicDownloadProperties.ProxyType.HTTP,PublicDownloadProperties.ProxyType.HTTPS))
            try(var server=server(type);var executor=Executors.newSingleThreadExecutor()) {
                var received=executor.submit(()->serve(server));
                var client=client(type,server.getLocalPort());
                try(var response=client.newCall(new Request.Builder().url("https://origin.test/synthetic").build()).execute()) {
                    assertThat(response.code()).isEqualTo(200);assertThat(response.body().string()).isEqualTo("synthetic");
                }
                String request=received.get(5,TimeUnit.SECONDS);
                assertThat(request).startsWith("CONNECT 185.199.108.133:443 HTTP/1.1").contains("Host: origin.test\r\n","SNI=origin.test").doesNotContain("Authorization","Cookie");
            }
    }
    @Test void originHostnameMismatchIsRejectedAfterProxyTunnel()throws Exception {
        try(var server=server(PublicDownloadProperties.ProxyType.HTTP);var executor=Executors.newSingleThreadExecutor()) {
            var received=executor.submit(()->{try{return serve(server);}catch(IOException expected){return "TLS rejected";}});
            assertThatThrownBy(()->client(PublicDownloadProperties.ProxyType.HTTP,server.getLocalPort()).newCall(new Request.Builder().url("https://wrong.test/synthetic").build()).execute())
                .isInstanceOf(SSLPeerUnverifiedException.class);
            received.get(5,TimeUnit.SECONDS);
        }
    }
    @Test void httpsProxyHasItsOwnHostnameCheckBeforeAnyConnectRequest()throws Exception {
        try(var server=server(PublicDownloadProperties.ProxyType.HTTPS);var executor=Executors.newSingleThreadExecutor()) {
            var received=executor.submit(()->{try(var peer=(SSLSocket)server.accept()){peer.setSoTimeout(2000);try{peer.startHandshake();return peer.getInputStream().read();}catch(IOException rejected){return -1;}}});
            var p=new PublicDownloadProperties(null,List.of(),PublicDownloadProperties.ProxyType.HTTPS,"localhost",server.getLocalPort());
            try(var socket=new PublicDownloadSocketFactory(p,tls.getSocketFactory()).createSocket()) {
                assertThatThrownBy(()->socket.connect(new InetSocketAddress(InetAddress.getByName("185.199.108.133"),443),2000))
                    .isInstanceOf(PublicDownloadException.class).hasMessage("PUBLIC_DOWNLOAD_TLS_FAILED");
            }
            assertThat(received.get(5,TimeUnit.SECONDS)).isEqualTo(-1);
        }
    }
    private ServerSocket server(PublicDownloadProperties.ProxyType type)throws IOException {
        ServerSocket server=type==PublicDownloadProperties.ProxyType.HTTPS?tls.getServerSocketFactory().createServerSocket():new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0));return server;
    }
    private OkHttpClient client(PublicDownloadProperties.ProxyType type,int port)throws Exception {
        var p=new PublicDownloadProperties(null,List.of(),type,"127.0.0.1",port);
        return new PublicDownloadHttpClients(p).create(Duration.ofSeconds(2),Duration.ofSeconds(3),Duration.ofSeconds(5)).newBuilder()
            .dns(host->List.of(InetAddress.getByName("185.199.108.133")))
            .socketFactory(new PublicDownloadSocketFactory(p,tls.getSocketFactory()))
            .sslSocketFactory(tls.getSocketFactory(),trust).protocols(List.of(Protocol.HTTP_1_1)).build();
    }
    private String serve(ServerSocket server)throws Exception {
        try(var peer=server.accept()) {
            peer.setSoTimeout(4000);String connect=PublicDownloadSocketFactoryTest.headers(peer.getInputStream());
            peer.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));peer.getOutputStream().flush();
            try(var origin=(SSLSocket)tls.getSocketFactory().createSocket(peer,"origin.test",443,false)) {
                origin.setUseClientMode(false);origin.setSoTimeout(4000);origin.startHandshake();
                String request=PublicDownloadSocketFactoryTest.headers(origin.getInputStream());
                origin.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 9\r\nConnection: close\r\n\r\nsynthetic".getBytes(StandardCharsets.US_ASCII));origin.getOutputStream().flush();return connect+request+"SNI="+((ExtendedSSLSession)origin.getSession()).getRequestedServerNames().stream().filter(n->n instanceof SNIHostName).map(n->((SNIHostName)n).getAsciiName()).findFirst().orElse("missing");
            }
        }
    }
}
