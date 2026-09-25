package dev.agenvas.provider.infrastructure;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Database catalog; published version rows are inserted once and never updated. */
@Repository
public class JdbcMediaCapabilityRepository {

    public record Connection(UUID id, String name, boolean enabled, long version,
            int currentVersion) {}
    public record ConnectionVersion(UUID connectionId, int version, String origin,
            String originSha256, byte[] credentialCiphertext, byte[] credentialNonce,
            Integer credentialKeyVersion) {}
    public record Capability(UUID id, UUID connectionId, String name, boolean enabled,
            long version, int currentVersion) {}
    public record Snapshot(Connection connection, Capability capability,
            ConnectionVersion connectionVersion, String adapterId, String mappingSha256) {}

    private final JdbcClient jdbc;

    public JdbcMediaCapabilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertConnection(UUID id, String name, String origin, String originSha256,
            byte[] ciphertext, byte[] nonce, Integer keyVersion, Instant now) {
        jdbc.sql("insert into media_provider_connection "
                + "(id,name,enabled,version,current_version,created_at,updated_at) "
                + "values (:id,:name,true,0,1,:now,:now)")
                .param("id", id).param("name", name).param("now", now.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("insert into media_provider_connection_version "
                + "(connection_id,version,origin,origin_sha256,credential_ciphertext,"
                + "credential_nonce,credential_key_version,created_at) "
                + "values (:id,1,:origin,:hash,:ciphertext,:nonce,:keyVersion,:now)")
                .param("id", id).param("origin", origin).param("hash", originSha256)
                .param("ciphertext", ciphertext).param("nonce", nonce)
                .param("keyVersion", keyVersion).param("now", now.atOffset(ZoneOffset.UTC)).update();
    }

    public Optional<Connection> connection(UUID id) {
        return jdbc.sql("select id,name,enabled,version,current_version "
                + "from media_provider_connection where id=:id")
                .param("id", id).query((rs, row) -> new Connection(
                        rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getBoolean("enabled"), rs.getLong("version"),
                        rs.getInt("current_version"))).optional();
    }

    public Optional<ConnectionVersion> connectionVersion(UUID id, int version) {
        return jdbc.sql("select connection_id,version,origin,origin_sha256,"
                + "credential_ciphertext,credential_nonce,credential_key_version "
                + "from media_provider_connection_version "
                + "where connection_id=:id and version=:version")
                .param("id", id).param("version", version)
                .query((rs, row) -> new ConnectionVersion(
                        rs.getObject("connection_id", UUID.class), rs.getInt("version"),
                        rs.getString("origin"), rs.getString("origin_sha256"),
                        rs.getBytes("credential_ciphertext"), rs.getBytes("credential_nonce"),
                        (Integer) rs.getObject("credential_key_version"))).optional();
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

    public Optional<Snapshot> snapshot(UUID capabilityId) {
        return jdbc.sql("select c.id as connection_id,c.name as connection_name,"
                + "c.enabled as connection_enabled,c.version as connection_version,"
                + "c.current_version as connection_revision,v.origin,v.origin_sha256,"
                + "v.credential_ciphertext,v.credential_nonce,v.credential_key_version,"
                + "a.id as capability_id,a.name as capability_name,a.enabled as capability_enabled,"
                + "a.version as capability_version,a.current_version as capability_revision,"
                + "av.adapter_id,av.mapping_sha256 from media_capability a "
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
                            rs.getString("connection_name"), rs.getBoolean("connection_enabled"),
                            rs.getLong("connection_version"), connectionRevision);
                    Capability capability = new Capability(
                            rs.getObject("capability_id", UUID.class), connectionId,
                            rs.getString("capability_name"), rs.getBoolean("capability_enabled"),
                            rs.getLong("capability_version"), rs.getInt("capability_revision"));
                    ConnectionVersion version = new ConnectionVersion(connectionId,
                            connectionRevision, rs.getString("origin"),
                            rs.getString("origin_sha256"), rs.getBytes("credential_ciphertext"),
                            rs.getBytes("credential_nonce"),
                            (Integer) rs.getObject("credential_key_version"));
                    return new Snapshot(connection, capability, version,
                            rs.getString("adapter_id"), rs.getString("mapping_sha256"));
                }).optional();
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
