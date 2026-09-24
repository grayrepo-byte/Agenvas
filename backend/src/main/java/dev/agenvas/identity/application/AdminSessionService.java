package dev.agenvas.identity.application;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

/** Invalidates administrator sessions made stale by a password change. */
@Service
public class AdminSessionService {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public AdminSessionService(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    /** Deletes every session for the principal except the request that changed the password. */
    public void invalidateOtherSessions(String loginName, String currentSessionId) {
        sessions.findByPrincipalName(loginName).values().stream()
                .filter(session -> !session.getId().equals(currentSessionId))
                .forEach(session -> sessions.deleteById(session.getId()));
    }
}
