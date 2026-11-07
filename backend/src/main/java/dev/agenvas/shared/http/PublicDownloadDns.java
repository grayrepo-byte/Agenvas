package dev.agenvas.shared.http;

import java.net.*;
import java.util.*;
import okhttp3.Dns;

/** Preserve resolver answers and classify lookup failures without filtering destination addresses. */
final class PublicDownloadDns implements Dns {
    private final Dns upstream;
    PublicDownloadDns(Dns upstream){this.upstream=upstream;}
    @Override public List<InetAddress> lookup(String host)throws UnknownHostException {
        List<InetAddress> answers;
        try{answers=upstream.lookup(host);}
        catch(UnknownHostException failure){
            var reason=PublicDownloadException.reason(failure);
            throw PublicDownloadException.dns(reason==PublicDownloadException.Reason.REMOTE_UNAVAILABLE?PublicDownloadException.Reason.DNS_FAILED:reason);
        }
        if(answers.isEmpty())
            throw PublicDownloadException.dns(PublicDownloadException.Reason.DNS_FAILED);
        return List.copyOf(answers);
    }
}
