package dev.agenvas.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.mcp.application.McpTokenRepository.StoredToken;
import dev.agenvas.mcp.domain.McpPrincipal;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpTokenServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC);
    private final UUID user = UUID.randomUUID();
    private final McpTokenRepository repository = mock(McpTokenRepository.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final McpTokenService service = new McpTokenService(repository, identities, CLOCK);
    private final AtomicReference<StoredToken> stored = new AtomicReference<>();

    @BeforeEach
    void persistence() {
        when(identities.isActiveUser(user)).thenReturn(true);
        doAnswer(call -> { stored.set(call.getArgument(0)); return null; }).when(repository).insert(any());
        when(repository.find(any())).thenAnswer(call -> Optional.ofNullable(stored.get())
                .filter(token -> token.id().equals(call.getArgument(0))));
        when(repository.lock(any())).thenAnswer(call -> Optional.ofNullable(stored.get())
                .filter(token -> token.id().equals(call.getArgument(0))));
    }

    @Test
    void createsRandomSecretOnlyOnceAndPersistsOnlyAHash() {
        var first = create(Set.of(McpScope.READ));
        var second = create(Set.of(McpScope.READ));
        assertThat(first.token()).matches("agv_mcp_[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}");
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(second.credential().name()).isEqualTo("External client");
        assertThat(second.credential().expiresAt()).isEqualTo(CLOCK.instant().plus(30, ChronoUnit.DAYS));
        assertThat(stored.get().secretHash()).isEqualTo(Sha256.hex(second.token().split("\\.")[1]));
        assertThat(stored.get().toString()).doesNotContain(second.token());
        assertThat(second.toString()).doesNotContain(second.token()).contains("[REDACTED]");
        when(repository.list(user)).thenReturn(List.of(stored.get()));
        assertThat(service.list(user)).containsExactly(second.credential());
        assertThat(service.list(user).toString()).doesNotContain(stored.get().secretHash());
    }

    @Test
    void requiresReadAndBoundsNameAndExpiryWithoutWritingInvalidRequests() {
        List<McpTokenService.Create> requests = List.of(
                new McpTokenService.Create(" ", Set.of(McpScope.READ), 30),
                new McpTokenService.Create("x".repeat(101), Set.of(McpScope.READ), 30),
                new McpTokenService.Create("name", Set.of(McpScope.READ), 0),
                new McpTokenService.Create("name", Set.of(McpScope.READ), 91),
                new McpTokenService.Create("name", Set.of(McpScope.MEDIA_GENERATE), 30),
                new McpTokenService.Create("name", Set.of(), 30));
        requests.forEach(request -> assertCode("VALIDATION_ERROR", () -> service.create(user, request)));
        verify(repository, never()).insert(any());
    }

    @Test
    void authenticatesCurrentGrantsAndSeparatesContentFromGeneration() {
        var created = create(EnumSet.of(McpScope.READ, McpScope.MEDIA_GENERATE));
        McpPrincipal principal = service.authenticate(created.token());
        assertThat(principal.userId()).isEqualTo(user);
        assertThat(principal.tokenId()).isEqualTo(created.credential().id());
        assertThat(principal.scopes()).containsExactly(McpScope.READ, McpScope.MEDIA_GENERATE);
        assertThat(principal.scopes()).doesNotContain(McpScope.CONTENT_WRITE);
        verify(repository).recordUse(principal.tokenId(), CLOCK.instant());
        assertThatThrownBy(() -> principal.scopes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMalformedForgedExpiredRevokedAndDisabledCredentialsEqually() {
        for (String malformed : List.of("", "secret", "Bearer secret", "agv_mcp_" + UUID.randomUUID() + ".bad")) {
            assertCode("MCP_UNAUTHENTICATED", () -> service.authenticate(malformed));
        }
        assertCode("MCP_UNAUTHENTICATED", () -> service.authenticate(null));
        var created = create(Set.of(McpScope.READ));
        String forged = created.token().substring(0, created.token().length() - 1)
                + (created.token().endsWith("a") ? "b" : "a");
        assertCode("MCP_UNAUTHENTICATED", () -> service.authenticate(forged));
        StoredToken original = stored.get();
        stored.set(copy(original, CLOCK.instant(), null));
        assertCode("MCP_UNAUTHENTICATED", () -> service.authenticate(created.token()));
        stored.set(copy(original, original.expiresAt(), CLOCK.instant()));
        assertCode("MCP_UNAUTHENTICATED", () -> service.authenticate(created.token()));
        stored.set(original);
        when(identities.isActiveUser(user)).thenReturn(false);
        assertCode("MCP_UNAUTHENTICATED", () -> service.authenticate(created.token()));
    }

    @Test
    void revalidatesPersistedScopesAndIdentityForReadsAndWrites() {
        var created = create(Set.of(McpScope.READ));
        McpPrincipal fabricatedScopes = new McpPrincipal(user, created.credential().id(), EnumSet.allOf(McpScope.class));
        assertThat(service.requireValidForRead(fabricatedScopes).scopes()).containsExactly(McpScope.READ);
        assertThat(service.requireValidForWrite(fabricatedScopes).scopes()).containsExactly(McpScope.READ);
        var wrongOwner = new McpPrincipal(UUID.randomUUID(), created.credential().id(), Set.of(McpScope.READ));
        assertCode("MCP_UNAUTHENTICATED", () -> service.requireValidForRead(wrongOwner));
        assertCode("MCP_UNAUTHENTICATED", () -> service.requireValidForWrite(wrongOwner));
        StoredToken original = stored.get();
        stored.set(copy(original, original.expiresAt(), CLOCK.instant()));
        assertCode("MCP_UNAUTHENTICATED", () -> service.requireValidForRead(fabricatedScopes));
        assertCode("MCP_UNAUTHENTICATED", () -> service.requireValidForWrite(fabricatedScopes));
        verify(repository, org.mockito.Mockito.times(3)).lock(created.credential().id());
    }

    @Test
    void revokeUsesCasAndIsIdempotentAfterACommittedRevocation() {
        var created = create(Set.of(McpScope.READ));
        UUID id = created.credential().id();
        assertCode("VERSION_CONFLICT", () -> service.revoke(user, id, 9));
        when(repository.revoke(id, 0, CLOCK.instant())).thenReturn(true);
        var revoked = service.revoke(user, id, 0);
        assertThat(revoked.version()).isEqualTo(1);
        assertThat(revoked.revokedAt()).isEqualTo(CLOCK.instant());
        stored.set(new StoredToken(id, user, revoked.name(), stored.get().secretHash(), revoked.tokenHint(),
                revoked.scopes(), revoked.version(), revoked.createdAt(), revoked.expiresAt(), revoked.revokedAt(), null));
        assertThat(service.revoke(user, id, 0)).isEqualTo(revoked);
        UUID otherUser = UUID.randomUUID();
        when(identities.isActiveUser(otherUser)).thenReturn(true);
        assertCode("RESOURCE_NOT_FOUND", () -> service.revoke(otherUser, id, 1));
        verify(repository).revoke(id, 0, CLOCK.instant());
    }

    private McpTokenService.Created create(Set<McpScope> scopes) {
        return service.create(user, new McpTokenService.Create(" External client ", scopes, null));
    }

    private StoredToken copy(StoredToken token, Instant expiry, Instant revokedAt) {
        return new StoredToken(token.id(), token.userId(), token.name(), token.secretHash(), token.tokenHint(),
                token.scopes(), token.version(), token.createdAt(), expiry, revokedAt, token.lastUsedAt());
    }

    private void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code()).isEqualTo(code);
    }
}
