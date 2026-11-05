import { describe, expect, it } from "vitest";
import { PROVIDER_FAILURE_CODES, providerFailureMessage, taskErrorDetail, taskErrorMessage, type ProviderFailureDiagnostic } from "./taskErrorMessages";
import { getLocale, setLocale, SUPPORTED_LOCALES } from "../../shared/i18n";

describe("taskErrorMessages", () => {
  it("explains image safety blocks with the public code, category and moderation stage in every locale", () => {
    const expected = {
      en: { reason: "safety check", input: "Input safety check", output: "Generated output safety check", category: "Sexual content" },
      zh: { reason: "安全检查", input: "输入内容安全检查", output: "生成结果安全检查", category: "性内容（sexual）" },
      ru: { reason: "проверкой безопасности", input: "Проверка входных данных", output: "Проверка результата генерации", category: "сексуальное содержимое (sexual)" },
      ja: { reason: "安全性チェック", input: "入力の安全性チェック", output: "生成結果の安全性チェック", category: "性的な内容（sexual）" },
    };
    const previous = getLocale();
    try {
      for (const locale of SUPPORTED_LOCALES) {
        setLocale(locale);
        for (const stage of ["input", "output"] as const) {
          const diagnostic = { stage: "SUBMIT" as const, category: "MODERATION" as const, httpStatus: 400, upstreamCode: null, nodeId: null, fieldName: null,
            moderation: { code: "moderation_blocked" as const, stage, categories: ["sexual" as const], message: "PRIVATE_UPSTREAM_MESSAGE" } };
          const message = providerFailureMessage(diagnostic);
          expect(message, `${locale}: ${stage}`).toContain(expected[locale].reason);
          expect(message).toContain(expected[locale][stage]);
          expect(message).toContain("moderation_blocked");
          expect(message).toContain(expected[locale].category);
          expect(message).not.toContain("PRIVATE_UPSTREAM_MESSAGE");
          expect(message).not.toContain("RunningHub");
          const detail = taskErrorDetail("OPENAI_IMAGE_MODERATION_BLOCKED", diagnostic);
          expect(detail).toContain(expected[locale].reason);
          expect(detail).toContain(expected[locale][stage]);
          expect(detail).toContain("moderation_blocked");
          expect(detail).toContain(expected[locale].category);
          expect(detail).not.toContain("PRIVATE_UPSTREAM_MESSAGE");
          expect(detail).not.toContain("RunningHub");
          expect(detail).not.toContain("OPENAI_IMAGE_MODERATION_BLOCKED");
        }
      }
    } finally { setLocale(previous); }
  });

  it("keeps legacy image rejections readable without inventing moderation details", () => {
    expect(taskErrorDetail("OPENAI_IMAGE_REJECTED")).toBe(" · 上游拒绝了请求（参数或凭证）");
    expect(taskErrorDetail("OPENAI_IMAGE_MODERATION_BLOCKED")).toContain("安全检查");
    const diagnostic = { stage: "SUBMIT" as const, category: "MODERATION" as const, httpStatus: 400, upstreamCode: null, nodeId: null, fieldName: null,
      moderation: { code: "content_policy_violation" as const, stage: "unknown" as const, categories: [] } };
    expect(providerFailureMessage(diagnostic)).toContain("content_policy_violation");
    expect(providerFailureMessage(diagnostic)).toContain("安全检查阶段未提供");
    expect(providerFailureMessage(diagnostic)).not.toContain("安全类别");
  });

  it.each(["PRIVATE_UPSTREAM_STAGE", "toString"])("does not render unexpected moderation fields with stage %s", (stage) => {
    const diagnostic = { stage: "SUBMIT", category: "MODERATION", httpStatus: 400, upstreamCode: null, nodeId: null, fieldName: null, moderation: {
      code: "PRIVATE_UPSTREAM_CODE", stage, categories: ["PRIVATE_UPSTREAM_CATEGORY", "toString"],
    } } as unknown as ProviderFailureDiagnostic;
    const message = providerFailureMessage(diagnostic);
    expect(message).toContain("安全检查");
    expect(message).not.toContain("PRIVATE_UPSTREAM");
  });

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

  it("maps incomplete model streams to a readable explicit retry explanation", () => {
    expect(taskErrorMessage("LLM_STREAM_INCOMPLETE")).toContain("完成信号");
    expect(taskErrorMessage("LLM_STREAM_INCOMPLETE")).toContain("不会自动重试");
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
