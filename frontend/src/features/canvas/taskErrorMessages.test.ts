import { describe, expect, it } from "vitest";
import { PROVIDER_FAILURE_CODES, providerFailureMessage, taskErrorDetail, taskErrorMessage } from "./taskErrorMessages";
import { getLocale, setLocale, SUPPORTED_LOCALES } from "../../shared/i18n";

describe("taskErrorMessages", () => {
  it("explains RunningHub failures and safe node locations in every locale while retaining unknown submission semantics", () => {
    const previous = getLocale();
    try {
      for (const locale of SUPPORTED_LOCALES) {
        setLocale(locale);
        for (const code of Object.values(PROVIDER_FAILURE_CODES).filter((value) => value.startsWith("RUNNINGHUB_"))) {
          expect(taskErrorMessage(code), `${locale}: ${code}`).toBeTruthy();
          expect(taskErrorMessage(code)).not.toContain(code);
        }
        const diagnostic = { stage: "SUBMIT" as const, category: "PARAMETER" as const, httpStatus: 400, upstreamCode: 301, nodeId: "20", fieldName: "duration" };
        expect(providerFailureMessage(diagnostic)).toContain("20");
        expect(providerFailureMessage(diagnostic)).toContain("duration");
        expect(taskErrorDetail(PROVIDER_FAILURE_CODES.SUBMISSION_UNKNOWN, diagnostic)).toContain(taskErrorMessage(PROVIDER_FAILURE_CODES.SUBMISSION_UNKNOWN));
      }
    } finally { setLocale(previous); }
  });
  it("explains composition validation, overflow and local execution failures in every locale", () => {
    const previous = getLocale();
    try {
      for (const locale of SUPPORTED_LOCALES) {
        setLocale(locale);
        for (const code of ["VIDEO_COMPOSITION_INVALID", "VIDEO_COMPOSITION_AUDIO_OVERFLOW", "LOCAL_VIDEO_COMPOSITION_FAILED",
          "LOCAL_VIDEO_COMPOSITION_TIMEOUT", "LOCAL_VIDEO_COMPOSITION_CANCELED", "AUDIO_TRIM_INVALID",
          "AUDIO_TRIM_OUTPUT_TOO_LARGE", "LOCAL_AUDIO_TRIM_FAILED", "LOCAL_AUDIO_TRIM_TIMEOUT", "LOCAL_AUDIO_TRIM_CANCELED",
          "LLM_INSUFFICIENT_CREDITS", "LLM_MEDIA_INPUT_UNSUPPORTED", "STORYBOARD_VERSION_CONFLICT"]) {
          expect(taskErrorMessage(code), `${locale}: ${code}`).toBeTruthy();
          expect(taskErrorMessage(code)).not.toContain(code);
        }
      }
    } finally { setLocale(previous); }
  });

  it("explains result collection exhaustion and the new-task retry", () => {
    expect(taskErrorMessage("PROVIDER_POLL_RETRY_EXHAUSTED")).toContain("创建新任务");
  });
  it("explains the local stop caused by history cleanup", () => {
    expect(taskErrorDetail("EXECUTION_HISTORY_CLEANED")).toBe(" · 执行已过期，清理历史时已停止本地任务");
  });
  it("maps every registered provider failure code to readable Chinese", () => {
    for (const code of Object.values(PROVIDER_FAILURE_CODES)) {
      expect(taskErrorMessage(code), code).toBeTruthy();
    }
  });

  it("returns null for missing or unregistered codes so callers can fall back", () => {
    expect(taskErrorMessage(null)).toBeNull();
    expect(taskErrorMessage(undefined)).toBeNull();
    expect(taskErrorMessage("SOME_UNREGISTERED_CODE")).toBeNull();
  });

  /** 连接超时也走这个码，那时请求可能根本没发出去，措辞不能断言「已提交」。 */
  it("keeps the timeout wording neutral", () => {
    expect(taskErrorMessage(PROVIDER_FAILURE_CODES.CALL_TIMEOUT)).not.toContain("已提交");
  });

  it("prefers the readable reason and falls back to the raw code", () => {
    expect(taskErrorDetail(PROVIDER_FAILURE_CODES.CALL_TIMEOUT)).toBe(" · 调用超时，结果未知");
    expect(taskErrorDetail("SOME_UNREGISTERED_CODE")).toBe(" · SOME_UNREGISTERED_CODE");
    expect(taskErrorDetail(null)).toBe("");
    expect(taskErrorDetail(undefined)).toBe("");
  });
  it("explains AutoDL permission failures and uncertain submissions", () => {
    expect(taskErrorMessage("AUTODL_CREDENTIAL_REJECTED")).toContain("ComfyUI 权限");
    expect(taskErrorMessage("AUTODL_CREATE_UNCERTAIN")).toContain("显式重试");
    expect(taskErrorMessage("AUTODL_RESULT_EXPIRED")).toContain("已过期");
  });

});
