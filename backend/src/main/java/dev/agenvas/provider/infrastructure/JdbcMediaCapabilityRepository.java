package dev.agenvas.provider.infrastructure;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Database catalog; published version rows are inserted once and never updated. */
@Repository
public class JdbcMediaCapabilityRepository {

    public record Connection(UUID id, String name, String platform, boolean enabled,
            long version, int currentVersion) {}
    public record ConnectionVersion(UUID connectionId, int version, String origin,
            String originSha256, byte[] credentialCiphertext, byte[] credentialNonce,
            Integer credentialKeyVersion, String keyMask) {}
    public record Capability(UUID id, UUID connectionId, String name, boolean enabled,
            long version, int currentVersion) {}
    public record Snapshot(Connection connection, Capability capability,
            ConnectionVersion connectionVersion, String adapterId, String mappingSha256,
            String specJson) {}
    public record CreateKey(String payloadSha256, UUID entityId) {}

    private final JdbcClient jdbc;

    public JdbcMediaCapabilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertConnection(UUID id, String name, String platform, String origin,
            String originSha256, byte[] ciphertext, byte[] nonce, Integer keyVersion,
            String keyMask, Instant now) {
        jdbc.sql("insert into media_provider_connection "
                + "(id,name,platform,enabled,version,current_version,created_at,updated_at) "
                + "values (:id,:name,:platform,true,0,1,:now,:now)")
                .param("id", id).param("name", name).param("platform", platform)
                .param("now", now.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("insert into media_provider_connection_version "
                + "(connection_id,version,origin,origin_sha256,credential_ciphertext,"
                + "credential_nonce,credential_key_version,key_mask,created_at) "
                + "values (:id,1,:origin,:hash,:ciphertext,:nonce,:keyVersion,:keyMask,:now)")
                .param("id", id).param("origin", origin).param("hash", originSha256)
                .param("ciphertext", ciphertext).param("nonce", nonce)
                .param("keyVersion", keyVersion).param("keyMask", keyMask)
                .param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    public Optional<Connection> connection(UUID id) {
        return jdbc.sql("select id,name,platform,enabled,version,current_version "
                + "from media_provider_connection where id=:id")
                .param("id", id).query((rs, row) -> new Connection(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("platform"),
                        rs.getBoolean("enabled"), rs.getLong("version"),
                        rs.getInt("current_version"))).optional();
    }

    public List<Connection> connections() {
        return jdbc.sql("select id,name,platform,enabled,version,current_version "
                + "from media_provider_connection order by created_at,id")
                .query((rs, row) -> new Connection(rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getString("platform"),
                        rs.getBoolean("enabled"), rs.getLong("version"),
                        rs.getInt("current_version"))).list();
    }

    public boolean claimCreateKey(String key, String payloadSha256, UUID connectionId,
            Instant now) {
        return jdbc.sql("insert into media_connection_create_key "
                + "(idempotency_key,payload_sha256,connection_id,created_at) "
                + "values (:key,:hash,:connectionId,:now) on conflict do nothing")
                .param("key", key).param("hash", payloadSha256)
                .param("connectionId", connectionId)
                .param("now", now.atOffset(ZoneOffset.UTC)).update() == 1;
    }

    public Optional<CreateKey> createKey(String key) {
        return jdbc.sql("select payload_sha256,connection_id "
                + "from media_connection_create_key where idempotency_key=:key")
                .param("key", key).query((rs, row) -> new CreateKey(
                        rs.getString("payload_sha256"),
                        rs.getObject("connection_id", UUID.class))).optional();
    }

    public boolean claimCapabilityCreateKey(String key, String payloadSha256,
            UUID capabilityId, Instant now) {
        return jdbc.sql("insert into media_capability_create_key "
                + "(idempotency_key,payload_sha256,capability_id,created_at) "
                + "values (:key,:hash,:capabilityId,:now) on conflict do nothing")
                .param("key", key).param("hash", payloadSha256)
                .param("capabilityId", capabilityId)
                .param("now", now.atOffset(ZoneOffset.UTC)).update() == 1;
    }

    public Optional<CreateKey> capabilityCreateKey(String key) {
        return jdbc.sql("select payload_sha256,capability_id "
                + "from media_capability_create_key where idempotency_key=:key")
                .param("key", key).query((rs, row) -> new CreateKey(
                        rs.getString("payload_sha256"),
                        rs.getObject("capability_id", UUID.class))).optional();
    }

    public boolean updateConnection(UUID id, long expectedVersion, String name,
            boolean enabled, int currentVersion, Instant now) {
        return jdbc.sql("update media_provider_connection set name=:name,enabled=:enabled,"
                + "current_version=:currentVersion,version=version+1,updated_at=:now "
                + "where id=:id and version=:expected")
                .param("name", name).param("enabled", enabled)
                .param("currentVersion", currentVersion)
                .param("now", now.atOffset(ZoneOffset.UTC)).param("id", id)
                .param("expected", expectedVersion).update() == 1;
    }

    public void insertConnectionVersion(UUID id, int version, String origin,
            String originSha256, byte[] ciphertext, byte[] nonce, Integer keyVersion,
            String keyMask, Instant now) {
        jdbc.sql("insert into media_provider_connection_version "
                + "(connection_id,version,origin,origin_sha256,credential_ciphertext,"
                + "credential_nonce,credential_key_version,key_mask,created_at) "
                + "values (:id,:version,:origin,:hash,:ciphertext,:nonce,:keyVersion,:keyMask,:now)")
                .param("id", id).param("version", version).param("origin", origin)
                .param("hash", originSha256).param("ciphertext", ciphertext)
                .param("nonce", nonce).param("keyVersion", keyVersion)
                .param("keyMask", keyMask)
                .param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    public Optional<ConnectionVersion> connectionVersion(UUID id, int version) {
        return jdbc.sql("select connection_id,version,origin,origin_sha256,"
                + "credential_ciphertext,credential_nonce,credential_key_version,key_mask "
                + "from media_provider_connection_version "
                + "where connection_id=:id and version=:version")
                .param("id", id).param("version", version)
                .query((rs, row) -> new ConnectionVersion(
                        rs.getObject("connection_id", UUID.class), rs.getInt("version"),
                        rs.getString("origin"), rs.getString("origin_sha256"),
                        rs.getBytes("credential_ciphertext"), rs.getBytes("credential_nonce"),
                        (Integer) rs.getObject("credential_key_version"),
                        rs.getString("key_mask"))).optional();
    }

    public boolean updateConnectionEnabled(UUID id, long expectedVersion, boolean enabled,
            Instant now) {
        return jdbc.sql("update media_provider_connection set enabled=:enabled,"
                + "version=version+1,updated_at=:now where id=:id and version=:expected")
                .param("enabled", enabled).param("now", now.atOffset(ZoneOffset.UTC)).param("id", id)
                .param("expected", expectedVersion).update() == 1;
    }

    public void insertCapability(UUID id, UUID connectionId, String name, String adapterId,
            String mappingSha256, String specJson, Instant now) {
        jdbc.sql("insert into media_capability "
                + "(id,connection_id,name,enabled,version,current_version,created_at,updated_at) "
                + "values (:id,:connectionId,:name,true,0,1,:now,:now)")
                .param("id", id).param("connectionId", connectionId)
                .param("name", name).param("now", now.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("insert into media_capability_version "
                + "(capability_id,version,adapter_id,mapping_sha256,spec_json,created_at) "
                + "values (:id,1,:adapterId,:hash,cast(:spec as jsonb),:now)")
                .param("id", id).param("adapterId", adapterId)
                .param("hash", mappingSha256).param("spec", specJson)
                .param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    public Optional<Capability> capability(UUID id) {
        return jdbc.sql("select id,connection_id,name,enabled,version,current_version "
                + "from media_capability where id=:id")
                .param("id", id).query((rs, row) -> new Capability(
                        rs.getObject("id", UUID.class),
                        rs.getObject("connection_id", UUID.class), rs.getString("name"),
                        rs.getBoolean("enabled"), rs.getLong("version"),
                        rs.getInt("current_version"))).optional();
    }

    public List<Capability> capabilities(UUID connectionId) {
        return jdbc.sql("select id,connection_id,name,enabled,version,current_version "
                + "from media_capability where connection_id=:connectionId order by created_at,id")
                .param("connectionId", connectionId).query((rs, row) -> new Capability(
                        rs.getObject("id", UUID.class),
                        rs.getObject("connection_id", UUID.class), rs.getString("name"),
                        rs.getBoolean("enabled"), rs.getLong("version"),
                        rs.getInt("current_version"))).list();
    }

    public boolean updateCapability(UUID id, long expectedVersion, String name,
            boolean enabled, int currentVersion, Instant now) {
        return jdbc.sql("update media_capability set name=:name,enabled=:enabled,"
                + "current_version=:currentVersion,version=version+1,updated_at=:now "
                + "where id=:id and version=:expected")
                .param("name", name).param("enabled", enabled)
                .param("currentVersion", currentVersion)
                .param("now", now.atOffset(ZoneOffset.UTC)).param("id", id)
                .param("expected", expectedVersion).update() == 1;
    }

    public void insertCapabilityVersion(UUID id, int version, String adapterId,
            String mappingSha256, String specJson, Instant now) {
        jdbc.sql("insert into media_capability_version "
                + "(capability_id,version,adapter_id,mapping_sha256,spec_json,created_at) "
                + "values (:id,:version,:adapterId,:hash,cast(:spec as jsonb),:now)")
                .param("id", id).param("version", version)
                .param("adapterId", adapterId).param("hash", mappingSha256)
                .param("spec", specJson)
                .param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    public Optional<Snapshot> snapshot(UUID capabilityId) {
        return jdbc.sql("select c.id as connection_id,c.name as connection_name,"
                + "c.platform,"
                + "c.enabled as connection_enabled,c.version as connection_version,"
                + "c.current_version as connection_revision,v.origin,v.origin_sha256,"
                + "v.credential_ciphertext,v.credential_nonce,v.credential_key_version,v.key_mask,"
                + "a.id as capability_id,a.name as capability_name,a.enabled as capability_enabled,"
                + "a.version as capability_version,a.current_version as capability_revision,"
                + "av.adapter_id,av.mapping_sha256,av.spec_json::text as spec_json "
                + "from media_capability a "
                + "join media_provider_connection c on c.id=a.connection_id "
                + "join media_provider_connection_version v "
                + "on v.connection_id=c.id and v.version=c.current_version "
                + "join media_capability_version av "
                + "on av.capability_id=a.id and av.version=a.current_version "
                + "where a.id=:id")
                .param("id", capabilityId).query((rs, row) -> {
                    UUID connectionId = rs.getObject("connection_id", UUID.class);
                    int connectionRevision = rs.getInt("connection_revision");
                    Connection connection = new Connection(connectionId,
                            rs.getString("connection_name"), rs.getString("platform"),
                            rs.getBoolean("connection_enabled"),
                            rs.getLong("connection_version"), connectionRevision);
                    Capability capability = new Capability(
                            rs.getObject("capability_id", UUID.class), connectionId,
                            rs.getString("capability_name"), rs.getBoolean("capability_enabled"),
                            rs.getLong("capability_version"), rs.getInt("capability_revision"));
                    ConnectionVersion version = new ConnectionVersion(connectionId,
                            connectionRevision, rs.getString("origin"),
                            rs.getString("origin_sha256"), rs.getBytes("credential_ciphertext"),
                            rs.getBytes("credential_nonce"),
                            (Integer) rs.getObject("credential_key_version"),
                            rs.getString("key_mask"));
                    return new Snapshot(connection, capability, version,
                            rs.getString("adapter_id"), rs.getString("mapping_sha256"),
                            rs.getString("spec_json"));
                }).optional();
    }

    /** Read exactly the immutable versions approved for a task, including old origins. */
    public Optional<Snapshot> snapshotAt(UUID capabilityId, int capabilityVersion,
            UUID connectionId, int connectionVersion) {
        return jdbc.sql("select c.name as connection_name,c.platform,c.enabled as connection_enabled,"
                + "c.version as connection_row_version,v.origin,v.origin_sha256,"
                + "v.credential_ciphertext,v.credential_nonce,v.credential_key_version,v.key_mask,"
                + "a.name as capability_name,a.enabled as capability_enabled,"
                + "a.version as capability_row_version,av.adapter_id,av.mapping_sha256,"
                + "av.spec_json::text as spec_json from media_capability a "
                + "join media_provider_connection c on c.id=a.connection_id "
                + "join media_provider_connection_version v on v.connection_id=c.id "
                + "and v.version=:connectionVersion "
                + "join media_capability_version av on av.capability_id=a.id "
                + "and av.version=:capabilityVersion "
                + "where a.id=:capabilityId and c.id=:connectionId")
                .param("capabilityId", capabilityId)
                .param("capabilityVersion", capabilityVersion)
                .param("connectionId", connectionId)
                .param("connectionVersion", connectionVersion)
                .query((rs, row) -> new Snapshot(
                        new Connection(connectionId, rs.getString("connection_name"),
                                rs.getString("platform"), rs.getBoolean("connection_enabled"),
                                rs.getLong("connection_row_version"), connectionVersion),
                        new Capability(capabilityId, connectionId,
                                rs.getString("capability_name"),
                                rs.getBoolean("capability_enabled"),
                                rs.getLong("capability_row_version"), capabilityVersion),
                        new ConnectionVersion(connectionId, connectionVersion,
                                rs.getString("origin"), rs.getString("origin_sha256"),
                                rs.getBytes("credential_ciphertext"),
                                rs.getBytes("credential_nonce"),
                                (Integer) rs.getObject("credential_key_version"),
                                rs.getString("key_mask")),
                        rs.getString("adapter_id"), rs.getString("mapping_sha256"),
                        rs.getString("spec_json"))).optional();
    }

    public long defaultVersion(String kind) {
        return jdbc.sql("select version from media_default where kind=:kind")
                .param("kind", kind).query(Long.class).single();
    }

    public UUID defaultCapabilityId(String kind) {
        return jdbc.sql("select capability_id from media_default where kind=:kind")
                .param("kind", kind).query(UUID.class).single();
    }

    public boolean updateDefault(String kind, long expectedVersion, UUID capabilityId) {
        return jdbc.sql("update media_default set capability_id=:capabilityId,version=version+1 "
                + "where kind=:kind and version=:expected")
                .param("capabilityId", capabilityId).param("kind", kind)
                .param("expected", expectedVersion).update() == 1;
    }
}
