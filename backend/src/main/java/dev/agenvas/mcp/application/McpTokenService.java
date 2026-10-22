package dev.agenvas.mcp.application;

import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.mcp.application.McpTokenRepository.StoredToken;
import dev.agenvas.mcp.domain.McpPrincipal;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates opaque user credentials and revalidates grants independently of MCP transport state. */
@Service
public class McpTokenService {
    private static final String PREFIX = "agv_mcp_";
    private static final int SECRET_BYTES = 32;
    private static final int DEFAULT_EXPIRY_DAYS = 30;
    private static final int MAX_EXPIRY_DAYS = 90;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int UUID_LENGTH = 36;
    private static final int ENCODED_SECRET_LENGTH = 43;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final McpTokenRepository tokens;
    private final Clock clock;
    private final IdentityService identities;

    public McpTokenService(McpTokenRepository tokens, IdentityService identities, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
        this.identities = identities;
    }

    /** Returns only safe credential metadata belonging to the logged-in user. */
    @Transactional(readOnly = true)
    public List<Summary> list(UUID userId) {
        requireActiveUser(userId);
        return tokens.list(userId).stream().map(this::summary).toList();
    }

    /** The returned secret is never persisted and cannot be retrieved by listing credentials. */
    @Transactional
    public Created create(UUID userId, Create request) {
        requireActiveUser(userId);
        String name = request.name() == null ? "" : request.name().trim();
        Integer requestedDays = request.expiresInDays();
        int days = requestedDays == null ? DEFAULT_EXPIRY_DAYS : requestedDays;
        Set<McpScope> scopes = request.scopes();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || days < 1 || days > MAX_EXPIRY_DAYS
                || scopes == null || scopes.isEmpty() || scopes.stream().anyMatch(java.util.Objects::isNull)
                || !scopes.contains(McpScope.READ)) {
            throw invalidRequest();
        }
        UUID id = UUID.randomUUID();
        byte[] randomBytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(randomBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String rawToken = PREFIX + id + "." + secret;
        Instant now = clock.instant();
        StoredToken token = new StoredToken(id, userId, name, Sha256.hex(secret),
                PREFIX + id.toString().substring(0, 8) + "…", scopes, 0,
                now, now.plus(days, ChronoUnit.DAYS), null, null);
        tokens.insert(token);
        return new Created(summary(token), rawToken);
    }

    /** Repeating a successful revoke is safe even when the client retained the previous version. */
    @Transactional
    public Summary revoke(UUID userId, UUID tokenId, long expectedVersion) {
        requireActiveUser(userId);
        StoredToken token = tokens.lock(tokenId)
                .filter(found -> found.userId().equals(userId)).orElseThrow(this::notFound);
        if (token.revokedAt() != null) return summary(token);
        if (expectedVersion < 0 || token.version() != expectedVersion) throw versionConflict();
        Instant now = clock.instant();
        if (!tokens.revoke(tokenId, expectedVersion, now)) throw versionConflict();
        return summary(new StoredToken(token.id(), token.userId(), token.name(), token.secretHash(),
                token.tokenHint(), token.scopes(), token.version() + 1,
                token.createdAt(), token.expiresAt(), now, token.lastUsedAt()));
    }

    /** Invalid format, unknown ID, wrong secret and unavailable credentials share one response. */
    @Transactional
    public McpPrincipal authenticate(String rawToken) {
        UUID id = tokenId(rawToken);
        StoredToken token = tokens.find(id).orElseThrow(McpTokenService::unauthenticated);
        String secret = rawToken.substring(PREFIX.length() + UUID_LENGTH + 1);
        if (!MessageDigest.isEqual(Sha256.hex(secret).getBytes(StandardCharsets.US_ASCII),
                token.secretHash().getBytes(StandardCharsets.US_ASCII))) {
            throw unauthenticated();
        }
        McpPrincipal principal = requireValid(token, token.userId());
        tokens.recordUse(id, clock.instant());
        return principal;
    }

    /** Re-checks current state before discovery, reads and replay; an old session cannot extend grants. */
    @Transactional(readOnly = true)
    public McpPrincipal requireValidForRead(McpPrincipal principal) {
        if (principal == null) throw unauthenticated();
        return requireValid(tokens.find(principal.tokenId()).orElseThrow(McpTokenService::unauthenticated),
                principal.userId());
    }

    /**
     * Lock order is project → credential → target/operation. Keeping this lock until the command
     * commits makes a completed revoke a barrier against every subsequent write authorization.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public McpPrincipal requireValidForWrite(McpPrincipal principal) {
        if (principal == null) throw unauthenticated();
        return requireValid(tokens.lock(principal.tokenId()).orElseThrow(McpTokenService::unauthenticated),
                principal.userId());
    }

    private McpPrincipal requireValid(StoredToken token, UUID userId) {
        if (!token.userId().equals(userId) || token.revokedAt() != null
                || !token.expiresAt().isAfter(clock.instant())
                || !token.scopes().contains(McpScope.READ) || !identities.isActiveUser(userId)) {
            throw unauthenticated();
        }
        return new McpPrincipal(token.userId(), token.id(), token.scopes());
    }

    private UUID tokenId(String rawToken) {
        int expectedLength = PREFIX.length() + UUID_LENGTH + 1 + ENCODED_SECRET_LENGTH;
        if (rawToken == null || rawToken.length() != expectedLength || !rawToken.startsWith(PREFIX)
                || rawToken.charAt(PREFIX.length() + UUID_LENGTH) != '.'
                || !rawToken.substring(PREFIX.length() + UUID_LENGTH + 1).matches("[A-Za-z0-9_-]{43}")) {
            throw unauthenticated();
        }
        String id = rawToken.substring(PREFIX.length(), PREFIX.length() + UUID_LENGTH);
        try {
            UUID parsed = UUID.fromString(id);
            if (!parsed.toString().equals(id)) throw unauthenticated();
            return parsed;
        } catch (IllegalArgumentException invalidId) {
            throw unauthenticated();
        }
    }

    private void requireActiveUser(UUID userId) {
        if (!identities.isActiveUser(userId)) throw unauthenticated();
    }

    private Summary summary(StoredToken token) {
        return new Summary(token.id(), token.name(), token.tokenHint(), token.scopes(), token.version(),
                token.createdAt(), token.expiresAt(), token.revokedAt(), token.lastUsedAt());
    }

    public static ApiProblemException unauthenticated() {
        return new ApiProblemException(HttpStatus.UNAUTHORIZED, "MCP_UNAUTHENTICATED",
                ApiMessage.of("mcp.token.unauthenticated-title"),
                ApiMessage.of("mcp.token.unauthenticated"), false);
    }

    private ApiProblemException invalidRequest() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                ApiMessage.of("mcp.token.invalid-title"), ApiMessage.of("mcp.token.invalid"), false);
    }

    private ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                ApiMessage.of("mcp.token.not-found-title"), ApiMessage.of("mcp.token.not-found"), false);
    }

    private ApiProblemException versionConflict() {
        return new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                ApiMessage.of("mcp.token.version-conflict-title"),
                ApiMessage.of("mcp.token.version-conflict"), false);
    }

    /** Safe metadata; IDs and hints identify a credential without exposing its secret or hash. */
    public record Summary(UUID id, String name, String tokenHint, Set<McpScope> scopes, long version,
            Instant createdAt, Instant expiresAt, Instant revokedAt, Instant lastUsedAt) {
        public Summary {
            scopes = Collections.unmodifiableSet(EnumSet.copyOf(scopes));
        }
    }

    public record Create(String name, Set<McpScope> scopes, Integer expiresInDays) {}

    /** This one-time response must never be logged or cached. */
    public record Created(Summary credential, String token) {
        @Override
        public String toString() {
            return "Created[credential=" + credential + ", token=[REDACTED]]";
        }
    }
}
