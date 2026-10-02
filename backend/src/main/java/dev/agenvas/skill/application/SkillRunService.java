package dev.agenvas.skill.application;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.SkillAssetArchiveService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import dev.agenvas.skill.domain.SkillContent;
import dev.agenvas.skill.infrastructure.JooqSkillInstallRepository;
import dev.agenvas.skill.infrastructure.JooqSkillInstallRepository.Operation;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Agent-only selection, durable project installation and immutable Run context. */
@Service
public class SkillRunService {
    public enum SelectionMode { DEFAULT, NONE, VERSION }
    public enum InstallStatus { ACCEPTED, PREPARING, SUCCEEDED, FAILED, CLEANING }
    public record Input(String alias, UUID artifactVersionId) {}
    public record Selection(SelectionMode mode, UUID skillId, UUID skillVersionId, List<Input> inputs) {}
    public record BindingResponse(long agentVersion, UUID skillId, UUID skillVersionId) {}
    public record Installation(UUID id, InstallStatus status, UUID skillId, UUID skillVersionId, String errorCode, String errorDetail) {}
    public record Summary(UUID skillId, UUID skillVersionId, String title, long versionNumber, String bundleHash,
            List<SkillContent.InputSlot> inputSlots, List<SkillContent.PublishedResource> resources,
            List<SkillService.AssetResponse> assets, boolean installed) {}
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(SkillRunService.class);
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final int MAX_BINDINGS = 40;
    private static final int MAX_INPUTS = 14;
    private static final int MAX_KEY = 200;
    private final SkillService skills;
    private final JooqSkillInstallRepository operations;
    private final SkillAssetArchiveService archive;
    private final AgentInstanceService agents;
    private final ArtifactService artifacts;
    private final ProjectService projects;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ShutdownGate gate;
    private final TransactionTemplate tx;

    public SkillRunService(SkillService skills, JooqSkillInstallRepository operations, SkillAssetArchiveService archive,
            AgentInstanceService agents, ArtifactService artifacts, ProjectService projects, ProjectEventService events,
            ObjectMapper mapper, Clock clock, ShutdownGate gate, PlatformTransactionManager transactions) {
        this.skills=skills;this.operations=operations;this.archive=archive;this.agents=agents;this.artifacts=artifacts;
        this.projects=projects;this.events=events;this.mapper=mapper;this.clock=clock;this.gate=gate;tx=new TransactionTemplate(transactions);
    }

    public BindingResponse getBinding(UUID owner, UUID project, UUID agent) {
        AgentInstance instance=agents.get(owner,project,agent);
        return skills.getBinding(owner,project,agent).map(binding -> new BindingResponse(instance.version(),binding.skillId(),binding.skillVersionId()))
                .orElse(new BindingResponse(instance.version(),null,null));
    }

    public BindingResponse saveBinding(UUID owner, UUID project, UUID agent, long expected, UUID skill, UUID version, String key) {
        checkKey(key);
        if ((skill==null)!=(version==null) || expected<0) throw problem("SKILL_SELECTION_INVALID",HttpStatus.BAD_REQUEST,ApiMessage.of("api.skill-run.skill-selection-invalid"));
        String hash=Sha256.hex(agent+"\n"+expected+"\n"+skill+"\n"+version);
        return events.recordChange(owner,project,() -> {
            agents.get(owner,project,agent); // Ordinary canvas IDs cannot enter this boundary.
            var prior=operations.bindingCommand(owner,project,key);
            if(prior.isPresent()) {
                if(!prior.get().payloadHash().equals(hash)) throw problem("IDEMPOTENCY_CONFLICT",HttpStatus.CONFLICT,ApiMessage.of("api.skill-run.idempotency-conflict"));
                return ProjectEventService.Change.unchanged(mapper.treeToValue(prior.get().response(),BindingResponse.class));
            }
            if(skill!=null) skills.requireSelectableVersion(owner,skill,version);
            AgentInstance changed=agents.touchConfigurationWithinChange(owner,project,agent,expected);
            skills.saveBinding(owner,project,agent,skill,version);
            BindingResponse response=new BindingResponse(changed.version(),skill,version);
            operations.saveBindingCommand(owner,project,agent,key,hash,mapper.valueToTree(response),clock.instant());
            ObjectNode payload=mapper.createObjectNode().put("agentId",agent.toString());
            return ProjectEventService.Change.changed(response,new ProjectEventService.EventDraft("agent.instance.changed",1,agent,changed.version(),payload));
        }).value();
    }

    /** Acceptance creates only local copy work. It never calls a model or places a canvas card. */
    public Installation install(UUID owner, UUID project, UUID agent, UUID skill, UUID version, String key) {
        checkKey(key); gate.requireAcceptingRuns();
        String hash=Sha256.hex(agent+"\n"+skill+"\n"+version);
        return events.recordChange(owner,project,() -> {
            projects.requireActiveProject(owner,project); agents.get(owner,project,agent);
            var prior=operations.command(owner,project,key);
            if(prior.isPresent()) {
                if(!prior.get().payloadHash().equals(hash)) throw problem("IDEMPOTENCY_CONFLICT",HttpStatus.CONFLICT,ApiMessage.of("api.skill-run.idempotency-conflict"));
                return ProjectEventService.Change.unchanged(response(requireOperation(owner,project,prior.get().operationId())));
            }
            // A retained default binding may explicitly reuse its frozen version after trash.
            boolean bound=skills.getBinding(owner,project,agent).filter(b -> b.skillId().equals(skill)&&b.skillVersionId().equals(version)).isPresent();
            SkillContent.Version bundle=bound?skills.getBundle(owner,skill,version):skills.requireSelectableVersion(owner,skill,version);
            Operation operation=operations.version(owner,project,version).orElse(null);
            if(operation==null) {
                ObjectNode input=mapper.createObjectNode().put("schemaVersion",SkillContent.SCHEMA_VERSION);
                input.put("bundleHash",bundle.bundleHash());
                operation=new Operation(UUID.randomUUID(),owner,project,skill,version,key,hash,input,null,mapper.createArrayNode(),InstallStatus.ACCEPTED.name(),0,null,null,null);
                operations.create(operation,clock.instant());
            } else if(InstallStatus.FAILED.name().equals(operation.status())) {
                if(!operations.retry(operation,clock.instant())) throw problem("SKILL_INSTALL_CONFLICT",HttpStatus.CONFLICT,ApiMessage.of("api.skill-run.skill-install-conflict"));
                operation=requireOperation(owner,project,operation.id());
            }
            operations.saveCommand(owner,project,key,hash,operation.id(),clock.instant());
            return ProjectEventService.Change.unchanged(response(operation));
        }).value();
    }

    public Installation getInstallation(UUID owner, UUID project, UUID agent, UUID operation) {
        agents.get(owner,project,agent);
        return response(requireOperation(owner,project,operation));
    }

    /** Short claim and final fenced transaction surround bounded filesystem copying. */
    public boolean processNext() {
        var claimed=gate.claimOrEmpty(() -> tx.execute(ignored -> operations.claim(clock.instant(),clock.instant().plus(LEASE))),java.util.Optional.<Operation>empty());
        if(claimed==null || claimed.isEmpty()) return false;
        Operation op=claimed.get();
        List<Asset> prepared=new ArrayList<>();
        ObjectNode progress=mapper.createObjectNode();
        ArrayNode retained=progress.putArray("preparedAssets");
        if(op.result()!=null) for(JsonNode prior:op.result().path("preparedAssets")) retained.add(prior.deepCopy());
        try {
            SkillContent.Version version=skills.getBundle(op.ownerId(),op.skillId(),op.skillVersionId());
            if(!version.bundleHash().equals(op.input().path("bundleHash").asText())) throw new IllegalStateException("Immutable Skill hash mismatch");
            for(var asset:version.bundle().assets()) {
                UUID id=UUID.nameUUIDFromBytes(("skill-install:"+op.id()+":"+op.epoch()+":"+asset.alias()).getBytes(StandardCharsets.UTF_8));
                ObjectNode planned=mapper.createObjectNode().put("id",id.toString()).put("projectId",op.projectId().toString())
                        .put("mediaKind",Asset.MediaKind.IMAGE.name());
                tx.executeWithoutResult(ignored -> operations.trackPrepared(op,planned));
                // Check after acquiring the file lock, so an expired writer cannot recreate cleaned bytes.
                Asset file=archive.prepareProjectImport(op.ownerId(),op.projectId(),id,asset.media(),
                        () -> Boolean.TRUE.equals(tx.execute(ignored -> operations.active(op,clock.instant()))));
                tx.executeWithoutResult(ignored -> operations.trackPrepared(op,mapper.valueToTree(file)));
                prepared.add(file);
                boolean saved=false;
                for(JsonNode existing:retained) if(id.toString().equals(existing.path("id").asText())) saved=true;
                if(!saved) retained.add(mapper.valueToTree(file));
                boolean active=Boolean.TRUE.equals(tx.execute(ignored -> operations.checkpoint(op,progress,clock.instant())));
                if(!active) return true;
            }
            events.recordChange(op.ownerId(),op.projectId(),() -> {
                projects.requireActiveProject(op.ownerId(),op.projectId());
                if(!operations.active(op,clock.instant())) return ProjectEventService.Change.unchanged(false);
                ObjectNode result=mapper.createObjectNode().put("schemaVersion",SkillContent.SCHEMA_VERSION);
                ArrayNode mappings=result.putArray("assets");
                for(int i=0;i<prepared.size();i++) {
                    var source=version.bundle().assets().get(i);
                    var registered=archive.registerProjectImport(op.ownerId(),prepared.get(i),source.title());
                    ObjectNode mapping=mappings.addObject();
                    mapping.put("alias",source.alias()).put("artifactId",registered.artifact().id().toString())
                            .put("artifactVersionId",registered.resourceDefaultVersion().id().toString());
                }
                if(!operations.finish(op,InstallStatus.SUCCEEDED.name(),result,null,null,clock.instant())) throw new IllegalStateException("Skill installation lease changed");
                return ProjectEventService.Change.unchanged(true);
            });
        } catch(RuntimeException failure) {
            LOGGER.warn("Skill installation failed: {}", failure.getClass().getSimpleName());
            // Files retain deterministic identities for explicit archival retry; no generation occurs.
            tx.executeWithoutResult(ignored -> operations.finish(op,InstallStatus.FAILED.name(),retained.isEmpty()?null:progress,
                    "SKILL_INSTALL_FAILED",ApiMessage.of("api.skill-run.operation-failed").source(),clock.instant()));
        }
        return true;
    }

    /** A durable cleanup lease excludes explicit retry until each unregistered file is removed. */
    public boolean cleanupNext() {
        var claimed=gate.claimOrEmpty(() -> tx.execute(ignored -> operations.claimCleanup(clock.instant(),clock.instant().plus(LEASE))),java.util.Optional.<Operation>empty());
        if(claimed==null||claimed.isEmpty()) return false;
        Operation op=claimed.get();
        try {
            for(JsonNode prepared:op.cleanup()) {
                if(!clock.instant().isBefore(op.leaseUntil())) return true;
                if(prepared.path("objectKey").isTextual()) archive.cleanupPreparedProjectImport(op.ownerId(),mapper.treeToValue(prepared,Asset.class));
                else archive.cleanupPlannedSkillImport(op.ownerId(),op.projectId(),UUID.fromString(prepared.path("id").asText()));
            }
            tx.executeWithoutResult(ignored -> operations.finishCleanup(op,clock.instant()));
        } catch(RuntimeException unavailable) {
            LOGGER.warn("Skill installation cleanup deferred: {}", unavailable.getClass().getSimpleName());
            // The lease expires for recovery; retaining metadata prevents silent orphaning.
        }
        return true;
    }

    public Summary preview(UUID owner, UUID project, UUID agent, Selection selection) {
        var version=selectedVersion(owner,project,agent,selection);
        if(version==null) return null;
        var published=skills.getVersion(owner,version.skillId(),version.id());
        boolean installed=operations.version(owner,project,version.id()).filter(Operation::registered).isPresent();
        return new Summary(version.skillId(),version.id(),skills.get(owner,version.skillId()).title(),version.versionNumber(),version.bundleHash(),
                version.bundle().inputSlots(),version.bundle().resources(),published.assets(),installed);
    }

    /** Call only within the final Run creation transaction, after Agent/conversation CAS checks. */
    public void freezeIntoRun(UUID owner, UUID project, AgentInstance agent, UUID runId, Selection selection, ObjectNode context) {
        var version=selectedVersion(owner,project,agent.id(),selection);
        if(version==null) return;
        Operation installed=operations.version(owner,project,version.id()).filter(Operation::registered)
                .orElseThrow(() -> problem("SKILL_INSTALL_PENDING",HttpStatus.CONFLICT,ApiMessage.of("api.skill-run.skill-install-pending")));
        ObjectNode snapshot=mapper.createObjectNode().put("schemaVersion",SkillContent.SCHEMA_VERSION);
        snapshot.put("agentRunId",runId.toString()).put("skillId",version.skillId().toString()).put("skillVersionId",version.id().toString())
                .put("versionNumber",version.versionNumber()).put("bundleHash",version.bundleHash()).put("name",version.bundle().name())
                .put("description",version.bundle().description()).put("skillMd",version.bundle().skillMd());
        snapshot.set("outputKinds",mapper.valueToTree(version.bundle().outputKinds()));
        snapshot.set("resources",mapper.valueToTree(version.bundle().resources()));
        ArrayNode manifest=snapshot.putArray("resourceManifest");
        version.bundle().resources().forEach(resource -> manifest.addObject().put("path",resource.path()).put("contentHash",resource.contentHash()));
        ArrayNode inputs=snapshot.putArray("inputs");
        List<Input> requested=selection==null||selection.inputs()==null?List.of():selection.inputs();
        if(requested.size()>MAX_INPUTS) throw problem("SKILL_INPUT_INVALID",HttpStatus.BAD_REQUEST,ApiMessage.of("api.skill-run.skill-input-invalid"));
        HashSet<String> aliases=new HashSet<>();
        for(var input:requested) {
            if(input==null || input.alias()==null || input.artifactVersionId()==null || !aliases.add(input.alias())
                    || version.bundle().inputSlots().stream().noneMatch(slot -> slot.alias().equals(input.alias()))) throw problem("SKILL_INPUT_INVALID",HttpStatus.BAD_REQUEST,ApiMessage.of("api.skill-run.skill-input-invalid"));
        }
        for(var slot:version.bundle().inputSlots()) {
            Input input=requested.stream().filter(item -> item.alias().equals(slot.alias())).findFirst().orElse(null);
            if(input==null) { if(slot.required()) throw problem("SKILL_INPUT_REQUIRED",HttpStatus.UNPROCESSABLE_ENTITY,ApiMessage.of("api.skill-run.skill-input-required")); else continue; }
            var binding=agent.bindings().stream().filter(item -> item.selectedVersionId().equals(input.artifactVersionId())).findFirst()
                    .orElseThrow(() -> problem("SKILL_INPUT_NOT_BOUND",HttpStatus.UNPROCESSABLE_ENTITY,ApiMessage.of("api.skill-run.skill-input-not-bound")));
            var artifact=artifacts.get(owner,project,binding.artifactId()).artifact();
            artifacts.requireVersion(owner,project,binding.artifactId(),input.artifactVersionId());
            if(artifact.kind()!=slot.kind()) throw problem("SKILL_INPUT_KIND_MISMATCH",HttpStatus.UNPROCESSABLE_ENTITY,ApiMessage.of("api.skill-run.skill-input-kind-mismatch"));
            inputs.addObject().put("alias",slot.alias()).put("artifactId",binding.artifactId().toString())
                    .put("artifactVersionId",input.artifactVersionId().toString()).put("kind",slot.kind().name()).put("required",slot.required());
        }
        ArrayNode assets=snapshot.putArray("assets");
        ArrayNode bindings=(ArrayNode)context.path("bindings");
        for(var source:version.bundle().assets()) {
            JsonNode mapping=null;
            for(var candidate:installed.result().path("assets")) if(source.alias().equals(candidate.path("alias").asText())) { mapping=candidate;break; }
            if(mapping==null) throw new IllegalStateException("Skill installation mapping missing");
            UUID artifactId=UUID.fromString(mapping.path("artifactId").asText()), versionId=UUID.fromString(mapping.path("artifactVersionId").asText());
            artifacts.requireVersion(owner,project,artifactId,versionId);
            assets.addObject().put("alias",source.alias()).put("kind",source.kind().name()).put("title",source.title())
                    .put("contentHash",source.contentHash()).put("usage",source.usage().name()).put("required",source.required())
                    .put("purpose",source.purpose()).put("artifactId",artifactId.toString()).put("artifactVersionId",versionId.toString());
            bindings.addObject().put("artifactId",artifactId.toString()).put("selectedVersionId",versionId.toString())
                    .put("bindingType",AgentInstance.BindingType.INPUT.name()).put("kind",source.kind().name()).put("title",source.title());
        }
        if(bindings.size()>MAX_BINDINGS) throw problem("SKILL_CONTEXT_LIMIT",HttpStatus.UNPROCESSABLE_ENTITY,ApiMessage.of("api.skill-run.skill-context-limit"));
        context.set("creativeSkill",snapshot);
    }

    /** Export fixed source content and exact project mappings without private archive metadata. */
    public List<JsonNode> exportProject(UUID owner, UUID project) {
        projects.get(owner,project);
        return operations.successful(owner,project).stream().map(op -> {
            var published=skills.getVersion(owner,op.skillId(),op.skillVersionId());
            ObjectNode safe=mapper.createObjectNode();
            safe.set("version",mapper.valueToTree(published));safe.set("mapping",op.result().deepCopy());
            // Account-serving URLs are regenerated on import, never persisted in a backup.
            for(JsonNode item:safe.path("version").path("assets")) if(item instanceof ObjectNode asset) {asset.remove("contentUrl");asset.remove("thumbnailUrl");}
            return (JsonNode)safe;
        }).toList();
    }
    private SkillContent.Version selectedVersion(UUID owner, UUID project, UUID agent, Selection selection) {
        if(selection!=null && selection.mode()==null) throw problem("SKILL_SELECTION_INVALID",HttpStatus.BAD_REQUEST,ApiMessage.of("api.skill-run.skill-selection-invalid"));
        SelectionMode mode=selection==null?SelectionMode.DEFAULT:selection.mode();
        if(mode!=SelectionMode.VERSION && selection!=null&&(selection.skillId()!=null||selection.skillVersionId()!=null)) throw problem("SKILL_SELECTION_INVALID",HttpStatus.BAD_REQUEST,ApiMessage.of("api.skill-run.skill-selection-invalid"));
        if(mode==SelectionMode.NONE) {
            if(selection.inputs()!=null&&!selection.inputs().isEmpty()) throw problem("SKILL_SELECTION_INVALID",HttpStatus.BAD_REQUEST,ApiMessage.of("api.skill-run.skill-selection-invalid"));
            return null;
        }
        if(mode==SelectionMode.DEFAULT) {
            var binding = skills.getBinding(owner,project,agent);
            if(binding.isEmpty() && selection!=null && selection.inputs()!=null && !selection.inputs().isEmpty()) throw problem("SKILL_SELECTION_INVALID",HttpStatus.BAD_REQUEST,ApiMessage.of("api.skill-run.skill-selection-invalid"));
            return binding.map(value -> skills.getBundle(owner,value.skillId(),value.skillVersionId())).orElse(null);
        }
        if(selection.skillId()==null||selection.skillVersionId()==null) throw problem("SKILL_SELECTION_INVALID",HttpStatus.BAD_REQUEST,ApiMessage.of("api.skill-run.skill-selection-invalid"));
        boolean retained = skills.getBinding(owner,project,agent).filter(binding -> binding.skillId().equals(selection.skillId())
                && binding.skillVersionId().equals(selection.skillVersionId())).isPresent();
        return retained ? skills.getBundle(owner,selection.skillId(),selection.skillVersionId())
                : skills.requireSelectableVersion(owner,selection.skillId(),selection.skillVersionId());
    }
    private Operation requireOperation(UUID owner, UUID project, UUID id) {return operations.find(owner,project,id).orElseThrow(() -> problem("SKILL_INSTALL_NOT_FOUND",HttpStatus.NOT_FOUND,ApiMessage.of("api.skill-run.skill-install-not-found")));}
    private Installation response(Operation op){return new Installation(op.id(),op.registered()?InstallStatus.SUCCEEDED:InstallStatus.valueOf(op.status()),op.skillId(),op.skillVersionId(),op.errorCode(),op.errorDetail());}
    private void checkKey(String key){if(key==null||key.isBlank()||key.length()>MAX_KEY)throw problem("IDEMPOTENCY_KEY_INVALID",HttpStatus.BAD_REQUEST,ApiMessage.of("api.skill-run.idempotency-key-invalid"));}
    private ApiProblemException problem(String code, HttpStatus status, ApiMessage detail) {
        return new ApiProblemException(status, code, ApiMessage.of("api.skill-run.invalid"), detail, false);
    }
}
