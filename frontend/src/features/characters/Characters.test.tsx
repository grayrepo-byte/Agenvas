import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { useState } from "react";
import type { CharacterBinding, CharacterIndex, CharacterProfile, MediaDraft, PreviewCharacterBindings, SaveCharacter } from "../../shared/api/client";
import { server } from "../../test/server";
import { changeControl } from "../../test/controls";
import { useMediaDraftSession, useMediaDraftSessionLifecycle } from "../canvas/useMediaDraftSession";
import { useCanvasStore } from "../canvas/canvasStore";
import { CharacterManagerDialog } from "./CharacterManagerDialog";
import { CharacterBindingsDialog } from "./CharacterBindingsDialog";

const PROJECT = "character-project";
const ITEM = "shot-one";
const BASE = `/api/v1/projects/${PROJECT}`;
const NOW = "2026-10-23T00:00:00Z";
beforeEach(() => useCanvasStore.setState({ mediaDraftRecoveries: {} }));
const profile: CharacterProfile = { id: "character-one", projectId: PROJECT, version: 1, archived: false, createdAt: NOW, updatedAt: NOW,
  content: { name: "Mira", description: "Persistent identity", looks: [{ id: "look-one", name: "Travel", clothing: "Blue coat", expression: "Calm", description: "Stable travel look", views: [{ versionId: "front-image", purpose: "Front", description: "Exact image" }, { versionId: "side-image", purpose: "Side", description: "Exact side image" }] }, { id: "look-two", name: "Formal", clothing: "Suit", expression: "Smile", description: "Formal look", views: [] }], voice: { versionId: "voice-one", speakerId: "zh_female_xiaohe_uranus_bigtts", description: "Warm" } } };
const draft: MediaDraft = { projectId: PROJECT, canvasItemId: ITEM, version: 1, createdAt: NOW, updatedAt: NOW, prompt: "A shot", parameters: {}, durationSeconds: 5, capabilityId: "video-cap", videoInputMode: "TEXT", styleId: null, mediaInputs: [], mentions: [], displayMode: "DRAFT" };
function binding(): CharacterBinding { return { canvasItemId: ITEM, version: 1, draftVersion: 2, selectedVersionId: "result-one", snapshots: [{ characterId: profile.id, characterVersion: 1, name: "Mira", description: profile.content.description, look: profile.content.looks[0] ?? null, voice: profile.content.voice, useAppearance: true, useVoice: true }], needsReview: true, reviewReasons: ["NOT_REVIEWED"], contribution: { versionIds: ["front-image"], promptBlock: "Mira in blue coat", speakerId: null }, reviewedAt: null }; }
function client() { return new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } }); }
function baseHandlers(index: CharacterIndex = { characters: [profile], bindings: [] }) {
  server.use(
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get(`${BASE}/artifacts`, () => HttpResponse.json({ items: [] })),
    http.get(`${BASE}/characters`, () => HttpResponse.json(index)),
    http.get(`${BASE}/canvas/items`, () => HttpResponse.json({ items: [{ id: ITEM, selectedVersionId: "result-one" }] })),
    http.get(`${BASE}/canvas-items/${ITEM}/media-draft`, () => HttpResponse.json(draft)),
  );
}
function Harness() {
  const session = useMediaDraftSession(PROJECT, ITEM);
  const [busy, setBusy] = useState(false);
  useMediaDraftSessionLifecycle(session, "VIDEO", { readOnly: false, refreshBlocked: busy, autosaveBlocked: true, closeBlocked: true });
  return <><button onClick={() => session.edit((fields) => ({ ...fields, prompt: "Edited during preview" }))}>Edit draft test</button><p data-testid="current-prompt">{session.state.fields?.prompt}</p><CharacterBindingsDialog projectId={PROJECT} itemId={ITEM} session={session} readOnly={false} onBusy={setBusy} onClose={() => {}} /></>;
}
function mountBindings(index?: CharacterIndex) { baseHandlers(index); const queryClient = client(); render(<QueryClientProvider client={queryClient}><Harness /></QueryClientProvider>); return queryClient; }
function installPreview() {
  const requests: PreviewCharacterBindings[] = [];
  server.use(http.post(`${BASE}/canvas-items/${ITEM}/character-bindings/preview`, async ({ request }) => {
    const input = await request.json() as PreviewCharacterBindings; requests.push(input);
    return HttpResponse.json({ previewHash: "hash-one", expectedDraftVersion: 1, expectedBindingVersion: 0, snapshots: binding().snapshots,
      candidateDraft: { ...draft, prompt: "A shot\nMira in blue coat", version: 2, videoInputMode: "GENERAL_REFERENCE", mediaInputs: [{ versionId: "front-image", artifactId: "image-artifact", role: "REFERENCE", color: "#123456", order: 0, sources: [] }] }, warnings: ["SPEAKER_NOT_APPLIED"], changes: ["PROMPT_UPDATED", "REFERENCES_ADDED", "INPUT_MODE_CHANGED"] });
  })); return requests;
}

describe("project characters", () => {
  it("edits several stable looks and exact references, previews affected shots, and preserves a failed save", async () => {
    baseHandlers();
    const submitted: SaveCharacter[] = [];
    server.use(http.post(`${BASE}/characters/${profile.id}/preview-change`, async ({ request }) => { submitted.push(await request.json() as SaveCharacter); return HttpResponse.json({ characterId: profile.id, expectedVersion: 1, affectedItems: [{ canvasItemId: ITEM, title: "Shot 1", bindingVersion: 2 }] }); }),
      http.put(`${BASE}/characters/${profile.id}`, () => HttpResponse.json({ code: "CHARACTER_CONFLICT", detail: "Concurrent edit", title: "Concurrent edit", retryable: false }, { status: 409, headers: { "Content-Type": "application/problem+json" } })),
      http.get(`${BASE}/artifacts`, () => HttpResponse.json({ items: [] })));
    render(<QueryClientProvider client={client()}><CharacterManagerDialog projectId={PROJECT} onClose={() => {}} /></QueryClientProvider>);
    await userEvent.click(await screen.findByRole("button", { name: "Mira" }));
    expect(screen.getAllByLabelText("造型名称")).toHaveLength(2);
    fireEvent.change(screen.getAllByLabelText("造型名称")[1]!, { target: { value: "Formal revised" } });
    expect(screen.getByText("front-image")).toBeInTheDocument(); expect(screen.getByText("voice-one")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "预览保存影响" }));
    expect(await screen.findByText(/Shot 1/)).toBeVisible();
    expect(submitted[0]?.content.looks[1]?.name).toBe("Formal revised");
    expect(submitted[0]?.content.looks[0]?.views).toHaveLength(2);
    expect(submitted[0]?.content.voice).toMatchObject({ versionId: "voice-one", speakerId: "zh_female_xiaohe_uranus_bigtts" });
    await userEvent.click(screen.getByRole("button", { name: "确认保存" }));
    expect(await screen.findByText("Concurrent edit")).toBeVisible();
    expect(screen.getAllByLabelText("造型名称")[1]).toHaveValue("Formal revised");
    expect(screen.getByText("voice-one")).toBeInTheDocument();
  });

  it("chooses current-project canvas images and audio by exact version without consulting personal assets", async () => {
    baseHandlers({ characters: [], bindings: [] });
    server.use(http.get(`${BASE}/artifacts`, () => HttpResponse.json({ items: [{ id: "image-artifact", title: "Front image", kind: "IMAGE" }, { id: "audio-artifact", title: "Voice sample", kind: "AUDIO" }] })),
      http.get(`${BASE}/canvas/items`, () => HttpResponse.json({ items: [{ selectedVersionId: "front-image" }, { selectedVersionId: "voice-one" }] })),
      http.get(`${BASE}/artifacts/image-artifact/versions`, () => HttpResponse.json({ items: [{ id: "front-image", versionNo: 3, content: { assetId: "front-asset" } }] })),
      http.get(`${BASE}/artifacts/audio-artifact/versions`, () => HttpResponse.json({ items: [{ id: "voice-one", versionNo: 2, content: { assetId: "voice-asset" } }] })));
    render(<QueryClientProvider client={client()}><CharacterManagerDialog projectId={PROJECT} onClose={() => {}} /></QueryClientProvider>);
    await userEvent.click(screen.getByRole("button", { name: "新建角色" }));
    fireEvent.change(screen.getByLabelText("角色名称"), { target: { value: "Mira" } });
    await userEvent.click(screen.getByRole("button", { name: "添加造型" }));
    await userEvent.click(screen.getByRole("button", { name: "添加参考图" }));
    expect(await screen.findByText("Front image · v3")).toBeVisible();
    await userEvent.click(screen.getByRole("button", { name: "选用此版本" }));
    expect(screen.getByText("front-image")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("参考图用途（如正面、侧面）"), { target: { value: "Front" } });
    await userEvent.click(screen.getByRole("button", { name: "选择精确版本声音" }));
    expect(await screen.findByText("Voice sample · v2")).toBeVisible();
    const audio = document.querySelector("audio"); expect(audio?.src).toContain(`/projects/${PROJECT}/assets/voice-asset/content`);
    await userEvent.click(screen.getByRole("button", { name: "选用此版本" }));
    expect(screen.getByText("voice-one")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "选择官方音色" }));
    await userEvent.click(screen.getByRole("button", { name: /^小何 2.0/ }));
    expect(screen.getByText(/zh_female_xiaohe_uranus_bigtts/)).toBeVisible();
  });

  it("previews archive impact before committing and preserves the fixed profile snapshot", async () => {
    const current = binding();
    baseHandlers({ characters: [profile], bindings: [current] });
    let archived = 0;
    server.use(http.get(`${BASE}/artifacts`, () => HttpResponse.json({ items: [] })),
      http.post(`${BASE}/characters/${profile.id}/preview-change`, () => HttpResponse.json({ characterId: profile.id, expectedVersion: 1, affectedItems: [{ canvasItemId: ITEM, title: "Shot 1", bindingVersion: 1 }] })),
      http.post(`${BASE}/characters/${profile.id}/archive`, () => { archived++; return HttpResponse.json({ ...profile, archived: true, version: 2 }); }));
    render(<QueryClientProvider client={client()}><CharacterManagerDialog projectId={PROJECT} onClose={() => {}} /></QueryClientProvider>);
    await userEvent.click(await screen.findByRole("button", { name: "Mira" }));
    await userEvent.click(screen.getByRole("button", { name: "归档角色" }));
    expect(await screen.findByText("归档影响的镜头")).toBeVisible();
    expect(archived).toBe(0);
    await userEvent.click(screen.getByRole("button", { name: "确认保存" }));
    expect(await screen.findByText("角色已归档，已有镜头保留快照并需要复核。")).toBeVisible();
    expect(archived).toBe(1);
    expect(screen.getByLabelText("角色名称")).toHaveValue("Mira");
    expect(screen.getByLabelText("角色名称")).toBeDisabled();
  });

  it("previews look and voice choices, applies only a draft, then reviews an exact result separately", async () => {
    const second = { ...profile, id: "character-two", content: { ...profile.content, name: "Niko", voice: null } };
    const index: CharacterIndex = { characters: [profile, second], bindings: [] };
    baseHandlers(index); const requests = installPreview(); let applyCount = 0; let reviewCount = 0;
    server.use(http.post(`${BASE}/canvas-items/${ITEM}/character-bindings/apply`, () => { applyCount++; index.bindings = [binding()]; return HttpResponse.json({ binding: binding(), draft: { ...draft, prompt: "A shot\nMira in blue coat", version: 2 } }); }),
      http.post(`${BASE}/canvas-items/${ITEM}/character-bindings/review`, async ({ request }) => { reviewCount++; expect(await request.json()).toMatchObject({ expectedBindingVersion: 1, expectedDraftVersion: 2, selectedVersionId: "result-one" }); index.bindings = [{ ...binding(), needsReview: false, reviewReasons: [], reviewedAt: NOW }]; return HttpResponse.json(index.bindings[0]); }));
    render(<QueryClientProvider client={client()}><Harness /></QueryClientProvider>);
    const include = await screen.findAllByRole("checkbox", { name: "关联此角色" });
    await userEvent.click(include[0]!); await userEvent.click(include[1]!);
    await changeControl(screen.getAllByRole("combobox", { name: "造型名称" })[0]!, { target: { value: "look-two" } });
    await userEvent.click(screen.getAllByRole("checkbox", { name: "应用声音与音色" })[0]!);
    await userEvent.click(screen.getByRole("button", { name: "预览实际草稿变化" }));
    expect(await screen.findByLabelText("应用后提示词")).toHaveValue("A shot\nMira in blue coat");
    expect(requests[0]?.selections).toEqual([{ characterId: profile.id, characterVersion: 1, lookId: "look-two", useAppearance: true, useVoice: true }, { characterId: "character-two", characterVersion: 1, lookId: "look-one", useAppearance: true, useVoice: false }]);
    expect(applyCount).toBe(0); expect(reviewCount).toBe(0);
    await userEvent.click(screen.getByRole("button", { name: "明确应用到草稿" }));
    expect(await screen.findByText("已应用到草稿，尚未生成。请运行后独立复核当前选用结果。")).toBeVisible();
    expect(screen.getByTestId("current-prompt")).toHaveTextContent("Mira in blue coat"); expect(reviewCount).toBe(0);
    await userEvent.click(screen.getByRole("button", { name: "确认已检查当前结果" }));
    expect(await screen.findByText("已记录对此版本结果的复核")).toBeVisible(); expect(reviewCount).toBe(1);
  });

  it("expires a server preview when the current draft changes and retains choices", async () => {
    mountBindings(); installPreview();
    await userEvent.click(await screen.findByRole("checkbox", { name: "关联此角色" }));
    await userEvent.click(screen.getByRole("button", { name: "预览实际草稿变化" }));
    await screen.findByLabelText("应用后提示词");
    fireEvent.click(screen.getByRole("button", { name: "Edit draft test", hidden: true }));
    expect(await screen.findByText(/预览已过期/)).toBeVisible();
    expect(screen.getByRole("button", { name: "明确应用到草稿" })).toBeDisabled();
    expect(screen.getByRole("checkbox", { name: "关联此角色" })).toBeChecked();
  });

  it("keeps selections after apply conflicts and requires another preview", async () => {
    mountBindings(); installPreview();
    server.use(http.post(`${BASE}/canvas-items/${ITEM}/character-bindings/apply`, () => HttpResponse.json({ code: "CHARACTER_CONFLICT", detail: "Draft conflict", title: "Draft conflict", retryable: false }, { status: 409, headers: { "Content-Type": "application/problem+json" } })));
    await userEvent.click(await screen.findByRole("checkbox", { name: "关联此角色" }));
    await userEvent.click(screen.getByRole("button", { name: "预览实际草稿变化" })); await screen.findByLabelText("应用后提示词");
    await userEvent.click(screen.getByRole("button", { name: "明确应用到草稿" }));
    expect(await screen.findByText("Draft conflict")).toBeVisible();
    expect(screen.getByRole("checkbox", { name: "关联此角色" })).toBeChecked();
    expect(screen.getByRole("button", { name: "明确应用到草稿" })).toBeDisabled();
    expect(screen.getByTestId("current-prompt")).toHaveTextContent("A shot");
  });

  it("refreshes CAS versions without losing selections or local fields before another preview", async () => {
    mountBindings(); const requests = installPreview();
    let current = draft;
    const saves: Array<{ prompt: string; expectedVersion: number }> = [];
    server.use(http.get(`${BASE}/canvas-items/${ITEM}/media-draft`, () => HttpResponse.json(current)),
      http.put(`${BASE}/canvas-items/${ITEM}/media-draft`, async ({ request }) => { const input = await request.json() as { prompt: string; expectedVersion: number }; saves.push(input); current = { ...draft, ...input, version: input.expectedVersion + 1 }; return HttpResponse.json(current); }),
      http.post(`${BASE}/canvas-items/${ITEM}/character-bindings/apply`, () => HttpResponse.json({ code: "CHARACTER_CONFLICT", detail: "Draft conflict", title: "Draft conflict", retryable: false }, { status: 409, headers: { "Content-Type": "application/problem+json" } })));
    await userEvent.click(await screen.findByRole("checkbox", { name: "关联此角色" }));
    await userEvent.click(screen.getByRole("button", { name: "预览实际草稿变化" })); await screen.findByLabelText("应用后提示词");
    await userEvent.click(screen.getByRole("button", { name: "明确应用到草稿" })); await screen.findByText("Draft conflict");
    current = { ...draft, prompt: "Concurrent server edit", version: 4 };
    await userEvent.click(screen.getByRole("button", { name: "刷新版本并保留编辑" }));
    await waitFor(() => expect(screen.queryByText("Draft conflict")).not.toBeInTheDocument());
    expect(screen.getByTestId("current-prompt")).toHaveTextContent("A shot");
    expect(screen.getByRole("checkbox", { name: "关联此角色" })).toBeChecked();
    await userEvent.click(screen.getByRole("button", { name: "预览实际草稿变化" }));
    await screen.findByLabelText("应用后提示词");
    expect(saves).toEqual([expect.objectContaining({ prompt: "A shot", expectedVersion: 4 })]);
    expect(requests[1]?.expectedDraftVersion).toBe(5);
  });

  it("blocks duplicate writes and closure until server preview settles", async () => {
    mountBindings(); let finish = () => {}; let calls = 0;
    const gate = new Promise<void>((resolve) => { finish = resolve; });
    server.use(http.post(`${BASE}/canvas-items/${ITEM}/character-bindings/preview`, async () => { calls++; await gate; return HttpResponse.json({ previewHash: "hash", expectedDraftVersion: 1, expectedBindingVersion: 0, snapshots: [], candidateDraft: draft, warnings: [], changes: [] }); }));
    await screen.findByRole("checkbox", { name: "关联此角色" });
    const preview = screen.getByRole("button", { name: "预览实际草稿变化" });
    fireEvent.click(preview); fireEvent.click(preview);
    await waitFor(() => expect(calls).toBe(1));
    expect(screen.getByRole("button", { name: "关闭窗口" })).toBeDisabled();
    expect(preview).toBeDisabled();
    await act(async () => finish());
    await waitFor(() => expect(preview).toBeEnabled());
  });
});
