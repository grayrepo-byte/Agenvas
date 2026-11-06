package dev.agenvas.shared.http;

import dev.agenvas.shared.security.EndpointAddressRules;
import java.net.*;
import java.util.*;
import okhttp3.Dns;

/** Resolve once, validate the complete answer set, and hand those exact addresses to the connector. */
final class PublicDownloadDns implements Dns {
    private final Dns upstream;
    private final Set<String> hosts;
    PublicDownloadDns(Dns upstream,Set<String> hosts){this.upstream=upstream;this.hosts=Set.copyOf(hosts);}
    @Override public List<InetAddress> lookup(String host)throws UnknownHostException {
        if(!hosts.isEmpty() && !hosts.contains(host))throw PublicDownloadException.dns(PublicDownloadException.Reason.DNS_BLOCKED);
        List<InetAddress> answers;
        try{answers=upstream.lookup(host);}
        catch(UnknownHostException failure){
            var reason=PublicDownloadException.reason(failure);
            throw PublicDownloadException.dns(reason==PublicDownloadException.Reason.REMOTE_UNAVAILABLE?PublicDownloadException.Reason.DNS_FAILED:reason);
        }
        if(answers.isEmpty() || answers.stream().anyMatch(a->!EndpointAddressRules.allowsPublicDownload(a)))
            throw PublicDownloadException.dns(PublicDownloadException.Reason.DNS_BLOCKED);
        return List.copyOf(answers);
    }
}
