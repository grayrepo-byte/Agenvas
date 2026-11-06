package dev.agenvas.shared.http;

import dev.agenvas.shared.security.EndpointAddressRules;
import java.net.*;
import java.time.Duration;
import java.util.*;
import javax.net.SocketFactory;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Dedicated opt-in public download transport. Provider submissions and global transports are unchanged. */
@Component
public final class PublicDownloadHttpClients {
    private final PublicDownloadProperties properties;
    private final HttpUrl resolverUrl;
    private final List<InetAddress> bootstrap;
    public PublicDownloadHttpClients(){this(PublicDownloadProperties.defaults());}
    @Autowired public PublicDownloadHttpClients(PublicDownloadProperties properties){
        this.properties=properties;
        try {
            if(properties.proxyType()!=PublicDownloadProperties.ProxyType.DIRECT
                    && (properties.proxyPort()<1 || properties.proxyPort()>65535 || properties.proxyHost().isBlank()
                    || properties.proxyHost().length()>253 || !properties.proxyHost().matches("[a-zA-Z0-9._:-]+")))throw new IllegalArgumentException();
            if(properties.dohUrl().isEmpty()){
                if(!properties.dohBootstrapAddresses().isEmpty())throw new IllegalArgumentException();
                resolverUrl=null;bootstrap=List.of();
            }else{
                URI uri=URI.create(properties.dohUrl());
                if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getHost().contains(":") || uri.getHost().matches("[0-9.]+") || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                        || uri.getPort()==0 || uri.getPort()>65535 || properties.dohUrl().length()>1024
                        || properties.dohBootstrapAddresses().isEmpty() || properties.dohBootstrapAddresses().size()>8)throw new IllegalArgumentException();
                resolverUrl=HttpUrl.get(uri);
                var addresses=new ArrayList<InetAddress>();
                for(String literal:properties.dohBootstrapAddresses()){
                    var address=PublicDownloadDoH.literal(literal);
                    if(!EndpointAddressRules.allowsPublicDownload(address))throw new IllegalArgumentException();addresses.add(address);
                }
                bootstrap=List.copyOf(addresses);
            }
        }catch(Exception failure){throw new IllegalArgumentException("Invalid public-download deployment configuration");}
    }
    public OkHttpClient create(Set<String> hosts,Duration connect,Duration read,Duration call){
        Set<String> allowed=Set.copyOf(hosts);
        SocketFactory sockets=properties.proxyType()==PublicDownloadProperties.ProxyType.DIRECT?null:new PublicDownloadSocketFactory(properties);
        Dns resolver=Dns.SYSTEM;
        if(resolverUrl!=null) {
            var builder=PinnedHttpClients.pinnedUncaptured(host->{
                if(!host.equals(resolverUrl.host()))throw PublicDownloadException.dns(PublicDownloadException.Reason.DNS_BLOCKED);
                return bootstrap;
            },connect,read,call).newBuilder();
            builder.callTimeout(call.isZero() || call.compareTo(Duration.ofSeconds(10))>0?Duration.ofSeconds(10):call);
            if(sockets!=null)builder.socketFactory(sockets);
            resolver=new PublicDownloadDoH(builder.build(),resolverUrl);
        }
        var builder=PinnedHttpClients.pinnedUncaptured(new PublicDownloadDns(resolver,allowed),connect,read,call).newBuilder();
        if(sockets!=null)builder.socketFactory(sockets);
        // Literal origins bypass OkHttp DNS; reject them here. GETs cannot carry ambient credentials.
        builder.addInterceptor(chain->{
            var request=chain.request();String host=request.url().host();
            if(!request.method().equals("GET") || host.contains(":") || host.matches("[0-9.]+")
                    || !allowed.isEmpty() && !allowed.contains(host) || !request.url().username().isEmpty() || !request.url().password().isEmpty()
                    || request.header("Authorization")!=null || request.header("Proxy-Authorization")!=null || request.header("Cookie")!=null)
                throw new PublicDownloadException(PublicDownloadException.Reason.DNS_BLOCKED);
            return chain.proceed(request);
        });
        return builder.build();
    }
}
