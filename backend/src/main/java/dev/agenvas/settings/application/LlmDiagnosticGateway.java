package dev.agenvas.settings.application;

import dev.agenvas.llm.application.ChatGateway;

/** 仅使用管理员选定的配置版本执行合成能力探测。 */
public interface LlmDiagnosticGateway {

    /** 调用方不得将用户编写的提示词或业务工具传入诊断探测。 */
    ChatGateway open(LlmProviderConfig config);
}
