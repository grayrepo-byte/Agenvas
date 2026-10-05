package dev.agenvas.event.domain;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 项目内单条不可变事务事件；seq 由项目计数行产生，可用于一致补发。
 *
 * @param projectId 事件所属项目
 * @param seq 项目内严格递增序号
 * @param eventId 全局唯一事件身份，用于客户端去重
 * @param type 稳定事件类型
 * @param schemaVersion 事件负载协议版本
 * @param aggregateId 发生变化的业务聚合 ID
 * @param aggregateVersion 聚合自身版本，供前端拒绝旧投影
 * @param payload 由业务服务选择的客户端可见负载
 * @param occurredAt 事务写入事件的时间
 */
public record ProjectEvent(
        UUID projectId,
        long seq,
        UUID eventId,
        String type,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        JsonNode payload,
        Instant occurredAt) {}
