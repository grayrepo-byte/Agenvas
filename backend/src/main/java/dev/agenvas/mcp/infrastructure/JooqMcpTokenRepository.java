package dev.agenvas.mcp.infrastructure;

import static dev.agenvas.db.Tables.MCP_ACCESS_TOKEN;

import dev.agenvas.db.tables.records.McpAccessTokenRecord;
import dev.agenvas.mcp.application.McpTokenRepository;
import dev.agenvas.mcp.domain.McpScope;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** jOOQ credential persistence; row locks arbitrate authorization against concurrent revocation. */
@Repository
public class JooqMcpTokenRepository implements McpTokenRepository {
    private static final java.time.Duration USE_METADATA_INTERVAL = java.time.Duration.ofMinutes(1);
    private final DSLContext dsl;
    private final ObjectMapper mapper;

    public JooqMcpTokenRepository(DSLContext dsl, ObjectMapper mapper) {
        this.dsl = dsl;
        this.mapper = mapper;
    }

    @Override
    public List<StoredToken> list(UUID userId) {
        return dsl.selectFrom(MCP_ACCESS_TOKEN)
                .where(MCP_ACCESS_TOKEN.USER_ID.eq(userId))
                .orderBy(MCP_ACCESS_TOKEN.CREATED_AT.desc(), MCP_ACCESS_TOKEN.ID.desc())
                .fetch(this::map);
    }

    @Override
    public Optional<StoredToken> find(UUID tokenId) {
        return dsl.selectFrom(MCP_ACCESS_TOKEN).where(MCP_ACCESS_TOKEN.ID.eq(tokenId))
                .fetchOptional().map(this::map);
    }

    @Override
    public Optional<StoredToken> lock(UUID tokenId) {
        return dsl.selectFrom(MCP_ACCESS_TOKEN).where(MCP_ACCESS_TOKEN.ID.eq(tokenId))
                .forUpdate().fetchOptional().map(this::map);
    }

    @Override
    public void insert(StoredToken token) {
        dsl.insertInto(MCP_ACCESS_TOKEN)
                .set(MCP_ACCESS_TOKEN.ID, token.id())
                .set(MCP_ACCESS_TOKEN.USER_ID, token.userId())
                .set(MCP_ACCESS_TOKEN.NAME, token.name())
                .set(MCP_ACCESS_TOKEN.SECRET_HASH, token.secretHash())
                .set(MCP_ACCESS_TOKEN.TOKEN_HINT, token.tokenHint())
                .set(MCP_ACCESS_TOKEN.SCOPES, JSONB.valueOf(mapper.writeValueAsString(token.scopes())))
                .set(MCP_ACCESS_TOKEN.VERSION, token.version())
                .set(MCP_ACCESS_TOKEN.CREATED_AT, offset(token.createdAt()))
                .set(MCP_ACCESS_TOKEN.EXPIRES_AT, offset(token.expiresAt()))
                .execute();
    }

    @Override
    public boolean revoke(UUID tokenId, long expectedVersion, Instant revokedAt) {
        return dsl.update(MCP_ACCESS_TOKEN)
                .set(MCP_ACCESS_TOKEN.REVOKED_AT, offset(revokedAt))
                .set(MCP_ACCESS_TOKEN.VERSION, MCP_ACCESS_TOKEN.VERSION.plus(1))
                .where(MCP_ACCESS_TOKEN.ID.eq(tokenId))
                .and(MCP_ACCESS_TOKEN.VERSION.eq(expectedVersion))
                .and(MCP_ACCESS_TOKEN.REVOKED_AT.isNull()).execute() == 1;
    }

    @Override
    public void recordUse(UUID tokenId, Instant usedAt) {
        // Throttle metadata writes. This projection must not change the credential CAS version.
        dsl.update(MCP_ACCESS_TOKEN).set(MCP_ACCESS_TOKEN.LAST_USED_AT, offset(usedAt))
                .where(MCP_ACCESS_TOKEN.ID.eq(tokenId))
                .and(MCP_ACCESS_TOKEN.REVOKED_AT.isNull())
                .and(MCP_ACCESS_TOKEN.EXPIRES_AT.gt(offset(usedAt)))
                .and(MCP_ACCESS_TOKEN.LAST_USED_AT.isNull()
                        .or(MCP_ACCESS_TOKEN.LAST_USED_AT.lt(offset(usedAt.minus(USE_METADATA_INTERVAL)))))
                .execute();
    }

    private StoredToken map(McpAccessTokenRecord row) {
        McpScope[] scopes = mapper.readValue(row.getScopes().data(), McpScope[].class);
        return new StoredToken(row.getId(), row.getUserId(), row.getName(), row.getSecretHash(),
                row.getTokenHint(), scopes.length == 0 ? EnumSet.noneOf(McpScope.class)
                        : EnumSet.copyOf(Arrays.asList(scopes)),
                row.getVersion(), row.getCreatedAt().toInstant(), row.getExpiresAt().toInstant(),
                instant(row.getRevokedAt()), instant(row.getLastUsedAt()));
    }

    private OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
