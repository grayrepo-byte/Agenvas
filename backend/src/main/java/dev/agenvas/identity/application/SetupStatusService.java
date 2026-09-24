package dev.agenvas.identity.application;

import org.springframework.stereotype.Service;

/** Reads whether this installation still needs its first administrator. */
@Service
public class SetupStatusService {

    private final AdminAccountRepository adminAccountRepository;

    public SetupStatusService(AdminAccountRepository adminAccountRepository) {
        this.adminAccountRepository = adminAccountRepository;
    }

    /** Returns true only while no active administrator exists. */
    public boolean isSetupRequired() {
        return !adminAccountRepository.hasAdminAccount();
    }
}
