package dev.agenvas.referencecatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Explicit opt-in only: real public metadata and exactly one manually selected reference PNG.
 * No provider calls, third-party image fixtures, published copies or production databases.
 * A task-owned TCP bridge is stopped after acquisition to prove genuinely offline cache use;
 * it never changes, stops or records traffic from the operator's existing proxy.
 */
@EnabledIfEnvironmentVariable(named = "AGENVAS_REFERENCE_LIVE_TEST", matches = "true")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.reference-catalog.cache.worker-enabled=false",
        "agenvas.media-templates.sync-enabled=false"})
class ReferenceCatalogLivePrivateCacheIT {
    private static final String COMMIT = "b290a06270dde0da4b8aeab1b4c1d99b1f74065f";
    private static final String SOURCE = "live-selected-reference";
    private static final Path STORAGE = temporaryStorage();
    private static final ProxyBridge BRIDGE = new ProxyBridge(
            System.getenv().getOrDefault("AGENVAS_REFERENCE_LIVE_PROXY_HOST", "127.0.0.1"),
            Integer.parseInt(System.getenv().getOrDefault("AGENVAS_REFERENCE_LIVE_PROXY_PORT", "7890")));

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("agenvas.storage.root", STORAGE::toString);
        properties.add("agenvas.public-download.proxy-type", () -> "SOCKS5");
        properties.add("agenvas.public-download.proxy-host", () -> "127.0.0.1");
        properties.add("agenvas.public-download.proxy-port", BRIDGE::port);
        properties.add("agenvas.public-download.doh-url", () -> "https://cloudflare-dns.com/dns-query");
        properties.add("agenvas.public-download.doh-bootstrap-addresses", () -> "1.1.1.1,1.0.0.1");
    }

    @Autowired
    IdentityService identities;
    @Autowired
    ProjectService projects;
    @Autowired
    WebApplicationContext context;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcClient jdbc;

    @Test
    void realGitHubSelectedImageIsCachedThenPreviewedImportedAndClearedWithoutNetwork() throws Exception {
        AdminPrincipal owner = identities.setup("live-cache-owner", "synthetic-live-cache-password");
        var auth = new UsernamePasswordAuthenticationToken(owner, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();

        mvc.perform(post("/api/v1/settings/reference-catalog/sources")
                .with(authentication(auth)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("id", SOURCE, "name", "Selected private reference test",
                        "owner", "hongye007", "repository", "open-pose-costume", "ref", COMMIT,
                        "manifestPath", "public/data/catalog.json", "format", "OPEN_POSE_COSTUME",
                        "imageDistributionApproved", false))))
                .andExpect(status().isCreated());
        JsonNode sync = mapper.readTree(mvc
                .perform(post("/api/v1/settings/reference-catalog/sources/" + SOURCE + "/sync")
                        .with(authentication(auth)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(sync.path("state").asText()).as("Real fixed-commit metadata synchronization").isEqualTo("SUCCESS");
        assertThat(sync.path("entryCount").asInt()).isEqualTo(3144);
        String snapshot = sync.path("snapshotId").asText();
        JsonNode page = mapper.readTree(mvc.perform(get("/api/v1/reference-catalog")
                .param("sourceId", SOURCE).param("kind", "POSE").param("query", "full_single_P001")
                .with(authentication(auth))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.path("items").get(0).path("variants").get(0).path("privateCacheAllowed").asBoolean()).isTrue();
        byte[] input = mapper.writeValueAsBytes(Map.of("sourceId", SOURCE, "snapshotId", snapshot,
                "entryId", "full_single_P001", "variantId", "full_single_P001_front", "commandKey",
                "one-selected-private-image"));
        JsonNode image = mapper.readTree(mvc.perform(post("/api/v1/reference-catalog/private-images")
                .with(authentication(auth)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(image.path("cached").asBoolean()).isTrue();
        assertThat(image.path("byteSize").asLong()).isBetween(1L, 20L * 1024 * 1024);
        String imageId = image.path("id").asText();
        String expectedHash = image.path("sha256").asText();
        JsonNode stats = mapper.readTree(mvc.perform(get("/api/v1/settings/reference-catalog/cache")
                .with(authentication(auth))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(stats.path("fileCount").asInt()).isEqualTo(1);
        assertThat(stats.path("protectedFileCount").asInt()).isEqualTo(1);

        BRIDGE.offline();
        int attempts = BRIDGE.attempts();
        byte[] cached = mvc.perform(get(image.path("previewUrl").asText()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(sha256(cached)).isEqualTo(expectedHash);
        JsonNode replay = mapper.readTree(mvc.perform(post("/api/v1/reference-catalog/private-images")
                .with(authentication(auth)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(replay.path("id").asText()).isEqualTo(imageId);
        var project = projects.create(owner.userId(), "Offline selected reference", Project.AspectRatio.LANDSCAPE_16_9);
        JsonNode imported = mapper.readTree(mvc.perform(post("/api/v1/projects/" + project.id()
                + "/reference-catalog/private-images/" + imageId + "/import")
                .with(authentication(auth)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandKey\":\"offline-project-copy\",\"placement\":{\"x\":40,\"y\":40}}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(imported.path("canvasItemId").isNull()).isFalse();
        assertThat(jdbc.sql("select count(*) from task where project_id=:project").param("project", project.id())
                .query(Long.class).single()).as("No provider task").isZero();
        JsonNode retained = mapper.readTree(mvc.perform(post("/api/v1/settings/reference-catalog/cache/clear")
                .with(authentication(auth)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(retained.path("removedFiles").asInt()).isZero();
        assertThat(retained.path("stats").path("protectedFileCount").asInt()).isEqualTo(1);
        JsonNode cleared = mapper.readTree(mvc.perform(delete("/api/v1/reference-catalog/private-images/" + imageId)
                .with(authentication(auth)).with(csrf())).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        assertThat(cleared.path("cached").asBoolean()).isFalse();
        byte[] projectCopy = mvc.perform(get("/api/v1/projects/" + project.id() + "/assets/"
                + imported.path("assetId").asText() + "/content").with(authentication(auth)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(sha256(projectCopy)).isEqualTo(expectedHash);
        assertThat(BRIDGE.attempts()).as("No network after going offline").isEqualTo(attempts);
        System.out.println("LIVE_REFERENCE_CACHE metadataEntries=3144 selectedImages=1 bytes=" + cached.length
                + " sha256=" + expectedHash + " offlinePreview=true offlineImport=true protectedClear=true"
                + " ownerClearPreservesProject=true offlineNetworkAttempts=0 providerTasks=0");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("agenvas-live-private-reference-");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        BRIDGE.close();
        try (var paths = Files.walk(STORAGE)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }

    /** Only forwards this test's sockets; never inspects or logs the traffic. */
    private static final class ProxyBridge implements AutoCloseable {
        private final ServerSocket listener;
        private final String host;
        private final int port;
        private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicInteger accepted = new AtomicInteger();
        private volatile boolean online = true;

        ProxyBridge(String host, int port) {
            this.host = host;
            this.port = port;
            try {
                listener = new ServerSocket();
                listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                threads.submit(() -> {
                    while (!listener.isClosed()) {
                        try {
                            Socket socket = listener.accept();
                            accepted.incrementAndGet();
                            if (!online)
                                socket.close();
                            else
                                threads.submit(() -> bridge(socket));
                        } catch (IOException closed) {
                            break;
                        }
                    }
                });
            } catch (IOException failure) {
                threads.close();
                throw new IllegalStateException(failure);
            }
        }

        private void bridge(Socket client) {
            try (client; Socket upstream = new Socket()) {
                sockets.add(client);
                sockets.add(upstream);
                upstream.connect(new InetSocketAddress(host, port), 10_000);
                threads.submit(() -> copy(upstream, client));
                copy(client, upstream);
            } catch (IOException ignored) {
                // Closed task-owned bridge sockets are the deliberate offline boundary.
            } finally {
                sockets.remove(client);
            }
        }

        private static void copy(Socket source, Socket destination) {
            try {
                source.getInputStream().transferTo(destination.getOutputStream());
            } catch (IOException ignored) {
                /* Connection shutdown, no traffic recording. */ } finally {
                try {
                    destination.close();
                } catch (IOException ignored) {
                    /* Already closed. */ }
            }
        }

        int port() {
            return listener.getLocalPort();
        }
        int attempts() {
            return accepted.get();
        }
        void offline() {
            online = false;
            for (Socket socket : sockets)
                try {
                    socket.close();
                } catch (IOException ignored) {
                    /* Closed. */ }
        }
        @Override
        public void close() throws Exception {
            offline();
            listener.close();
            threads.close();
        }
    }
}
