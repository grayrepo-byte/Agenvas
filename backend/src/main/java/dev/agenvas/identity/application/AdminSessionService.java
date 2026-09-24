package dev.agenvas.identity.application;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

/** 管理员密码变更后清理旧会话，防止旧凭据建立的会话继续有效。 */
@Service
public class AdminSessionService {

    /** 按认证主体索引查询并删除数据库会话。 */
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    /** 注入数据库会话仓储。
     * @param sessions 支持按管理员登录名查找会话的 Session 仓储
     */
    public AdminSessionService(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    /** 删除该管理员除当前改密请求外的全部会话。 */
    public void invalidateOtherSessions(String loginName, String currentSessionId) {
        sessions.findByPrincipalName(loginName).values().stream()
                .filter(session -> !session.getId().equals(currentSessionId))
                .forEach(session -> sessions.deleteById(session.getId()));
    }
}
