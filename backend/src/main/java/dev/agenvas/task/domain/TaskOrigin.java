package dev.agenvas.task.domain;

/** `task.origin` 列取值；与 `ck_task_origin_scope` 约束保持一致。 */
public enum TaskOrigin {
    /** Agent 回合创建的持久任务，必须挂在 Run 上。 */
    AGENT,
    /** 用户在画布直接发起、没有 Run 的文字或媒体任务。 */
    USER_DIRECT
}
