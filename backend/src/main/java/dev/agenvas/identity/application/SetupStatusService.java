package dev.agenvas.identity.application;

import org.springframework.stereotype.Service;

@Service
public class SetupStatusService {

    private final AdminAccountRepository adminAccountRepository;

    public SetupStatusService(AdminAccountRepository adminAccountRepository) {
        this.adminAccountRepository = adminAccountRepository;
    }

    public boolean isSetupRequired() {
        return !adminAccountRepository.hasAdminAccount();
    }
}
