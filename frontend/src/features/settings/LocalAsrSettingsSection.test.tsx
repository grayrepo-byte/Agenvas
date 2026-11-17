import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { createQueryClient } from "../../app/queryClient";
import { t } from "../../shared/i18n";
import { LocalAsrSettingsSection } from "./LocalAsrSettingsSection";
import type { LocalAsrSettings } from "./localAsrApi";
let state: LocalAsrSettings;
let downloads: number;
beforeEach(() => {
  downloads = 0;
  state = {
    mode: "MANAGED",
    enabled: false,
    version: 1,
    state: "MISSING",
    operationId: null,
    downloadedBytes: 0,
    totalBytes: 32152673,
    errorCode: null,
    readiness: "DISABLED",
    modelId: "whisper-tiny-q5_1",
    engineVersion: "1.9.5",
    source: "https://huggingface.co/ggerganov/whisper.cpp",
    sha256: "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
    license: "MIT",
    memory: { admitted: false, usedBytes: null, limitBytes: null },
  };
  server.use(
    http.get("/api/v1/settings/local-asr", () => HttpResponse.json(state)),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "fixture" })),
    http.put("/api/v1/settings/local-asr", async ({ request }) => {
      const body = (await request.json()) as { enabled: boolean; expectedVersion: number };
      expect(body.expectedVersion).toBe(state.version);
      state = {
        ...state,
        enabled: body.enabled,
        version: state.version + 1,
        readiness: body.enabled ? "MODEL_MISSING" : "DISABLED",
      };
      return HttpResponse.json(state);
    }),
    http.post("/api/v1/settings/local-asr/download", () => {
      downloads++;
      state = {
        ...state,
        state: "DOWNLOADING",
        operationId: "fixture-download",
        version: state.version + 1,
        downloadedBytes: 16000000,
      };
      return HttpResponse.json(state);
    }),
    http.post("/api/v1/settings/local-asr/cancel", async ({ request }) => {
      expect(await request.json()).toEqual({ operationId: "fixture-download" });
      state = { ...state, state: "CANCELED", version: state.version + 1 };
      return HttpResponse.json(state);
    }),
  );
});
function mount(client = createQueryClient()) {
  return render(
    <QueryClientProvider client={client}>
      <LocalAsrSettingsSection enabled />
    </QueryClientProvider>,
  );
}
describe("managed local speech recognition settings", () => {
  it("persists consent without downloading, then downloads only on explicit request and can cancel and retry", async () => {
    mount();
    const user = userEvent.setup();
    await screen.findByRole("switch", { name: t("localAsr.enable") });
    expect(await screen.findByRole("button", { name: t("localAsr.download") })).toBeDisabled();
    await user.click(screen.getByRole("switch"));
    await user.click(screen.getByRole("button", { name: t("localAsr.save") }));
    await waitFor(() => expect(state.enabled).toBe(true));
    expect(downloads).toBe(0);
    await user.click(screen.getByRole("button", { name: t("localAsr.download") }));
    await screen.findByRole("progressbar", { name: t("localAsr.progress") });
    expect(downloads).toBe(1);
    await user.click(screen.getByRole("button", { name: t("localAsr.cancel") }));
    await screen.findByText(t("localAsr.canceled"));
    await user.click(screen.getByRole("button", { name: t("localAsr.retryDownload") }));
    await waitFor(() => expect(downloads).toBe(2));
  });
  it("keeps a failed switch draft and refreshes the CAS base before explicit retry", async () => {
    let fail = true;
    server.use(
      http.put("/api/v1/settings/local-asr", async ({ request }) => {
        if (fail) {
          state = { ...state, version: 2 };
          return HttpResponse.json(
            { detail: "Synthetic version conflict" },
            { status: 409, headers: { "Content-Type": "application/problem+json" } },
          );
        }
        expect(await request.json()).toEqual({ enabled: true, expectedVersion: 2 });
        state = { ...state, enabled: true, version: 3 };
        return HttpResponse.json(state);
      }),
    );
    mount();
    const user = userEvent.setup();
    await screen.findByRole("switch");
    await user.click(screen.getByRole("switch"));
    await user.click(screen.getByRole("button", { name: t("localAsr.save") }));
    await screen.findByText(t("localAsr.conflict"));
    expect(screen.getByRole("switch")).toBeChecked();
    fail = false;
    await user.click(screen.getByRole("button", { name: t("common.retry") }));
    await waitFor(() => expect(screen.getByRole("button", { name: t("localAsr.save") })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: t("localAsr.save") }));
    await waitFor(() => expect(state.version).toBe(3));
    expect(downloads).toBe(0);
  });
  it("shows interrupted failures without automatically downloading and allows local checking", async () => {
    state = { ...state, enabled: true, state: "FAILED", errorCode: "INTERRUPTED", readiness: "ENGINE_UNAVAILABLE" };
    server.use(
      http.post("/api/v1/settings/local-asr/check", () => {
        state = { ...state, state: "MISSING", errorCode: null, version: 2 };
        return HttpResponse.json(state);
      }),
    );
    mount();
    await screen.findByText(/INTERRUPTED/);
    expect(downloads).toBe(0);
    await userEvent.setup().click(screen.getByRole("button", { name: t("localAsr.check") }));
    await screen.findByText(t("localAsr.missing"));
    expect(downloads).toBe(0);
  });
  it("does not roll back saved consent when a later list response contains an older revision", async () => {
    const client = createQueryClient();
    const old = { ...state };
    mount(client);
    const user = userEvent.setup();
    await screen.findByRole("button", { name: t("localAsr.download") });
    await user.click(screen.getByRole("switch"));
    await user.click(screen.getByRole("button", { name: t("localAsr.save") }));
    await waitFor(() => expect(state.enabled).toBe(true));
    server.use(http.get("/api/v1/settings/local-asr", () => HttpResponse.json(old)));
    await act(async () => {
      await client.invalidateQueries({ queryKey: ["settings", "local-asr"] });
    });
    expect(screen.getByRole("switch")).toBeChecked();
    expect(screen.getByRole("button", { name: t("localAsr.download") })).toBeEnabled();
    expect(downloads).toBe(0);
  });
  it("keeps operator-provided installations separate from managed downloads and licenses", async () => {
    state = {
      ...state,
      mode: "MANUAL",
      state: "READY",
      sha256: null,
      license: "OPERATOR_PROVIDED",
      modelId: "manual",
      engineVersion: "operator-provided",
    };
    mount();
    await screen.findByText(t("localAsr.manual"));
    expect(screen.queryByRole("button", { name: t("localAsr.download") })).not.toBeInTheDocument();
    expect(screen.queryByText(t("localAsr.quality"))).not.toBeInTheDocument();
    expect(downloads).toBe(0);
  });

  it("observes removal of an operator model even when the settings revision has not changed", async () => {
    state = {
      ...state,
      enabled: true,
      mode: "MANUAL",
      state: "READY",
      readiness: "AVAILABLE",
      sha256: null,
      license: "OPERATOR_PROVIDED",
    };
    const client = createQueryClient();
    mount(client);
    await screen.findByText(t("localAsr.available"));
    state = { ...state, state: "MISSING", readiness: "MODEL_MISSING" };
    await act(async () => {
      await client.invalidateQueries({ queryKey: ["settings", "local-asr"] });
    });
    expect(await screen.findByText(t("localAsr.modelMissing"))).toBeVisible();
    expect(await screen.findByText(t("localAsr.manualMissing"))).toBeVisible();
  });
});
