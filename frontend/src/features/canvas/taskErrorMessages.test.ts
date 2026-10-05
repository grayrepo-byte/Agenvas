import { describe, expect, it } from "vitest";
import { PROVIDER_FAILURE_CODES, taskErrorDetail, taskErrorMessage } from "./taskErrorMessages";

describe("taskErrorMessages", () => {
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
