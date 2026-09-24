package dev.agenvas.event.api;

import dev.agenvas.event.application.ProjectEventHub;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 项目事件 SSE 入口；游标采用项目事件序号并按所有者作用域补发。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/events")
public class ProjectEventController {

    /** 连接内存通知优化与数据库可靠补发的事件服务。 */
    private final ProjectEventHub hub;

    /** 注入项目事件订阅服务。
     * @param hub 校验项目访问并建立 SSE 订阅的事件中心
     */
    public ProjectEventController(ProjectEventHub hub) {
        this.hub = hub;
    }

    /** 从互斥游标之后推送已提交项目事件。
     * @param principal 当前认证用户
     * @param projectId 项目事件作用域
     * @param after 查询参数中的补发起始序号
     * @param lastEventId 浏览器自动重连时发送的 SSE 游标，优先于 after
     * @return 项目事件流；客户端按事件序号去重
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @RequestParam(required = false) Long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        long cursor = lastEventId == null || lastEventId.isBlank()
                ? after == null ? 0 : after
                : parseLastEventId(lastEventId);
        return hub.subscribe(principal.userId(), projectId, cursor);
    }

    /** 校验浏览器游标只包含可解析的事件序号。
     * @param value Last-Event-ID 请求头内容
     * @return 作为独占起点使用的项目事件序号
     * @throws ApiProblemException 请求头不是合法整数时返回 400
     */
    private long parseLastEventId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "事件游标无效",
                    "Last-Event-ID 必须是项目事件序号。",
                    false);
        }
    }
}
