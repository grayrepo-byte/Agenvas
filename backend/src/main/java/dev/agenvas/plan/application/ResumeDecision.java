package dev.agenvas.plan.application;

/** 用户对执行计划的审批决定；写入续跑回合输入并由恢复上下文读取。 */
public enum ResumeDecision {
    /** 用户批准计划，据此创建媒体任务。 */
    APPROVED,
    /** 用户拒绝计划，不创建媒体任务。 */
    REJECTED
}
