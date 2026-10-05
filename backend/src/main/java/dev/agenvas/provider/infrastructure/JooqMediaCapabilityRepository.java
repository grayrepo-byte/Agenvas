package dev.agenvas.provider.infrastructure;

import static dev.agenvas.db.Tables.MEDIA_CAPABILITY;
import static dev.agenvas.db.Tables.MEDIA_CAPABILITY_CREATE_KEY;
import static dev.agenvas.db.Tables.MEDIA_CAPABILITY_VERSION;
import static dev.agenvas.db.Tables.MEDIA_CONNECTION_CREATE_KEY;
import static dev.agenvas.db.Tables.MEDIA_DEFAULT;
import static dev.agenvas.db.Tables.MEDIA_FUNCTION_SETTING;
import static dev.agenvas.db.Tables.MEDIA_PROVIDER_CONNECTION;
import static dev.agenvas.db.Tables.MEDIA_PROVIDER_CONNECTION_VERSION;

import dev.agenvas.db.tables.records.MediaCapabilityRecord;
import dev.agenvas.db.tables.records.MediaProviderConnectionRecord;
import dev.agenvas.provider.domain.MediaPlatform;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

/** Database catalog; published version rows are inserted once and never updated. */
@Repository
public class JooqMediaCapabilityRepository {

    public record Connection(UUID id, String name, MediaPlatform platform, boolean enabled,
            long version, int currentVersion) {}
    public record ConnectionVersion(UUID connectionId, int version, String origin,
            String originSha256, byte[] credentialCiphertext, byte[] credentialNonce,
            Integer credentialKeyVersion, String keyMask) {}
    public record Capability(UUID id, UUID connectionId, String name, boolean enabled,
            long version, int currentVersion, boolean deleted) {}
    public record Snapshot(Connection connection, Capability capability,
            ConnectionVersion connectionVersion, String adapterId, String mappingSha256,
            String specJson) {}
    public record CreateKey(String payloadSha256, UUID entityId) {}

    private final DSLContext dsl;

    public JooqMediaCapabilityRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insertConnection(UUID id, String name, MediaPlatform platform, String origin,
            String originSha256, byte[] ciphertext, byte[] nonce, Integer keyVersion,
            String keyMask, Instant now) {
        dsl.insertInto(MEDIA_PROVIDER_CONNECTION)
                .set(MEDIA_PROVIDER_CONNECTION.ID, id)
                .set(MEDIA_PROVIDER_CONNECTION.NAME, name)
                .set(MEDIA_PROVIDER_CONNECTION.PLATFORM, platform.name())
                .set(MEDIA_PROVIDER_CONNECTION.ENABLED, true)
                .set(MEDIA_PROVIDER_CONNECTION.VERSION, 0L)
                .set(MEDIA_PROVIDER_CONNECTION.CURRENT_VERSION, 1)
                .set(MEDIA_PROVIDER_CONNECTION.CREATED_AT, atUtc(now))
                .set(MEDIA_PROVIDER_CONNECTION.UPDATED_AT, atUtc(now))
                .execute();
        dsl.insertInto(MEDIA_PROVIDER_CONNECTION_VERSION)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.CONNECTION_ID, id)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.VERSION, 1)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.ORIGIN, origin)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.ORIGIN_SHA256, originSha256)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.CREDENTIAL_CIPHERTEXT, ciphertext)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.CREDENTIAL_NONCE, nonce)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.CREDENTIAL_KEY_VERSION, keyVersion)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.KEY_MASK, keyMask)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.CREATED_AT, atUtc(now))
                .execute();
    }

    public Optional<Connection> connection(UUID id) {
        return dsl.selectFrom(MEDIA_PROVIDER_CONNECTION)
                .where(MEDIA_PROVIDER_CONNECTION.ID.eq(id))
                .fetchOptional(this::mapConnection);
    }

    public List<Connection> connections() {
        return dsl.selectFrom(MEDIA_PROVIDER_CONNECTION)
                .orderBy(MEDIA_PROVIDER_CONNECTION.CREATED_AT, MEDIA_PROVIDER_CONNECTION.ID)
                .fetch(this::mapConnection);
    }

    public boolean claimCreateKey(String key, String payloadSha256, UUID connectionId,
            Instant now) {
        return dsl.insertInto(MEDIA_CONNECTION_CREATE_KEY)
                .set(MEDIA_CONNECTION_CREATE_KEY.IDEMPOTENCY_KEY, key)
                .set(MEDIA_CONNECTION_CREATE_KEY.PAYLOAD_SHA256, payloadSha256)
                .set(MEDIA_CONNECTION_CREATE_KEY.CONNECTION_ID, connectionId)
                .set(MEDIA_CONNECTION_CREATE_KEY.CREATED_AT, atUtc(now))
                .onDuplicateKeyIgnore()
                .execute() == 1;
    }

    public Optional<CreateKey> createKey(String key) {
        return dsl.select(MEDIA_CONNECTION_CREATE_KEY.PAYLOAD_SHA256,
                        MEDIA_CONNECTION_CREATE_KEY.CONNECTION_ID)
                .from(MEDIA_CONNECTION_CREATE_KEY)
                .where(MEDIA_CONNECTION_CREATE_KEY.IDEMPOTENCY_KEY.eq(key))
                .fetchOptional(row -> new CreateKey(row.value1(), row.value2()));
    }

    public boolean claimCapabilityCreateKey(String key, String payloadSha256,
            UUID capabilityId, Instant now) {
        return dsl.insertInto(MEDIA_CAPABILITY_CREATE_KEY)
                .set(MEDIA_CAPABILITY_CREATE_KEY.IDEMPOTENCY_KEY, key)
                .set(MEDIA_CAPABILITY_CREATE_KEY.PAYLOAD_SHA256, payloadSha256)
                .set(MEDIA_CAPABILITY_CREATE_KEY.CAPABILITY_ID, capabilityId)
                .set(MEDIA_CAPABILITY_CREATE_KEY.CREATED_AT, atUtc(now))
                .onDuplicateKeyIgnore()
                .execute() == 1;
    }

    public Optional<CreateKey> capabilityCreateKey(String key) {
        return dsl.select(MEDIA_CAPABILITY_CREATE_KEY.PAYLOAD_SHA256,
                        MEDIA_CAPABILITY_CREATE_KEY.CAPABILITY_ID)
                .from(MEDIA_CAPABILITY_CREATE_KEY)
                .where(MEDIA_CAPABILITY_CREATE_KEY.IDEMPOTENCY_KEY.eq(key))
                .fetchOptional(row -> new CreateKey(row.value1(), row.value2()));
    }

    public boolean updateConnection(UUID id, long expectedVersion, String name,
            boolean enabled, int currentVersion, Instant now) {
        return dsl.update(MEDIA_PROVIDER_CONNECTION)
                .set(MEDIA_PROVIDER_CONNECTION.NAME, name)
                .set(MEDIA_PROVIDER_CONNECTION.ENABLED, enabled)
                .set(MEDIA_PROVIDER_CONNECTION.CURRENT_VERSION, currentVersion)
                .set(MEDIA_PROVIDER_CONNECTION.VERSION, MEDIA_PROVIDER_CONNECTION.VERSION.plus(1))
                .set(MEDIA_PROVIDER_CONNECTION.UPDATED_AT, atUtc(now))
                .where(MEDIA_PROVIDER_CONNECTION.ID.eq(id))
                .and(MEDIA_PROVIDER_CONNECTION.VERSION.eq(expectedVersion))
                .execute() == 1;
    }

    public void insertConnectionVersion(UUID id, int version, String origin,
            String originSha256, byte[] ciphertext, byte[] nonce, Integer keyVersion,
            String keyMask, Instant now) {
        dsl.insertInto(MEDIA_PROVIDER_CONNECTION_VERSION)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.CONNECTION_ID, id)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.VERSION, version)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.ORIGIN, origin)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.ORIGIN_SHA256, originSha256)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.CREDENTIAL_CIPHERTEXT, ciphertext)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.CREDENTIAL_NONCE, nonce)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.CREDENTIAL_KEY_VERSION, keyVersion)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.KEY_MASK, keyMask)
                .set(MEDIA_PROVIDER_CONNECTION_VERSION.CREATED_AT, atUtc(now))
                .execute();
    }

    public Optional<ConnectionVersion> connectionVersion(UUID id, int version) {
        return dsl.selectFrom(MEDIA_PROVIDER_CONNECTION_VERSION)
                .where(MEDIA_PROVIDER_CONNECTION_VERSION.CONNECTION_ID.eq(id))
                .and(MEDIA_PROVIDER_CONNECTION_VERSION.VERSION.eq(version))
                .fetchOptional(row -> new ConnectionVersion(row.getConnectionId(),
                        row.getVersion(), row.getOrigin(), row.getOriginSha256(),
                        row.getCredentialCiphertext(), row.getCredentialNonce(),
                        row.getCredentialKeyVersion(), row.getKeyMask()));
    }

    public boolean updateConnectionEnabled(UUID id, long expectedVersion, boolean enabled,
            Instant now) {
        return dsl.update(MEDIA_PROVIDER_CONNECTION)
                .set(MEDIA_PROVIDER_CONNECTION.ENABLED, enabled)
                .set(MEDIA_PROVIDER_CONNECTION.VERSION, MEDIA_PROVIDER_CONNECTION.VERSION.plus(1))
                .set(MEDIA_PROVIDER_CONNECTION.UPDATED_AT, atUtc(now))
                .where(MEDIA_PROVIDER_CONNECTION.ID.eq(id))
                .and(MEDIA_PROVIDER_CONNECTION.VERSION.eq(expectedVersion))
                .execute() == 1;
    }

    public void insertCapability(UUID id, UUID connectionId, String name, String adapterId,
            String mappingSha256, String specJson, Instant now) {
        dsl.insertInto(MEDIA_CAPABILITY)
                .set(MEDIA_CAPABILITY.ID, id)
                .set(MEDIA_CAPABILITY.CONNECTION_ID, connectionId)
                .set(MEDIA_CAPABILITY.NAME, name)
                .set(MEDIA_CAPABILITY.ENABLED, true)
                .set(MEDIA_CAPABILITY.VERSION, 0L)
                .set(MEDIA_CAPABILITY.CURRENT_VERSION, 1)
                .set(MEDIA_CAPABILITY.CREATED_AT, atUtc(now))
                .set(MEDIA_CAPABILITY.UPDATED_AT, atUtc(now))
                .execute();
        dsl.insertInto(MEDIA_CAPABILITY_VERSION)
                .set(MEDIA_CAPABILITY_VERSION.CAPABILITY_ID, id)
                .set(MEDIA_CAPABILITY_VERSION.VERSION, 1)
                .set(MEDIA_CAPABILITY_VERSION.ADAPTER_ID, adapterId)
                .set(MEDIA_CAPABILITY_VERSION.MAPPING_SHA256, mappingSha256)
                .set(MEDIA_CAPABILITY_VERSION.SPEC_JSON, JSONB.valueOf(specJson))
                .set(MEDIA_CAPABILITY_VERSION.CREATED_AT, atUtc(now))
                .execute();
    }

    public Optional<Capability> capability(UUID id) {
        return dsl.selectFrom(MEDIA_CAPABILITY)
                .where(MEDIA_CAPABILITY.ID.eq(id))
                .fetchOptional(this::mapCapability);
    }

    public List<Capability> capabilities(UUID connectionId) {
        return dsl.selectFrom(MEDIA_CAPABILITY)
                .where(MEDIA_CAPABILITY.CONNECTION_ID.eq(connectionId))
                .and(MEDIA_CAPABILITY.DELETED_AT.isNull())
                .orderBy(MEDIA_CAPABILITY.CREATED_AT, MEDIA_CAPABILITY.ID)
                .fetch(this::mapCapability);
    }

    /** Serialize type changes with default selection before either reads the current version. */
    public void lockCapability(UUID id) {
        dsl.select(MEDIA_CAPABILITY.ID).from(MEDIA_CAPABILITY)
                .where(MEDIA_CAPABILITY.ID.eq(id)).forUpdate().fetch();
    }

    public boolean updateCapability(UUID id, long expectedVersion, String name,
            boolean enabled, int currentVersion, Instant now) {
        return dsl.update(MEDIA_CAPABILITY)
                .set(MEDIA_CAPABILITY.NAME, name)
                .set(MEDIA_CAPABILITY.ENABLED, enabled)
                .set(MEDIA_CAPABILITY.CURRENT_VERSION, currentVersion)
                .set(MEDIA_CAPABILITY.VERSION, MEDIA_CAPABILITY.VERSION.plus(1))
                .set(MEDIA_CAPABILITY.UPDATED_AT, atUtc(now))
                .where(MEDIA_CAPABILITY.ID.eq(id))
                .and(MEDIA_CAPABILITY.VERSION.eq(expectedVersion))
                .and(MEDIA_CAPABILITY.DELETED_AT.isNull())
                .execute() == 1;
    }

    /** Delete from the live catalog while keeping version rows for pinned tasks and drafts. */
    public boolean deleteCapability(UUID id, long expectedVersion, Instant now) {
        return dsl.update(MEDIA_CAPABILITY)
                .set(MEDIA_CAPABILITY.DELETED_AT, atUtc(now))
                .set(MEDIA_CAPABILITY.ENABLED, false)
                .set(MEDIA_CAPABILITY.VERSION, MEDIA_CAPABILITY.VERSION.plus(1))
                .set(MEDIA_CAPABILITY.UPDATED_AT, atUtc(now))
                .where(MEDIA_CAPABILITY.ID.eq(id))
                .and(MEDIA_CAPABILITY.VERSION.eq(expectedVersion))
                .and(MEDIA_CAPABILITY.DELETED_AT.isNull())
                .execute() == 1;
    }

    public void clearSelectionsForCapability(UUID id, Instant now) {
        dsl.update(MEDIA_DEFAULT)
                .set(MEDIA_DEFAULT.CAPABILITY_ID, (UUID) null)
                .set(MEDIA_DEFAULT.VERSION, MEDIA_DEFAULT.VERSION.plus(1))
                .where(MEDIA_DEFAULT.CAPABILITY_ID.eq(id)).execute();
        dsl.update(MEDIA_FUNCTION_SETTING)
                .set(MEDIA_FUNCTION_SETTING.CAPABILITY_ID, (UUID) null)
                .set(MEDIA_FUNCTION_SETTING.VERSION, MEDIA_FUNCTION_SETTING.VERSION.plus(1))
                .set(MEDIA_FUNCTION_SETTING.UPDATED_AT, atUtc(now))
                .where(MEDIA_FUNCTION_SETTING.CAPABILITY_ID.eq(id)).execute();
    }

    public void insertCapabilityVersion(UUID id, int version, String adapterId,
            String mappingSha256, String specJson, Instant now) {
        dsl.insertInto(MEDIA_CAPABILITY_VERSION)
                .set(MEDIA_CAPABILITY_VERSION.CAPABILITY_ID, id)
                .set(MEDIA_CAPABILITY_VERSION.VERSION, version)
                .set(MEDIA_CAPABILITY_VERSION.ADAPTER_ID, adapterId)
                .set(MEDIA_CAPABILITY_VERSION.MAPPING_SHA256, mappingSha256)
                .set(MEDIA_CAPABILITY_VERSION.SPEC_JSON, JSONB.valueOf(specJson))
                .set(MEDIA_CAPABILITY_VERSION.CREATED_AT, atUtc(now))
                .execute();
    }

    /** 读取能力当前发布的连接、凭据与协议快照；连接和能力的行版本一并返回给调用方校验。 */
    public Optional<Snapshot> snapshot(UUID capabilityId) {
        var capabilityTable = MEDIA_CAPABILITY.as("a");
        var connectionTable = MEDIA_PROVIDER_CONNECTION.as("c");
        var connectionVersionTable = MEDIA_PROVIDER_CONNECTION_VERSION.as("v");
        var capabilityVersionTable = MEDIA_CAPABILITY_VERSION.as("av");
        return dsl.select(connectionTable.ID, connectionTable.NAME, connectionTable.PLATFORM,
                        connectionTable.ENABLED, connectionTable.VERSION,
                        connectionTable.CURRENT_VERSION, connectionVersionTable.ORIGIN,
                        connectionVersionTable.ORIGIN_SHA256,
                        connectionVersionTable.CREDENTIAL_CIPHERTEXT,
                        connectionVersionTable.CREDENTIAL_NONCE,
                        connectionVersionTable.CREDENTIAL_KEY_VERSION,
                        connectionVersionTable.KEY_MASK, capabilityTable.ID, capabilityTable.NAME,
                        capabilityTable.ENABLED, capabilityTable.VERSION,
                        capabilityTable.CURRENT_VERSION, capabilityVersionTable.ADAPTER_ID,
                        capabilityVersionTable.MAPPING_SHA256, capabilityVersionTable.SPEC_JSON,
                        capabilityTable.DELETED_AT)
                .from(capabilityTable)
                .join(connectionTable).on(connectionTable.ID.eq(capabilityTable.CONNECTION_ID))
                .join(connectionVersionTable).on(connectionVersionTable.CONNECTION_ID
                        .eq(connectionTable.ID)
                        .and(connectionVersionTable.VERSION.eq(connectionTable.CURRENT_VERSION)))
                .join(capabilityVersionTable).on(capabilityVersionTable.CAPABILITY_ID
                        .eq(capabilityTable.ID)
                        .and(capabilityVersionTable.VERSION.eq(capabilityTable.CURRENT_VERSION)))
                .where(capabilityTable.ID.eq(capabilityId))
                .fetchOptional(row -> {
                    UUID connectionId = row.value1();
                    int connectionVersion = row.value6();
                    Connection connection = new Connection(connectionId, row.value2(),
                            MediaPlatform.valueOf(row.value3()),
                            row.value4(), row.value5(), connectionVersion);
                    Capability capability = new Capability(row.value13(), connectionId,
                            row.value14(), row.value15(), row.value16(), row.value17(), row.value21() != null);
                    ConnectionVersion version = new ConnectionVersion(connectionId,
                            connectionVersion, row.value7(), row.value8(), row.value9(),
                            row.value10(), row.value11(), row.value12());
                    return new Snapshot(connection, capability, version, row.value18(),
                            row.value19(), row.value20().data());
                });
    }

    /** Read exactly the immutable versions approved for a task, including old origins. */
    public Optional<Snapshot> snapshotAt(UUID capabilityId, int capabilityVersion,
            UUID connectionId, int connectionVersion) {
        var capabilityTable = MEDIA_CAPABILITY.as("a");
        var connectionTable = MEDIA_PROVIDER_CONNECTION.as("c");
        var connectionVersionTable = MEDIA_PROVIDER_CONNECTION_VERSION.as("v");
        var capabilityVersionTable = MEDIA_CAPABILITY_VERSION.as("av");
        return dsl.select(connectionTable.NAME, connectionTable.PLATFORM,
                        connectionTable.ENABLED, connectionTable.VERSION,
                        connectionVersionTable.ORIGIN, connectionVersionTable.ORIGIN_SHA256,
                        connectionVersionTable.CREDENTIAL_CIPHERTEXT,
                        connectionVersionTable.CREDENTIAL_NONCE,
                        connectionVersionTable.CREDENTIAL_KEY_VERSION,
                        connectionVersionTable.KEY_MASK, capabilityTable.NAME,
                        capabilityTable.ENABLED, capabilityTable.VERSION,
                        capabilityVersionTable.ADAPTER_ID, capabilityVersionTable.MAPPING_SHA256,
                        capabilityVersionTable.SPEC_JSON, capabilityTable.DELETED_AT)
                .from(capabilityTable)
                .join(connectionTable).on(connectionTable.ID.eq(capabilityTable.CONNECTION_ID))
                .join(connectionVersionTable).on(connectionVersionTable.CONNECTION_ID
                        .eq(connectionTable.ID)
                        .and(connectionVersionTable.VERSION.eq(connectionVersion)))
                .join(capabilityVersionTable).on(capabilityVersionTable.CAPABILITY_ID
                        .eq(capabilityTable.ID)
                        .and(capabilityVersionTable.VERSION.eq(capabilityVersion)))
                .where(capabilityTable.ID.eq(capabilityId))
                .and(connectionTable.ID.eq(connectionId))
                .fetchOptional(row -> new Snapshot(
                        new Connection(connectionId, row.value1(),
                                MediaPlatform.valueOf(row.value2()), row.value3(),
                                row.value4(), connectionVersion),
                        new Capability(capabilityId, connectionId, row.value11(), row.value12(),
                                row.value13(), capabilityVersion, row.value17() != null),
                        new ConnectionVersion(connectionId, connectionVersion, row.value5(),
                                row.value6(), row.value7(), row.value8(), row.value9(),
                                row.value10()),
                        row.value14(), row.value15(), row.value16().data()));
    }

    public long defaultVersion(String kind) {
        return dsl.select(MEDIA_DEFAULT.VERSION)
                .from(MEDIA_DEFAULT)
                .where(MEDIA_DEFAULT.KIND.eq(kind))
                .fetchSingle(MEDIA_DEFAULT.VERSION);
    }

    public UUID defaultCapabilityId(String kind) {
        return dsl.select(MEDIA_DEFAULT.CAPABILITY_ID)
                .from(MEDIA_DEFAULT)
                .where(MEDIA_DEFAULT.KIND.eq(kind))
                .fetchSingle(MEDIA_DEFAULT.CAPABILITY_ID);
    }

    public boolean updateDefault(String kind, long expectedVersion, UUID capabilityId) {
        return dsl.update(MEDIA_DEFAULT)
                .set(MEDIA_DEFAULT.CAPABILITY_ID, capabilityId)
                .set(MEDIA_DEFAULT.VERSION, MEDIA_DEFAULT.VERSION.plus(1))
                .where(MEDIA_DEFAULT.KIND.eq(kind))
                .and(MEDIA_DEFAULT.VERSION.eq(expectedVersion))
                .execute() == 1;
    }

    /** Clearing only this capability preserves another administrator's explicit choice. */
    public void clearDefaultForCapability(String kind, UUID capabilityId) {
        dsl.update(MEDIA_DEFAULT)
                .set(MEDIA_DEFAULT.CAPABILITY_ID, (UUID) null)
                .set(MEDIA_DEFAULT.VERSION, MEDIA_DEFAULT.VERSION.plus(1))
                .where(MEDIA_DEFAULT.KIND.eq(kind))
                .and(MEDIA_DEFAULT.CAPABILITY_ID.eq(capabilityId))
                .execute();
    }

    private Connection mapConnection(MediaProviderConnectionRecord row) {
        return new Connection(row.getId(), row.getName(), MediaPlatform.valueOf(row.getPlatform()), row.getEnabled(),
                row.getVersion(), row.getCurrentVersion());
    }

    private Capability mapCapability(MediaCapabilityRecord row) {
        return new Capability(row.getId(), row.getConnectionId(), row.getName(),
                row.getEnabled(), row.getVersion(), row.getCurrentVersion(), row.getDeletedAt() != null);
    }

    /** 将业务时间转换为 PostgreSQL 使用的 UTC 偏移时间。 */
    private static OffsetDateTime atUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
