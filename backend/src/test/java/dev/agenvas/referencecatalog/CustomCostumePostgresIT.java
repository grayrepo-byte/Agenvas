package dev.agenvas.referencecatalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.*;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.testing.ImageAssetFixture;
import dev.agenvas.referencecatalog.application.*;
import dev.agenvas.referencecatalog.infrastructure.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.*;
import java.util.concurrent.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=AgenvasApplication.class)
class CustomCostumePostgresIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.11-alpine");
    static final Path STORAGE=temp();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);r.add("agenvas.storage.root",STORAGE::toString);}
    @Autowired IdentityService identities; @Autowired ProjectService projects; @Autowired AssetService assets;
    @Autowired ArtifactService artifacts; @Autowired CanvasService canvas; @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context; @Autowired JdbcClient jdbc;
    @Autowired ReferenceCatalogService catalog;
    @Autowired ReferenceCatalogCacheService cache;
    @Autowired CustomCostumeService costumes;
    @Autowired ReferenceCatalogImportService imports;
    @MockitoSpyBean CustomCostumeRepository costumeRepository;
    @MockitoSpyBean dev.agenvas.asset.application.PrivateMediaArchive archive;
    @MockitoSpyBean ReferenceCatalogRepository importRepository;
    @MockitoBean ReferenceCatalogGitHubClient github;
    static AdminPrincipal owner; MockMvc mvc; RequestPostProcessor auth;
    static Path temp(){try{return Files.createTempDirectory("custom-costume-it-");}catch(Exception e){throw new IllegalStateException(e);}}
    @BeforeEach void setup(){if(owner==null)owner=identities.setup("costume-admin","synthetic-costume-password");mvc=webAppContextSetup(context).apply(springSecurity()).build();auth=authentication(new UsernamePasswordAuthenticationToken(owner,null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return i.callRealMethod();}).when(archive).pin(any(),any(),any());
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return i.callRealMethod();}).when(archive).archive(any(),any(),any(),any(Path.class));
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return i.callRealMethod();}).when(archive).file(any(),any(),anyBoolean());
    }
    record Source(UUID project,UUID item,UUID version,long itemVersion,UUID asset){}
    Source source(){var p=projects.create(owner.userId(),"Costume source",Project.AspectRatio.LANDSCAPE_16_9);var asset=ImageAssetFixture.archive(assets,owner.userId(),p.id());var a=artifacts.create(owner.userId(),p.id(),Artifact.Kind.IMAGE,"Costume",mapper.valueToTree(Map.of("sourceType","UPLOAD","assetId",asset)));var item=canvas.apply(owner.userId(),p.id(),List.of(new CanvasService.PlaceArtifact(UUID.randomUUID(),a.artifact().id(),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.valueOf(320),BigDecimal.valueOf(320),0,null,false,a.resourceDefaultVersion().id()))).getFirst().item();return new Source(p.id(),item.id(),a.resourceDefaultVersion().id(),item.version(),asset);}
    Map<String,Object> saveInput(Source s,String key){return Map.of("canvasItemId",s.item(),"sourceVersionId",s.version(),"expectedCanvasItemVersion",s.itemVersion(),"name","Synthetic scarlet coat","category","coats","tags",List.of("scarlet","winter"),"commandKey",key);}
    JsonNode save(Source s,Map<String,Object> input)throws Exception{return mapper.readTree(mvc.perform(post("/api/v1/projects/"+s.project()+"/reference-catalog/costumes").with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(input))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());}
    @Test void savesIndependentBytesUnifiesSearchAndReplaysAfterSourceDeletion()throws Exception{
        var s=source();var input=saveInput(s,"save");var saved=save(s,input);String id=saved.path("id").asText();assertThat(saved.path("origin").asText()).isEqualTo("SELF_CREATED");assertThat(saved.path("sourceId").asText()).isEqualTo("self-created");assertThat(saved.toString()).doesNotContain("objectKey","media_json","thumbnailKey");
        byte[] original=Files.readAllBytes(assets.get(owner.userId(),s.project(),s.asset()).path());
        jdbc.sql("delete from canvas_item where id=:id").param("id",s.item()).update();Files.delete(assets.get(owner.userId(),s.project(),s.asset()).path());
        assertThat(save(s,input)).isEqualTo(saved);
        mvc.perform(get("/api/v1/reference-catalog/costumes/"+id+"/image").with(auth)).andExpect(status().isOk()).andExpect(content().bytes(original));
        var page=mapper.readTree(mvc.perform(get("/api/v1/reference-catalog").with(auth).param("kind","COSTUME").param("query","scarlet winter "+id).param("category","coats")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertThat(page.path("items").size()).isEqualTo(1);
        var changed=new HashMap<>(input);changed.put("name","Different");mvc.perform(post("/api/v1/projects/"+s.project()+"/reference-catalog/costumes").with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(changed))).andExpect(status().isConflict());
        var sourceProject=projects.get(owner.userId(),s.project());var archived=projects.archive(owner.userId(),s.project(),sourceProject.version());projects.delete(owner.userId(),s.project(),archived.version());
        mvc.perform(get("/api/v1/reference-catalog/costumes/"+id+"/image").with(auth)).andExpect(status().isOk()).andExpect(content().bytes(original));
        var target=projects.create(owner.userId(),"Costume target",Project.AspectRatio.LANDSCAPE_16_9);String importPath="/api/v1/projects/"+target.id()+"/reference-catalog/costumes/"+id+"/import";String body=mapper.writeValueAsString(Map.of("commandKey","import","expectedVersion",0,"placement",Map.of("x",12,"y",34)));
        String imported=mvc.perform(post(importPath).with(auth).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(patch("/api/v1/reference-catalog/costumes/"+id).with(auth).with(csrf()).contentType("application/json").content("{\"expectedVersion\":0,\"name\":\"Updated\",\"tags\":[]}")).andExpect(status().isOk()).andExpect(jsonPath("version").value(1));
        mvc.perform(delete("/api/v1/reference-catalog/costumes/"+id).with(auth).with(csrf()).param("expectedVersion","0")).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/reference-catalog/costumes/"+id).with(auth).with(csrf()).param("expectedVersion","1")).andExpect(status().isNoContent());
        mvc.perform(post(importPath).with(auth).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(content().json(imported));
        mvc.perform(get("/api/v1/reference-catalog/costumes/"+id+"/image").with(auth)).andExpect(status().isNotFound());
        mvc.perform(post(importPath).with(auth).with(csrf()).contentType("application/json").content(body.replace("\"import\"","\"new-import\""))).andExpect(status().isNotFound());
    }
    @Test void acceptedSaveAndImportRecoverWithImmutableBytesAfterDeletion()throws Exception{
        var s=source();var request=new CustomCostumeService.Save(s.item(),s.version(),s.itemVersion(),"Recovery coat",null,List.of(),"recover-save");
        doThrow(new IllegalStateException("synthetic save publication failure")).doCallRealMethod().when(costumeRepository).complete(any(),any());
        assertThatThrownBy(()->costumes.save(owner.userId(),s.project(),request)).isInstanceOf(IllegalStateException.class);
        Files.delete(assets.get(owner.userId(),s.project(),s.asset()).path());
        var saved=costumes.save(owner.userId(),s.project(),request);UUID id=UUID.fromString(saved.id());
        var target=projects.create(owner.userId(),"Recovery target",Project.AspectRatio.LANDSCAPE_16_9);
        doThrow(new IllegalStateException("synthetic import publication failure")).doCallRealMethod().when(importRepository).complete(any(),any());
        assertThatThrownBy(()->imports.importCostume(owner.userId(),target.id(),id,0,"recover-import",null)).isInstanceOf(IllegalStateException.class);
        costumes.delete(id,0);
        var recovered=imports.importCostume(owner.userId(),target.id(),id,0,"recover-import",null);
        assertThat(recovered.versionId()).isNotNull();assertThat(imports.importCostume(owner.userId(),target.id(),id,0,"recover-import",null)).isEqualTo(recovered);
        assertThat(jdbc.sql("select count(*) from artifact_version where project_id=:p").param("p",target.id()).query(Long.class).single()).isEqualTo(1);
    }
    @Test void concurrentSaveAndImportPublishOnceAndExternalCleanupCannotTouchCostumes()throws Exception{
        var s=source();var request=new CustomCostumeService.Save(s.item(),s.version(),s.itemVersion(),"Concurrent coat",null,List.of("synthetic"),"concurrent-save");
        ReferenceCatalogService.EntryView saved;
        try(var pool=Executors.newFixedThreadPool(2)){var first=pool.submit(()->costumes.save(owner.userId(),s.project(),request));var second=pool.submit(()->costumes.save(owner.userId(),s.project(),request));saved=first.get(30,TimeUnit.SECONDS);assertThat(second.get(30,TimeUnit.SECONDS)).isEqualTo(saved);}
        var external=catalog.create("synthetic-"+UUID.randomUUID(),"Synthetic external","fixture","repo","a".repeat(40),"reference-pack.json",Format.AGENVAS_REFERENCE_PACK,true,dev.agenvas.referencecatalog.domain.ReferenceCatalog.SourceType.GITHUB);
        when(github.resolve(any())).thenReturn("a".repeat(40));
        when(github.file(any(),any(),eq("reference-pack.json"),anyInt())).thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return Files.readAllBytes(Path.of("../examples/reference-pack/reference-pack.json"));});
        assertThat(catalog.sync(external.id(),0).state()).isEqualTo("SUCCESS");
        var mixed=catalog.list(Kind.COSTUME,null,"",null,null,null,null,null,null,0,100);assertThat(mixed.items()).extracting(ReferenceCatalogService.EntryView::origin).contains(Origin.SELF_CREATED,Origin.THIRD_PARTY);
        assertThat(catalog.list(Kind.COSTUME,external.id(),"",null,null,null,null,null,null,0,100).items()).allMatch(e->e.origin()==Origin.THIRD_PARTY);
        var automatic=mapper.readTree(mvc.perform(get("/api/v1/reference-catalog").with(auth)
                .param("kind","COSTUME").param("sourceId",external.id()).param("includeSelfCreated","true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(automatic.path("items")).anyMatch(e->e.path("origin").asText().equals("SELF_CREATED"));
        assertThat(automatic.path("items")).anyMatch(e->e.path("origin").asText().equals("THIRD_PARTY"));
        var latest=catalog.source(external.id());catalog.update(external.id(),latest.version(),false,false);cache.clear(null,null,List.of());
        UUID id=UUID.fromString(saved.id());assertThat(Files.readAllBytes(costumes.image(id).path())).isEqualTo(Files.readAllBytes(assets.get(owner.userId(),s.project(),s.asset()).path()));
        var target=projects.create(owner.userId(),"Concurrent target",Project.AspectRatio.LANDSCAPE_16_9);
        try(var pool=Executors.newFixedThreadPool(2)){var first=pool.submit(()->imports.importCostume(owner.userId(),target.id(),id,0,"concurrent-import",null));var second=pool.submit(()->imports.importCostume(owner.userId(),target.id(),id,0,"concurrent-import",null));assertThat(first.get(30,TimeUnit.SECONDS)).isEqualTo(second.get(30,TimeUnit.SECONDS));}
    }
    @Test void readsVisibleMetadataForAnyAuthenticatedSessionAndHidesDeletedEntries()throws Exception{
        var s=source();var saved=save(s,saveInput(s,"read-metadata"));String path="/api/v1/reference-catalog/costumes/"+saved.path("id").asText();
        mvc.perform(get(path).with(auth)).andExpect(status().isOk()).andExpect(content().json(saved.toString()));
        mvc.perform(get(path).with(user("viewer").roles("USER"))).andExpect(status().isOk()).andExpect(content().json(saved.toString()));
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(delete(path).with(auth).with(csrf()).param("expectedVersion","0")).andExpect(status().isNoContent());
        mvc.perform(get(path).with(auth)).andExpect(status().isNotFound());
        mvc.perform(get(path).with(user("viewer").roles("USER"))).andExpect(status().isNotFound());
    }
    @Test void rejectsDuplicateTagsBeforeCreatingACommand()throws Exception{
        var s=source();var input=new HashMap<>(saveInput(s,"duplicate-tags"));input.put("tags",List.of("coat","coat"));
        mvc.perform(post("/api/v1/projects/"+s.project()+"/reference-catalog/costumes").with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(input))).andExpect(status().isBadRequest());
    }
    @Test void validatesSourceCasProjectIsolationAndAdminBoundary()throws Exception{
        var s=source();var wrong=new HashMap<>(saveInput(s,"bad-cas"));wrong.put("expectedCanvasItemVersion",s.itemVersion()+1);
        mvc.perform(post("/api/v1/projects/"+s.project()+"/reference-catalog/costumes").with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(wrong))).andExpect(status().isConflict());
        var p=projects.create(owner.userId(),"Another",Project.AspectRatio.LANDSCAPE_16_9);
        mvc.perform(post("/api/v1/projects/"+p.id()+"/reference-catalog/costumes").with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(saveInput(s,"scope")))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/projects/"+s.project()+"/reference-catalog/costumes").with(user("viewer").roles("USER")).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(saveInput(s,"viewer")))).andExpect(status().isForbidden());
        var saved=save(s,saveInput(s,"authorized"));UUID id=UUID.fromString(saved.path("id").asText());
        assertThatThrownBy(()->imports.importCostume(UUID.randomUUID(),p.id(),id,0,"unauthorized-target",null)).isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        mvc.perform(patch("/api/v1/reference-catalog/costumes/"+id).with(user("viewer").roles("USER")).with(csrf()).contentType("application/json").content("{\"expectedVersion\":0,\"name\":\"x\",\"tags\":[]}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/reference-catalog/costumes/"+id).with(user("viewer").roles("USER")).with(csrf()).param("expectedVersion","0")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reference-catalog/costumes/"+UUID.randomUUID()+"/image")).andExpect(status().isUnauthorized());
    }
}
