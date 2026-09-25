package dev.agenvas.event.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSE 长连接的有界配置。
 *
 * @param heartbeatInterval 无事件连接的心跳间隔。客户端断开无法立即感知，只能靠下一次心跳写入
 *                          失败来回收，因此该值同时是失效连接的回收延迟上限
 */
@ConfigurationProperties(prefix = "agenvas.sse")
public record SseProperties(Duration heartbeatInterval) {

    /** 缺省 15 秒，并拒绝非正间隔。 */
    public SseProperties {
        heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(15) : heartbeatInterval;
        if (heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("Invalid SSE heartbeat interval configuration");
        }
    }
}
