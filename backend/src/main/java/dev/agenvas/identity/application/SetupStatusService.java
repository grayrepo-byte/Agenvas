package dev.agenvas.identity.application;

import org.springframework.stereotype.Service;

/** 查询当前安装是否仍需创建首位管理员。 */
@Service
public class SetupStatusService {

    /** 查询唯一管理员账户是否已经初始化。 */
    private final AdminAccountRepository adminAccountRepository;

    /** 注入管理员账户只读仓储。
     * @param adminAccountRepository 检查管理员记录是否存在
     */
    public SetupStatusService(AdminAccountRepository adminAccountRepository) {
        this.adminAccountRepository = adminAccountRepository;
    }

    /** 仅当安装从未完成初始化时返回 true，不随管理员停用或删除而重开。 */
    public boolean isSetupRequired() {
        return !adminAccountRepository.isSetupCompleted();
    }
}
