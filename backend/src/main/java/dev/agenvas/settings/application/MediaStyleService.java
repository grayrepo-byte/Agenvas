package dev.agenvas.settings.application;

import static dev.agenvas.db.Tables.MEDIA_STYLE;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Global administrator-controlled visual presets. Accepted Tasks keep an independent snapshot. */
@Service
public class MediaStyleService {
    public static final int MAX_NAME_LENGTH = 80;
    public static final int MAX_CATEGORY_LENGTH = 40;
    public static final int MAX_PROMPT_LENGTH = 4000;
    private static final String PROMPT_SEPARATOR = "\n\nVisual style: ";
    private static final String PREVIEW_PATH = "/api/v1/media-styles/";
    private static final String PRESET_DIRECTORY = "media-styles/";
    private static final String PRESET_EXTENSION = ".webp";
    private static final org.jooq.Field<Boolean> HAS_PREVIEW = org.jooq.impl.DSL.field(MEDIA_STYLE.THUMBNAIL_BYTES.isNotNull()).as("hasPreview");
    private static final List<org.jooq.SelectField<?>> CATALOG_FIELDS = List.of(MEDIA_STYLE.ID, MEDIA_STYLE.NAME,
            MEDIA_STYLE.CATEGORY, MEDIA_STYLE.ENABLED, MEDIA_STYLE.VERSION, MEDIA_STYLE.BUILTIN_KEY, HAS_PREVIEW);
    private static final List<org.jooq.SelectField<?>> SNAPSHOT_FIELDS = List.of(MEDIA_STYLE.ID, MEDIA_STYLE.NAME,
            MEDIA_STYLE.ENABLED, MEDIA_STYLE.VERSION, MEDIA_STYLE.PROMPT_SUFFIX);
    private final DSLContext dsl;
    private final Clock clock;
    private final MediaStyleThumbnailCodec thumbnails;

    public MediaStyleService(DSLContext dsl, Clock clock, MediaStyleThumbnailCodec thumbnails) {
        this.dsl = dsl;
        this.clock = clock;
        this.thumbnails = thumbnails;
    }

    public record Summary(UUID id, String name, String category, boolean enabled,
            long version, String thumbnailUrl, boolean builtIn) {}
    public record Style(UUID id, String name, String category, boolean enabled,
            long version, String thumbnailUrl, boolean builtIn, String promptSuffix) {}
    public record Snapshot(UUID id, long version, String name, String promptSuffix) {}
    public record Thumbnail(byte[] bytes, String contentType) {}

    @Transactional(readOnly = true)
    public List<Summary> catalog() {
        return dsl.select(CATALOG_FIELDS).from(MEDIA_STYLE).orderBy(MEDIA_STYLE.CREATED_AT, MEDIA_STYLE.ID)
                .fetch(this::summary);
    }

    @Transactional(readOnly = true)
    public List<Style> settings() {
        return dsl.select(CATALOG_FIELDS).select(MEDIA_STYLE.PROMPT_SUFFIX).from(MEDIA_STYLE)
                .orderBy(MEDIA_STYLE.CREATED_AT, MEDIA_STYLE.ID).fetch(this::style);
    }

    /** Read-only preflight resolves the current preset without acquiring a row lock. */
    @Transactional(readOnly = true)
    public Snapshot forGeneration(UUID id, Artifact.Kind kind) {
        if (id == null) return null;
        if (kind != Artifact.Kind.IMAGE && kind != Artifact.Kind.VIDEO) {
            throw problem(HttpStatus.BAD_REQUEST, "MEDIA_STYLE_KIND_UNSUPPORTED", Error.KIND);
        }
        var row = dsl.select(SNAPSHOT_FIELDS).from(MEDIA_STYLE).where(MEDIA_STYLE.ID.eq(id)).fetchOne();
        if (row == null || !row.get(MEDIA_STYLE.ENABLED)) {
            throw problem(HttpStatus.CONFLICT, "MEDIA_STYLE_UNAVAILABLE", Error.UNAVAILABLE);
        }
        return new Snapshot(id, row.get(MEDIA_STYLE.VERSION), row.get(MEDIA_STYLE.NAME), row.get(MEDIA_STYLE.PROMPT_SUFFIX));
    }

    /** Keeps a preset unchanged until the caller commits approval revalidation and Task acceptance. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockForGeneration(UUID id) {
        if (id == null) return;
        var row = dsl.select(MEDIA_STYLE.ENABLED).from(MEDIA_STYLE).where(MEDIA_STYLE.ID.eq(id)).forShare().fetchOne();
        if (row == null || !row.get(MEDIA_STYLE.ENABLED))
            throw problem(HttpStatus.CONFLICT, "MEDIA_STYLE_UNAVAILABLE", Error.UNAVAILABLE);
    }

    /** A disabled choice remains in a draft and fails explicitly during submission. */
    @Transactional(readOnly = true)
    public void validateSelection(UUID id, Artifact.Kind kind) {
        if (id == null) return;
        if (kind != Artifact.Kind.IMAGE && kind != Artifact.Kind.VIDEO) {
            throw problem(HttpStatus.BAD_REQUEST, "MEDIA_STYLE_KIND_UNSUPPORTED", Error.KIND);
        }
        var row = dsl.select(MEDIA_STYLE.ENABLED).from(MEDIA_STYLE).where(MEDIA_STYLE.ID.eq(id)).fetchOne();
        if (row == null) {
            throw problem(HttpStatus.CONFLICT, "MEDIA_STYLE_UNAVAILABLE", Error.UNAVAILABLE);
        }
    }

    public static String compose(String userPrompt, Snapshot style) {
        return style == null ? userPrompt : userPrompt + PROMPT_SEPARATOR + style.promptSuffix();
    }

    @Transactional(readOnly = true)
    public Snapshot snapshotForExport(UUID id) {
        if (id == null) return null;
        var row = dsl.select(SNAPSHOT_FIELDS).from(MEDIA_STYLE).where(MEDIA_STYLE.ID.eq(id)).fetchOne();
        if (row == null) throw new IllegalStateException("Draft style violates its foreign key");
        return new Snapshot(id, row.get(MEDIA_STYLE.VERSION), row.get(MEDIA_STYLE.NAME), row.get(MEDIA_STYLE.PROMPT_SUFFIX));
    }

    @Transactional
    public Style create(String name, String category, String promptSuffix, boolean enabled) {
        validate(name, category, promptSuffix);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        var row = dsl.insertInto(MEDIA_STYLE).set(MEDIA_STYLE.ID, id)
                .set(MEDIA_STYLE.NAME, name.strip()).set(MEDIA_STYLE.CATEGORY, category.strip())
                .set(MEDIA_STYLE.PROMPT_SUFFIX, promptSuffix.strip()).set(MEDIA_STYLE.ENABLED, enabled)
                .set(MEDIA_STYLE.VERSION, 0L).set(MEDIA_STYLE.CREATED_AT, utc(now))
                .set(MEDIA_STYLE.UPDATED_AT, utc(now)).returning().fetchSingle();
        return style(row);
    }

    @Transactional
    public Style update(UUID id, long expectedVersion, String name, String category,
            String promptSuffix, boolean enabled) {
        validate(name, category, promptSuffix);
        if (expectedVersion < 0) throw problem(HttpStatus.BAD_REQUEST, "MEDIA_STYLE_INVALID", Error.INVALID);
        var row = dsl.update(MEDIA_STYLE).set(MEDIA_STYLE.NAME, name.strip())
                .set(MEDIA_STYLE.CATEGORY, category.strip()).set(MEDIA_STYLE.PROMPT_SUFFIX, promptSuffix.strip())
                .set(MEDIA_STYLE.ENABLED, enabled).set(MEDIA_STYLE.VERSION, MEDIA_STYLE.VERSION.plus(1))
                .set(MEDIA_STYLE.UPDATED_AT, utc(clock.instant())).where(MEDIA_STYLE.ID.eq(id))
                .and(MEDIA_STYLE.VERSION.eq(expectedVersion)).returning().fetchOne();
        if (row == null) throw problem(HttpStatus.CONFLICT, "MEDIA_STYLE_CONFLICT", Error.CONFLICT);
        return style(row);
    }

    /** Decoding takes place outside the short database update transaction. */
    public byte[] prepareThumbnail(InputStream input) { return thumbnails.decode(input); }

    @Transactional
    public Style replaceThumbnail(UUID id, long expectedVersion, byte[] png) {
        if (expectedVersion < 0 || png.length == 0 || png.length > MediaStyleThumbnailCodec.MAX_STORED_BYTES)
            throw problem(HttpStatus.BAD_REQUEST, "MEDIA_STYLE_INVALID_THUMBNAIL", Error.THUMBNAIL);
        var row = dsl.update(MEDIA_STYLE).set(MEDIA_STYLE.THUMBNAIL_BYTES, png)
                .set(MEDIA_STYLE.THUMBNAIL_CONTENT_TYPE, "image/png")
                .set(MEDIA_STYLE.VERSION, MEDIA_STYLE.VERSION.plus(1))
                .set(MEDIA_STYLE.UPDATED_AT, utc(clock.instant())).where(MEDIA_STYLE.ID.eq(id))
                .and(MEDIA_STYLE.VERSION.eq(expectedVersion)).returning().fetchOne();
        if (row == null) throw problem(HttpStatus.CONFLICT, "MEDIA_STYLE_CONFLICT", Error.CONFLICT);
        return style(row);
    }

    @Transactional(readOnly = true)
    public Thumbnail thumbnail(UUID id) {
        var row = dsl.selectFrom(MEDIA_STYLE).where(MEDIA_STYLE.ID.eq(id)).fetchOne();
        if (row == null) throw problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", Error.NOT_FOUND);
        if (row.getThumbnailBytes() != null) return new Thumbnail(row.getThumbnailBytes(), row.getThumbnailContentType());
        if (row.getBuiltinKey() != null) {
            ClassPathResource resource = new ClassPathResource(PRESET_DIRECTORY + row.getBuiltinKey() + PRESET_EXTENSION);
            try (InputStream input = resource.getInputStream()) {
                return new Thumbnail(input.readNBytes(MediaStyleThumbnailCodec.MAX_STORED_BYTES + 1), "image/webp");
            } catch (IOException failure) {
                throw problem(HttpStatus.NOT_FOUND, "MEDIA_STYLE_THUMBNAIL_NOT_FOUND", Error.NOT_FOUND);
            }
        }
        throw problem(HttpStatus.NOT_FOUND, "MEDIA_STYLE_THUMBNAIL_NOT_FOUND", Error.NOT_FOUND);
    }

    private Summary summary(Record row) {
        UUID id = row.get(MEDIA_STYLE.ID);
        long version = row.get(MEDIA_STYLE.VERSION);
        boolean builtIn = row.get(MEDIA_STYLE.BUILTIN_KEY) != null;
        boolean uploadedPreview = row.field(HAS_PREVIEW) != null ? Boolean.TRUE.equals(row.get(HAS_PREVIEW))
                : row.get(MEDIA_STYLE.THUMBNAIL_BYTES) != null;
        String url = builtIn || uploadedPreview
                ? PREVIEW_PATH + id + "/thumbnail?v=" + version : null;
        return new Summary(id, row.get(MEDIA_STYLE.NAME), row.get(MEDIA_STYLE.CATEGORY),
                row.get(MEDIA_STYLE.ENABLED), version, url, builtIn);
    }

    private Style style(Record row) {
        Summary summary = summary(row);
        return new Style(summary.id(), summary.name(), summary.category(), summary.enabled(),
                summary.version(), summary.thumbnailUrl(), summary.builtIn(), row.get(MEDIA_STYLE.PROMPT_SUFFIX));
    }

    private void validate(String name, String category, String promptSuffix) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH
                || category == null || category.isBlank() || category.length() > MAX_CATEGORY_LENGTH
                || promptSuffix == null || promptSuffix.isBlank() || promptSuffix.length() > MAX_PROMPT_LENGTH)
            throw problem(HttpStatus.BAD_REQUEST, "MEDIA_STYLE_INVALID", Error.INVALID);
    }

    private static OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    enum Error { KIND, UNAVAILABLE, INVALID, CONFLICT, THUMBNAIL, NOT_FOUND }
    static ApiProblemException problem(HttpStatus status, String code, Error error) {
        ApiMessage detail = switch (error) {
            case KIND -> ApiMessage.of("api.media-style.kind");
            case UNAVAILABLE -> ApiMessage.of("api.media-style.unavailable");
            case INVALID -> ApiMessage.of("api.media-style.invalid");
            case CONFLICT -> ApiMessage.of("api.media-style.conflict");
            case THUMBNAIL -> ApiMessage.of("api.media-style.thumbnail");
            case NOT_FOUND -> ApiMessage.of("api.media-style.not-found");
        };
        return new ApiProblemException(status, code, ApiMessage.of("api.media-style.title"), detail, false);
    }
}
