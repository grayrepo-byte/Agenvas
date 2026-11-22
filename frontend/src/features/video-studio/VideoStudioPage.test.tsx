import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent, { type UserEvent } from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type {
  VideoStudioCapability,
  VideoStudioFrame,
  VideoStudioGeneration,
  VideoStudioReferenceAsset,
} from "../../shared/api/videoStudio";
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
  requiresEndFrame: false,
  minDurationSeconds: 3,
  maxDurationSeconds: 12,
  defaultDurationSeconds: 5,
  aspectRatios: ["16:9", "9:16"],
  defaultAspectRatio: "16:9",
  videoResolutions: [],
  defaultVideoResolution: null,
  referenceLimits: { image: 0, video: 0, audio: 0 },
  fields: null,
  defaultDynamicValues: {},
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
  videoResolution: null,
  dynamicValues: {},
  references: [],
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
  expect(screen.getByRole("combobox", { name: t("media.editor.aspectRatio") })).toHaveValue("9:16");
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

it("preserves mixed exact versions across prompt-only selection, HEAD-checked restore, and a lost receipt", async () => {
  const model: VideoStudioCapability = {
    ...MODEL,
    modes: ["TEXT", "GENERAL_REFERENCE"],
    defaultMode: "GENERAL_REFERENCE",
    minDurationSeconds: null,
    maxDurationSeconds: null,
    defaultDurationSeconds: null,
    aspectRatios: [],
    defaultAspectRatio: null,
    videoResolutions: ["480p", "720p"],
    defaultVideoResolution: "480p",
    referenceLimits: { image: 1, video: 1, audio: 1 },
  };
  const refs: VideoStudioReferenceAsset[] = (["IMAGE", "VIDEO", "AUDIO"] as const).map((kind, index) => ({
    ...FIRST,
    assetId: `mixed-asset-${kind}`,
    artifactId: `mixed-artifact-${kind}`,
    versionId: `mixed-version-${kind}`,
    kind,
    fieldKey: null,
    durationMs: index ? 1_000 : null,
    contentType: ["image/png", "video/mp4", "audio/mpeg"][index]!,
  }));
  const saved: VideoStudioGeneration = {
    ...SAVED,
    mode: "GENERAL_REFERENCE",
    aspectRatio: null,
    durationSeconds: null,
    videoResolution: "720p",
    startFrame: null,
    endFrame: null,
    references: refs,
  };
  const uploaded = { ...refs[0]!, versionId: "new-image-version", assetId: "new-image-asset" };
  const head = vi.fn();
  const submitted: { key: string | null; body: string }[] = [];
  const fetchWithMsw = globalThis.fetch;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    if (input === `${BASE}/references/IMAGE`) return HttpResponse.json(uploaded, { status: 201 });
    return fetchWithMsw(input, init);
  });
  server.use(
    http.get(`${BASE}/context`, () => catalog([model])),
    http.get(`${BASE}/generations`, () => page([saved])),
    http.get(`${BASE}/generations/${saved.id}`, () => HttpResponse.json(saved)),
    http.head(`/api/v1/projects/${PROJECT}/assets/:id/content`, ({ params }) => {
      head(params.id);
      return new HttpResponse(null);
    }),
    http.post(`${BASE}/generations`, async ({ request }) => {
      submitted.push({ key: request.headers.get("Idempotency-Key"), body: await request.text() });
      return submitted.length === 1 ? HttpResponse.error() : HttpResponse.json(saved, { status: 201 });
    }),
  );
  show();
  const user = userEvent.setup();
  const upload = await screen.findByLabelText(`${t("media.runningHub.uploadNamed", { 0: t("common.image") })} (0/1)`);
  await user.upload(upload, new File(["synthetic"], "replacement.png", { type: "image/png" }));
  const image = t("templates.numberedImage", { 0: 1 });
  expect(await screen.findByRole("img", { name: image })).toHaveAttribute(
    "src",
    expect.stringContaining(uploaded.assetId),
  );
  expect(upload).toBeDisabled();
  await user.click(await screen.findByRole("button", { name: /Original saved prompt/ }));
  expect(prompt()).toHaveValue(saved.prompt);
  expect(screen.getByRole("img", { name: image })).toHaveAttribute("src", expect.stringContaining(uploaded.assetId));
  expect(screen.getByRole("combobox", { name: t("media.editor.resolution") })).toHaveTextContent("480p");
  await user.click(screen.getByRole("button", { name: t("videoStudio.restoreSourceSettings") }));
  await waitFor(() =>
    expect(screen.getByRole("img", { name: image })).toHaveAttribute("src", expect.stringContaining(refs[0]!.assetId)),
  );
  expect(head.mock.calls.flat().sort()).toEqual(refs.map(ref => ref.assetId).sort());
  const list = screen.getByRole("list", { name: t("common.mixedReference") });
  expect(within(list).getAllByRole("listitem")).toHaveLength(3);
  expect(list.querySelector("video")).toHaveAttribute("preload", "none");
  expect(list.querySelector("audio")).toHaveAttribute("preload", "none");
  expect(screen.queryByRole("spinbutton", { name: t("videoStudio.seconds") })).not.toBeInTheDocument();
  expect(list.querySelector("video")).toHaveAttribute("src", expect.stringContaining(refs[1]!.assetId));
  await user.click(generate());
  const retry = await screen.findByRole("button", { name: t("imageStudio.retryAcceptance") });
  expect(within(list).getByRole("button", { name: t("videoStudio.removeFrame", { 0: image }) })).toBeDisabled();
  await user.click(retry);
  await waitFor(() => expect(submitted).toHaveLength(2));
  expect(submitted[0]!.key).toBeTruthy();
  expect(submitted[1]).toEqual(submitted[0]);
  expect(JSON.parse(submitted[0]!.body)).toEqual({
    capabilityId: model.capabilityId,
    capabilityVersion: model.capabilityVersion,
    mode: "GENERAL_REFERENCE",
    prompt: saved.prompt,
    durationSeconds: null,
    videoResolution: "720p",
    references: refs.map(({ kind, versionId }) => ({ kind, versionId })),
  });
});

it("binds a named reference to its workflow slot and confirms when a parameter would drop it", async () => {
  const model: VideoStudioCapability = {
    ...MODEL,
    capabilityId: "named-workflow-model",
    name: "Published named workflow",
    modes: ["GENERAL_REFERENCE"],
    defaultMode: "GENERAL_REFERENCE",
    minDurationSeconds: null,
    maxDurationSeconds: null,
    defaultDurationSeconds: null,
    aspectRatios: [],
    defaultAspectRatio: null,
    referenceLimits: { image: 0, video: 1, audio: 0 },
    defaultDynamicValues: { useClip: true },
    fields: [
      {
        key: "useClip",
        label: "Use source clip",
        source: "PARAMETER",
        type: "BOOLEAN",
        defaultValue: true,
        required: false,
        advanced: false,
      },
      {
        key: "clip",
        label: "Source clip",
        source: "PARAMETER",
        type: "VIDEO",
        required: true,
        advanced: false,
        enabledWhen: { field: "useClip", value: true },
      },
    ],
  };
  const clip: VideoStudioReferenceAsset = {
    ...FIRST,
    kind: "VIDEO",
    fieldKey: null,
    durationMs: 1_000,
    contentType: "video/mp4",
  };
  const posted = vi.fn();
  const uploaded = vi.fn();
  const fetchWithMsw = globalThis.fetch;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    if (input === `${BASE}/references/VIDEO`) {
      uploaded((init?.body as FormData).get("file"));
      return HttpResponse.json(clip, { status: 201 });
    }
    return fetchWithMsw(input, init);
  });
  server.use(
    http.get(`${BASE}/context`, () => catalog([MODEL, model])),
    http.get(`${BASE}/generations/${SAVED.id}`, () => HttpResponse.json(SAVED)),
    http.post(`${BASE}/generations`, async ({ request }) => {
      posted(await request.json());
      return HttpResponse.json(SAVED, { status: 201 });
    }),
  );
  show();
  const user = userEvent.setup();
  await select(user, t("common.model"), /Published named workflow/);
  expect(await screen.findByText(t("media.runningHub.requiredField", { 0: "Source clip" }))).toBeVisible();
  expect(generate()).toBeDisabled();
  await user.upload(
    screen.getByLabelText(t("media.runningHub.uploadNamed", { 0: "Source clip" })),
    new File(["synthetic"], "clip.mp4", { type: "video/mp4" }),
  );
  await waitFor(() => expect(uploaded).toHaveBeenCalledOnce());
  expect(await screen.findByRole("list", { name: t("common.mixedReference") })).toHaveTextContent("Source clip");
  await waitFor(() => expect(generate()).toBeEnabled());
  await select(user, "Use source clip", t("common.disabled"));
  const dialog = within(await screen.findByRole("dialog", { name: t("videoStudio.changeSettings") }));
  expect(dialog.getByRole("list")).toHaveTextContent("Source clip");
  await user.click(dialog.getByRole("button", { name: t("common.cancel") }));
  expect(screen.getByRole("combobox", { name: "Use source clip" })).toHaveTextContent(t("common.enabled"));
  expect(within(screen.getByRole("list", { name: t("common.mixedReference") })).getByText("Source clip")).toBeVisible();
  await user.click(generate());
  await waitFor(() =>
    expect(posted).toHaveBeenCalledExactlyOnceWith({
      capabilityId: model.capabilityId,
      capabilityVersion: model.capabilityVersion,
      mode: "GENERAL_REFERENCE",
      prompt: "",
      durationSeconds: null,
      dynamicValues: { useClip: true },
      references: [{ kind: "VIDEO", versionId: clip.versionId, fieldKey: "clip" }],
    }),
  );
});
