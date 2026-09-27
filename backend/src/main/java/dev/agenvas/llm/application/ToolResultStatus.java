package dev.agenvas.llm.application;

/** 工具执行结果 JSON 中 {@code status} 字段的取值。写入方与读取方共用同一来源，避免字符串拼错导致读取端静默不匹配。 */
public enum ToolResultStatus {
    /** 工具已完成业务动作。 */
    SUCCEEDED,
    /** 工具调用被拒绝，未产生业务副作用。 */
    REJECTED
}
