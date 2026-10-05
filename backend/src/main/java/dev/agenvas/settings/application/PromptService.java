package dev.agenvas.settings.application;

import static dev.agenvas.db.Tables.PROMPT_DEFINITION;

import dev.agenvas.db.tables.records.PromptDefinitionRecord;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A prompt is a reusable definition; callers copy its content into their immutable execution input. */
@Service
public class PromptService {
    public static final String DIRECTOR_KEY = "agent.director";
    public static final String TEXT_GENERATION_KEY = "text.generate";
    public static final int MAX_KEY_LENGTH = 120;
    public static final int MAX_NAME_LENGTH = 120;
    public static final int MAX_CONTENT_LENGTH = 8000;
    public static final int MAX_DESCRIPTION_LENGTH = 1000;
    public static final String KEY_PATTERN = "^[a-z][a-z0-9._-]{0,119}$";
    private final DSLContext dsl;
    private final Clock clock;
    public PromptService(DSLContext dsl, Clock clock) { this.dsl = dsl; this.clock = clock; }
    public enum Kind { AGENT, FUNCTION }
    public record Prompt(UUID id, String key, Kind kind, String name, String description, String content,
            boolean builtIn, long version, Instant createdAt, Instant updatedAt) {}
    public record AgentPreset(String key, String name) {}

    @Transactional(readOnly = true)
    public List<Prompt> list() {
        return dsl.selectFrom(PROMPT_DEFINITION).orderBy(PROMPT_DEFINITION.BUILT_IN.desc(), PROMPT_DEFINITION.KEY.asc()).fetch(this::view);
    }
    @Transactional(readOnly = true)
    public List<AgentPreset> agentPresets() {
        return dsl.select(PROMPT_DEFINITION.KEY, PROMPT_DEFINITION.NAME).from(PROMPT_DEFINITION)
                .where(PROMPT_DEFINITION.KIND.eq(Kind.AGENT.name())).orderBy(PROMPT_DEFINITION.BUILT_IN.desc(), PROMPT_DEFINITION.KEY.asc())
                .fetch(row -> new AgentPreset(row.value1(), row.value2()));
    }
    @Transactional(readOnly = true)
    public Prompt get(UUID id) {
        return dsl.selectFrom(PROMPT_DEFINITION).where(PROMPT_DEFINITION.ID.eq(id)).fetchOptional(this::view).orElseThrow(this::notFound);
    }
    @Transactional(readOnly = true)
    public Prompt require(String key, Kind kind) {
        return dsl.selectFrom(PROMPT_DEFINITION).where(PROMPT_DEFINITION.KEY.eq(key)).and(PROMPT_DEFINITION.KIND.eq(kind.name()))
                .fetchOptional(this::view).orElseThrow(this::notFound);
    }
    @Transactional
    public Prompt create(String key, Kind kind, String name, String description, String content) {
        if (key == null || !key.matches(KEY_PATTERN) || kind == null) throw invalid();
        String validName = text(name, MAX_NAME_LENGTH, false);
        String validDescription = text(description, MAX_DESCRIPTION_LENGTH, true);
        String validContent = text(content, MAX_CONTENT_LENGTH, false);
        UUID id = UUID.randomUUID(); OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int changed = dsl.insertInto(PROMPT_DEFINITION).set(PROMPT_DEFINITION.ID, id).set(PROMPT_DEFINITION.KEY, key)
                .set(PROMPT_DEFINITION.KIND, kind.name()).set(PROMPT_DEFINITION.NAME, validName)
                .set(PROMPT_DEFINITION.DESCRIPTION, validDescription).set(PROMPT_DEFINITION.CONTENT, validContent)
                .set(PROMPT_DEFINITION.CREATED_AT, now).set(PROMPT_DEFINITION.UPDATED_AT, now)
                .onConflict(PROMPT_DEFINITION.KEY).doNothing().execute();
        if (changed != 1) throw problem(HttpStatus.CONFLICT, "PROMPT_KEY_CONFLICT", ApiMessage.of("api.prompt.key-conflict"));
        return get(id);
    }
    @Transactional
    public Prompt update(UUID id, long expectedVersion, String name, String description, String content) {
        String validName = text(name, MAX_NAME_LENGTH, false);
        String validDescription = text(description, MAX_DESCRIPTION_LENGTH, true);
        String validContent = text(content, MAX_CONTENT_LENGTH, false);
        get(id);
        int changed = dsl.update(PROMPT_DEFINITION).set(PROMPT_DEFINITION.NAME, validName)
                .set(PROMPT_DEFINITION.DESCRIPTION, validDescription).set(PROMPT_DEFINITION.CONTENT, validContent)
                .set(PROMPT_DEFINITION.VERSION, PROMPT_DEFINITION.VERSION.plus(1))
                .set(PROMPT_DEFINITION.UPDATED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .where(PROMPT_DEFINITION.ID.eq(id)).and(PROMPT_DEFINITION.VERSION.eq(expectedVersion)).execute();
        if (changed != 1) throw conflict();
        return get(id);
    }
    @Transactional
    public void delete(UUID id, long expectedVersion) {
        Prompt prior = get(id);
        if (prior.builtIn()) throw problem(HttpStatus.CONFLICT, "PROMPT_IN_USE", ApiMessage.of("api.prompt.in-use"));
        if (dsl.deleteFrom(PROMPT_DEFINITION).where(PROMPT_DEFINITION.ID.eq(id))
                .and(PROMPT_DEFINITION.VERSION.eq(expectedVersion)).and(PROMPT_DEFINITION.BUILT_IN.isFalse()).execute() != 1) throw conflict();
    }
    private Prompt view(PromptDefinitionRecord row) {
        return new Prompt(row.getId(), row.getKey(), Kind.valueOf(row.getKind()), row.getName(), row.getDescription(),
                row.getContent(), row.getBuiltIn(), row.getVersion(), row.getCreatedAt().toInstant(), row.getUpdatedAt().toInstant());
    }
    private String text(String value, int maximum, boolean emptyAllowed) {
        String normalized = value == null ? "" : value.trim();
        if ((!emptyAllowed && normalized.isEmpty()) || normalized.length() > maximum) throw invalid();
        return normalized;
    }
    private ApiProblemException notFound() { return problem(HttpStatus.NOT_FOUND, "PROMPT_NOT_FOUND", ApiMessage.of("api.prompt.not-found")); }
    private ApiProblemException invalid() { return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ApiMessage.of("api.prompt.invalid")); }
    private ApiProblemException conflict() { return problem(HttpStatus.CONFLICT, "PROMPT_CONFLICT", ApiMessage.of("api.prompt.conflict")); }
    private ApiProblemException problem(HttpStatus status, String code, ApiMessage message) {
        return new ApiProblemException(status, code, message, message, false);
    }
}
