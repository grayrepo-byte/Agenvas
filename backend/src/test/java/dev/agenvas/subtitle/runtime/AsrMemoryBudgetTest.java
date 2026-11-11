package dev.agenvas.subtitle.runtime;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class AsrMemoryBudgetTest {
 @TempDir Path root;
 @Test void admitsOnlyMeasuredHeadroomAndFailsClosedForUnknownOrBusyCgroups()throws Exception{
  assertThat(AsrMemoryBudget.read(root).admitted()).isFalse();
  Files.writeString(root.resolve("memory.max"),"1610612736");Files.writeString(root.resolve("memory.current"),"536870912");
  assertThat(AsrMemoryBudget.read(root).admitted()).isTrue();
  Files.writeString(root.resolve("memory.current"),"1200000000");assertThat(AsrMemoryBudget.read(root).admitted()).isFalse();
  Files.writeString(root.resolve("memory.max"),"max");assertThat(AsrMemoryBudget.read(root).admitted()).isFalse();
 }
 @Test void downloadRedirectsKeepUrlAndCredentialValidationWithoutRejectingPrivateOrFakeIpAddresses(){
  OfficialAsrModelSource.validate(java.net.URI.create("https://cas-bridge.xethub.hf.co/file?signature=fixture"));
  OfficialAsrModelSource.validate(java.net.URI.create("http://127.0.0.1:7890/file"));
  OfficialAsrModelSource.validate(java.net.URI.create("https://198.18.0.2/file"));
  for(String url:new String[]{"ftp://huggingface.co/file","https://huggingface.co:0/file","https://user:password@huggingface.co/file"})
   assertThatThrownBy(()->OfficialAsrModelSource.validate(java.net.URI.create(url))).isInstanceOf(AsrDownloadException.class);
 }
}
