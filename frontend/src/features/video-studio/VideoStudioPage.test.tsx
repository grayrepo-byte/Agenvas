import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent, { type UserEvent } from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { VideoStudioCapability, VideoStudioFrame, VideoStudioGeneration } from "../../shared/api/videoStudio";
import { t } from "../../shared/i18n";
import { PageShell } from "../../shared/ui/PageShell";
import { server } from "../../test/server";
import { VideoStudioPage } from "./VideoStudioPage";
import { useVideoStudioDraftStore } from "./videoStudioDraft";

const BASE = "/api/v1/video-studio";
const PROJECT = "private-studio";
const MODEL: VideoStudioCapability = {
  capabilityId: "two-frame-model",
  capabilityVersion: 3,
  name: "Two-frame mock",
  connectionName: "Offline",
  adapterId: "MOCK_VIDEO",
  modes: ["TEXT", "START_END"],
  defaultMode: "TEXT",
  supportsEndFrame: true,
  minDurationSeconds: 3,
  maxDurationSeconds: 12,
  defaultDurationSeconds: 5,
  aspectRatios: ["16:9", "9:16"],
  defaultAspectRatio: "16:9",
};
const FIRST: VideoStudioFrame = {
  projectId: PROJECT,
  artifactId: "first-image",
  assetId: "first-original",
  versionId: "first-version",
  width: 640,
  height: 360,
};
const LAST: VideoStudioFrame = {
  ...FIRST,
  artifactId: "last-image",
  assetId: "last-original",
  versionId: "last-version",
};
const SAVED: VideoStudioGeneration = {
  id: "saved-video",
  projectId: PROJECT,
  capabilityId: MODEL.capabilityId,
  capabilityVersion: MODEL.capabilityVersion,
  mode: "START_END",
  prompt: "Original saved prompt",
  durationSeconds: 8,
  aspectRatio: "9:16",
  startFrame: FIRST,
  endFrame: LAST,
  createdAt: "2026-11-19T08:00:00Z",
  task: {
    id: "saved-task",
    status: "SUCCEEDED",
    errorCode: null,
    version: 5,
    results: [
      {
        assetId: "video-file",
        artifactId: "video-artifact",
        artifactVersionId: "video-version",
        contentType: "video/mp4",
        durationMs: 8_000,
        width: 360,
        height: 640,
      },
    ],
  },
};
const FAILED: VideoStudioGeneration = {
  ...SAVED,
  id: "failed-video",
  prompt: "Other history record",
  task: { ...SAVED.task, id: "failed-task", status: "FAILED", results: [] },
};

function catalog(capabilities: VideoStudioCapability[] = [MODEL]) {
  return HttpResponse.json({
    projectId: PROJECT,
    defaultCapabilityId: capabilities[0]?.capabilityId ?? null,
    capabilities,
  });
}

function page(items: VideoStudioGeneration[], nextCursor: string | null = null) {
  return HttpResponse.json({ items, nextCursor });
}

function show(client = createQueryClient()) {
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/video-studio"]}>
        <Routes>
          <Route path="/video-studio" element={<VideoStudioPage />} />
          <Route path="/projects" element={<PageShell title="Projects">Projects workspace</PageShell>} />
          <Route path="/login" element={<h1>Signed out</h1>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function prompt() {
  return screen.getByRole("textbox", { name: t("imageStudio.prompt") });
}

function generate() {
  return screen.getByRole("button", { name: t("videoStudio.generate") });
}

async function select(user: UserEvent, label: string, choice: string | RegExp) {
  await user.click(await screen.findByRole("combobox", { name: label }));
  await user.click(await screen.findByRole("option", { name: choice }));
}

beforeEach(() => {
  useVideoStudioDraftStore.getState().clear();
  vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "owner", loginName: "creator", role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
    http.get(`${BASE}/context`, () => catalog()),
    http.get(`${BASE}/generations`, () => page([])),
  );
});

afterEach(() => vi.unstubAllGlobals());

it("shows configuration instead of a placeholder model and returns focus after keyboard history navigation", async () => {
  vi.stubGlobal("matchMedia", () => ({ matches: true, addEventListener() {}, removeEventListener() {} }));
  const asked = vi.fn();
  server.use(
    http.get(`${BASE}/context`, () => catalog([])),
    http.get(`${BASE}/generations`, ({ request }) => {
      const before = new URL(request.url).searchParams.get("before");
      asked(before);
      return before ? page([SAVED]) : page([FAILED], "next-record");
    }),
    http.get(`${BASE}/generations/:id`, ({ params }) => HttpResponse.json(params.id === SAVED.id ? SAVED : FAILED)),
  );
  show();
  const user = userEvent.setup();
  expect(await screen.findByText(t("videoStudio.noModels"))).toBeVisible();
  expect(screen.getByRole("link", { name: t("imageStudio.configureModels") })).toHaveAttribute(
    "href",
    "/settings/media",
  );
  await user.type(prompt(), "Keep this draft without a model");
  expect(generate()).toBeDisabled();
  expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  const trigger = screen.getByRole("button", { name: t("imageStudio.expandHistory") });
  trigger.focus();
  await user.keyboard("{Enter}");
  const drawer = within(await screen.findByRole("dialog", { name: t("imageStudio.history") }));
  expect(await drawer.findByText(/Other history record/)).toBeVisible();
  expect(drawer.getByText(new RegExp(t("videoStudio.historyResultCount", { 0: 0 })))).toBeVisible();
  await user.click(drawer.getByRole("button", { name: t("videoStudio.moreHistory") }));
  await user.click(await drawer.findByRole("button", { name: /Original saved prompt/ }));
  await waitFor(() => expect(trigger).toHaveFocus());
  expect(prompt()).toHaveValue(SAVED.prompt);
  expect(asked).toHaveBeenCalledWith("next-record");
  await user.click(screen.getByRole("button", { name: t("imageStudio.undoHistoryPrompt") }));
  expect(prompt()).toHaveValue("Keep this draft without a model");
});

it("validates integer seconds and confirms incompatible frames without discarding a compatible start frame", async () => {
  const startOnly = { ...MODEL, capabilityId: "first-only", supportsEndFrame: false, name: "First-only mock" };
  const submit = vi.fn();
  const pending: VideoStudioGeneration = {
    ...SAVED,
    task: { ...SAVED.task, status: "READY", results: [] },
    endFrame: null,
  };
  const fetchWithMsw = globalThis.fetch;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    if (input !== `${BASE}/frames`) return fetchWithMsw(input, init);
    // jsdom creates a browser FormData; assert it before Node's fetch serializes the multipart body.
    expect(new Headers(init?.headers).has("Content-Type")).toBe(false);
    const file = (init?.body as FormData).get("file");
    const frame = file && typeof file !== "string" && file.name === "last.png" ? LAST : FIRST;
    return HttpResponse.json(frame, { status: 201 });
  });
  server.use(
    http.get(`${BASE}/context`, () => catalog([MODEL, startOnly])),
    http.post(`${BASE}/generations`, async ({ request }) => {
      submit(await request.json());
      return HttpResponse.json(pending, { status: 201 });
    }),
    http.get(`${BASE}/generations/${pending.id}`, () => HttpResponse.json(pending)),
  );
  show();
  const user = userEvent.setup();
  await user.type(await screen.findByRole("textbox", { name: t("imageStudio.prompt") }), "Walk through frame");
  const seconds = await screen.findByRole("spinbutton", { name: t("videoStudio.seconds") });
  fireEvent.change(seconds, { target: { value: "4.5" } });
  expect(generate()).toBeDisabled();
  fireEvent.change(seconds, { target: { value: "7" } });
  expect(generate()).toBeEnabled();
  await select(user, t("videoStudio.mode"), t("videoStudio.frameMode"));
  expect(generate()).toBeDisabled();
  for (const [label, name] of [
    [t("videoStudio.startFrame"), "first.png"],
    [t("videoStudio.endFrame"), "last.png"],
  ] as const) {
    await user.upload(
      screen.getByLabelText(label, { selector: "input" }),
      new File(["synthetic"], name, { type: "image/png" }),
    );
    await screen.findByRole("img", { name: label });
  }
  await select(user, t("common.model"), /First-only mock/);
  const confirm = within(await screen.findByRole("dialog", { name: t("videoStudio.changeSettings") }));
  expect(confirm.getByRole("list")).toHaveTextContent(t("videoStudio.endFrame"));
  expect(confirm.getByRole("list")).not.toHaveTextContent(t("videoStudio.startFrame"));
  await user.click(confirm.getByRole("button", { name: t("videoStudio.confirmChange") }));
  expect(screen.getByRole("img", { name: t("videoStudio.startFrame") })).toBeVisible();
  expect(screen.queryByLabelText(t("videoStudio.endFrame"), { selector: "input" })).not.toBeInTheDocument();
  await select(user, t("videoStudio.mode"), t("videoStudio.textMode"));
  const modeConfirm = within(await screen.findByRole("dialog", { name: t("videoStudio.changeSettings") }));
  expect(modeConfirm.getByRole("list")).toHaveTextContent(t("videoStudio.startFrame"));
  await user.click(modeConfirm.getByRole("button", { name: t("common.cancel") }));
  expect(screen.getByRole("combobox", { name: t("videoStudio.mode") })).toHaveTextContent(t("videoStudio.frameMode"));
  await user.click(generate());
  await waitFor(() =>
    expect(submit).toHaveBeenCalledExactlyOnceWith({
      capabilityId: startOnly.capabilityId,
      capabilityVersion: startOnly.capabilityVersion,
      prompt: "Walk through frame",
      mode: "START_END",
      aspectRatio: "16:9",
      durationSeconds: 7,
      startFrameVersionId: FIRST.versionId,
    }),
  );
});

it("replays only the original frozen command after a lost receipt; provider UNKNOWN is not local uncertainty", async () => {
  const submitted: { key: string | null; body: string }[] = [];
  const accepted: VideoStudioGeneration = {
    ...SAVED,
    id: "received-generation",
    prompt: "Lost receipt command",
    task: { ...SAVED.task, status: "UNKNOWN", errorCode: "PROVIDER_CALL_TIMEOUT", results: [] },
  };
  server.use(
    http.get(`${BASE}/generations`, () => page(submitted.length > 1 ? [accepted, FAILED, SAVED] : [FAILED, SAVED])),
    http.get(`${BASE}/generations/:id`, ({ params }) =>
      HttpResponse.json(params.id === accepted.id ? accepted : params.id === SAVED.id ? SAVED : FAILED),
    ),
    http.post(`${BASE}/generations`, async ({ request }) => {
      submitted.push({ key: request.headers.get("Idempotency-Key"), body: await request.text() });
      return submitted.length === 1 ? HttpResponse.error() : HttpResponse.json(accepted, { status: 201 });
    }),
  );
  show();
  const user = userEvent.setup();
  await user.type(await screen.findByRole("textbox", { name: t("imageStudio.prompt") }), accepted.prompt);
  await waitFor(() => expect(generate()).toBeEnabled());
  await user.click(generate());
  const check = await screen.findByRole("button", { name: t("imageStudio.retryAcceptance") });
  const history = within(screen.getByRole("complementary", { name: t("imageStudio.history") }));
  await user.click(history.getByRole("button", { name: /Original saved prompt/ }));
  expect(prompt()).toHaveValue(accepted.prompt);
  expect(prompt()).toHaveAttribute("readonly");
  expect(screen.getByRole("button", { name: t("videoStudio.restoreSettings") })).toBeDisabled();
  await user.click(screen.getByRole("button", { name: t("imageStudio.refresh") }));
  expect(submitted).toHaveLength(1);
  await user.click(check);
  await waitFor(() => expect(submitted).toHaveLength(2));
  expect(submitted[0]?.key).toBeTruthy();
  expect(submitted[1]).toEqual(submitted[0]);
  expect(JSON.parse(submitted[0]!.body)).toEqual({
    capabilityId: MODEL.capabilityId,
    capabilityVersion: MODEL.capabilityVersion,
    prompt: accepted.prompt,
    mode: "TEXT",
    aspectRatio: "16:9",
    durationSeconds: 5,
  });
  await user.click(await history.findByRole("button", { name: /Lost receipt command/ }));
  const result = within(screen.getByRole("region", { name: t("videoStudio.resultTitle") }));
  expect(await result.findByText(t("tasks.status.unknown"))).toBeVisible();
  expect(screen.queryByRole("button", { name: t("imageStudio.retryAcceptance") })).not.toBeInTheDocument();
  expect(submitted).toHaveLength(2);
});

it("restores the prompt source, not a later preview, only after both original frame files are readable", async () => {
  const newVersion = { ...MODEL, capabilityVersion: MODEL.capabilityVersion + 1 };
  const checked = vi.fn();
  let lastReadable = false;
  const write = vi.fn();
  server.use(
    http.get(`${BASE}/context`, () => catalog([newVersion])),
    http.get(`${BASE}/generations`, () => page([FAILED, SAVED])),
    http.get(`${BASE}/generations/:id`, ({ params }) => HttpResponse.json(params.id === SAVED.id ? SAVED : FAILED)),
    http.head(`/api/v1/projects/${PROJECT}/assets/:id/content`, ({ params }) => {
      checked(params.id);
      return new HttpResponse(null, { status: params.id === FIRST.assetId || lastReadable ? 200 : 404 });
    }),
    http.post(`${BASE}/generations`, () => {
      write();
      return HttpResponse.json(SAVED);
    }),
  );
  show();
  const user = userEvent.setup();
  await user.type(await screen.findByRole("textbox", { name: t("imageStudio.prompt") }), "Untouched settings");
  const history = within(screen.getByRole("complementary", { name: t("imageStudio.history") }));
  await user.click(await history.findByRole("button", { name: /Original saved prompt/ }));
  expect(prompt()).toHaveValue(SAVED.prompt);
  expect(screen.getByRole("combobox", { name: t("videoStudio.mode") })).toHaveTextContent(t("videoStudio.textMode"));
  const other = history.getByRole("button", { name: /Other history record/ });
  await user.click(other);
  expect(other).toHaveAttribute("aria-pressed", "true");
  const restore = screen.getByRole("button", { name: t("videoStudio.restoreSourceSettings") });
  await user.click(restore);
  expect(
    await screen.findByText(t("videoStudio.restoreFrameRolesFailed", { 0: t("videoStudio.endFrame") })),
  ).toBeVisible();
  expect(checked.mock.calls.flat().sort()).toEqual([FIRST.assetId, LAST.assetId].sort());
  expect(screen.getByRole("spinbutton", { name: t("videoStudio.seconds") })).toHaveValue(MODEL.defaultDurationSeconds);
  lastReadable = true;
  await user.click(restore);
  expect(await screen.findByText(t("imageStudio.staleModel"))).toBeVisible();
  expect(checked).toHaveBeenCalledTimes(4);
  expect(prompt()).toHaveValue(SAVED.prompt);
  expect(screen.getByRole("spinbutton", { name: t("videoStudio.seconds") })).toHaveValue(SAVED.durationSeconds);
  expect(screen.getByRole("combobox", { name: t("media.editor.aspectRatio") })).toHaveTextContent(SAVED.aspectRatio);
  expect(screen.getByRole("img", { name: t("videoStudio.startFrame") })).toHaveAttribute(
    "src",
    expect.stringContaining(FIRST.assetId),
  );
  expect(screen.getByRole("img", { name: t("videoStudio.endFrame") })).toHaveAttribute(
    "src",
    expect.stringContaining(LAST.assetId),
  );
  expect(generate()).toBeDisabled();
  expect(other).toHaveAttribute("aria-pressed", "true");
  expect(write).not.toHaveBeenCalled();
});

it("polls a selected generation and lets native extra results be previewed without changing the draft or posting", async () => {
  const pending: VideoStudioGeneration = {
    ...SAVED,
    task: { ...SAVED.task, version: 1, status: "WAITING_PROVIDER", results: [] },
  };
  const extra = {
    ...SAVED.task.results[0]!,
    assetId: "extra-file",
    artifactVersionId: "extra-version",
  };
  let detail = pending;
  const submitted = vi.fn();
  server.use(
    http.get(`${BASE}/generations`, () => page([pending])),
    http.get(`${BASE}/generations/${SAVED.id}`, () => HttpResponse.json(detail)),
    http.post(`${BASE}/generations`, () => {
      submitted();
      return HttpResponse.json(SAVED);
    }),
  );
  show();
  const user = userEvent.setup();
  const waiting = within(await screen.findByRole("complementary", { name: t("imageStudio.history") }));
  await user.click(await waiting.findByRole("button", { name: /Original saved prompt/ }));
  await waitFor(() =>
    expect(
      within(screen.getByRole("region", { name: t("videoStudio.resultTitle") })).getByText(
        t("logs.calls.waitingForExternal"),
      ),
    ).toBeVisible(),
  );
  const result = within(screen.getByRole("region", { name: t("videoStudio.resultTitle") }));
  await user.type(prompt(), "Work in progress");
  detail = { ...SAVED, task: { ...SAVED.task, results: [...SAVED.task.results, extra] } };
  await result.findByRole("combobox", { name: t("videoStudio.resultTitle") }, { timeout: 4_000 });
  await select(user, t("videoStudio.resultTitle"), t("videoStudio.resultPosition", { 0: 2, 1: 2 }));
  expect(result.getByRole("link", { name: t("videoStudio.download") })).toHaveAttribute(
    "href",
    expect.stringContaining(extra.assetId),
  );
  expect(prompt()).toHaveValue("Work in progress");
  expect(submitted).not.toHaveBeenCalled();
});

it("ignores a delayed restore after typing and clears unsaved drafts on sign-out", async () => {
  let release!: () => void;
  const head = new Promise<void>(resolve => {
    release = resolve;
  });
  const checked = vi.fn();
  server.use(
    http.get(`${BASE}/generations`, () => page([SAVED])),
    http.get(`${BASE}/generations/${SAVED.id}`, () => HttpResponse.json(SAVED)),
    http.head(`/api/v1/projects/${PROJECT}/assets/:id/content`, async () => {
      checked();
      await head;
      return new HttpResponse(null);
    }),
    http.post("/api/v1/auth/logout", () => new HttpResponse(null, { status: 204 })),
  );
  const first = show();
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: t("videoStudio.restoreSettings") }));
  try {
    await waitFor(() => expect(checked).toHaveBeenCalledTimes(2));
    await user.type(prompt(), "Newer draft");
  } finally {
    await act(async () => release());
  }
  await waitFor(() => expect(screen.queryByText(t("videoStudio.restorePending"))).not.toBeInTheDocument());
  expect(prompt()).toHaveValue("Newer draft");
  await user.click(screen.getByRole("link", { name: t("ui.pageShell.homeLabel") }));
  expect(await screen.findByRole("link", { name: t("videoStudio.title") })).toHaveAttribute("href", "/video-studio");
  await user.click(screen.getByRole("button", { name: t("ui.pageShell.signOut") }));
  await screen.findByRole("heading", { name: "Signed out" });
  first.unmount();
  show();
  expect(await screen.findByRole("textbox", { name: t("imageStudio.prompt") })).toHaveValue("");
});
