package dev.agenvas.identity.application;

import java.time.Instant;
import java.util.UUID;

/** 管理员账户的应用层只读视图。
 * @param id 管理员 UUID
 * @param loginName 登录名
 * @param passwordHash 服务端保存的密码哈希，不得返回 API 或日志
 * @param status 账户是否允许登录
 * @param createdAt 账户创建时间
 * @param passwordChangedAt 最近一次密码更新的时间
 * @param version 并发更新版本
 */
public record AdminAccount(
        UUID id,
        String loginName,
        String passwordHash,
        Status status,
        Instant createdAt,
        Instant passwordChangedAt,
        long version) {

    /** 持久化的管理员账户状态。 */
    public enum Status {
        /** 允许通过认证流程登录。 */
        ACTIVE,
        /** 禁止登录，但保留账户记录。 */
        DISABLED
    }
}
