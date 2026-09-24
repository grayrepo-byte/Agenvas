package dev.agenvas.settings.application;

import java.util.Optional;

/** 全局管理员配置由 PostgreSQL 计数行统一分配版本。 */
public interface LlmProviderConfigRepository {

    /** 锁定计数行并原子校验预期配置版本。 */
    int lockVersion();

    /** 读取当前安全展示状态或运行配置。 */
    Optional<LlmProviderConfig> active();

    /** 保留旧加密版本，供已启动任务继续使用固定配置。 */
    Optional<LlmProviderConfig> findVersion(int version);

    /** 调用方持有计数锁时发布且只发布一个新版本。 */
    void publish(int expectedVersion, LlmProviderConfig config);

    /** 仅将仍为活动状态且完成往返验证的版本标记为支持工具调用。 */
    boolean markToolCallingVerified(int version);
}
