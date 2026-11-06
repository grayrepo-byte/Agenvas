package dev.agenvas.characterlibrary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Public requests over real PostgreSQL, with synthetic account/profile content only. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class CharacterLibraryPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path ROOT = root();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", ROOT::toString);
        registry.add("agenvas.character-library.worker-enabled", () -> "false");
        registry.add("agenvas.library.worker-enabled", () -> "false");
    }
    private static dev.agenvas.identity.application.AdminPrincipal owner;
    @Autowired IdentityService identities;
    @Autowired dev.agenvas.characterlibrary.application.CharacterLibraryService service;
    @Autowired dev.agenvas.character.application.CharacterService characters;
    @Autowired dev.agenvas.project.application.ProjectService projects;
    @Autowired dev.agenvas.asset.application.AssetService assets;
    @Autowired dev.agenvas.artifact.application.ArtifactService artifacts;
    @Autowired dev.agenvas.asset.infrastructure.MediaToolRunner mediaTools;
    @Autowired dev.agenvas.library.application.LibraryService library;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @Autowired dev.agenvas.characterlibrary.infrastructure.CharacterLibraryRepository repository;
    @Autowired dev.agenvas.asset.application.PrivateMediaArchive archive;
    @Autowired dev.agenvas.event.application.ProjectEventService events;
    @Autowired dev.agenvas.shared.lifecycle.ShutdownGate shutdown;
    @Autowired java.time.Clock clock;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @org.junit.jupiter.api.BeforeEach void setupOwner() {
        if (owner==null) owner=identities.setup("character-library-admin", "synthetic-password-123");
    }
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;

    @Test void ownedProfilesAreVersionedAndCommandsReplayTheirOriginalResult() throws Exception {
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        UUID id = UUID.randomUUID(), command = UUID.randomUUID();
        String path = "/api/v1/character-library/profiles/" + id;
        String request = mapper.writeValueAsString(Map.of("commandId", command, "expectedVersion", 0,
                "content", Map.of("name", "Mira", "persona", "Synthetic explorer", "appearance", "Silver hair",
                        "identityViews", List.of(), "looks", List.of()))).replace("\"looks\":[]", "\"looks\":[],\"voice\":null");
        String saved = mvc.perform(put(path).with(auth).with(csrf()).contentType("application/json").content(request))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(saved).path("version").asLong()).isEqualTo(1);
        mvc.perform(put(path).with(auth).with(csrf()).contentType("application/json").content(request))
                .andExpect(status().isOk()).andExpect(result -> assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("version").asLong()).isEqualTo(1));
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(auth)).andExpect(status().isOk());
    }
    @Test void concurrentProfileSavesHaveOneWinnerAndHistoricalReceiptsCannotOverwriteIt() throws Exception {
        UUID id=UUID.randomUUID(), firstCommand=UUID.randomUUID();
        var first=new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Save(firstCommand,0,content("First"));
        service.save(owner.userId(),id,first);
        try (var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(() -> concurrentSave(start,id,"Second A"));
            var b=pool.submit(() -> concurrentSave(start,id,"Second B"));
            start.countDown();
            assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder("SAVED","VERSION_CONFLICT");
        }
        assertThat(service.get(owner.userId(),id).version()).isEqualTo(2);
        assertThat(service.save(owner.userId(),id,first).version()).isEqualTo(1);
        assertThat(service.get(owner.userId(),id).version()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.get(UUID.randomUUID(),id))
                .isInstanceOfSatisfying(dev.agenvas.shared.error.ApiProblemException.class,p -> assertThat(p.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.save(owner.userId(),id,
                new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Save(firstCommand,0,content("Changed payload"))))
                .isInstanceOfSatisfying(dev.agenvas.shared.error.ApiProblemException.class,p -> assertThat(p.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }
    private String concurrentSave(java.util.concurrent.CountDownLatch start,UUID id,String name) throws InterruptedException {
        start.await();
        try { service.save(owner.userId(),id,new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Save(UUID.randomUUID(),1,content(name))); return "SAVED"; }
        catch (dev.agenvas.shared.error.ApiProblemException problem) { return problem.code(); }
    }
    @Test void publishedCharacterSurvivesSourceDeletionAndImportCreatesIndependentFixedProjectContent() throws Exception {
        var source=projects.create(owner.userId(),"Synthetic source",dev.agenvas.project.domain.Project.AspectRatio.LANDSCAPE_16_9);
        UUID image=dev.agenvas.testing.ImageAssetFixture.archive(assets,owner.userId(),source.id());
        byte[] original=Files.readAllBytes(assets.get(owner.userId(),source.id(),image).path());
        var version=artifacts.create(owner.userId(),source.id(),dev.agenvas.artifact.domain.Artifact.Kind.IMAGE,"Portrait",mapper.valueToTree(Map.of("sourceType","UPLOAD","assetId",image))).resourceDefaultVersion();
        UUID character=UUID.randomUUID(),look=UUID.randomUUID(),profileId=UUID.randomUUID(),command=UUID.randomUUID();
        var sourceContent=new dev.agenvas.character.application.CharacterModels.CharacterContent("Mira","Explorer",List.of(
                new dev.agenvas.character.application.CharacterModels.CharacterLook(look,"Coat","Red coat","Calm","Travel",List.of(
                        new dev.agenvas.character.application.CharacterModels.CharacterView(version.id(),"Front","Synthetic face")))),
                new dev.agenvas.character.application.CharacterModels.CharacterVoice(null,"synthetic_voice","Voice direction"));
        characters.save(owner.userId(),source.id(),character,new dev.agenvas.character.application.CharacterModels.SaveCharacter(UUID.randomUUID(),0,sourceContent));
        var publish=new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Publish(command,0,source.id(),character,1,List.of(version.id()));
        assertThat(service.publish(owner.userId(),profileId,publish).status().name()).isEqualTo("ACCEPTED");
        var archived=projects.archive(owner.userId(),source.id(),source.version()); projects.delete(owner.userId(),source.id(),archived.version());
        assertThat(service.processNext()).isTrue();
        var published=service.command(owner.userId(),command);
        assertThat(published.status().name()).isEqualTo("SUCCEEDED");
        var profile=service.get(owner.userId(),profileId);
        UUID media=profile.content().identityViews().getFirst().mediaId();
        Files.delete(ROOT.resolve(source.id().toString()).resolve(image+".png"));
        assertThat(Files.readAllBytes(service.file(owner.userId(),media,false).path())).containsExactly(original);
        var target=projects.create(owner.userId(),"Synthetic target",dev.agenvas.project.domain.Project.AspectRatio.LANDSCAPE_16_9);
        UUID targetCharacter=UUID.randomUUID(),importCommand=UUID.randomUUID();
        var transfer=new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Transfer(profileId,1,targetCharacter,0);
        var preview=service.preview(owner.userId(),target.id(),transfer);
        var request=new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Import(profileId,1,targetCharacter,0,importCommand,preview.previewHash());
        service.importProfile(owner.userId(),target.id(),request);
        assertThat(service.processNext()).isTrue();
        assertThat(service.command(owner.userId(),importCommand).status().name()).isEqualTo("SUCCEEDED");
        var imported=characters.list(owner.userId(),target.id()).characters().getFirst();
        assertThat(imported.content().description()).isEqualTo("Explorer");
        assertThat(imported.content().looks().getFirst().views()).singleElement().satisfies(view -> {
            assertThat(view.purpose()).startsWith("Identity baseline:");
            assertThat(view.versionId()).isNotEqualTo(version.id());
        });
        assertThat(imported.content().voice().speakerId()).isEqualTo("synthetic_voice");
        assertThat(assets.listProjectAssets(owner.userId(),target.id())).hasSize(1);
        assertThat(service.importProfile(owner.userId(),target.id(),request).result()).isEqualTo(service.command(owner.userId(),importCommand).result());
        var update=service.save(owner.userId(),profileId,new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Save(UUID.randomUUID(),1,
                new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Content("Mira new","New persona","Silver hair",profile.content().identityViews(),profile.content().looks(),profile.content().voice())));
        assertThat(service.links(owner.userId(),target.id()).items().getFirst().hasUpdate()).isTrue();
        assertThat(characters.list(owner.userId(),target.id()).characters().getFirst().version()).isEqualTo(1);
        var sync=new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Transfer(profileId,update.version(),targetCharacter,1);
        var syncPreview=service.preview(owner.userId(),target.id(),sync);
        service.save(owner.userId(),profileId,new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Save(UUID.randomUUID(),2,
                new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Content("Mira newest","Changed again","",profile.content().identityViews(),profile.content().looks(),profile.content().voice())));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.importProfile(owner.userId(),target.id(),
                new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Import(profileId,2,targetCharacter,1,UUID.randomUUID(),syncPreview.previewHash())))
                .isInstanceOfSatisfying(dev.agenvas.shared.error.ApiProblemException.class,p -> assertThat(p.code()).isEqualTo("VERSION_CONFLICT"));
        var fresh=service.preview(owner.userId(),target.id(),new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Transfer(profileId,3,targetCharacter,1));
        UUID syncCommand=UUID.randomUUID();
        service.importProfile(owner.userId(),target.id(),new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Import(profileId,3,targetCharacter,1,syncCommand,fresh.previewHash()));
        service.processNext();
        assertThat(service.command(owner.userId(),syncCommand).status().name()).isEqualTo("SUCCEEDED");
        assertThat(characters.list(owner.userId(),target.id()).characters().getFirst().version()).isEqualTo(2);
        assertThat(service.links(owner.userId(),target.id()).items().getFirst().hasUpdate()).isFalse();
        service.archive(owner.userId(),profileId,new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Archive(UUID.randomUUID(),3));
        assertThat(characters.list(owner.userId(),target.id()).characters().getFirst().content().name()).isEqualTo("Mira newest");
        assertThat(Files.readAllBytes(service.file(owner.userId(),media,false).path())).containsExactly(original);
    }
    @Test void libraryEntryDeletionAndExpiredWorkerLeaseDoNotBreakAcceptedMedia() throws Exception {
        UUID upload=UUID.randomUUID();
        byte[] bytes=dev.agenvas.testing.ImageAssetFixture.encoded("png",3,3);
        var source=library.upload(owner.userId(),"Synthetic library portrait",dev.agenvas.library.domain.LibraryEntry.Category.CHARACTER,
                dev.agenvas.asset.domain.Asset.MediaKind.IMAGE,upload.toString(),new org.springframework.mock.web.MockMultipartFile("file","synthetic.png","image/png",bytes));
        assertThat(library.processNext()).isTrue();
        UUID entry=UUID.fromString(library.command(owner.userId(),source.id()).result().path("entryId").asText());
        UUID command=UUID.randomUUID();
        var request=new dev.agenvas.characterlibrary.application.CharacterLibraryModels.MediaSource(command,dev.agenvas.characterlibrary.application.CharacterLibraryModels.SourceType.LIBRARY_ENTRY,entry,0L,null,null);
        service.media(owner.userId(),request);
        var trashed=library.trash(owner.userId(),entry,0,false); library.delete(owner.userId(),entry,trashed.version());
        // Simulated crash after claim, before committing an archive. A new worker must reclaim it.
        jdbc.sql("update character_library_command set status='ARCHIVING',epoch=9,lease_until=now()-interval '1 minute' where owner_id=:owner and id=:id")
                .param("owner",owner.userId()).param("id",command).update();
        assertThat(service.processNext()).isTrue();
        var finished=service.command(owner.userId(),command);
        assertThat(finished.status().name()).isEqualTo("SUCCEEDED");
        UUID media=UUID.fromString(finished.result().path("mediaId").asText());
        assertThat(Files.readAllBytes(service.file(owner.userId(),media,false).path())).containsExactly(bytes);
        assertThat(service.media(owner.userId(),request).result()).isEqualTo(finished.result());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.file(UUID.randomUUID(),media,false))
                .isInstanceOfSatisfying(dev.agenvas.shared.error.ApiProblemException.class,p -> assertThat(p.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        service.cleanupNext();
        assertThat(Files.readAllBytes(service.file(owner.userId(),media,false).path())).containsExactly(bytes);
    }
    @Test void multipleLooksKeepIdentityDescriptionsAndExactAudioWhenCopiedToAProject() throws Exception {
        var source=projects.create(owner.userId(),"Synthetic audio source",dev.agenvas.project.domain.Project.AspectRatio.LANDSCAPE_16_9);
        UUID audioAsset=dev.agenvas.testing.AudioAssetFixture.archive(assets,mediaTools,owner.userId(),source.id());
        var audioVersion=artifacts.create(owner.userId(),source.id(),dev.agenvas.artifact.domain.Artifact.Kind.AUDIO,"Synthetic voice",
                mapper.valueToTree(Map.of("sourceType","UPLOAD","assetId",audioAsset))).resourceDefaultVersion();
        UUID audioCommand=UUID.randomUUID();
        service.media(owner.userId(),new dev.agenvas.characterlibrary.application.CharacterLibraryModels.MediaSource(audioCommand,
                dev.agenvas.characterlibrary.application.CharacterLibraryModels.SourceType.PROJECT_VERSION,null,null,source.id(),audioVersion.id()));
        service.processNext();
        assertThat(service.command(owner.userId(),audioCommand).status().name()).isEqualTo("SUCCEEDED");
        UUID audio=UUID.fromString(service.command(owner.userId(),audioCommand).result().path("mediaId").asText());
        UUID image=uploadImage();
        var identity=new dev.agenvas.characterlibrary.application.CharacterLibraryModels.View(image,"Face","Recognizable face");
        var looks=List.of(new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Look(UUID.randomUUID(),"Day","Coat","Calm","Daylight",
                List.of(new dev.agenvas.characterlibrary.application.CharacterLibraryModels.View(image,"Front","Day makeup"))),
                new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Look(UUID.randomUUID(),"Night","Dress","Alert","Evening",List.of()));
        UUID libraryId=UUID.randomUUID();
        service.save(owner.userId(),libraryId,new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Save(UUID.randomUUID(),0,
                new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Content("Mira","Explorer","Silver hair",List.of(identity),looks,
                        new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Voice(audio,"synthetic_voice","Quiet tone"))));
        var target=projects.create(owner.userId(),"Synthetic audio target",dev.agenvas.project.domain.Project.AspectRatio.LANDSCAPE_16_9);
        UUID character=UUID.randomUUID(),command=UUID.randomUUID();
        var preview=service.preview(owner.userId(),target.id(),new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Transfer(libraryId,1,character,0));
        service.importProfile(owner.userId(),target.id(),new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Import(libraryId,1,character,0,command,preview.previewHash()));
        service.processNext();
        assertThat(service.command(owner.userId(),command).status().name()).isEqualTo("SUCCEEDED");
        var imported=characters.list(owner.userId(),target.id()).characters().getFirst();
        assertThat(imported.content().description()).isEqualTo("Explorer\n\nAppearance:\nSilver hair");
        assertThat(imported.content().looks()).hasSize(2).allSatisfy(look -> assertThat(look.views()).hasSize(1));
        assertThat(imported.content().looks().getFirst().views().getFirst().description()).contains("Recognizable face","Front","Day makeup");
        assertThat(imported.content().voice().versionId()).isNotEqualTo(audioVersion.id());
        var copiedAudio=artifacts.requireVersionById(owner.userId(),target.id(),imported.content().voice().versionId());
        var ready=assets.get(owner.userId(),target.id(),UUID.fromString(copiedAudio.content().path("assetId").asText()));
        assertThat(Files.readAllBytes(ready.path())).containsExactly(Files.readAllBytes(service.file(owner.userId(),audio,false).path()));
        assertThat(assets.listProjectAssets(owner.userId(),target.id())).hasSize(2);
    }
    @Test void libraryChangesAfterAcceptanceRollBackAllPreparedProjectContentAndCleanupCopies() throws Exception {
        UUID image=uploadImage(),libraryId=UUID.randomUUID();
        var content=new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Content("Mira","Explorer","",List.of(
                new dev.agenvas.characterlibrary.application.CharacterLibraryModels.View(image,"Face","Synthetic")),List.of(),null);
        service.save(owner.userId(),libraryId,new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Save(UUID.randomUUID(),0,content));
        var target=projects.create(owner.userId(),"Synthetic rollback target",dev.agenvas.project.domain.Project.AspectRatio.LANDSCAPE_16_9);
        UUID character=UUID.randomUUID(),command=UUID.randomUUID();
        var preview=service.preview(owner.userId(),target.id(),new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Transfer(libraryId,1,character,0));
        service.importProfile(owner.userId(),target.id(),new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Import(libraryId,1,character,0,command,preview.previewHash()));
        service.save(owner.userId(),libraryId,new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Save(UUID.randomUUID(),1,content));
        service.processNext();
        assertThat(service.command(owner.userId(),command).errorCode()).isEqualTo("VERSION_CONFLICT");
        assertThat(assets.listProjectAssets(owner.userId(),target.id())).isEmpty();
        assertThat(artifacts.listProject(owner.userId(),target.id())).isEmpty();
        assertThat(characters.list(owner.userId(),target.id()).characters()).isEmpty();
        for (int i=0;i<30;i++) service.cleanupNext();
        try (var files=Files.list(ROOT.resolve(target.id().toString()))) {
            assertThat(files.filter(path -> path.toString().endsWith(".png")).toList()).isEmpty();
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.retry(owner.userId(),command))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        assertThat(Files.exists(service.file(owner.userId(),image,false).path())).isTrue();
    }
    @Test void targetDeletionRejectsAcceptedImportWithoutDamagingTheLibrary() {
        UUID libraryId=UUID.randomUUID();
        service.save(owner.userId(),libraryId,new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Save(UUID.randomUUID(),0,content("Mira")));
        var target=projects.create(owner.userId(),"Synthetic removed target",dev.agenvas.project.domain.Project.AspectRatio.LANDSCAPE_16_9);
        UUID character=UUID.randomUUID(),command=UUID.randomUUID();
        var preview=service.preview(owner.userId(),target.id(),new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Transfer(libraryId,1,character,0));
        service.importProfile(owner.userId(),target.id(),new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Import(libraryId,1,character,0,command,preview.previewHash()));
        var archived=projects.archive(owner.userId(),target.id(),target.version()); projects.delete(owner.userId(),target.id(),archived.version());
        service.processNext();
        assertThat(service.command(owner.userId(),command).status().name()).isEqualTo("FAILED");
        assertThat(service.command(owner.userId(),command).errorCode()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(service.get(owner.userId(),libraryId).version()).isEqualTo(1);
    }
    @Test void syncPreviewShowsProjectCustomizationsBeforeExplicitReplacement() throws Exception {
        UUID libraryId=UUID.randomUUID();
        service.save(owner.userId(),libraryId,new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Save(UUID.randomUUID(),0,content("Library name")));
        var target=projects.create(owner.userId(),"Synthetic diff target",dev.agenvas.project.domain.Project.AspectRatio.LANDSCAPE_16_9);
        UUID character=UUID.randomUUID(),command=UUID.randomUUID();
        var initial=service.preview(owner.userId(),target.id(),new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Transfer(libraryId,1,character,0));
        service.importProfile(owner.userId(),target.id(),new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Import(libraryId,1,character,0,command,initial.previewHash()));
        service.processNext();
        var custom=new dev.agenvas.character.application.CharacterModels.CharacterContent("Project name","Project custom persona",
                List.of(new dev.agenvas.character.application.CharacterModels.CharacterLook(UUID.randomUUID(),"Project custom look","Blue coat","Happy","Custom detail",List.of())),
                new dev.agenvas.character.application.CharacterModels.CharacterVoice(null,"project_custom_voice","Custom voice direction"));
        characters.save(owner.userId(),target.id(),character,new dev.agenvas.character.application.CharacterModels.SaveCharacter(UUID.randomUUID(),1,custom));
        var auth=authentication(new UsernamePasswordAuthenticationToken(owner,null,List.of()));
        var mvc=webAppContextSetup(context).apply(springSecurity()).build();
        String response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/projects/"+target.id()+"/character-library/imports/preview")
                .with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("libraryId",libraryId,"libraryVersion",1,"characterId",character,"expectedCharacterVersion",2))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var preview=mapper.readTree(response);
        assertThat(preview.path("currentCharacter").path("content").path("description").asText()).isEqualTo("Project custom persona");
        assertThat(preview.path("currentCharacter").path("content").path("looks").get(0).path("name").asText()).isEqualTo("Project custom look");
        assertThat(preview.path("currentCharacter").path("content").path("voice").path("speakerId").asText()).isEqualTo("project_custom_voice");
        assertThat(preview.path("content").path("name").asText()).isEqualTo("Library name");
        assertThat(characters.list(owner.userId(),target.id()).characters().getFirst().version()).isEqualTo(2);
    }
    @Test void lostCommitAcknowledgementKeepsAcceptedPinsRecoverable() throws Exception {
        var loseAcknowledgement=new java.util.concurrent.atomic.AtomicBoolean(true);
        var uncertain=new org.springframework.transaction.PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return transactions.getTransaction(definition);
            }
            @Override public void commit(org.springframework.transaction.TransactionStatus status) {
                transactions.commit(status);
                if (loseAcknowledgement.getAndSet(false)) throw new org.springframework.transaction.TransactionSystemException("Synthetic commit acknowledgement lost");
            }
            @Override public void rollback(org.springframework.transaction.TransactionStatus status) { transactions.rollback(status); }
        };
        var accepting=new dev.agenvas.characterlibrary.application.CharacterLibraryService(repository,archive,library,assets,artifacts,characters,projects,events,shutdown,clock,mapper,uncertain);
        var source=projects.create(owner.userId(),"Synthetic uncertain source",dev.agenvas.project.domain.Project.AspectRatio.LANDSCAPE_16_9);
        UUID asset=dev.agenvas.testing.ImageAssetFixture.archive(assets,owner.userId(),source.id());
        byte[] original=Files.readAllBytes(assets.get(owner.userId(),source.id(),asset).path());
        var version=artifacts.create(owner.userId(),source.id(),dev.agenvas.artifact.domain.Artifact.Kind.IMAGE,"Portrait",mapper.valueToTree(Map.of("sourceType","UPLOAD","assetId",asset))).resourceDefaultVersion();
        UUID command=UUID.randomUUID();
        var request=new dev.agenvas.characterlibrary.application.CharacterLibraryModels.MediaSource(command,
                dev.agenvas.characterlibrary.application.CharacterLibraryModels.SourceType.PROJECT_VERSION,null,null,source.id(),version.id());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> accepting.media(owner.userId(),request))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThat(service.command(owner.userId(),command).status().name()).isEqualTo("ACCEPTED");
        service.processNext();
        assertThat(service.command(owner.userId(),command).status().name()).isEqualTo("SUCCEEDED");
        UUID media=UUID.fromString(service.command(owner.userId(),command).result().path("mediaId").asText());
        assertThat(Files.readAllBytes(service.file(owner.userId(),media,false).path())).containsExactly(original);
        assertThat(service.media(owner.userId(),request).result()).isEqualTo(service.command(owner.userId(),command).result());
    }
    private UUID uploadImage() {
        UUID command=UUID.randomUUID();
        service.upload(owner.userId(),command,dev.agenvas.asset.domain.Asset.MediaKind.IMAGE,
                new org.springframework.mock.web.MockMultipartFile("file","synthetic.png","image/png",dev.agenvas.testing.ImageAssetFixture.encoded("png",3,3)));
        service.processNext();
        assertThat(service.command(owner.userId(),command).status().name()).isEqualTo("SUCCEEDED");
        return UUID.fromString(service.command(owner.userId(),command).result().path("mediaId").asText());
    }
    private dev.agenvas.characterlibrary.application.CharacterLibraryModels.Content content(String name) {
        return new dev.agenvas.characterlibrary.application.CharacterLibraryModels.Content(name,"Synthetic persona","",List.of(),List.of(),null);
    }
    private static Path root() {
        try { return Files.createTempDirectory("agenvas-character-library-it-"); }
        catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
