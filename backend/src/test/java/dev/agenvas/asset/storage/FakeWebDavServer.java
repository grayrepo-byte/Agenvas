package dev.agenvas.asset.storage;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic HTTP WebDAV fixture; production DNS/TLS is bypassed only in this test transport. */
final class FakeWebDavServer implements AutoCloseable {
    final HttpServer server;
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final AtomicInteger gets = new AtomicInteger(), puts = new AtomicInteger();
    final AtomicBoolean networkInTransaction = new AtomicBoolean();
    boolean ignoreRange, failMoveAfterCommit, failPut, failThumbnail, redirect;
    FakeWebDavServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int code = 500;
            byte[] body = new byte[0];
            if (redirect) { exchange.getResponseHeaders().set("Location", "https://other.example.com/private"); code = 302; }
            else switch (exchange.getRequestMethod()) {
                case "MKCOL" -> code = 201;
                case "PUT" -> {
                    puts.incrementAndGet();
                    if (failPut || failThumbnail && path.contains(".thumb.png")) { code = 503; failThumbnail = false; }
                    else { code = files.putIfAbsent(path, exchange.getRequestBody().readAllBytes()) == null ? 201 : 412; }
                }
                case "MOVE" -> {
                    String destination = java.net.URI.create(exchange.getRequestHeaders().getFirst("Destination")).getPath();
                    if (!"F".equals(exchange.getRequestHeaders().getFirst("Overwrite"))) code = 400;
                    else if (files.containsKey(destination)) code = 412;
                    else {
                        files.put(destination, files.remove(path));
                        code = failMoveAfterCommit ? 503 : 201;
                        failMoveAfterCommit = false;
                    }
                }
                case "DELETE" -> { files.remove(path); code = 204; }
                case "HEAD", "GET" -> {
                    byte[] bytes = files.get(path);
                    if (bytes == null) code = 404;
                    else {
                        body = bytes; code = 200;
                        String range = exchange.getRequestHeaders().getFirst("Range");
                        if ("GET".equals(exchange.getRequestMethod())) {
                            gets.incrementAndGet();
                            if (range != null && !ignoreRange) {
                                String[] parts = range.substring(6).split("-");
                                int start = Integer.parseInt(parts[0]), end = Integer.parseInt(parts[1]);
                                body = Arrays.copyOfRange(bytes, start, end + 1); code = 206;
                                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length);
                            }
                        }
                        exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
                    }
                }
                default -> code = 405;
            }
            boolean noBody = "HEAD".equals(exchange.getRequestMethod()) || body.length == 0;
            exchange.sendResponseHeaders(code, noBody ? -1 : body.length);
            if (!noBody) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }
    OkHttpClient client() {
        return new OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).addInterceptor(chain -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) networkInTransaction.set(true);
            var target = chain.request().url().newBuilder().scheme("http").host("127.0.0.1").port(server.getAddress().getPort()).build();
            return chain.proceed(chain.request().newBuilder().url(target).build());
        }).build();
    }
    @Override public void close() { server.stop(0); }
}
