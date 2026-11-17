package dev.agenvas.shared.http;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;
import javax.net.ssl.*;
import static dev.agenvas.shared.http.PublicDownloadException.Reason.*;

/** A proxy carries an resolved numeric route; it never resolves the origin's hostname. */
final class PublicDownloadSocketFactory extends SocketFactory {
    private final PublicDownloadProperties properties;
    private final SSLSocketFactory proxyTls;
    PublicDownloadSocketFactory(PublicDownloadProperties properties) {
        this(properties, (SSLSocketFactory) SSLSocketFactory.getDefault());
    }
    PublicDownloadSocketFactory(PublicDownloadProperties properties, SSLSocketFactory proxyTls) {
        this.properties = properties;
        this.proxyTls = proxyTls;
    }
    @Override
    public Socket createSocket() {
        return new TunnelSocket();
    }
    // Callers must resolve the origin before selecting a numeric route.
    @Override
    public Socket createSocket(String h, int p) throws IOException {
        throw new PublicDownloadException(DNS_FAILED);
    }
    @Override
    public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException {
        throw new PublicDownloadException(DNS_FAILED);
    }
    @Override
    public Socket createSocket(InetAddress h, int p) throws IOException {
        throw new PublicDownloadException(DNS_FAILED);
    }
    @Override
    public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException {
        throw new PublicDownloadException(DNS_FAILED);
    }

    private final class TunnelSocket extends Socket {
        private volatile Socket delegate;
        private volatile boolean closed, connected;
        private InetSocketAddress origin;
        private int readTimeout;
        TunnelSocket() {
            super(Proxy.NO_PROXY);
        }
        @Override
        public void connect(SocketAddress target, int timeout) throws IOException {
            if (closed)
                throw new SocketException("Socket is closed");
            if (connected)
                throw new SocketException("Socket is connected");
            if (!(target instanceof InetSocketAddress numeric) || numeric.isUnresolved())
                throw new PublicDownloadException(DNS_FAILED);
            origin = numeric;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout > 0 ? timeout : 10_000);
            try {
                InetAddress[] proxyAddresses;
                try {
                    proxyAddresses = InetAddress.getAllByName(properties.proxyHost());
                } catch (UnknownHostException failure) {
                    throw new PublicDownloadException(PROXY_UNREACHABLE);
                }
                if (proxyAddresses.length == 0)
                    throw new PublicDownloadException(PROXY_UNREACHABLE);
                var plain = new Socket(Proxy.NO_PROXY);
                delegate = plain;
                if (closed) {
                    plain.close();
                    throw new SocketException("Socket is closed");
                }
                try {
                    plain.connect(new InetSocketAddress(proxyAddresses[0], properties.proxyPort()),
                            remaining(deadline));
                } catch (IOException failure) {
                    throw new PublicDownloadException(PROXY_UNREACHABLE);
                }
                plain.setSoTimeout(remaining(deadline));
                if (properties.proxyType() == PublicDownloadProperties.ProxyType.HTTPS) {
                    var tls = (SSLSocket) proxyTls.createSocket(plain, properties.proxyHost(), properties.proxyPort(),
                            true);
                    delegate = tls;
                    if (closed) {
                        tls.close();
                        throw new SocketException("Socket is closed");
                    }
                    var params = tls.getSSLParameters();
                    params.setEndpointIdentificationAlgorithm("HTTPS");
                    if (!properties.proxyHost().contains(":") && !properties.proxyHost().matches("[0-9.]+"))
                        params.setServerNames(List.of(new SNIHostName(properties.proxyHost())));
                    params.setApplicationProtocols(new String[]{"http/1.1"});
                    tls.setSSLParameters(params);
                    tls.setSoTimeout(remaining(deadline));
                    try {
                        tls.startHandshake();
                    } catch (IOException failure) {
                        throw new PublicDownloadException(TLS_FAILED);
                    }
                }
                if (properties.proxyType() == PublicDownloadProperties.ProxyType.SOCKS5)
                    socks(deadline);
                else
                    http(deadline);
                delegate.setSoTimeout(readTimeout);
                connected = true;
            } catch (IOException failure) {
                close();
                throw failure instanceof PublicDownloadException ? failure : new PublicDownloadException(PROXY_ERROR);
            } catch (RuntimeException failure) {
                close();
                throw new PublicDownloadException(PROXY_ERROR);
            }
        }
        @Override
        public void connect(SocketAddress target) throws IOException {
            connect(target, 10_000);
        }
        private int remaining(long deadline) throws SocketTimeoutException {
            long nanos = deadline - System.nanoTime();
            if (nanos <= 0)
                throw new SocketTimeoutException("Proxy handshake timed out");
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
        }
        private int read(long deadline) throws IOException {
            delegate.setSoTimeout(remaining(deadline));
            int value = delegate.getInputStream().read();
            if (value < 0)
                throw new EOFException("Proxy handshake ended");
            return value;
        }
        private byte[] read(int count, long deadline) throws IOException {
            byte[] result = new byte[count];
            for (int i = 0; i < count; i++)
                result[i] = (byte) read(deadline);
            return result;
        }
        private void http(long deadline) throws IOException {
            String ip = origin.getAddress().getHostAddress();
            String authority = (ip.contains(":") ? "[" + ip + "]" : ip) + ":" + origin.getPort();
            delegate.getOutputStream().write(("CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            delegate.getOutputStream().flush();
            var headers = new ByteArrayOutputStream();
            int tail = 0;
            while (headers.size() < 8192) {
                int value = read(deadline);
                if (value > 127 || value < 32 && value != '\r' && value != '\n' && value != '\t')
                    throw new PublicDownloadException(PROXY_ERROR);
                headers.write(value);
                tail = (tail << 8) | value;
                if (tail == 0x0d0a0d0a)
                    break;
            }
            if (tail != 0x0d0a0d0a)
                throw new PublicDownloadException(PROXY_ERROR);
            String[] lines = headers.toString(StandardCharsets.US_ASCII).split("\r\n");
            if (!lines[0].matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?"))
                throw new PublicDownloadException(PROXY_ERROR);
            int status = Integer.parseInt(lines[0].substring(9, 12));
            if (status == 407)
                throw new PublicDownloadException(PROXY_AUTH_REQUIRED);
            if (status < 200 || status >= 300)
                throw new PublicDownloadException(PROXY_ERROR);
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon < 1)
                    throw new PublicDownloadException(PROXY_ERROR);
                String key = lines[i].substring(0, colon).strip();
                String value = lines[i].substring(colon + 1).strip();
                if (key.equalsIgnoreCase("Transfer-Encoding")
                        || key.equalsIgnoreCase("Content-Length") && !value.equals("0"))
                    throw new PublicDownloadException(PROXY_ERROR);
            }
        }
        private void socks(long deadline) throws IOException {
            var out = delegate.getOutputStream();
            out.write(new byte[]{5, 1, 0});
            out.flush();
            if (read(deadline) != 5)
                throw new PublicDownloadException(PROXY_ERROR);
            int method = read(deadline);
            if (method != 0)
                throw new PublicDownloadException(method == 2 || method == 255 ? PROXY_AUTH_REQUIRED : PROXY_ERROR);
            byte[] address = origin.getAddress().getAddress();
            out.write(new byte[]{5, 1, 0, (byte) (address.length == 4 ? 1 : 4)});
            out.write(address);
            out.write(origin.getPort() >>> 8);
            out.write(origin.getPort() & 255);
            out.flush();
            byte[] reply = read(4, deadline);
            if (reply[0] != 5 || reply[1] != 0 || reply[2] != 0)
                throw new PublicDownloadException(PROXY_ERROR);
            int count = switch (reply[3] & 255) {
                case 1 -> 4;
                case 4 -> 16;
                case 3 -> read(deadline);
                default -> throw new PublicDownloadException(PROXY_ERROR);
            };
            if (count == 0)
                throw new PublicDownloadException(PROXY_ERROR);
            read(count + 2, deadline);
        }
        private Socket stream() throws SocketException {
            if (closed || !connected)
                throw new SocketException("Socket is not connected");
            return delegate;
        }
        @Override
        public InputStream getInputStream() throws IOException {
            return stream().getInputStream();
        }
        @Override
        public OutputStream getOutputStream() throws IOException {
            return stream().getOutputStream();
        }
        @Override
        public void close() throws IOException {
            closed = true;
            var socket = delegate;
            if (socket != null)
                socket.close();
            super.close();
        }
        @Override
        public boolean isClosed() {
            return closed;
        }
        @Override
        public boolean isConnected() {
            return connected;
        }
        @Override
        public boolean isBound() {
            return delegate != null && delegate.isBound();
        }
        @Override
        public InetAddress getInetAddress() {
            return origin == null ? null : origin.getAddress();
        }
        @Override
        public int getPort() {
            return origin == null ? 0 : origin.getPort();
        }
        @Override
        public SocketAddress getRemoteSocketAddress() {
            return origin;
        }
        @Override
        public InetAddress getLocalAddress() {
            return delegate == null ? null : delegate.getLocalAddress();
        }
        @Override
        public int getLocalPort() {
            return delegate == null ? -1 : delegate.getLocalPort();
        }
        @Override
        public SocketAddress getLocalSocketAddress() {
            return delegate == null ? null : delegate.getLocalSocketAddress();
        }
        @Override
        public void setSoTimeout(int timeout) throws SocketException {
            if (timeout < 0)
                throw new IllegalArgumentException();
            readTimeout = timeout;
            if (delegate != null && connected)
                delegate.setSoTimeout(timeout);
        }
        @Override
        public int getSoTimeout() {
            return readTimeout;
        }
        @Override
        public void setTcpNoDelay(boolean value) throws SocketException {
            if (delegate != null)
                delegate.setTcpNoDelay(value);
        }
        @Override
        public boolean getTcpNoDelay() throws SocketException {
            return delegate != null && delegate.getTcpNoDelay();
        }
        @Override
        public void setKeepAlive(boolean value) throws SocketException {
            if (delegate != null)
                delegate.setKeepAlive(value);
        }
        @Override
        public boolean getKeepAlive() throws SocketException {
            return delegate != null && delegate.getKeepAlive();
        }
        @Override
        public void shutdownInput() throws IOException {
            stream().shutdownInput();
        }
        @Override
        public void shutdownOutput() throws IOException {
            stream().shutdownOutput();
        }
        @Override
        public boolean isInputShutdown() {
            return delegate != null && delegate.isInputShutdown();
        }
        @Override
        public boolean isOutputShutdown() {
            return delegate != null && delegate.isOutputShutdown();
        }
    }
}
