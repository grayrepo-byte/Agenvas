import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { ApiError, type AgentMediaApproval, type CanvasItem, type MediaDraft, type SaveStoryboardRequest, type Storyboard, type Task } from "../../shared/api/client";
import { clickControl } from "../../test/controls";
import { videoFunctionCapability, videoFunctionsFixture } from "../../test/videoFunctionsFixture";
import { StoryboardWorkbench } from "./StoryboardWorkbench";
import { newStoryboardShot, useStoryboardDraftStore } from "./storyboardDraft";

const api = vi.hoisted(() => ({ getStoryboard: vi.fn(), saveStoryboard: vi.fn(), listStoryboardMediaApprovals: vi.fn(),
  listDirectMediaTasks: vi.fn(), listCanvasMediaVersions: vi.fn(), getMediaDraft: vi.fn(), getMediaSettings: vi.fn(), runMediaDraft: vi.fn(), decideRunMediaApproval: vi.fn() }));
vi.mock("../../shared/api/client", async (importOriginal) => ({ ...await importOriginal<typeof import("../../shared/api/client")>(), ...api }));
const timestamp = "2026-10-23T10:00:00Z";
const item: CanvasItem = { id: "video-node", subjectType: "ARTIFACT", subjectId: "video-artifact", title: "Video production", x: 0, y: 0,
  width: 300, height: 240, zIndex: 0, locked: false, selectedVersionId: "paid-result", version: 1, selectedVersion: null,
  artifact: { id: "video-artifact", projectId: "test-project", kind: "VIDEO", title: "Video production", resourceDefaultVersionId: null,
    version: 1, createdAt: timestamp, updatedAt: timestamp, resourceDefaultVersion: null } };
const mediaDraft: MediaDraft = { projectId: "test-project", canvasItemId: "video-node", prompt: "Synthetic saved prompt", parameters: {}, durationSeconds: 5,
  capabilityId: "capability-1", styleId: null, videoInputMode: "TEXT", mediaInputs: [], mentions: [], displayMode: "DRAFT", version: 4, createdAt: timestamp, updatedAt: timestamp };
function mediaTask(status: Task["status"]): Task {
  return { id: "task-1", projectId: "test-project", runId: null, stepKey: "direct", kind: "VIDEO_GENERATION", status,
    cancelRequested: false, input: { canvasItemId: item.id }, providerRequestId: null, attemptNo: 1,
    nextActionAt: timestamp, version: 0, createdAt: timestamp, updatedAt: timestamp };
}
function storyboard(): Storyboard {
  return { projectId: "test-project", version: 2, shots: Array.from({ length: 3 }, (_, index) => ({ ...newStoryboardShot(index), id: `shot-${index + 1}`,
    title: `Shot ${index + 1}`, dialogue: `Line ${index + 1}`, revision: 1,
    productions: index === 2 ? [{ role: "VIDEO", canvasItemId: item.id, selectedVersionId: "paid-result", reviewedRevision: 1, needsReview: false }] : [] })),
    composition: { canvasItemId: item.id, selectedVersionId: "paid-result", needsReview: false } };
}
const candidateItem: CanvasItem = { ...item, id: "candidate-node", subjectId: "candidate-artifact", title: "Director candidate",
  selectedVersionId: "candidate-result", artifact: { ...item.artifact, id: "candidate-artifact" } };
function linkedApproval(status: AgentMediaApproval["status"], shotId = "shot-1", shotRevision = 1): AgentMediaApproval {
  const storyboard = { shotId, shotCode: shotId === "shot-1" ? "SHOT-01" : "SHOT-03", shotRevision, role: "VIDEO" as const };
  return { id: "linked-approval", projectId: "test-project", runId: "director-run", operationId: "director-operation", status,
    version: status === "PENDING" ? 0 : 2, createdAt: timestamp, expiresAt: timestamp, executionDeadline: null,
    taskIds: status === "PENDING" ? [] : ["director-task"], outputs: [{ kind: "VIDEO", title: "Director video",
      artifactId: candidateItem.artifact.id, canvasItemId: candidateItem.id, draftVersion: 1, preview: {}, storyboard }],
    result: status === "SUCCEEDED" ? { schemaVersion: 1, status, tasks: [{ taskId: "director-task", kind: "VIDEO_GENERATION", status: "SUCCEEDED",
      outputIndex: 0, canvasItemId: candidateItem.id, storyboard, artifactId: candidateItem.artifact.id, artifactVersionId: "candidate-result", selected: true }] } : null };
}
function candidateHistory() {
  return { items: [{ id: "candidate-result", versionNo: 2, schemaVersion: 1, content: { assetId: "candidate-asset", sourceType: "GENERATED" },
    inputReferences: [], createdByKind: "TASK", createdAt: timestamp }] };
}
function show(items: CanvasItem[] = [item]) {
  const client = createQueryClient(); client.setDefaultOptions({ queries: { retry: false, staleTime: 30_000 } });
  const onClose = vi.fn(); const onOpenNode = vi.fn();
  const view = render(<QueryClientProvider client={client}><StoryboardWorkbench projectId="test-project" items={items} onClose={onClose} onOpenNode={onOpenNode} /></QueryClientProvider>);
  return { ...view, client, onClose, onOpenNode };
}
async function chooseThird() { await clickControl(await screen.findByRole("button", { name: /3\. SHOT-03/ })); }
beforeEach(() => {
  vi.resetAllMocks(); useStoryboardDraftStore.setState({ projects: {} });
  api.getStoryboard.mockResolvedValue(storyboard()); api.listStoryboardMediaApprovals.mockResolvedValue({ items: [] });
  api.listDirectMediaTasks.mockResolvedValue([]); api.listCanvasMediaVersions.mockResolvedValue({ items: [] });
  api.getMediaDraft.mockResolvedValue(mediaDraft);
  const settings = videoFunctionsFixture();
  settings.connections[0]!.capabilities = [videoFunctionCapability({ id: "capability-1", name: "Synthetic", supportedVideoInputModes: ["TEXT"], defaultVideoInputMode: "TEXT" })];
  api.getMediaSettings.mockResolvedValue(settings);
  api.runMediaDraft.mockResolvedValue(mediaTask("READY"));
});

describe("storyboard workbench interaction", () => {
  it("returns proposals and archived candidates to an unbound shot without automatically approving or choosing them", async () => {
    api.listStoryboardMediaApprovals.mockResolvedValue({ items: [linkedApproval("PENDING")] });
    api.listCanvasMediaVersions.mockResolvedValue(candidateHistory());
    const view = show([item, candidateItem]);
    const returned = await screen.findByRole("region", { name: "导演提案与结果" });
    expect(within(returned).getByText("等待批准")).toBeVisible();
    expect(api.decideRunMediaApproval).not.toHaveBeenCalled();
    expect(useStoryboardDraftStore.getState().projects["test-project"]?.input.shots[0]?.productions).toEqual([]);
    await act(async () => { view.client.setQueryData(["storyboard-media-approvals", "test-project"], { items: [linkedApproval("SUCCEEDED")] }); });
    const choose = await within(returned).findByRole("button", { name: "选用此结果" });
    await waitFor(() => expect(choose).toBeEnabled());
    expect(useStoryboardDraftStore.getState().projects["test-project"]?.dirty).toBe(false);
    expect(useStoryboardDraftStore.getState().projects["test-project"]?.input.shots[2]?.productions[0]?.selectedVersionId).toBe("paid-result");
    expect(api.saveStoryboard).not.toHaveBeenCalled();
    await clickControl(choose);
    const draft = useStoryboardDraftStore.getState().projects["test-project"]!;
    expect(draft.input.shots[0]?.productions).toEqual([{ role: "VIDEO", canvasItemId: "candidate-node", selectedVersionId: "candidate-result", confirmReviewed: false }]);
    expect(draft.dirty).toBe(true);
    expect(api.saveStoryboard).not.toHaveBeenCalled(); expect(api.decideRunMediaApproval).not.toHaveBeenCalled();
  });

  it("retains a late old-revision result as an explicit draft choice needing review, including after save failure", async () => {
    const base = storyboard(); base.shots[2]!.revision = 2;
    api.getStoryboard.mockResolvedValue(base);
    api.listStoryboardMediaApprovals.mockResolvedValue({ items: [linkedApproval("SUCCEEDED", "shot-3", 1)] });
    api.listCanvasMediaVersions.mockResolvedValue(candidateHistory());
    api.saveStoryboard.mockRejectedValue(new ApiError(409, "STORYBOARD_VERSION_CONFLICT", "Conflict", false));
    show([item, candidateItem]); await chooseThird();
    const returned = screen.getByRole("region", { name: "导演提案与结果" });
    expect(within(returned).getByText("生成依据：SHOT-03 修订 1 · 当前修订 2")).toBeVisible();
    expect(useStoryboardDraftStore.getState().projects["test-project"]?.input.shots[2]?.productions[0]?.selectedVersionId).toBe("paid-result");
    const choose = within(returned).getByRole("button", { name: "选用此结果" });
    await waitFor(() => expect(choose).toBeEnabled()); await clickControl(choose);
    expect(within(document.querySelector(".storyboard-editor-heading") as HTMLElement).getByText("待复核")).toBeVisible();
    await clickControl(screen.getByRole("button", { name: "保存分镜" }));
    await screen.findByText(/分镜已被其他操作更新/);
    const draft = useStoryboardDraftStore.getState().projects["test-project"]!;
    expect(draft.input.shots[2]?.productions[0]).toEqual({ role: "VIDEO", canvasItemId: "candidate-node", selectedVersionId: "candidate-result", confirmReviewed: false });
    expect(draft.dirty).toBe(true);
    expect((api.saveStoryboard.mock.calls[0]?.[1] as SaveStoryboardRequest).shots[0]?.dialogue).toBe("Line 1");
  });

  it("keeps a removed candidate node visible without allowing its result to be chosen", async () => {
    api.listStoryboardMediaApprovals.mockResolvedValue({ items: [linkedApproval("SUCCEEDED")] }); show();
    const returned = await screen.findByRole("region", { name: "导演提案与结果" });
    expect(within(returned).getByText("节点已不在画布，请重新绑定")).toBeVisible();
    expect(within(returned).getByRole("button", { name: "选用此结果" })).toBeDisabled();
    expect(useStoryboardDraftStore.getState().projects["test-project"]?.dirty).toBe(false);
  });
  it("keeps a late archive available after cancellation without changing the immutable approval or selecting it", async () => {
    const approval = linkedApproval("CANCELED"); approval.archivedResults = linkedApproval("SUCCEEDED").result!.tasks;
    api.listStoryboardMediaApprovals.mockResolvedValue({ items: [approval] });
    api.listCanvasMediaVersions.mockResolvedValue(candidateHistory()); show([item, candidateItem]);
    const returned = await screen.findByRole("region", { name: "导演提案与结果" });
    expect(within(returned).getByText("已取消")).toBeVisible();
    await waitFor(() => expect(within(returned).getByRole("button", { name: "选用此结果" })).toBeEnabled());
    expect(approval.result).toBeNull();
    expect(useStoryboardDraftStore.getState().projects["test-project"]?.input.shots[0]?.productions).toEqual([]);
    expect(api.decideRunMediaApproval).not.toHaveBeenCalled(); expect(api.saveStoryboard).not.toHaveBeenCalled();
  });
  it("uses the actual audio node kind for an extra result returned by a video proposal", async () => {
    const approval = linkedApproval("SUCCEEDED", "shot-3");
    approval.result!.tasks[0]!.additionalResults = [{ ordinal: 1, artifactId: "audio-artifact", canvasItemId: "audio-node", artifactVersionId: "audio-result", selected: true }];
    const audioItem: CanvasItem = { ...candidateItem, id: "audio-node", artifact: { ...candidateItem.artifact, id: "audio-artifact", kind: "AUDIO" } };
    api.listStoryboardMediaApprovals.mockResolvedValue({ items: [approval] });
    api.listCanvasMediaVersions.mockResolvedValue({ items: [...candidateHistory().items, { ...candidateHistory().items[0]!, id: "audio-result" }] });
    show([item, candidateItem, audioItem]); await chooseThird();
    const candidate = document.querySelector('[data-result-version="audio-result"]') as HTMLElement;
    expect(within(candidate).getByText("配音 / 音频")).toBeVisible();
    const choose = within(candidate).getByRole("button", { name: "选用此结果" });
    await waitFor(() => expect(choose).toBeEnabled()); await clickControl(choose);
    const productions = useStoryboardDraftStore.getState().projects["test-project"]?.input.shots[2]?.productions;
    expect(productions?.find((production) => production.role === "AUDIO")?.selectedVersionId).toBe("audio-result");
    expect(productions?.find((production) => production.role === "VIDEO")?.selectedVersionId).toBe("paid-result");
  });

  it("requires opening the complete batch before any explicit approval action", async () => {
    const approval = linkedApproval("PENDING");
    approval.outputs.push({ ...approval.outputs[0]!, canvasItemId: "another-node", title: "Another shot output", storyboard: {
      shotId: "shot-2", shotCode: "SHOT-02", shotRevision: 1, role: "VIDEO" } });
    api.listStoryboardMediaApprovals.mockResolvedValue({ items: [approval] }); show();
    await clickControl(await screen.findByRole("button", { name: "查看整批审批" }));
    const card = await screen.findByRole("region", { name: "批准媒体生成 · 2 项" });
    expect(within(card).getByText(/Another shot output/)).toBeVisible();
    expect(within(card).getByRole("button", { name: "批准并生成" })).toBeVisible();
    expect(api.decideRunMediaApproval).not.toHaveBeenCalled();
  });

  it("retains detached result references while showing their missing production node as preparing", async () => {
    const base = storyboard(); base.shots[2]!.productions[0]!.canvasItemId = "removed-node";
    api.getStoryboard.mockResolvedValue(base); show(); await chooseThird();
    expect(within(document.querySelector(".storyboard-editor-heading") as HTMLElement).getByText("待准备")).toBeVisible();
    expect(useStoryboardDraftStore.getState().projects["test-project"]?.input.shots[2]?.productions[0]?.selectedVersionId)
      .toBe("paid-result");
  });

  it("retains edited dialogue and paid results on CAS failure, then asks before discarding", async () => {
    const view = show(); await chooseThird();
    fireEvent.change(screen.getByLabelText("台词 / 旁白"), { target: { value: "Changed third shot" } });
    api.saveStoryboard.mockRejectedValue(new ApiError(409, "VERSION_CONFLICT", "Conflict", false));
    await clickControl(screen.getByRole("button", { name: "保存分镜" }));
    await screen.findByText(/分镜已被其他操作更新/);
    expect(screen.getByLabelText("台词 / 旁白")).toHaveValue("Changed third shot");
    const input = api.saveStoryboard.mock.calls[0]?.[1] as SaveStoryboardRequest;
    expect(input.expectedVersion).toBe(2); expect(input.shots[2]?.productions[0]?.selectedVersionId).toBe("paid-result");
    expect(input.shots[0]?.dialogue).toBe("Line 1");
    await clickControl(screen.getByRole("button", { name: "关闭窗口" }));
    expect(view.onClose).not.toHaveBeenCalled();
    await screen.findByText("放弃未保存的分镜草稿？");
    await clickControl(screen.getByRole("button", { name: "取消" }));
    expect(screen.getByLabelText("台词 / 旁白")).toHaveValue("Changed third shot");
  });
  it("does not overwrite a dirty draft with stale cache when loading latest fails", async () => {
    show(); await chooseThird();
    fireEvent.change(screen.getByLabelText("台词 / 旁白"), { target: { value: "Keep this local draft" } });
    api.getStoryboard.mockRejectedValue(new Error("Read failed"));
    await clickControl(screen.getByRole("button", { name: "载入最新" }));
    await clickControl(screen.getByRole("button", { name: "放弃草稿" }));
    await screen.findByText("Read failed");
    expect(screen.getByLabelText("台词 / 旁白")).toHaveValue("Keep this local draft");
    expect(useStoryboardDraftStore.getState().projects["test-project"]?.dirty).toBe(true);
  });
  it("does not regress the saved CAS base when an older GET finishes after a successful save", async () => {
    const saved = storyboard(); saved.version = 3; saved.shots[2]!.dialogue = "Saved third-shot edit";
    api.saveStoryboard.mockResolvedValue(saved);
    let finishRead: ((board: Storyboard) => void) | undefined;
    const view = show(); await chooseThird();
    api.getStoryboard.mockImplementationOnce(() => new Promise<Storyboard>((resolve) => { finishRead = resolve; }));
    let staleRead: Promise<unknown> | undefined;
    await act(async () => { staleRead = view.client.refetchQueries({ queryKey: ["storyboard", "test-project"] }); });
    fireEvent.change(screen.getByLabelText("台词 / 旁白"), { target: { value: saved.shots[2]!.dialogue } });
    await clickControl(screen.getByRole("button", { name: "保存分镜" }));
    await waitFor(() => expect(useStoryboardDraftStore.getState().projects["test-project"]?.base.version).toBe(3));
    expect(finishRead).toBeDefined();
    await act(async () => { finishRead!(storyboard()); await staleRead; });
    expect(useStoryboardDraftStore.getState().projects["test-project"]?.base.version).toBe(3);
    expect(screen.getByLabelText("台词 / 旁白")).toHaveValue("Saved third-shot edit");
    fireEvent.change(screen.getByLabelText("台词 / 旁白"), { target: { value: "Next explicit edit" } });
    expect(screen.queryByText(/分镜已被其他操作更新/)).not.toBeInTheDocument();
    await clickControl(screen.getByRole("button", { name: "保存分镜" }));
    await waitFor(() => expect(api.saveStoryboard).toHaveBeenCalledTimes(2));
    expect((api.saveStoryboard.mock.calls[1]?.[1] as SaveStoryboardRequest).expectedVersion).toBe(3);
  });
  it("checks the current draft when a stale beforeunload listener outlives a completed save", async () => {
    show(); await chooseThird();
    fireEvent.change(screen.getByLabelText("台词 / 旁白"), { target: { value: "An unsaved edit" } });
    const dirtyEvent = new Event("beforeunload", { cancelable: true }); window.dispatchEvent(dirtyEvent);
    expect(dirtyEvent.defaultPrevented).toBe(true);
    act(() => {
      useStoryboardDraftStore.getState().accept("test-project", storyboard());
      const cleanEvent = new Event("beforeunload", { cancelable: true }); window.dispatchEvent(cleanEvent);
      expect(cleanEvent.defaultPrevented).toBe(false);
    });
  });
  it("clears an earlier review confirmation if dialogue is edited again before saving", async () => {
    const base = storyboard(); base.shots[2]!.productions[0]!.needsReview = true;
    api.getStoryboard.mockResolvedValue(base); show(); await chooseThird();
    const production = document.querySelector(".storyboard-production-grid")!;
    await clickControl(within(production as HTMLElement).getByRole("button", { name: "已复核，保留此结果" }));
    expect(useStoryboardDraftStore.getState().projects["test-project"]?.input.shots[2]?.productions[0]?.confirmReviewed).toBe(true);
    fireEvent.change(screen.getByLabelText("台词 / 旁白"), { target: { value: "A later edit" } });
    expect(useStoryboardDraftStore.getState().projects["test-project"]?.input.shots[2]?.productions[0]?.confirmReviewed).toBe(false);
  });
  it("requires final cost confirmation and generates only the selected video stage", async () => {
    show(); await chooseThird();
    await clickControl(screen.getByRole("checkbox", { name: "选择镜头 SHOT-03" }));
    await clickControl(screen.getByRole("button", { name: "预检选中镜头（1）" }));
    await screen.findByText("确认本次媒体生成");
    expect(api.runMediaDraft).not.toHaveBeenCalled();
    expect(screen.getByText(/未知费用不代表免费/)).toBeVisible();
    await clickControl(screen.getByRole("button", { name: "确认费用并开始生成" }));
    await screen.findByText("本次逐项提交结果");
    expect(api.runMediaDraft).toHaveBeenCalledTimes(1);
    expect(api.runMediaDraft).toHaveBeenCalledWith("test-project", "video-artifact", { canvasItemId: "video-node", expectedDraftVersion: 4 }, expect.any(String));
  });
  it("retains a submission identity after an uncertain response and never automatically retries", async () => {
    api.runMediaDraft.mockRejectedValue(new TypeError("Connection lost")); show(); await chooseThird();
    await clickControl(screen.getByRole("checkbox", { name: "选择镜头 SHOT-03" }));
    await clickControl(screen.getByRole("button", { name: "预检选中镜头（1）" }));
    await clickControl(await screen.findByRole("button", { name: "确认费用并开始生成" }));
    await screen.findByText(/受理结果待核对/); expect(api.runMediaDraft).toHaveBeenCalledTimes(1);
    const key = api.runMediaDraft.mock.calls[0]?.[3];
    await clickControl(screen.getByRole("button", { name: "预检选中镜头（1）" }));
    await clickControl(await screen.findByRole("button", { name: "确认费用并开始生成" }));
    await waitFor(() => expect(api.runMediaDraft).toHaveBeenCalledTimes(2));
    expect(api.runMediaDraft.mock.calls[1]?.[3]).toBe(key);
  });
  it("blocks generation from an UNKNOWN node before showing final confirmation", async () => {
    api.listDirectMediaTasks.mockResolvedValue([mediaTask("UNKNOWN")]);
    show(); await chooseThird(); await clickControl(screen.getByRole("checkbox", { name: "选择镜头 SHOT-03" }));
    await clickControl(screen.getByRole("button", { name: "预检选中镜头（1）" }));
    await screen.findByText(/此节点有进行中或 UNKNOWN 请求/);
    expect(api.runMediaDraft).not.toHaveBeenCalled(); expect(screen.queryByText("确认本次媒体生成")).not.toBeInTheDocument();
  });
  it("rejects JSON imports with shot codes that already exist in the board", async () => {
    show(); await screen.findByLabelText("台词 / 旁白");
    await clickControl(screen.getByRole("button", { name: "导入分镜 JSON" }));
    const jsonInput = screen.getByLabelText("镜头 JSON（示例可直接替换）");
    fireEvent.change(jsonInput, { target: { value: JSON.stringify([{ code: "SHOT-01", dialogue: "Duplicate" }]) } });
    await clickControl(screen.getByRole("button", { name: "校验并预览" }));
    await screen.findByText(/格式无效/); expect(screen.getByRole("button", { name: "追加到分镜草稿" })).toBeDisabled();
  });
});
