import { act, fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterAll, beforeAll, expect, it, vi } from "vitest";
import type { LlmModelPreview, LlmSettings } from "../src/shared/api/client";

const preview = vi.hoisted(() => ({ root: null as { unmount(): void } | null }));
vi.mock("react-dom/client", async (importOriginal) => {
  const original = await importOriginal<typeof import("react-dom/client")>();
  return { ...original, createRoot: (...args: Parameters<typeof original.createRoot>) => {
    const root = original.createRoot(...args);
    preview.root = root;
    return root;
  } };
});

const originalFetch = window.fetch;
const originalUrl = window.location.href;
afterAll(async () => {
  await act(async () => preview.root?.unmount());
  document.getElementById("root")?.remove();
  window.fetch = originalFetch;
  window.history.replaceState(null, "", originalUrl);
});

beforeAll(async () => {
  window.history.replaceState(null, "", "/e2e/settings-design.html?route=/settings/media");
  const container = document.createElement("div");
  container.id = "root";
  document.body.append(container);
  // Import the actual browser entry, including its routes and isolated fetch handler.
  await act(async () => { await import("./settings-design.fixture"); });
  await screen.findByRole("heading", { name: "媒体连接与能力" });
}, 30_000);

it("opens director settings from the studio preview navigation and saves synthetic settings", async () => {
  const user = userEvent.setup();
  await user.click(screen.getByRole("link", { name: "导演设置" }));
  expect(await screen.findByRole("heading", { name: "导演设置" })).toBeVisible();
  expect(screen.getByRole("link", { name: "导演设置" })).toHaveAttribute("aria-current", "page");
  expect(screen.getAllByRole("spinbutton")).toHaveLength(9);
  const threshold = screen.getByLabelText("自动压缩阈值（%）");
  expect(threshold).toHaveValue(50);
  fireEvent.change(threshold, { target: { value: "25" } });
  await user.click(screen.getByRole("button", { name: "保存导演设置" }));
  await screen.findByText("导演设置已保存，无需重启。");
  await user.click(screen.getByRole("link", { name: "Provider 配置" }));
  await screen.findByRole("heading", { name: "Provider 配置" });
  await user.click(screen.getByRole("link", { name: "导演设置" }));
  expect(await screen.findByLabelText("自动压缩阈值（%）")).toHaveValue(25);
});

async function llmRequest(path = "", method = "GET", body?: object) {
  return window.fetch(`/api/v1/settings/llm${path}`, { method, ...(body ? { body: JSON.stringify(body) } : {}) });
}

const manualSelection = { provider: "COMPATIBLE", mode: "MANUAL", contextWindowTokens: 1_000_000,
  inputLimitTokens: null, maxOutputTokens: 4096, outputParameter: "MAX_TOKENS" } as const;

it("persists synthetic manual capacity and rejects a stale write without replacing the saved policy", async () => {
  const initial = await (await llmRequest()).json() as LlmSettings;
  expect(initial.modelPolicy).toBeNull();
  const body = { expectedVersion: initial.version, endpoint: initial.endpoint, modelId: initial.modelId,
    apiKey: "synthetic-key", modelSelection: manualSelection };
  const saved = await (await llmRequest("", "PUT", body)).json() as LlmSettings;
  expect(saved).toMatchObject({ version: initial.version + 1, modelPolicy: { provider: "COMPATIBLE", source: "MANUAL",
    contextWindowTokens: 1_000_000, inputLimitTokens: null, maxOutputTokens: 4096, outputParameter: "MAX_TOKENS" } });
  expect(saved.modelPolicy).not.toHaveProperty("mode");
  const stale = await llmRequest("", "PUT", { ...body, modelId: "stale-model" });
  expect(stale.status).toBe(409);
  expect(await (await llmRequest()).json()).toEqual(saved);
});

it("discovers synthetic models and binds automatic capacity to the preview scope and settings version", async () => {
  const base = await (await llmRequest()).json() as LlmSettings;
  const preview = await (await llmRequest("/models", "POST", { provider: "COMPATIBLE", credentialMode: "DRAFT",
    expectedVersion: base.version, endpoint: base.endpoint, apiKey: "synthetic-key", modelId: base.modelId })).json() as LlmModelPreview;
  expect(preview).toMatchObject({ partial: false, models: [
    { id: "creative-director-model", contextWindowTokens: 32768, outputLimitTokens: 8192 },
    { id: "creative-director-extended", contextWindowTokens: 131072 },
    { id: "creative-director-unreported", contextWindowTokens: null },
  ] });
  const body = { expectedVersion: base.version, endpoint: base.endpoint, modelId: "creative-director-model",
    apiKey: "synthetic-key", modelSelection: { provider: "COMPATIBLE", mode: "AUTO", previewId: preview.id,
      maxOutputTokens: 4096, outputParameter: "MAX_TOKENS" } };
  const wrongScope = await llmRequest("", "PUT", { ...body, endpoint: "https://other.example.test/v1" });
  expect(wrongScope.status).toBe(409);
  expect(await (await llmRequest()).json()).toEqual(base);
  const saved = await (await llmRequest("", "PUT", body)).json() as LlmSettings;
  expect(saved.modelPolicy).toMatchObject({ provider: "COMPATIBLE", source: "PROVIDER_REPORTED",
    contextWindowTokens: 32768, maxOutputTokens: 4096, theoreticalContextTokens: 32768, reportedOutputTokens: 8192 });
  const expired = await llmRequest("", "PUT", { ...body, expectedVersion: saved.version });
  expect(expired.status).toBe(409);
  expect(await (await llmRequest()).json()).toEqual(saved);
});

it("keeps saved settings when discovery or capacity validation fails", async () => {
  const base = await (await llmRequest()).json() as LlmSettings;
  const discovery = await llmRequest("/models", "POST", { provider: "COMPATIBLE", credentialMode: "DRAFT",
    expectedVersion: base.version, endpoint: "https://fail.example.test/v1", apiKey: "synthetic-key" });
  expect(discovery.status).toBe(502);
  const invalid = await llmRequest("", "PUT", { expectedVersion: base.version, endpoint: base.endpoint,
    modelId: base.modelId, apiKey: "synthetic-key", modelSelection: { ...manualSelection, maxOutputTokens: 1_000_000 } });
  expect(invalid.status).toBe(400);
  expect(await (await llmRequest()).json()).toEqual(base);
});
