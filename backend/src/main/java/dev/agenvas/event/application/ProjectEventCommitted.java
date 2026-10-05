package dev.agenvas.event.application;

import java.util.UUID;

/** 事务提交后提醒订阅端尽快读取持久化事件；通知自身不承载业务负载。
 * @param projectId 有新事件提交的项目 UUID
 */
public record ProjectEventCommitted(UUID projectId) {}
