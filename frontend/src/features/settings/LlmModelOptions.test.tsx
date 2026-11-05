import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import * as api from "../../shared/api/client";
import { createQueryClient } from "../../app/queryClient";
import { setLocale } from "../../shared/i18n";
import { server } from "../../test/server";
import { LlmModelOptions } from "./LlmModelOptions";
import { modelOptionsFromPolicy, type ModelOptionsDraft } from "./llmModelConfiguration";

const response = { id: "00000000-0000-4000-8000-000000000001", partial: false, checkedAt: "2026-11-04T00:00:00Z",
  models: [{ id: "model-a", contextWindowTokens: 32000, deploymentContextTokens: null, inputLimitTokens: null,
    outputLimitTokens: 2048, runtimeContextTokens: null, digest: null }] };
function Harness({ endpoint = "https://provider.example/v1", apiKey = "synthetic-key", base }: { endpoint?: string; apiKey?: string; base?: api.LlmSettings } = {}) {
  const [client] = useState(createQueryClient);
  const [value, setValue] = useState<ModelOptionsDraft>(modelOptionsFromPolicy(null));
  const [modelId, setModelId] = useState("model-a");
  return <QueryClientProvider client={client}><LlmModelOptions value={value} onChange={setValue} endpoint={endpoint}
    apiKey={apiKey} base={base} modelId={modelId} onSelectModel={setModelId} disabled={false} />
    <output aria-label="draft">{JSON.stringify(value)}</output></QueryClientProvider>;
}
describe("model discovery in connection form", () => {
  it("uses only the saved version and never combines its credential with an edited endpoint", async () => {
    setLocale("en"); const read = vi.spyOn(api, "previewLlmModels").mockResolvedValue(response);
    const base: api.LlmSettings = { configured: true, version: 7, endpoint: "https://provider.example/v1", modelId: "model-a",
      keyMask: "masked", toolCallingVerified: false, updatedAt: null };
    try {
      const view = render(<Harness apiKey="" base={base} />); const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: "Read model list" }));
      await waitFor(() => expect(read).toHaveBeenCalledTimes(1));
      expect(read.mock.calls.at(0)?.[0]).toEqual({ provider: "COMPATIBLE", credentialMode: "SAVED", expectedVersion: 7, modelId: "model-a" });
      view.rerender(<Harness apiKey="" base={base} endpoint="https://different.example/v1" />);
      await user.click(screen.getByRole("button", { name: "Read model list" }));
      expect(await screen.findByText(/Enter a valid key for this draft endpoint/)).toBeInTheDocument();
      expect(read).toHaveBeenCalledTimes(1);
    } finally { read.mockRestore(); }
  });
  it("ignores late results even if transport ignores cancellation", async () => {
    setLocale("en"); let resolve!: (value: api.LlmModelPreview) => void;
    const read = vi.spyOn(api, "previewLlmModels").mockImplementation(() => new Promise(done => { resolve = done; }));
    try {
      const view = render(<Harness />); const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: "Read model list" }));
      await waitFor(() => expect(read).toHaveBeenCalledTimes(1));
      view.rerender(<Harness endpoint="https://different.example/v1" />);
      await act(async () => { resolve(response); });
      expect(screen.getByLabelText("Confirmed context window")).toHaveValue(null);
      expect(screen.queryByLabelText("Fetched models")).not.toBeInTheDocument();
    } finally { read.mockRestore(); }
  });
  it("reads the draft credentials once, auto fills capacity without saving, and retains a manual override", async () => {
    setLocale("en"); const sent: unknown[] = [];
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
      http.post("/api/v1/settings/llm/models", async ({ request }) => { sent.push(await request.json()); return HttpResponse.json(response); }));
    render(<Harness />); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Read model list" }));
    await waitFor(() => expect(screen.getByLabelText("Confirmed context window")).toHaveValue(32000));
    expect(sent).toEqual([{ provider: "COMPATIBLE", credentialMode: "DRAFT", expectedVersion: 0, endpoint: "https://provider.example/v1", apiKey: "synthetic-key", modelId: "model-a" }]);
    expect(screen.getByLabelText("Request output limit")).toHaveValue(2048);
    await user.click(screen.getByRole("button", { name: "Use manual values" }));
    fireEvent.change(screen.getByLabelText("Confirmed context window"), { target: { value: "16384" } });
    await user.click(screen.getByRole("button", { name: "Read model list" }));
    await waitFor(() => expect(sent).toHaveLength(2));
    expect(screen.getByLabelText("Confirmed context window")).toHaveValue(16384);
    expect(screen.getByLabelText("draft")).toHaveTextContent('"mode":"MANUAL"');
  });
  it("keeps absent capacity unknown and allows manual configuration after failure", async () => {
    setLocale("en");
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
      http.post("/api/v1/settings/llm/models", () => HttpResponse.json({ ...response, models: [{ ...response.models[0], contextWindowTokens: null, outputLimitTokens: null }] })));
    render(<Harness />); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Read model list" }));
    expect(await screen.findByText(/Capacity is unknown/)).toBeInTheDocument();
    expect(screen.getByLabelText("Confirmed context window")).toHaveValue(null);
    fireEvent.change(screen.getByLabelText("Confirmed context window"), { target: { value: "8192" } });
    expect(screen.getByLabelText("Confirmed context window")).toHaveValue(8192);
  });
});
