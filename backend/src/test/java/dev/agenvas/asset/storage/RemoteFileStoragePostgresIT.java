package dev.agenvas.asset.storage;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL and synthetic WebDAV HTTP; SMB management is covered without a real NAS. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = { AgenvasApplication.class, RemoteFileStoragePostgresIT.Transport.class }, properties = {
        "agenvas.library.worker-enabled=false", "agenvas.llm.scheduler-enabled=false" })
class RemoteFileStoragePostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    static final Path ROOT = root();
    static final FakeWebDavServer DAV = server();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl); registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword); registry.add("agenvas.storage.root", ROOT::toString);
        registry.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    @TestConfiguration static class Transport {
        @Bean @Primary WebDavStorageClient fakeWebDav(StorageSettingsService settings) { return new WebDavStorageClient(settings,DAV.client()); }
    }
    @Autowired StorageSettingsService settings;
    @Autowired StorageRepository routes;
    @Autowired AssetService assets;
    @Autowired ConfiguredAssetStorage storage;
    @Autowired LocalAssetStorage local;
    @Autowired ProjectService projects;
    @Autowired IdentityService identities;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    static Path root() { try { return Files.createTempDirectory("agenvas-remote-files-it-"); } catch (java.io.IOException e) { throw new IllegalStateException(e); } }
    static FakeWebDavServer server() { try { return new FakeWebDavServer(); } catch (java.io.IOException e) { throw new IllegalStateException(e); } }
    @AfterAll static void cleanup() throws Exception {
        DAV.close();
        try (var paths = Files.walk(ROOT)) { for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    @Test void protocolManagementArchiveRecoveryAndHistoricalReadsShareTheExistingBoundaries() throws Exception {
        var owner = identities.setup("remote-file-admin","synthetic-strong-password");
        var auth = UsernamePasswordAuthenticationToken.authenticated(owner,null,java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        UUID project = projects.create(owner.userId(),"remote archives",dev.agenvas.project.domain.Project.AspectRatio.LANDSCAPE_16_9).id();
        var image = new ByteArrayOutputStream(); ImageIO.write(new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",image);
        byte[] png = image.toByteArray();
        var original = assets.archiveImage(owner.userId(),project,new ByteArrayInputStream(png));
        UUID dav = null;
        for (var protocol : java.util.List.of(StorageProfile.Provider.SMB,StorageProfile.Provider.WEBDAV)) {
            String endpoint = protocol == StorageProfile.Provider.SMB ? "smb://nas.example.com/media/archives" : "https://nas.example.com/dav/media";
            var input = mapper.createObjectNode().put("expectedVersion",settings.status().version()).put("name",protocol.name())
                    .put("provider",protocol.name()).put("endpoint",endpoint).put("region","").put("bucket","").put("keyPrefix","agenvas")
                    .put("pathStyle",false).put("accessKeyId","synthetic-user").put("secretAccessKey","synthetic-password");
            mvc.perform(post("/api/v1/settings/storage/profiles").with(authentication(auth)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(input))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-password"))));
            UUID id = settings.status().profiles().stream().filter(p -> p.provider() == protocol).findFirst().orElseThrow().id();
            assertThat(settings.credentials(settings.requireProfile(id))).containsExactly("synthetic-user","synthetic-password");
            assertThatThrownBy(() -> settings.activateRelay(settings.status().version(),id,true,true)).isInstanceOf(ApiProblemException.class);
            if (protocol == StorageProfile.Provider.WEBDAV) dav = id;
            else {
                settings.activate(settings.status().version(),id); routes.pin(project,UUID.randomUUID(),"IMAGE");
                assertThatThrownBy(() -> settings.delete(settings.status().version(),id)).isInstanceOf(ApiProblemException.class);
                assertThatThrownBy(() -> settings.update(settings.status().version(),id,"moved",protocol,"smb://nas.example.com/other","","","agenvas",false,null,null))
                        .isInstanceOf(ApiProblemException.class);
                settings.rotate(settings.status().version(),id,"DOMAIN\\synthetic-user","synthetic-new-password");
                assertThat(settings.credentials(settings.requireProfile(id))).containsExactly("DOMAIN\\synthetic-user","synthetic-new-password");
            }
        }
        settings.activate(settings.status().version(),dav);
        var archived = assets.archiveImage(owner.userId(),project,new ByteArrayInputStream(png));
        assertThat(archived.objectKey()).startsWith("objects/"+dav+"/");
        assertThat(storage.cloudObject(archived.objectKey())).isEmpty();
        assertThat(Files.exists(local.checkedPath(project+"/"+archived.id()+".png"))).isFalse();
        int gets = DAV.gets.get();
        mvc.perform(head("/api/v1/projects/{p}/assets/{a}/content",project,archived.id()).with(authentication(auth)))
                .andExpect(status().isOk()).andExpect(header().string("Content-Length",Long.toString(archived.byteSize())));
        assertThat(DAV.gets.get()).isEqualTo(gets);
        mvc.perform(get("/api/v1/projects/{p}/assets/{a}/content",project,archived.id()).with(authentication(auth)).header("Range","bytes=2-5"))
                .andExpect(status().isPartialContent()).andExpect(content().bytes(java.util.Arrays.copyOfRange(png,2,6)));
        assertThat(Files.readAllBytes(storage.checkedPath(archived.objectKey()))).isEqualTo(png);
        UUID task = UUID.randomUUID(); var downloads = new AtomicInteger(); DAV.failThumbnail = true;
        assertThatThrownBy(() -> assets.archiveTaskImage(owner.userId(),project,task,() -> { downloads.incrementAndGet(); return new ByteArrayInputStream(png); }))
                .isInstanceOf(ApiProblemException.class);
        settings.activate(settings.status().version(),null);
        var recovered = assets.archiveTaskImage(owner.userId(),project,task,() -> { throw new AssertionError("Must not download or regenerate"); });
        assertThat(downloads.get()).isEqualTo(1); assertThat(recovered.objectKey()).startsWith("objects/"+dav+"/");
        mvc.perform(get("/api/v1/projects/{p}/assets/{a}/content",project,archived.id()).with(authentication(auth)))
                .andExpect(status().isOk()).andExpect(content().bytes(png));
        mvc.perform(get("/api/v1/projects/{p}/assets/{a}/content",project,original.id()).with(authentication(auth)))
                .andExpect(status().isOk()).andExpect(content().bytes(png));
        assertThat(DAV.networkInTransaction).isFalse();
        assertThatThrownBy(() -> settings.delete(settings.status().version(),davId())).isInstanceOf(ApiProblemException.class);
    }
    private UUID davId() { return settings.status().profiles().stream().filter(p -> p.provider() == StorageProfile.Provider.WEBDAV).findFirst().orElseThrow().id(); }
}
