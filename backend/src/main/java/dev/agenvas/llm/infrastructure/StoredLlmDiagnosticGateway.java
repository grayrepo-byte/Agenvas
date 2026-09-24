package dev.agenvas.llm.infrastructure;

import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.settings.application.LlmDiagnosticGateway;
import dev.agenvas.settings.application.LlmProviderConfig;
import org.springframework.stereotype.Component;

/** 使用与正常 Run 相同的固定配置版本和安全传输策略构造诊断模型。 */
@Component
public class StoredLlmDiagnosticGateway implements LlmDiagnosticGateway {

    /** 解密已保存凭据并构造受端点策略限制的模型客户端。 */
    private final StoredChatModelFactory factory;

    /** 注入与正常模型派发共享的客户端工厂。
     * @param factory 固定凭据版本并配置安全传输的模型工厂
     */
    public StoredLlmDiagnosticGateway(StoredChatModelFactory factory) {
        this.factory = factory;
    }

    /** 为显式诊断创建仅绑定当前配置版本的模型访问入口。
     * @param config 管理员当前确认的 LLM 配置版本
     * @return 使用相同端点和凭据保护策略的聊天网关
     */
    @Override
    public ChatGateway open(LlmProviderConfig config) {
        return factory.create(config);
    }
}
