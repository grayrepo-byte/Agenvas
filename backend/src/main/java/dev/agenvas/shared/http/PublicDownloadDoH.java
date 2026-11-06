package dev.agenvas.shared.http;

import dev.agenvas.shared.security.EndpointAddressRules;
import java.net.*;
import java.io.IOException;
import java.util.*;
import okhttp3.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/** Explicit DNS-JSON resolver. Its TLS hostname and public bootstrap IPs are independently pinned. */
final class PublicDownloadDoH implements Dns {
    private static final int MAX_DNS_BYTES=64*1024;
    private static final ObjectMapper JSON=new ObjectMapper();
    private final OkHttpClient client;
    private final HttpUrl endpoint;
    PublicDownloadDoH(OkHttpClient client,HttpUrl endpoint){this.client=client;this.endpoint=endpoint;}
    @Override public List<InetAddress> lookup(String host)throws UnknownHostException {
        var result=new ArrayList<InetAddress>();
        for(int type:new int[]{1,28}) {
            var url=endpoint.newBuilder().addQueryParameter("name",host).addQueryParameter("type",Integer.toString(type)).build();
            try(var response=client.newCall(new Request.Builder().url(url).header("Accept","application/dns-json").get().build()).execute()) {
                if(response.code()!=200 || response.body()==null)throw PublicDownloadException.dns(PublicDownloadException.Reason.DNS_FAILED);
                var contentType=response.body().contentType();
                if(contentType==null || !contentType.type().equals("application") || !contentType.subtype().equals("dns-json"))
                    throw PublicDownloadException.dns(PublicDownloadException.Reason.DNS_FAILED);
                try(var input=response.body().byteStream()) {
                    byte[] bytes=input.readNBytes(MAX_DNS_BYTES+1);
                    if(bytes.length>MAX_DNS_BYTES)throw PublicDownloadException.dns(PublicDownloadException.Reason.DNS_FAILED);
                    result.addAll(answers(host,type,bytes));
                }
            }catch(IOException failure){
                var reason=PublicDownloadException.reason(failure);
                throw PublicDownloadException.dns(reason==PublicDownloadException.Reason.REMOTE_UNAVAILABLE?PublicDownloadException.Reason.DNS_FAILED:reason);
            }
        }
        if(result.isEmpty())throw PublicDownloadException.dns(PublicDownloadException.Reason.DNS_FAILED);
        return List.copyOf(result);
    }
    static List<InetAddress> answers(String host,int type,byte[] bytes)throws UnknownHostException {
        try {
            if(bytes.length>MAX_DNS_BYTES || type!=1 && type!=28)throw invalidAnswer();
            String questionName=name(host);
            var root=JSON.readTree(bytes);var questions=root.path("Question");
            if(root.path("Status").asInt(-1)!=0 || root.path("TC").asBoolean() || !questions.isArray() || questions.size()!=1
                    || !name(questions.get(0).path("name").asString("")).equals(questionName)
                    || questions.get(0).path("type").asInt()!=type)throw invalidAnswer();
            var records=root.path("Answer");
            if(records.isMissingNode())return List.of();
            if(!records.isArray() || records.size()>128)throw invalidAnswer();
            Map<String,String> aliases=new LinkedHashMap<>();
            Map<String,List<InetAddress>> addresses=new LinkedHashMap<>();
            for(var record:records) {
                int recordType=record.path("type").asInt();
                if(recordType!=1 && recordType!=28 && recordType!=5)continue;
                String owner=name(record.path("name").asString(""));
                if(recordType==5) {
                    String target=name(record.path("data").asString(""));
                    String previous=aliases.putIfAbsent(owner,target);
                    if(previous!=null && !previous.equals(target))throw invalidAnswer();
                }else{
                    if(recordType!=type)throw invalidAnswer();
                    var address=literal(record.path("data").asString(""));
                    if(type==1 && address.getAddress().length!=4 || type==28 && address.getAddress().length!=16)throw invalidAnswer();
                    addresses.computeIfAbsent(owner,ignored->new ArrayList<>()).add(address);
                }
            }
            String terminal=questionName;
            Set<String> visited=new HashSet<>();
            int hops=0;
            while(aliases.containsKey(terminal)) {
                if(!visited.add(terminal) || ++hops>16 || addresses.containsKey(terminal))throw invalidAnswer();
                terminal=aliases.get(terminal);
            }
            if(!visited.containsAll(aliases.keySet()) || !addresses.keySet().isEmpty() && (addresses.size()!=1 || !addresses.containsKey(terminal)))throw invalidAnswer();
            List<InetAddress> result=addresses.getOrDefault(terminal,List.of());
            // A recursive resolver may prove CNAME -> NODATA with an authoritative SOA.
            if(result.isEmpty() && !aliases.isEmpty() && !nodata(root,terminal))throw invalidAnswer();
            if(result.stream().anyMatch(address->!EndpointAddressRules.allowsPublicDownload(address)))
                throw PublicDownloadException.dns(PublicDownloadException.Reason.DNS_BLOCKED);
            return List.copyOf(result);
        }catch(UnknownHostException failure){throw failure;}
        catch(Exception failure){throw invalidAnswer();}
    }
    private static String name(String value)throws UnknownHostException {
        String normalized=value.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        if(normalized.isEmpty() || normalized.length()>253 || !normalized.matches("[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*")
                || Arrays.stream(normalized.split("\\.")).anyMatch(label->label.length()>63))throw invalidAnswer();
        return normalized;
    }
    private static boolean nodata(JsonNode root,String terminal)throws UnknownHostException {
        var authority=root.path("Authority");
        if(!authority.isArray() || authority.size()>128)return false;
        for(var record:authority)if(record.path("type").asInt()==6) {
            String zone=name(record.path("name").asString(""));
            if((terminal.equals(zone) || terminal.endsWith("."+zone)) && record.path("data").isTextual())return true;
        }
        return false;
    }
    private static UnknownHostException invalidAnswer(){return PublicDownloadException.dns(PublicDownloadException.Reason.DNS_FAILED);}
    static InetAddress literal(String value)throws UnknownHostException {
        if(value==null || !(value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}") || value.contains(":") && value.matches("[0-9a-fA-F:]+")))
            throw PublicDownloadException.dns(PublicDownloadException.Reason.DNS_FAILED);
        return InetAddress.getByName(value);
    }
}
