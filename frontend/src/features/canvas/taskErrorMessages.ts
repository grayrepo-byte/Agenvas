import { t } from "../../shared/i18n";
/**
 * 任务失败原因码到共享多语言文案的映射。
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
  get LLM_RETRY_EXHAUSTED() { return t("agent.retry.exhausted"); },
  get LLM_CONNECTION_FAILED() { return t("agent.retry.connection"); },
  get LLM_RATE_LIMITED() { return t("agent.retry.rateLimited"); },
  get LLM_SERVICE_UNAVAILABLE() { return t("agent.retry.unavailable"); },
  get LLM_CALL_TIMEOUT() { return t("agent.retry.timeout"); },
  get EXECUTION_HISTORY_CLEANED() { return t("tasks.errors.executionExpired"); },
  // 措辞保持中性：连接超时也走这个码，那时请求可能根本没发出去，
  // 所以不能说成「已提交但没拿到结果」。
  get [PROVIDER_FAILURE_CODES.CALL_TIMEOUT]() { return t("tasks.errors.callTimeout"); },
  get [PROVIDER_FAILURE_CODES.DOWNLOAD_FAILED]() { return t("tasks.errors.resultDownloadFailed"); },
  get [PROVIDER_FAILURE_CODES.RESPONSE_LOST]() { return t("tasks.errors.responseLost"); },
  get [PROVIDER_FAILURE_CODES.PROTOCOL_INVALID]() { return t("tasks.errors.protocolInvalid"); },
  get [PROVIDER_FAILURE_CODES.RESULT_URL_INVALID]() { return t("tasks.errors.resultUrlRejected"); },
  get [PROVIDER_FAILURE_CODES.RESPONSE_TOO_LARGE]() { return t("tasks.errors.responseTooLarge"); },
  get [PROVIDER_FAILURE_CODES.RESULT_TOO_LARGE]() { return t("tasks.errors.resultTooLarge"); },
  get [PROVIDER_FAILURE_CODES.SUBMISSION_UNKNOWN]() { return t("tasks.errors.submissionVerificationRequired"); },
  get SEED_AUDIO_RESULT_UNKNOWN() { return t("tasks.errors.audioResultUnknown"); },
  get SEED_AUDIO_REJECTED() { return t("tasks.errors.audioRequestRejected"); },
  get AUTODL_CREATE_UNCERTAIN() { return t("tasks.errors.autoDlSubmissionUnknown"); },
  get AUTODL_CREDENTIAL_REJECTED() { return t("tasks.errors.autoDlCredentialsRejected"); },
  get AUTODL_CREATE_REJECTED() { return t("tasks.errors.autoDlRequestRejected"); },
  get AUTODL_INPUT_UNAVAILABLE() { return t("tasks.errors.autoDlInputUnavailable"); },
  get AUTODL_TASK_FAILED() { return t("tasks.errors.autoDlFailed"); },
  get AUTODL_RESULT_REJECTED() { return t("tasks.errors.autoDlResultRejected"); },
  get AUTODL_RESULT_EXPIRED() { return t("tasks.errors.autoDlResultExpired"); },
  get AUTODL_RESULT_MISSING_VIDEO() { return t("tasks.errors.autoDlVideoMissing"); },
  get MEDIA_REFERENCE_PREPARATION_FAILED() { return t("tasks.errors.videoPreparationFailed"); },
  get MEDIA_RELAY_REQUIRED() { return t("media.editor.videoRelayHint"); },
  get MEDIA_RELAY_PUBLIC_ENDPOINT_REQUIRED() { return t("tasks.errors.videoPublicEndpointRequired"); },
  get SEEDANCE_VIDEO_REFERENCE_INVALID() { return t("tasks.errors.videoReferenceInvalid"); },
  get ARK_CREATE_UNCERTAIN() { return t("tasks.errors.submissionUnknown"); },
  get OPENAI_IMAGE_REJECTED() { return t("tasks.errors.imageRequestRejected"); },
  get GOOGLE_IMAGE_REJECTED() { return t("tasks.errors.imageRequestRejected"); },
  get LOCAL_DEPTH_MODEL_UNAVAILABLE() { return t("tasks.errors.depthModelUnavailable"); },
  get LOCAL_IMAGE_PROCESSING_FAILED() { return t("tasks.errors.localProcessingFailed"); },
  get LOCAL_IMAGE_ENCODING_FAILED() { return t("tasks.errors.localEncodingFailed"); },
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
