import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import type { LlmSettings } from "../../shared/api/client";
import { MemoryRouter } from "react-router";
import { createQueryClient } from "../../app/queryClient";
import { setLocale } from "../../shared/i18n";
import { server } from "../../test/server";
import { LlmSettingsPage } from "./LlmSettingsPage";
const manualSelection = { provider: "COMPATIBLE", mode: "MANUAL", contextWindowTokens: 16384,
  inputLimitTokens: null, maxOutputTokens: 2048, outputParameter: "MAX_TOKENS" } as const;
function confirmCapacity() {
  fireEvent.change(screen.getByLabelText("确认的上下文窗口"), { target: { value: "16384" } });
  fireEvent.change(screen.getByLabelText("请求最大输出"), { target: { value: "2048" } });
}

function unconfiguredLlmSettings() {
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
    http.get("/api/v1/settings/llm", () => HttpResponse.json({ configured: false, version: 0,
      endpoint: null, modelId: null, keyMask: null, toolCallingVerified: false, updatedAt: null })),
    http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({ llmMode: "MOCK", mediaMode: "MOCK",
      imageConfigured: false, videoConfigured: false, recentErrors: [] })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
  );
}

describe("LlmSettingsPage", () => {
  it("saves the selected discovered model and its server-bound automatic policy together", async () => {
    setLocale("en"); unconfiguredLlmSettings();
    const writes: unknown[] = [];
    const previewId = "00000000-0000-4000-8000-000000000001";
    server.use(http.post("/api/v1/settings/llm/models", () => HttpResponse.json({ id: previewId,
      partial: false, checkedAt: "2026-11-04T00:00:00Z", models: [{ id: "model-b", contextWindowTokens: 32768,
        deploymentContextTokens: 16384, inputLimitTokens: null, outputLimitTokens: 2048, runtimeContextTokens: null, digest: null }] })),
    http.put("/api/v1/settings/llm", async ({ request }) => { writes.push(await request.json());
      return HttpResponse.json({ configured: true, version: 1, endpoint: "https://router.example/v1", modelId: "model-b",
        keyMask: "masked", toolCallingVerified: false, updatedAt: null }); }));
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    await user.type(await screen.findByRole("textbox", { name: "endpoint address" }), "https://router.example/v1");
    fireEvent.change(screen.getByLabelText("API Key (need to be re-entered each time it is modified)"), { target: { value: "synthetic-key" } });
    await user.click(screen.getByRole("combobox", { name: "Metadata provider" }));
    await user.click(screen.getByRole("option", { name: "OpenRouter" }));
    await user.click(screen.getByRole("button", { name: "Read model list" }));
    await user.click(await screen.findByRole("combobox", { name: "Fetched models" }));
    await user.click(screen.getByRole("option", { name: "model-b" }));
    await waitFor(() => expect(screen.getByLabelText("Confirmed context window")).toHaveValue(16384));
    expect(writes).toEqual([]);
    await user.click(screen.getByRole("button", { name: "Save configuration" }));
    await waitFor(() => expect(writes).toEqual([{ expectedVersion: 0, endpoint: "https://router.example/v1",
      modelId: "model-b", apiKey: "synthetic-key", modelSelection: { provider: "OPENROUTER", mode: "AUTO",
        previewId, maxOutputTokens: 2048, outputParameter: "MAX_TOKENS" } }]));
  });

  it("requires confirmed capacity before saving instead of silently assuming a million tokens", async () => {
    unconfiguredLlmSettings(); const writes: unknown[] = [];
    server.use(http.put("/api/v1/settings/llm", async ({ request }) => { writes.push(await request.json()); return HttpResponse.json({}); }));
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    await user.type(await screen.findByRole("textbox", { name: "端点地址" }), "https://api.example.com/v1");
    await user.type(screen.getByRole("textbox", { name: "模型 ID" }), "model-a");
    fireEvent.change(screen.getByLabelText("API Key（每次修改均需重新输入）"), { target: { value: "synthetic-key" } });
    await user.click(screen.getByRole("button", { name: "保存配置" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("请重新读取信息以使用自动值，或手动确认有效的容量和输出上限。");
    expect(writes).toEqual([]);
    expect(screen.getByLabelText("API Key（每次修改均需重新输入）")).toHaveValue("synthetic-key");
  });
  it.each([
    ["the usual six-character Ollama placeholder", "ollama", false],
    ["spaces", "local key", false],
    ["non-ASCII characters", "not-ascii-凭证", false],
    ["more than 4096 characters even with native validation bypassed", "x".repeat(4097), true],
  ] as const)("identifies API Key validation for %s without sending or discarding the draft", async (_description, invalidKey, bypassNative) => {
    unconfiguredLlmSettings();
    const submitted: unknown[] = [];
    server.use(http.put("/api/v1/settings/llm", async ({ request }) => {
      submitted.push(await request.json());
      return new HttpResponse(null, { status: 500 });
    }));
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    const endpoint = await screen.findByRole("textbox", { name: "端点地址" });
    const model = screen.getByRole("textbox", { name: "模型 ID" });
    const key = screen.getByLabelText("API Key（每次修改均需重新输入）");
    await user.type(endpoint, "https://api.example.com");
    await user.type(model, "model-a");
    fireEvent.change(key, { target: { value: invalidKey } });
    if (bypassNative) key.closest("form")!.noValidate = true;
    await user.click(screen.getByRole("button", { name: "保存配置" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("API Key 必须为 8–4096 个不含空格的可打印 ASCII 字符。");
    expect(key).toHaveAttribute("aria-invalid", "true");
    expect(key).toHaveAccessibleDescription(/API Key 必须为 8–4096/);
    expect(key).toHaveValue(invalidKey);
    expect(endpoint).toHaveValue("https://api.example.com");
    expect(model).toHaveValue("model-a");
    expect(submitted).toEqual([]);
    expect(screen.queryByText("密钥输入已清空，重试前请重新输入。")).not.toBeInTheDocument();
  });

  it.each([
    ["the minimum length", "abcdefgh"],
    ["the maximum length", "x".repeat(4096)],
    ["the documented Ollama placeholder", "ollama-local"],
  ])("accepts %s and clears the key only after submitting it", async (_description, validKey) => {
    unconfiguredLlmSettings();
    const submitted: unknown[] = [];
    server.use(http.put("/api/v1/settings/llm", async ({ request }) => {
      submitted.push(await request.json());
      return HttpResponse.json({ configured: true, version: 1, endpoint: "https://api.example.com",
        modelId: "model-a", keyMask: "••••test", toolCallingVerified: false, updatedAt: null });
    }));
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    await user.type(await screen.findByRole("textbox", { name: "端点地址" }), "https://api.example.com");
    await user.type(screen.getByRole("textbox", { name: "模型 ID" }), "model-a");
    const key = screen.getByLabelText("API Key（每次修改均需重新输入）");
    expect(key).toHaveValue("");
    expect(key).toHaveAccessibleDescription(/ollama-local/);
    fireEvent.change(key, { target: { value: "ollama" } });
    await user.click(screen.getByRole("button", { name: "保存配置" }));
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    fireEvent.change(key, { target: { value: validKey } });
    confirmCapacity();
    expect(key).not.toHaveAttribute("aria-invalid", "true");
    await user.click(screen.getByRole("button", { name: "保存配置" }));
    expect(await screen.findByText("配置已加密保存，密钥输入已清空。")).toBeInTheDocument();
    expect(key).toHaveValue("");
    expect(submitted).toEqual([{ expectedVersion: 0, endpoint: "https://api.example.com", modelId: "model-a", apiKey: validKey, modelSelection: manualSelection }]);
  });

  it.each(["http://127.0.0.1:11434/v1", "https://api.example.com"])("explains provider-dependent diagnostic charges for %s and requires consent", async (endpoint) => {
    setLocale("en");
    unconfiguredLlmSettings();
    server.use(http.get("/api/v1/settings/llm", () => HttpResponse.json({ configured: true, version: 1,
      endpoint, modelId: "model-a", keyMask: "••••test", toolCallingVerified: false, updatedAt: null })));
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const consent = await screen.findByRole("checkbox", { name: "I understand this diagnostic sends real model requests and may incur charges" });
    expect(consent).not.toBeChecked();
    expect(screen.getByText("Charges depend on your provider or proxy, including for local endpoints. Confirm before each diagnostic.")).toBeVisible();
    expect(screen.getByRole("button", { name: "Perform diagnostics that may be billable" })).toBeDisabled();
  });

  it("discards unsaved fields and credentials without saving or diagnosing", async () => {
    const writes: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/llm", () => HttpResponse.json({
        configured: true, version: 4, endpoint: "https://api.example.com", modelId: "model-a",
        keyMask: "••••7890", toolCallingVerified: false, updatedAt: "2026-09-23T00:00:00Z",
      })),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({
        checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
        llmMode: "MOCK", llmConfigured: true, llmToolCallingVerified: false,
        mediaMode: "MOCK", imageConfigured: true, videoConfigured: true, recentErrors: [],
      })),
      http.put("/api/v1/settings/llm", async ({ request }) => { writes.push(await request.json()); return new HttpResponse(null, { status: 500 }); }),
      http.post("/api/v1/settings/llm/diagnose", async ({ request }) => { writes.push(await request.json()); return new HttpResponse(null, { status: 500 }); }),
    );
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    const model = await screen.findByRole("textbox", { name: "模型 ID" });
    expect(screen.getByText("所有修改已保存")).toBeInTheDocument();
    const consent = screen.getByRole("checkbox", { name: "我了解此次诊断会发送真实模型请求，并可能产生费用" });
    await user.click(consent);
    await user.clear(model); await user.type(model, "draft-model");
    const key = screen.getByLabelText("API Key（每次修改均需重新输入）");
    await user.type(key, "unsaved-secret");
    expect(screen.getByText("有未保存的修改")).toBeInTheDocument();
    expect(screen.getByText("正在编辑 · 基于 v4")).toBeInTheDocument();
    expect(screen.getByText("model-a")).toBeInTheDocument();
    expect(consent).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "撤销修改" }));
    expect(model).toHaveValue("model-a");
    expect(key).toHaveValue("");
    expect(consent).not.toBeChecked();
    expect(screen.getByRole("button", { name: "执行可能计费的诊断" })).toBeDisabled();
    expect(screen.queryByText("有未保存的修改")).not.toBeInTheDocument();
    expect(screen.getByText("所有修改已保存")).toBeInTheDocument();
    expect(writes).toEqual([]);
  });

  it("submits the key once, clears its field, and displays only the server mask", async () => {
    const submitted: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/llm", () => HttpResponse.json({
        configured: false, version: 0, endpoint: null, modelId: null,
        keyMask: null, toolCallingVerified: false, updatedAt: null,
      })),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({
        checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
        llmMode: "MOCK", llmConfigured: true, llmToolCallingVerified: false,
        mediaMode: "CONFIGURED", imageConfigured: true, videoConfigured: false,
        recentErrors: [],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/llm", async ({ request }) => {
        submitted.push(await request.json());
        return HttpResponse.json({
          configured: true, version: 1, endpoint: "https://api.example.com", modelId: "model-a",
          keyMask: "••••7890", toolCallingVerified: false, updatedAt: "2026-09-23T00:00:00Z",
        });
      }),
    );

    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    await user.type(await screen.findByRole("textbox", { name: "端点地址" }), "https://api.example.com");
    expect(screen.getByText("保存配置后可验证")).toBeInTheDocument();
    await user.type(screen.getByRole("textbox", { name: "模型 ID" }), "model-a");
    const keyInput = screen.getByLabelText("API Key（每次修改均需重新输入）") as HTMLInputElement;
    await user.type(keyInput, "secret-7890");
    confirmCapacity();
    await user.click(screen.getByRole("button", { name: "保存配置" }));

    expect(await within(screen.getByRole("region", { name: "LLM 配置" })).findByRole("status")).toHaveTextContent("配置已加密保存");
    expect(keyInput).toHaveValue("");
    expect(screen.getByText(/密钥 ••••7890/)).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent("secret-7890");
    expect(submitted).toEqual([{ expectedVersion: 0, endpoint: "https://api.example.com", modelId: "model-a", apiKey: "secret-7890", modelSelection: manualSelection }]);
    expect(screen.queryByText("保存配置后可验证")).not.toBeInTheDocument();
    expect(screen.getByText(/已配置模型模式 · 图片已配置 · 视频未配置/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "查看系统诊断" })).toHaveAttribute("href", "/settings/general?tab=diagnostics");
  });

  it("requires visible cost consent and updates the verified state after a full probe", async () => {
    const submitted: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/llm", () => HttpResponse.json({
        configured: true, version: 4, endpoint: "https://api.example.com", modelId: "model-a",
        keyMask: "••••7890", toolCallingVerified: false, updatedAt: "2026-09-23T00:00:00Z",
      })),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({
        checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
        llmMode: "CONFIGURED", llmConfigured: true, llmToolCallingVerified: false,
        mediaMode: "MOCK", imageConfigured: true, videoConfigured: true,
        recentErrors: [],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/settings/llm/diagnose", async ({ request }) => {
        submitted.push(await request.json());
        return HttpResponse.json({
          configured: true, version: 4, endpoint: "https://api.example.com", modelId: "model-a",
          keyMask: "••••7890", toolCallingVerified: true, updatedAt: "2026-09-23T00:00:00Z",
        });
      }),
    );

    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    const diagnose = await screen.findByRole("button", { name: "执行可能计费的诊断" });
    expect(diagnose).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: "我了解此次诊断会发送真实模型请求，并可能产生费用" }));
    await user.click(diagnose);

    expect(await screen.findByText("已验证完整工具协议。")).toBeInTheDocument();
    expect(screen.getByText(/完整工具往返已验证/)).toBeInTheDocument();
    expect(submitted).toEqual([{ expectedVersion: 4, acknowledgeCost: true }]);
    expect(diagnose).toBeDisabled();
  });

  it("preserves a draft and its CAS base when a newer setting arrives, then explicitly loads the latest", async () => {
    const original: LlmSettings = {
      configured: true, version: 4, endpoint: "https://api.example.com", modelId: "model-a",
      keyMask: "••••7890", toolCallingVerified: false, updatedAt: "2026-09-23T00:00:00Z",
    };
    const latest: LlmSettings = { ...original, version: 5, modelId: "remote-model" };
    let current = original;
    const submitted: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/llm", () => HttpResponse.json(current)),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({
        checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
        llmMode: "MOCK", llmConfigured: true, llmToolCallingVerified: false,
        mediaMode: "MOCK", imageConfigured: true, videoConfigured: true, recentErrors: [],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/llm", async ({ request }) => {
        submitted.push(await request.json());
        return HttpResponse.json({ title: "Conflict", status: 409, code: "VERSION_CONFLICT", detail: "配置已更新，请核对。" }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
      }),
    );
    const queryClient = createQueryClient();
    render(<QueryClientProvider client={queryClient}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    const modelInput = await screen.findByRole("textbox", { name: "模型 ID" });
    await user.clear(modelInput);
    await user.type(modelInput, "local-draft");
    const keyInput = screen.getByLabelText("API Key（每次修改均需重新输入）");
    await user.type(keyInput, "local-secret");
    confirmCapacity();
    current = latest;
    act(() => { queryClient.setQueryData(["settings", "llm"], latest); });
    expect(await screen.findByText("已有更新的配置")).toBeInTheDocument();
    expect(modelInput).toHaveValue("local-draft");
    expect(screen.getByRole("button", { name: "执行可能计费的诊断" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "保存配置" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("配置已更新，请核对。");
    expect(submitted).toEqual([{ expectedVersion: 4, endpoint: original.endpoint, modelId: "local-draft", apiKey: "local-secret", modelSelection: manualSelection }]);
    expect(keyInput).toHaveValue("");
    expect(modelInput).toHaveValue("local-draft");
    await user.click(screen.getByRole("button", { name: "载入最新配置" }));
    expect(modelInput).toHaveValue("remote-model");
    expect(screen.queryByText("已有更新的配置")).not.toBeInTheDocument();
  });

  it("retries a failed configuration read without leaving the settings page", async () => {
    let shouldFail = true;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/llm", () => shouldFail ? HttpResponse.json({ status: 503, title: "Unavailable" }, { status: 503 }) : HttpResponse.json({
        configured: false, version: 0, endpoint: null, modelId: null, keyMask: null, toolCallingVerified: false, updatedAt: null,
      })),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({
        checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
        llmMode: "MOCK", llmConfigured: true, llmToolCallingVerified: false,
        mediaMode: "MOCK", imageConfigured: true, videoConfigured: true, recentErrors: [],
      })),
    );
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByRole("alert")).toHaveTextContent("读取配置失败");
    shouldFail = false;
    await userEvent.setup().click(screen.getByRole("button", { name: "重新读取配置" }));
    expect(await screen.findByRole("textbox", { name: "端点地址" })).toHaveValue("");
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  });


  it("invalidates cost consent and diagnostic success when the saved configuration version changes", async () => {
    const original: LlmSettings = {
      configured: true, version: 4, endpoint: "https://api.example.com", modelId: "model-a",
      keyMask: "••••7890", toolCallingVerified: false, updatedAt: "2026-09-23T00:00:00Z",
    };
    const latest: LlmSettings = { ...original, version: 5, endpoint: "https://new.example.com", modelId: "model-b" };
    const submitted: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/llm", () => HttpResponse.json(original)),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({
        checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
        llmMode: "CONFIGURED", llmConfigured: true, llmToolCallingVerified: false,
        mediaMode: "MOCK", imageConfigured: true, videoConfigured: true, recentErrors: [],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/settings/llm/diagnose", async ({ request }) => {
        submitted.push(await request.json());
        return HttpResponse.json({ ...latest, toolCallingVerified: true });
      }),
    );
    const queryClient = createQueryClient();
    render(<QueryClientProvider client={queryClient}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    const consent = await screen.findByRole("checkbox", { name: "我了解此次诊断会发送真实模型请求，并可能产生费用" });
    const diagnose = screen.getByRole("button", { name: "执行可能计费的诊断" });
    await user.click(consent);
    expect(diagnose).toBeEnabled();
    act(() => { queryClient.setQueryData(["settings", "llm"], latest); });
    await waitFor(() => expect(consent).not.toBeChecked());
    expect(diagnose).toBeDisabled();
    expect(submitted).toHaveLength(0);
    await user.click(consent);
    await user.click(diagnose);
    expect(await screen.findByText("已验证完整工具协议。")).toBeInTheDocument();
    expect(submitted).toEqual([{ expectedVersion: 5, acknowledgeCost: true }]);
    act(() => { queryClient.setQueryData(["settings", "llm"], { ...latest, version: 6 }); });
    await waitFor(() => expect(screen.queryByText("已验证完整工具协议。")).not.toBeInTheDocument());
    expect(screen.getByText(/当前版本 6：尚未验证/)).toBeInTheDocument();
    expect(consent).not.toBeChecked();
    expect(diagnose).toBeDisabled();
  });

  it("refreshes a diagnostic conflict, preserves the edit base, and lets the user diagnose the latest version", async () => {
    const original: LlmSettings = {
      configured: true, version: 4, endpoint: "https://api.example.com", modelId: "model-a",
      keyMask: "••••7890", toolCallingVerified: false, updatedAt: "2026-09-23T00:00:00Z",
    };
    const latest: LlmSettings = { ...original, version: 5, modelId: "remote-model" };
    let current = original;
    const submitted: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/llm", () => HttpResponse.json(current)),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({
        checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
        llmMode: "CONFIGURED", llmConfigured: true, llmToolCallingVerified: false,
        mediaMode: "MOCK", imageConfigured: true, videoConfigured: true, recentErrors: [],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/settings/llm/diagnose", async ({ request }) => {
        const input = await request.json() as { expectedVersion: number };
        submitted.push(input);
        if (input.expectedVersion !== latest.version) {
          current = latest;
          return HttpResponse.json({ title: "Conflict", status: 409, code: "PROVIDER_CONFIG_CHANGED", detail: "配置已变更，请重新读取。" }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
        }
        return HttpResponse.json({ ...latest, toolCallingVerified: true });
      }),
    );
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    const modelInput = await screen.findByRole("textbox", { name: "模型 ID" });
    // Restoring the value leaves a pinned edit base; a conflict must not silently replace it.
    await user.type(modelInput, "x{Backspace}");
    await user.click(screen.getByRole("checkbox", { name: "我了解此次诊断会发送真实模型请求，并可能产生费用" }));
    await user.click(screen.getByRole("button", { name: "执行可能计费的诊断" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("配置已变更，请重新读取。");
    expect(await screen.findByText("已有更新的配置")).toBeInTheDocument();
    expect(modelInput).toHaveValue("model-a");
    expect(submitted).toEqual([{ expectedVersion: 4, acknowledgeCost: true }]);
    await user.click(screen.getByRole("button", { name: "载入最新配置" }));
    expect(modelInput).toHaveValue("remote-model");
    const diagnose = screen.getByRole("button", { name: "执行可能计费的诊断" });
    expect(diagnose).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: "我了解此次诊断会发送真实模型请求，并可能产生费用" }));
    await user.click(diagnose);
    expect(await screen.findByText("已验证完整工具协议。")).toBeInTheDocument();
    expect(submitted).toEqual([{ expectedVersion: 4, acknowledgeCost: true }, { expectedVersion: 5, acknowledgeCost: true }]);
  });

});


it("fills discovered Ollama only on selection and preserves its unsaved draft", async () => {
  const writes: unknown[] = [];
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
    http.get("/api/v1/settings/llm", () => HttpResponse.json({ configured: true, version: 4, endpoint: "https://api.example.com",
      modelId: "saved-model", keyMask: "••••1234", toolCallingVerified: false, updatedAt: null })),
    http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({ llmMode: "MOCK", mediaMode: "MOCK", imageConfigured: false, videoConfigured: false, recentErrors: [] })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.post("/api/v1/settings/local-services/discover", () => HttpResponse.json({ service: "OLLAMA", runtime: "CONTAINER", status: "FOUND", endpoint: "http://host.docker.internal:11434/v1", policyAllowed: true })),
    http.put("/api/v1/settings/llm", async ({ request }) => { writes.push(await request.json()); return new HttpResponse(null, { status: 500 }); }),
  );
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
  const user = userEvent.setup(); const endpoint = await screen.findByRole("textbox", { name: "端点地址" });
  const model = screen.getByRole("textbox", { name: "模型 ID" });
  await user.clear(model); await user.type(model, "draft-model");
  const key = screen.getByLabelText("API Key（每次修改均需重新输入）"); await user.type(key, "draft-secret");
  const form = endpoint.closest("form")!;
  expect(within(form).getByRole("button", { name: "保存配置" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "执行可能计费的诊断" })).toBeInTheDocument();
  await user.click(within(form).getByRole("button", { name: "查找本机 Ollama" }));
  const fill = await screen.findByRole("button", { name: "填入地址" });
  expect(endpoint).toHaveValue("https://api.example.com"); await user.click(fill);
  expect(endpoint).toHaveValue("http://host.docker.internal:11434/v1");
  expect(model).toHaveValue("draft-model"); expect(key).toHaveValue("draft-secret");
  expect(screen.getByText("正在编辑 · 基于 v4")).toBeInTheDocument(); expect(writes).toEqual([]);
});
