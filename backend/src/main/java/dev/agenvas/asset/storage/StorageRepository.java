package dev.agenvas.asset.storage;

import static dev.agenvas.db.Tables.STORAGE_PROFILE;
import static dev.agenvas.db.Tables.STORAGE_SETTINGS;
import static dev.agenvas.db.Tables.ASSET_STORAGE_ROUTE;
import dev.agenvas.settings.application.CredentialCipher.Encrypted;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

/** Destination configuration and independent archive checkpoints; network calls stay outside transactions. */
@Repository
public class StorageRepository {
    private final DSLContext dsl;
    public StorageRepository(DSLContext dsl) { this.dsl = dsl; }
    public record State(int version, UUID activeProfileId) {}
    public record Route(UUID profileId, String mediaKind, String metadata, boolean ready) {}
    public State state() {
        var r = dsl.selectFrom(STORAGE_SETTINGS).where(STORAGE_SETTINGS.SINGLETON.isTrue()).fetchSingle();
        return new State(r.getVersion(), r.getActiveProfileId());
    }
    public int lockVersion() {
        return dsl.select(STORAGE_SETTINGS.VERSION).from(STORAGE_SETTINGS)
                .where(STORAGE_SETTINGS.SINGLETON.isTrue()).forUpdate().fetchSingle(STORAGE_SETTINGS.VERSION);
    }
    public void advance(int expected, UUID active) {
        if (dsl.update(STORAGE_SETTINGS).set(STORAGE_SETTINGS.VERSION, Math.addExact(expected, 1))
                .set(STORAGE_SETTINGS.ACTIVE_PROFILE_ID, active).where(STORAGE_SETTINGS.SINGLETON.isTrue())
                .and(STORAGE_SETTINGS.VERSION.eq(expected)).execute() != 1)
            throw new IllegalStateException("Storage configuration CAS failed");
    }
    public void insertProfile(StorageProfile p) {
        dsl.insertInto(STORAGE_PROFILE).set(STORAGE_PROFILE.ID, p.id()).set(STORAGE_PROFILE.NAME, p.name())
                .set(STORAGE_PROFILE.PROVIDER, p.provider().name()).set(STORAGE_PROFILE.ENDPOINT, p.endpoint())
                .set(STORAGE_PROFILE.REGION, p.region()).set(STORAGE_PROFILE.BUCKET, p.bucket())
                .set(STORAGE_PROFILE.KEY_PREFIX, p.keyPrefix()).set(STORAGE_PROFILE.PATH_STYLE, p.pathStyle())
                .set(STORAGE_PROFILE.CREDENTIAL_VERSION, p.credentialVersion())
                .set(STORAGE_PROFILE.CREDENTIAL_CIPHERTEXT, p.credentials().ciphertext())
                .set(STORAGE_PROFILE.CREDENTIAL_NONCE, p.credentials().nonce())
                .set(STORAGE_PROFILE.CREDENTIAL_KEY_VERSION, p.credentials().keyVersion())
                .set(STORAGE_PROFILE.ACCESS_KEY_MASK, p.accessKeyMask())
                .set(STORAGE_PROFILE.CREATED_AT, OffsetDateTime.ofInstant(p.createdAt(), ZoneOffset.UTC)).execute();
    }
    public void rotate(UUID id, int version, Encrypted encrypted, String mask) {
        if (dsl.update(STORAGE_PROFILE).set(STORAGE_PROFILE.CREDENTIAL_VERSION, version)
                .set(STORAGE_PROFILE.CREDENTIAL_CIPHERTEXT, encrypted.ciphertext())
                .set(STORAGE_PROFILE.CREDENTIAL_NONCE, encrypted.nonce())
                .set(STORAGE_PROFILE.CREDENTIAL_KEY_VERSION, encrypted.keyVersion())
                .set(STORAGE_PROFILE.ACCESS_KEY_MASK, mask).where(STORAGE_PROFILE.ID.eq(id)).execute() != 1)
            throw new IllegalStateException("Storage profile missing during credential rotation");
    }
    public List<StorageProfile> profiles() {
        return dsl.selectFrom(STORAGE_PROFILE).orderBy(STORAGE_PROFILE.CREATED_AT, STORAGE_PROFILE.ID).fetch(this::map);
    }
    public Optional<StorageProfile> profile(UUID id) {
        return dsl.selectFrom(STORAGE_PROFILE).where(STORAGE_PROFILE.ID.eq(id)).fetchOptional(this::map);
    }
    private StorageProfile map(dev.agenvas.db.tables.records.StorageProfileRecord r) {
        return new StorageProfile(r.getId(), r.getName(), StorageProfile.Provider.valueOf(r.getProvider()),
                r.getEndpoint(), r.getRegion(), r.getBucket(), r.getKeyPrefix(), r.getPathStyle(), r.getCredentialVersion(),
                new Encrypted(r.getCredentialCiphertext(), r.getCredentialNonce(), r.getCredentialKeyVersion()),
                r.getAccessKeyMask(), r.getCreatedAt().toInstant());
    }
    public Optional<Route> route(UUID project, UUID asset) {
        return dsl.selectFrom(ASSET_STORAGE_ROUTE).where(ASSET_STORAGE_ROUTE.PROJECT_ID.eq(project))
                .and(ASSET_STORAGE_ROUTE.ASSET_ID.eq(asset)).fetchOptional(r -> new Route(r.getProfileId(), r.getMediaKind(),
                        r.getMetadataJson() == null ? null : r.getMetadataJson().data(), r.getReady()));
    }
    public Route pinLocal(UUID project, UUID asset, String kind) {
        dsl.insertInto(ASSET_STORAGE_ROUTE).set(ASSET_STORAGE_ROUTE.PROJECT_ID, project)
                .set(ASSET_STORAGE_ROUTE.ASSET_ID, asset).set(ASSET_STORAGE_ROUTE.MEDIA_KIND, kind)
                .onConflict(ASSET_STORAGE_ROUTE.PROJECT_ID, ASSET_STORAGE_ROUTE.ASSET_ID).doNothing().execute();
        return route(project, asset).orElseThrow();
    }
    public Route pin(UUID project, UUID asset, String kind) {
        // One INSERT SELECT atomically snapshots the selected destination before any file/network work.
        dsl.insertInto(ASSET_STORAGE_ROUTE, ASSET_STORAGE_ROUTE.PROJECT_ID, ASSET_STORAGE_ROUTE.ASSET_ID,
                ASSET_STORAGE_ROUTE.MEDIA_KIND, ASSET_STORAGE_ROUTE.PROFILE_ID)
                .select(dsl.select(org.jooq.impl.DSL.val(project), org.jooq.impl.DSL.val(asset),
                        org.jooq.impl.DSL.val(kind), STORAGE_SETTINGS.ACTIVE_PROFILE_ID).from(STORAGE_SETTINGS)
                        .where(STORAGE_SETTINGS.SINGLETON.isTrue()))
                .onConflict(ASSET_STORAGE_ROUTE.PROJECT_ID, ASSET_STORAGE_ROUTE.ASSET_ID).doNothing().execute();
        Route route = route(project, asset).orElseThrow();
        if (!route.mediaKind().equals(kind)) throw new IllegalStateException("Archive kind differs from pinned route");
        return route;
    }
    public void checkpoint(UUID project, UUID asset, String metadata) {
        if (dsl.update(ASSET_STORAGE_ROUTE).set(ASSET_STORAGE_ROUTE.METADATA_JSON, JSONB.valueOf(metadata))
                .where(ASSET_STORAGE_ROUTE.PROJECT_ID.eq(project)).and(ASSET_STORAGE_ROUTE.ASSET_ID.eq(asset))
                .and(ASSET_STORAGE_ROUTE.METADATA_JSON.isNull()).execute() != 1)
            throw new IllegalStateException("Archive metadata already checkpointed");
    }
    public void ready(UUID project, UUID asset) {
        if (dsl.update(ASSET_STORAGE_ROUTE).set(ASSET_STORAGE_ROUTE.READY, true)
                .where(ASSET_STORAGE_ROUTE.PROJECT_ID.eq(project)).and(ASSET_STORAGE_ROUTE.ASSET_ID.eq(asset)).execute() != 1)
            throw new IllegalStateException("Archive route missing");
    }
}
