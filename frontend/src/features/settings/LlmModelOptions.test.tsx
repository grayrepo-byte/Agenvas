import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import * as api from "../../shared/api/client";
import { createQueryClient } from "../../app/queryClient";
import { setLocale, t } from "../../shared/i18n";
import { server } from "../../test/server";
import { LlmModelOptions } from "./LlmModelOptions";
import { modelOptionsFromPolicy, type ModelOptionsDraft } from "./llmModelConfiguration";

const response = {
  id: "00000000-0000-4000-8000-000000000001",
  partial: false,
  checkedAt: "2026-11-04T00:00:00Z",
  models: [
    {
      id: "model-a",
      contextWindowTokens: 32000,
      deploymentContextTokens: null,
      inputLimitTokens: null,
      outputLimitTokens: 2048,
      runtimeContextTokens: null,
      digest: null,
    },
  ],
};
function Harness({
  endpoint = "https://provider.example/v1",
  apiKey = "synthetic-key",
  base,
  connectionId,
}: { endpoint?: string; apiKey?: string; base?: api.LlmSettings; connectionId?: string } = {}) {
  const [client] = useState(createQueryClient);
  const [value, setValue] = useState<ModelOptionsDraft>(modelOptionsFromPolicy(null));
  const [modelId, setModelId] = useState("model-a");
  return (
    <QueryClientProvider client={client}>
      <LlmModelOptions
        value={value}
        onChange={setValue}
        endpoint={endpoint}
        apiKey={apiKey}
        base={base}
        connectionId={connectionId}
        modelId={modelId}
        onSelectModel={setModelId}
        disabled={false}
      />
      <output aria-label="draft">{JSON.stringify(value)}</output>
    </QueryClientProvider>
  );
}
describe("model discovery in connection form", () => {
  it("shows the default capacity without exposing advanced parameter inputs", () => {
    setLocale("en");
    render(<Harness />);
    const summary = screen.getByRole("region", { name: t("modelConnection.capacity") });
    expect(summary).toHaveTextContent("1M tokens");
    expect(summary).toHaveTextContent("4,096 tokens");
    expect(summary).toHaveTextContent(t("modelConnection.sourceDefault"));
    expect(screen.queryByRole("spinbutton", { name: "Confirmed context window" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: t("modelConnection.advanced") })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
  });
  it("retains manual capacity in the summary while advanced settings are closed", async () => {
    setLocale("en");
    render(<Harness />);
    const user = userEvent.setup();
    const advanced = screen.getByRole("button", { name: t("modelConnection.advanced") });
    await user.click(advanced);
    fireEvent.change(screen.getByLabelText("Confirmed context window"), { target: { value: "32768" } });
    await user.click(advanced);
    expect(screen.queryByRole("spinbutton", { name: "Confirmed context window" })).not.toBeInTheDocument();
    expect(screen.getByRole("region", { name: t("modelConnection.capacity") })).toHaveTextContent("32,768 tokens");
    await user.click(advanced);
    expect(screen.getByLabelText("Confirmed context window")).toHaveValue(32768);
    expect(screen.getByLabelText("Request output limit")).toHaveValue(4096);
    expect(screen.getByLabelText("draft")).toHaveTextContent('"manualOverride":true');
  });
  it("summarizes an input-only provider limit without claiming a generic combined window", async () => {
    setLocale("en");
    const read = vi.spyOn(api, "previewLlmModels").mockResolvedValue({
      ...response,
      models: [{ ...response.models[0]!, contextWindowTokens: null, inputLimitTokens: 32000 }],
    });
    try {
      render(<Harness />);
      const user = userEvent.setup();
      await user.click(screen.getByRole("combobox", { name: t("modelConnection.provider") }));
      await user.click(screen.getByRole("option", { name: "Gemini" }));
      await user.click(screen.getByRole("button", { name: "Read model list" }));
      const summary = screen.getByRole("region", { name: t("modelConnection.capacity") });
      await waitFor(() => expect(summary).toHaveTextContent("32,000 tokens"));
      expect(summary).toHaveTextContent("Separate input limit");
      expect(summary).not.toHaveTextContent("1M tokens");
      expect(screen.getByRole("button", { name: t("modelConnection.advanced") })).toHaveAttribute(
        "aria-expanded",
        "false",
      );
    } finally {
      read.mockRestore();
    }
  });
  it("uses only the saved version and never combines its credential with an edited endpoint", async () => {
    setLocale("en");
    const read = vi.spyOn(api, "previewLlmModels").mockResolvedValue(response);
    const base: api.LlmSettings = {
      configured: true,
      version: 7,
      endpoint: "https://provider.example/v1",
      modelId: "model-a",
      keyMask: "masked",
      toolCallingVerified: false,
      updatedAt: null,
    };
    try {
      const view = render(<Harness apiKey="" base={base} />);
      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: t("modelConnection.advanced") }));
      await user.click(screen.getByRole("button", { name: "Read model list" }));
      await waitFor(() => expect(read).toHaveBeenCalledTimes(1));
      expect(read.mock.calls.at(0)?.[0]).toEqual({
        provider: "COMPATIBLE",
        credentialMode: "SAVED",
        expectedVersion: 7,
        modelId: "model-a",
      });
      view.rerender(<Harness apiKey="" base={base} endpoint="https://different.example/v1" />);
      await user.click(screen.getByRole("button", { name: "Read model list" }));
      expect(await screen.findByText(/Enter a valid key for this draft endpoint/)).toBeInTheDocument();
      expect(read).toHaveBeenCalledTimes(1);
    } finally {
      read.mockRestore();
    }
  });
  it("ignores late results even if transport ignores cancellation", async () => {
    setLocale("en");
    let resolve!: (value: api.LlmModelPreview) => void;
    const read = vi.spyOn(api, "previewLlmModels").mockImplementation(
      () =>
        new Promise(done => {
          resolve = done;
        }),
    );
    try {
      const view = render(<Harness />);
      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: t("modelConnection.advanced") }));
      await user.click(screen.getByRole("button", { name: "Read model list" }));
      await waitFor(() => expect(read).toHaveBeenCalledTimes(1));
      view.rerender(<Harness endpoint="https://different.example/v1" />);
      await act(async () => {
        resolve(response);
      });
      expect(screen.getByLabelText("Confirmed context window")).toHaveValue(1_000_000);
      expect(screen.queryByLabelText("Fetched models")).not.toBeInTheDocument();
    } finally {
      read.mockRestore();
    }
  });
  it("reads the draft credentials once, auto fills capacity without saving, and retains a manual override", async () => {
    setLocale("en");
    const sent: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
      http.post("/api/v1/settings/llm/models", async ({ request }) => {
        sent.push(await request.json());
        return HttpResponse.json(response);
      }),
    );
    render(<Harness />);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: t("modelConnection.advanced") }));
    await user.click(screen.getByRole("button", { name: "Read model list" }));
    await waitFor(() => expect(screen.getByLabelText("Confirmed context window")).toHaveValue(32000));
    expect(sent).toEqual([
      {
        provider: "COMPATIBLE",
        credentialMode: "DRAFT",
        expectedVersion: 0,
        endpoint: "https://provider.example/v1",
        apiKey: "synthetic-key",
        modelId: "model-a",
      },
    ]);
    expect(screen.getByLabelText("Request output limit")).toHaveValue(2048);
    await user.click(screen.getByRole("button", { name: "Use manual values" }));
    fireEvent.change(screen.getByLabelText("Confirmed context window"), { target: { value: "16384" } });
    await user.click(screen.getByRole("button", { name: "Read model list" }));
    await waitFor(() => expect(sent).toHaveLength(2));
    expect(screen.getByLabelText("Confirmed context window")).toHaveValue(16384);
    expect(screen.getByLabelText("draft")).toHaveTextContent('"mode":"MANUAL"');
  });
  it("uses the generic default when discovered metadata has no capacity and permits manual overrides", async () => {
    setLocale("en");
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
      http.post("/api/v1/settings/llm/models", () =>
        HttpResponse.json({
          ...response,
          models: [{ ...response.models[0], contextWindowTokens: null, outputLimitTokens: null }],
        }),
      ),
    );
    render(<Harness />);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: t("modelConnection.advanced") }));
    await user.click(screen.getByRole("button", { name: "Read model list" }));
    await screen.findByLabelText("Fetched models");
    expect(screen.getByLabelText("Confirmed context window")).toHaveValue(1_000_000);
    expect(screen.getByLabelText("draft")).toHaveTextContent('"mode":"MANUAL"');
    expect(screen.getByRole("button", { name: "Use automatic values" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Confirmed context window"), { target: { value: "8192" } });
    expect(screen.getByLabelText("Confirmed context window")).toHaveValue(8192);
  });
  it("uses the default after metadata failure and preserves explicit manual values on retry", async () => {
    setLocale("en");
    const read = vi.spyOn(api, "previewLlmModels").mockRejectedValue(new Error("metadata unavailable"));
    try {
      render(<Harness />);
      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: t("modelConnection.advanced") }));
      await user.click(screen.getByRole("button", { name: "Read model list" }));
      await screen.findByText(/Model metadata could not be read/);
      expect(screen.getByLabelText("Confirmed context window")).toHaveValue(1_000_000);
      fireEvent.change(screen.getByLabelText("Confirmed context window"), { target: { value: "8192" } });
      await user.click(screen.getByRole("button", { name: "Read model list" }));
      await waitFor(() => expect(read).toHaveBeenCalledTimes(2));
      expect(screen.getByLabelText("Confirmed context window")).toHaveValue(8192);
    } finally {
      read.mockRestore();
    }
  });
  it("replaces previous detected capacity when selecting a model without capacity", async () => {
    setLocale("en");
    const read = vi.spyOn(api, "previewLlmModels").mockResolvedValue({
      ...response,
      models: [
        ...response.models,
        { ...response.models[0]!, id: "model-b", contextWindowTokens: null, outputLimitTokens: null },
      ],
    });
    try {
      render(<Harness />);
      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: t("modelConnection.advanced") }));
      await user.click(screen.getByRole("button", { name: "Read model list" }));
      await waitFor(() => expect(screen.getByLabelText("Confirmed context window")).toHaveValue(32000));
      await user.click(screen.getByLabelText("Fetched models"));
      await user.click(screen.getByRole("option", { name: "model-b" }));
      expect(screen.getByLabelText("Confirmed context window")).toHaveValue(1_000_000);
      expect(screen.getByLabelText("draft")).toHaveTextContent('"mode":"MANUAL"');
    } finally {
      read.mockRestore();
    }
  });
  it("returns to the default when an endpoint change invalidates detected capacity", async () => {
    setLocale("en");
    const read = vi.spyOn(api, "previewLlmModels").mockResolvedValue(response);
    try {
      const view = render(<Harness />);
      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: t("modelConnection.advanced") }));
      await user.click(screen.getByRole("button", { name: "Read model list" }));
      await waitFor(() => expect(screen.getByLabelText("Confirmed context window")).toHaveValue(32000));
      view.rerender(<Harness endpoint="https://different.example/v1" />);
      expect(screen.getByLabelText("Confirmed context window")).toHaveValue(1_000_000);
      expect(screen.getByLabelText("draft")).toHaveTextContent('"previewId":null');
    } finally {
      read.mockRestore();
    }
  });
  it("returns to the default if a refreshed list no longer includes the selected model", async () => {
    setLocale("en");
    const read = vi
      .spyOn(api, "previewLlmModels")
      .mockResolvedValueOnce(response)
      .mockResolvedValueOnce({ ...response, models: [] });
    try {
      render(<Harness />);
      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: t("modelConnection.advanced") }));
      await user.click(screen.getByRole("button", { name: "Read model list" }));
      await waitFor(() => expect(screen.getByLabelText("Confirmed context window")).toHaveValue(32000));
      await user.click(screen.getByRole("button", { name: "Read model list" }));
      await waitFor(() => expect(screen.getByLabelText("Confirmed context window")).toHaveValue(1_000_000));
      expect(screen.getByLabelText("Request output limit")).toHaveValue(2048);
      expect(screen.getByLabelText("draft")).toHaveTextContent('"mode":"MANUAL"');
    } finally {
      read.mockRestore();
    }
  });
  it("ignores a canceled request failure after a new endpoint has already returned metadata", async () => {
    setLocale("en");
    let reject!: (cause: Error) => void;
    const read = vi
      .spyOn(api, "previewLlmModels")
      .mockImplementationOnce(
        () =>
          new Promise((_resolve, fail) => {
            reject = fail;
          }),
      )
      .mockResolvedValue(response);
    try {
      const view = render(<Harness />);
      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: t("modelConnection.advanced") }));
      await user.click(screen.getByRole("button", { name: "Read model list" }));
      view.rerender(<Harness endpoint="https://different.example/v1" />);
      await user.click(screen.getByRole("button", { name: "Read model list" }));
      await waitFor(() => expect(screen.getByLabelText("Confirmed context window")).toHaveValue(32000));
      await act(async () => {
        reject(new Error("old endpoint unavailable"));
      });
      expect(screen.getByLabelText("Confirmed context window")).toHaveValue(32000);
      expect(screen.getByLabelText("draft")).toHaveTextContent('"mode":"AUTO"');
    } finally {
      read.mockRestore();
    }
  });
});
