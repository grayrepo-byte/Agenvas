package dev.agenvas.task.domain;

/** `task.origin` 列取值；与 `ck_task_origin_scope` 约束保持一致。 */
public enum TaskOrigin {
    /** Agent 回合创建的持久任务，必须挂在 Run 上。 */
    AGENT,
    /** 用户在画布直接发起、没有 Run 与审批计划的媒体任务。 */
    USER_DIRECT,
    /** 项目顺序导出创建的媒体任务。 */
    PROJECT_EXPORT
}
