package dev.agenvas.shared.http;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class PublicDownloadDnsTest {
    @Test void resolverKeepsFakePrivateAndMixedAnswers() throws Exception {
        for (String blocked : List.of("198.18.1.3","2001:2::e","127.0.0.1","10.0.0.1","169.254.169.254")) {
            var dns=new PublicDownloadDns(host->List.of(InetAddress.getByName("185.199.108.133"),InetAddress.getByName(blocked)));
            assertThat(dns.lookup("raw.githubusercontent.com")).extracting(InetAddress::getHostAddress)
                    .containsExactly("185.199.108.133", InetAddress.getByName(blocked).getHostAddress());
        }
    }
    @Test void resolverDoesNotImposeAHostAllowlist() throws Exception {
        var dns=new PublicDownloadDns(host->List.of(InetAddress.getByName("185.199.108.133"),InetAddress.getByName("2606:50c0:8000::154")));
        assertThat(dns.lookup("raw.githubusercontent.com")).hasSize(2);
        assertThat(dns.lookup("metadata.google.internal")).hasSize(2);
    }
    @Test void dohRequiresMatchingQuestionAndRetainsNonpublicAnswers() throws Exception {
        byte[] json=dnsJson("raw.githubusercontent.com",1,"185.199.108.133");
        assertThat(PublicDownloadDoH.answers("raw.githubusercontent.com",1,json)).extracting(InetAddress::getHostAddress).containsExactly("185.199.108.133");
        for(String blocked:List.of("198.18.1.3","10.0.0.1","169.254.169.254"))
            assertThat(PublicDownloadDoH.answers("raw.githubusercontent.com",1,dnsJson("raw.githubusercontent.com",1,blocked)))
                    .extracting(InetAddress::getHostAddress).containsExactly(blocked);
        assertThatThrownBy(()->PublicDownloadDoH.answers("raw.githubusercontent.com",1,dnsJson("other.example",1,"185.199.108.133"))).isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(()->PublicDownloadDoH.answers("raw.githubusercontent.com",1,"{\"Status\":2}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(UnknownHostException.class);
    }
    @Test void deploymentConfigurationRejectsCredentialsAndAcceptsNonpublicBootstrap() {
        for(String url:List.of("http://resolver.example/dns-query","https://user:secret@resolver.example/dns-query","https://resolver.example/dns-query?token=secret"))
            assertThatThrownBy(()->new PublicDownloadHttpClients(new PublicDownloadProperties(url,List.of("1.1.1.1"),null,null,0))).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
        assertThatCode(()->new PublicDownloadHttpClients(new PublicDownloadProperties("https://resolver.example/dns-query",List.of("198.18.1.3"),null,null,0))).doesNotThrowAnyException();
        assertThatThrownBy(()->new PublicDownloadHttpClients(new PublicDownloadProperties(null,List.of(),PublicDownloadProperties.ProxyType.HTTP,"user:secret@proxy",8080))).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
    }
    @Test void literalResolverAddressesAreAllowed() {
        for(String url:List.of("https://127.0.0.1/dns-query","https://[::1]/dns-query","https://1.1.1.1/dns-query"))
            assertThatCode(()->new PublicDownloadHttpClients(new PublicDownloadProperties(url,List.of("1.1.1.1"),null,null,0)))
                    .doesNotThrowAnyException();
    }
    @Test void unrelatedAnswerOwnerCannotBecomeTheRequestedOriginsAddress() {
        assertThatThrownBy(()->PublicDownloadDoH.answers("raw.githubusercontent.com",1,answerJson(rr("unrelated.example",1,"1.1.1.1"))))
                .isInstanceOf(UnknownHostException.class);
    }
    @Test void answerFamilyMustMatchTheQueryAndAddressFamily() {
        assertThatThrownBy(()->PublicDownloadDoH.answers("raw.githubusercontent.com",1,answerJson(rr("raw.githubusercontent.com",28,"2606:50c0:8000::154"))))
                .isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(()->PublicDownloadDoH.answers("raw.githubusercontent.com",1,answerJson(rr("raw.githubusercontent.com",1,"2606:50c0:8000::154"))))
                .isInstanceOf(UnknownHostException.class);
    }
    @Test void cnameChainMustBeRootedCompleteAcyclicAndBounded() {
        for(String records:List.of(
                rr("raw.githubusercontent.com",5,"alias.example")+","+rr("unrelated.example",1,"1.1.1.1"),
                rr("raw.githubusercontent.com",5,"alias.example")+","+rr("alias.example",5,"raw.githubusercontent.com"),
                rr("raw.githubusercontent.com",5,"alias.example")))
            assertThatThrownBy(()->PublicDownloadDoH.answers("raw.githubusercontent.com",1,answerJson(records))).isInstanceOf(UnknownHostException.class);
        var records=new ArrayList<String>();String owner="raw.githubusercontent.com";
        for(int i=0;i<18;i++){String target="alias"+i+".example";records.add(rr(owner,5,target));owner=target;}
        records.add(rr(owner,1,"185.199.108.133"));
        assertThatThrownBy(()->PublicDownloadDoH.answers("raw.githubusercontent.com",1,answerJson(String.join(",",records)))).isInstanceOf(UnknownHostException.class);
    }
    @Test void validCnameChainRetainsEveryTerminalAddressIncludingMixedFakeIp() throws Exception {
        String aliases=rr("raw.githubusercontent.com",5,"Alias.Example.")+","+rr("alias.example",5,"terminal.example")+",";
        assertThat(PublicDownloadDoH.answers("raw.githubusercontent.com",1,answerJson(aliases+rr("terminal.example",1,"185.199.108.133")+","+rr("terminal.example",1,"185.199.109.133"))))
                .extracting(InetAddress::getHostAddress).containsExactly("185.199.108.133","185.199.109.133");
        assertThat(PublicDownloadDoH.answers("raw.githubusercontent.com",1,answerJson(aliases+rr("terminal.example",1,"185.199.108.133")+","+rr("terminal.example",1,"198.18.1.3"))))
                .extracting(InetAddress::getHostAddress).containsExactly("185.199.108.133", "198.18.1.3");
    }
    @Test void cnameOnlyNodataNeedsAnAuthorityCoveringTheTerminalName() throws Exception {
        String json=new String(answerJson(rr("raw.githubusercontent.com",5,"terminal.example")),StandardCharsets.UTF_8);
        json=json.substring(0,json.length()-1)+",\"Authority\":["+rr("example",6,"ns.example. hostmaster.example. 1 2 3 4 5")+"]}";
        assertThat(PublicDownloadDoH.answers("raw.githubusercontent.com",1,json.getBytes(StandardCharsets.UTF_8))).isEmpty();
    }
    private String rr(String owner,int type,String data){return "{\"name\":\""+owner+"\",\"type\":"+type+",\"data\":\""+data+"\"}";}
    private byte[] answerJson(String records){return ("{\"Status\":0,\"TC\":false,\"Question\":[{\"name\":\"raw.githubusercontent.com.\",\"type\":1}],\"Answer\":["+records+"]}").getBytes(StandardCharsets.UTF_8);}
    private byte[] dnsJson(String host,int type,String address) {
        return ("{\"Status\":0,\"TC\":false,\"Question\":[{\"name\":\""+host+".\",\"type\":"+type+"}],\"Answer\":[{\"name\":\""+host+".\",\"type\":"+type+",\"data\":\""+address+"\"}]}").getBytes(StandardCharsets.UTF_8);
    }
}
