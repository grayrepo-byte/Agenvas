package dev.agenvas.shared.error;

/**
 * 媒体调用失败写入 {@code task.error_code} 与 {@code call_log.error_code} 的稳定词汇表。
 *
 * <p>这些码是用户与运维唯一的排查线索：卡片、Run 对话与调用日志都只展示码本身，
 * 不展示响应体、请求地址或凭据。因此每个失败点都必须给出能区分原因的具体码，
 * 不能退化成「提交结果未知」这一种笼统说法，否则只能靠翻服务端日志定位。
 *
 * <p>MVP-SPEC「主要错误码」一节与前端 {@code taskErrorMessages.ts} 必须与本表保持同步。
 */
public final class ProviderFailureCodes {

    /** 本地等待响应超时：已过固定上限仍未拿到结果，外部是否完成未确认。 */
    public static final String CALL_TIMEOUT = "PROVIDER_CALL_TIMEOUT";

    /** 已生成结果取回失败：结果地址不可用或多次重试仍未下载成功。 */
    public static final String DOWNLOAD_FAILED = "PROVIDER_DOWNLOAD_FAILED";

    /** 连接中断或响应读取失败，无法判断外部是否完成。 */
    public static final String RESPONSE_LOST = "PROVIDER_RESPONSE_LOST";

    /** 响应不符合固定协议：结构、编码或媒体格式不成立。 */
    public static final String PROTOCOL_INVALID = "PROVIDER_PROTOCOL_INVALID";

    /** 结果地址不合法，已拒绝下载。 */
    public static final String RESULT_URL_INVALID = "PROVIDER_RESULT_URL_INVALID";

    /** 响应体超出固定上限，已中止读取。 */
    public static final String RESPONSE_TOO_LARGE = "PROVIDER_RESPONSE_TOO_LARGE";

    /** 结果媒体超出固定上限，已拒绝归档。 */
    public static final String RESULT_TOO_LARGE = "PROVIDER_RESULT_TOO_LARGE";

    /** 外部受理状态不确定：只用于恢复扫描兜底无法细分原因的过期提交。 */
    public static final String SUBMISSION_UNKNOWN = "PROVIDER_SUBMISSION_UNKNOWN";

    /** 图片中继上传或签名准备失败；付费生成请求尚未发送。 */
    public static final String MEDIA_RELAY_PREPARATION_FAILED = "MEDIA_RELAY_PREPARATION_FAILED";

    public static final String MINIMAX_CREATE_REJECTED = "MINIMAX_CREATE_REJECTED";
    public static final String MINIMAX_CREATE_UNCERTAIN = "MINIMAX_CREATE_UNCERTAIN";
    public static final String MINIMAX_TASK_FAILED = "MINIMAX_TASK_FAILED";
    public static final String MINIMAX_TASK_EXPIRED = "MINIMAX_TASK_EXPIRED";
    public static final String MINIMAX_RESULT_EXPIRED = "MINIMAX_RESULT_EXPIRED";

    private ProviderFailureCodes() {}
}
