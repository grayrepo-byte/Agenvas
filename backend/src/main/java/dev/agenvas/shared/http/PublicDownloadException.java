package dev.agenvas.shared.http;

import java.io.IOException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Stable public categories only. Never retain resolver/proxy bodies, addresses, credentials or prose. */
public final class PublicDownloadException extends IOException {
    public enum Reason { INVALID_REQUEST, DNS_FAILED, PROXY_UNREACHABLE, PROXY_AUTH_REQUIRED, PROXY_ERROR,
        TLS_FAILED, REMOTE_RATE_LIMITED, REMOTE_UNAVAILABLE }
    private final Reason reason;
    public PublicDownloadException(Reason reason){super("PUBLIC_DOWNLOAD_"+reason.name());this.reason=reason;}
    public Reason reason(){return reason;}
    public static Reason reason(Throwable failure){
        Throwable current=failure;
        for(int i=0;current!=null && i<8;i++,current=current.getCause()) {
            if(current instanceof PublicDownloadException classified)return classified.reason();
            if(current instanceof SSLException)return Reason.TLS_FAILED;
        }
        return Reason.REMOTE_UNAVAILABLE;
    }
    static UnknownHostException dns(Reason reason){
        var failure=new UnknownHostException("PUBLIC_DOWNLOAD_"+reason.name());
        failure.initCause(new PublicDownloadException(reason));return failure;
    }
}
