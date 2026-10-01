/**
 * 任务失败原因码到可读中文的映射。
 *
 * 后端把每个失败点写成稳定的原因是刻意的：卡片、Run 对话与调用日志只展示码本身，
 * 不展示响应体、请求地址或凭据。因此这里必须给出能读懂的说法，否则用户只能看到
 * 「结果未知 · PROVIDER_CALL_TIMEOUT」这类机器码，还得自己去翻服务端日志。
 *
 * 未登记的码由调用方回退展示原始码，而不是隐藏——未知码至少还能拿去检索。
 */
import type { Task } from "../../shared/api/client";

/** 与后端 `dev.agenvas.shared.error.ProviderFailureCodes` 一一对应，改一处必须同步另一处。 */
export const PROVIDER_FAILURE_CODES = {
  CALL_TIMEOUT: "PROVIDER_CALL_TIMEOUT",
  DOWNLOAD_FAILED: "PROVIDER_DOWNLOAD_FAILED",
  RESPONSE_LOST: "PROVIDER_RESPONSE_LOST",
  PROTOCOL_INVALID: "PROVIDER_PROTOCOL_INVALID",
  RESULT_URL_INVALID: "PROVIDER_RESULT_URL_INVALID",
  RESPONSE_TOO_LARGE: "PROVIDER_RESPONSE_TOO_LARGE",
  RESULT_TOO_LARGE: "PROVIDER_RESULT_TOO_LARGE",
  SUBMISSION_UNKNOWN: "PROVIDER_SUBMISSION_UNKNOWN",
} as const;

const TASK_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  // 措辞保持中性：连接超时也走这个码，那时请求可能根本没发出去，
  // 所以不能说成「已提交但没拿到结果」。
  [PROVIDER_FAILURE_CODES.CALL_TIMEOUT]: "调用超时，结果未知",
  [PROVIDER_FAILURE_CODES.DOWNLOAD_FAILED]: "结果已生成但下载失败",
  [PROVIDER_FAILURE_CODES.RESPONSE_LOST]: "连接中断，结果未知",
  [PROVIDER_FAILURE_CODES.PROTOCOL_INVALID]: "返回内容不符合固定协议",
  [PROVIDER_FAILURE_CODES.RESULT_URL_INVALID]: "结果地址非法，已拒绝下载",
  [PROVIDER_FAILURE_CODES.RESPONSE_TOO_LARGE]: "响应超出大小上限",
  [PROVIDER_FAILURE_CODES.RESULT_TOO_LARGE]: "结果超出大小上限",
  [PROVIDER_FAILURE_CODES.SUBMISSION_UNKNOWN]: "提交结果未确认，需人工核对",
  SEED_AUDIO_RESULT_UNKNOWN: "音频生成结果未确认，需在节点中显式重试",
  SEED_AUDIO_REJECTED: "上游拒绝了音频请求（参数或凭证）",
  AUTODL_CREATE_UNCERTAIN: "AutoDL 提交结果未确认，需在节点中显式重试",
  AUTODL_CREDENTIAL_REJECTED: "AutoDL 拒绝了凭证，请检查 Token 的 ComfyUI 权限",
  AUTODL_CREATE_REJECTED: "AutoDL 拒绝了请求，请检查工作流参数",
  AUTODL_INPUT_UNAVAILABLE: "AutoDL 参考资源或固定配置不可读取",
  AUTODL_TASK_FAILED: "AutoDL 工作流执行失败",
  AUTODL_RESULT_REJECTED: "AutoDL 结果地址或媒体不符合协议，已拒绝下载",
  AUTODL_RESULT_EXPIRED: "AutoDL 结果地址已过期，原任务未返回可用地址",
  AUTODL_RESULT_MISSING_VIDEO: "AutoDL 成功响应缺少唯一的视频结果",
  ARK_CREATE_UNCERTAIN: "提交结果未确认",
  OPENAI_IMAGE_REJECTED: "上游拒绝了请求（参数或凭证）",
  GOOGLE_IMAGE_REJECTED: "上游拒绝了请求（参数或凭证）",
  LOCAL_DEPTH_MODEL_UNAVAILABLE: "本地深度模型未配置或文件不可用",
  LOCAL_IMAGE_PROCESSING_FAILED: "本地图片处理失败",
  LOCAL_IMAGE_ENCODING_FAILED: "本地图片编码失败",
};

/** @returns 可读原因；没有登记时返回 null，由调用方回退展示原始错误码。 */
export function taskErrorMessage(errorCode: Task["errorCode"]): string | null {
  if (!errorCode) return null;
  return TASK_ERROR_MESSAGES[errorCode] ?? null;
}

/**
 * 拼接在状态之后的「 · 原因」片段，供只展示一行摘要的位置复用；没有错误码时返回空串。
 *
 * @returns 形如 {@code " · 调用超时，结果未知"}；未登记的码回退为该码本身
 */
export function taskErrorDetail(errorCode: Task["errorCode"]): string {
  if (!errorCode) return "";
  return ` · ${taskErrorMessage(errorCode) ?? errorCode}`;
}
