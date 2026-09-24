package dev.agenvas.identity.application;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/** 数据库会话中的最小管理员主体，只包含服务端认证后可用的用户身份。
 * @param userId 管理员账户 UUID，作为授权服务的所有者作用域
 * @param loginName 登录名称，供 Principal.getName 返回
 */
public record AdminPrincipal(UUID userId, String loginName) implements Principal, Serializable {

    /** 保持会话序列化版本稳定。 */
    @Serial
    private static final long serialVersionUID = 1L;

    /** 向 Spring Security 和 Spring Session 提供稳定的主体名称索引。 */
    @Override
    public String getName() {
        return loginName;
    }
}
