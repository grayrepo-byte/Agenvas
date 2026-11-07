package dev.agenvas.referencecatalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.*;
import dev.agenvas.referencecatalog.application.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.*;
import dev.agenvas.referencecatalog.infrastructure.*;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=AgenvasApplication.class,properties="agenvas.recovery-mode=true")
class ReferenceCatalogCacheRecoveryPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.11-alpine");
    static final Path STORAGE=temp();static boolean initialized;
    static Path temp(){try{return Files.createTempDirectory("reference-cache-recovery-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void props(DynamicPropertyRegistry r){if(!initialized){Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();initialized=true;}r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);r.add("agenvas.storage.root",STORAGE::toString);}
    @Autowired ReferenceCatalogService catalog;@Autowired ReferenceCatalogCacheService jobs;@Autowired ReferenceCatalogImageCache files;@Autowired IdentityService identities;@Autowired ApplicationContext app;@Autowired WebApplicationContext web;
    @MockitoBean ReferenceCatalogGitHubClient github;
    @Test void recoveryPreviewReadsOnlyExistingCacheAndAllCacheControlsRemainReadOnly() throws Exception {
        var owner=identities.setup("cache-recovery","synthetic-reference-password");var auth=authentication(new UsernamePasswordAuthenticationToken(owner,null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var mvc=webAppContextSetup(web).apply(springSecurity()).build();var source=catalog.create("recovery-fixture","Original fixture","fixture","repo","e".repeat(40),"pack.json",Format.AGENVAS_REFERENCE_PACK,true, SourceType.GITHUB);
        when(github.resolve(any())).thenReturn("e".repeat(40));when(github.file(any(),any(),eq("pack.json"),anyInt())).thenReturn(Files.readAllBytes(Path.of("../examples/reference-pack/reference-pack.json")));
        assertThat(catalog.sync(source.id(),0).state()).isEqualTo("SUCCESS");var entry=catalog.list(Kind.POSE,source.id(),"neutral",null,null,null,null,null,null,0,10).items().getFirst();var variant=entry.variants().getFirst();
        mvc.perform(get(variant.previewUrl()).with(auth)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("REFERENCE_CATALOG_CACHE_MISS"));
        assertThat(Files.exists(STORAGE.resolve(".reference-catalog-cache"))).isFalse();verify(github,never()).file(any(),any(),startsWith("images/"),anyInt());
        byte[] image=Files.readAllBytes(Path.of("../examples/reference-pack/images/neutral-front.png"));files.get(variant.sha256(),variant.byteSize(),variant.contentType(),true,()->{},()->image);
        mvc.perform(get(variant.previewUrl()).with(auth)).andExpect(status().isOk()).andExpect(content().bytes(image));
        for(String path:List.of("/api/v1/settings/reference-catalog/cache/jobs","/api/v1/settings/reference-catalog/cache/clear","/api/v1/reference-catalog/private-images"))
            mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RECOVERY_MODE_READ_ONLY"));
        mvc.perform(get("/api/v1/settings/reference-catalog/cache").with(user("viewer").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/settings/reference-catalog/cache").with(auth)).andExpect(status().isOk());
        assertThat(app.getBeansOfType(ReferenceCatalogCacheWorker.class)).isEmpty();assertThat(jobs.processNext()).isFalse();
    }
}
