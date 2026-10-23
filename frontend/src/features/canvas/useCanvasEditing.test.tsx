import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { PropsWithChildren } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Canvas, CanvasEditReplayRequest, CanvasEditRequest, CanvasEditResponse, CanvasItem } from "../../shared/api/client";
import { server } from "../../test/server";
import { useCanvasStore } from "./canvasStore";
import { canvasItemPresentation, useCanvasEditing } from "./useCanvasEditing";
import { useCanvasLayoutSave } from "./useCanvasLayoutSave";

const PROJECT = "editing-project";
const URL = `/api/v1/projects/${PROJECT}/canvas/edits`;
const item: CanvasItem = { id: "item", subjectType: "ARTIFACT", subjectId: "artifact", title: "Title",
  x: 20, y: 40, width: 280, height: 180, zIndex: 0, groupId: null, locked: false,
  selectedVersionId: null, selectedVersion: null, version: 3,
  artifact: { id: "artifact", projectId: PROJECT, kind: "TEXT", title: "Title", version: 1,
    resourceDefaultVersionId: null, resourceDefaultVersion: null,
    createdAt: "2026-10-23T00:00:00Z", updatedAt: "2026-10-23T00:00:00Z" } };
const response = (request: CanvasEditRequest, items: CanvasItem[] = [item]): CanvasEditResponse => ({ items, groups: [],
  receipt: { id: request.commandId, operation: request.operation, revision: 0, state: "APPLIED" } });
const display = () => ({ operation: "DISPLAY" as const, itemChanges: [{ ...canvasItemPresentation(item), title: "Renamed" }] });

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const wrapper = ({ children }: PropsWithChildren) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  return { client, wrapper };
}
function gate() {
  let release = () => {};
  const promise = new Promise<void>((resolve) => { release = resolve; });
  return { promise, release };
}

beforeEach(() => {
  useCanvasStore.setState({ drafts: {}, selectedIds: [], mediaDraftRecoveries: {}, saveState: "saved" });
  server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })));
});

describe("canvas editing receipts over HTTP", () => {
  it("retries a committed duplicate with the same command and target IDs and records it once", async () => {
    const requests: CanvasEditRequest[] = [];
    const replayRequests: CanvasEditReplayRequest[] = [];
    let accepted: CanvasEditResponse | undefined;
    server.use(
      http.post(URL, async ({ request }) => {
        const input = await request.json() as CanvasEditRequest;
        requests.push(input);
        accepted ??= response(input, [item, { ...item, id: input.copies![0]!.targetItemId }]);
        return requests.length === 1 ? HttpResponse.error() : HttpResponse.json(accepted);
      }),
      http.post(`${URL}/:receiptId/replay`, async ({ request }) => {
        const input = await request.json() as CanvasEditReplayRequest;
        replayRequests.push(input);
        return HttpResponse.json({ ...accepted!, receipt: { ...accepted!.receipt, revision: 1, state: "UNDONE" } });
      }),
    );
    const { wrapper } = setup();
    const { result } = renderHook(() => useCanvasEditing(PROJECT), { wrapper });
    const onAccepted = vi.fn();
    await act(async () => {
      await expect(result.current.execute({ operation: "DUPLICATE", copies: [{ sourceItemId: item.id,
        targetItemId: "stable-target", expectedSourceVersion: 3, expectedSourceContentVersion: null,
        x: 52, y: 72, width: 280, height: 180, zIndex: 1, groupId: null }] }, onAccepted)).rejects.toThrow();
    });
    expect(result.current.error).toBeInstanceOf(Error);
    expect(result.current.canUndo).toBe(false);
    expect(onAccepted).not.toHaveBeenCalled();
    await act(async () => { await result.current.retry(); });
    expect(requests).toHaveLength(2);
    expect(requests[1]).toEqual(requests[0]);
    expect(requests[1]?.copies?.[0]?.targetItemId).toBe("stable-target");
    expect(onAccepted).toHaveBeenCalledTimes(1);
    expect(result.current.canUndo).toBe(true);
    await act(async () => { await result.current.retry(); });
    expect(requests).toHaveLength(2);
    await act(async () => { await result.current.replay("UNDO"); });
    expect(result.current.canUndo).toBe(false);
    expect(result.current.canRedo).toBe(true);
    await act(async () => { await result.current.replay("UNDO"); });
    expect(replayRequests).toHaveLength(1);
  });

  it("keeps a conflicted undo on its stack, retries the identical intent, and uses the acknowledged revision for redo", async () => {
    const replays: { receiptId: string; input: CanvasEditReplayRequest }[] = [];
    let receiptId = "";
    server.use(
      http.post(URL, async ({ request }) => {
        const input = await request.json() as CanvasEditRequest;
        receiptId = input.commandId;
        return HttpResponse.json(response(input));
      }),
      http.post(`${URL}/:receiptId/replay`, async ({ request, params }) => {
        const input = await request.json() as CanvasEditReplayRequest;
        replays.push({ receiptId: String(params.receiptId), input });
        if (replays.length === 1) return HttpResponse.json({ code: "CANVAS_EDIT_CONFLICT", detail: "Changed elsewhere" },
          { status: 409, headers: { "Content-Type": "application/problem+json" } });
        return HttpResponse.json({ items: [item], groups: [], receipt: { id: params.receiptId,
          operation: "DISPLAY", revision: input.expectedRevision + 1, state: input.direction === "UNDO" ? "UNDONE" : "APPLIED" } });
      }),
    );
    const { wrapper } = setup();
    const { result } = renderHook(() => useCanvasEditing(PROJECT), { wrapper });
    await act(async () => { await result.current.execute(display()); });
    await act(async () => { await expect(result.current.replay("UNDO")).rejects.toThrow("Changed elsewhere"); });
    expect(result.current.canUndo).toBe(false); // The error gates the unchanged stack until retry/discard.
    expect(result.current.canRedo).toBe(false);
    expect(useCanvasStore.getState().saveState).toBe("conflict");
    await act(async () => { await result.current.retry(); });
    expect(replays[1]).toEqual(replays[0]);
    expect(replays[1]?.input.expectedRevision).toBe(0);
    expect(result.current.canRedo).toBe(true);
    await act(async () => { await result.current.replay("REDO"); });
    expect(replays[2]).toMatchObject({ receiptId, input: { direction: "REDO", expectedRevision: 1 } });
    expect(replays[2]?.input.commandId).not.toBe(replays[1]?.input.commandId);
    await act(async () => { await result.current.replay("UNDO"); });
    expect(replays[3]?.input).toMatchObject({ direction: "UNDO", expectedRevision: 2 });
    await act(async () => { await result.current.execute(display()); });
    expect(result.current.canRedo).toBe(false);
    expect(result.current.canUndo).toBe(true);
  });

  it("gates new commands after a failure until the pending intent is explicitly discarded", async () => {
    const requests: CanvasEditRequest[] = [];
    server.use(http.post(URL, async ({ request }) => {
      const input = await request.json() as CanvasEditRequest;
      requests.push(input);
      return requests.length === 1 ? HttpResponse.error() : HttpResponse.json(response(input));
    }));
    const { wrapper } = setup();
    const { result } = renderHook(() => useCanvasEditing(PROJECT), { wrapper });
    await act(async () => { await expect(result.current.execute(display())).rejects.toThrow(); });
    await act(async () => { await expect(result.current.execute(display())).rejects.toThrow("画布编辑失败"); });
    expect(requests).toHaveLength(1);
    act(() => result.current.discardPending());
    await act(async () => { await result.current.execute(display()); });
    expect(requests).toHaveLength(2);
    expect(requests[1]?.commandId).not.toBe(requests[0]?.commandId);
    expect(result.current.canUndo).toBe(true);
  });

  it("acknowledges only the submitted layout draft while keeping a later equal-valued edit and newer cache version", async () => {
    const pending = gate();
    const requests: CanvasEditRequest[] = [];
    server.use(http.post(URL, async ({ request }) => {
      const input = await request.json() as CanvasEditRequest;
      requests.push(input);
      await pending.promise;
      return HttpResponse.json(response(input, [{ ...item, ...input.itemChanges![0], version: 4 }]));
    }));
    const { wrapper, client } = setup();
    const { result } = renderHook(() => {
      const editing = useCanvasEditing(PROJECT);
      const layout = useCanvasLayoutSave(PROJECT, (current, patch) => ({ width: patch.width ?? current.width,
        height: patch.height ?? current.height }), editing.execute);
      return { editing, layout };
    }, { wrapper });
    const patch = { x: 100, y: 120 };
    useCanvasStore.getState().updateDraft(item.id, patch);
    const original = useCanvasStore.getState().drafts[item.id];
    let saving: Promise<Canvas> | undefined;
    act(() => { saving = result.current.layout.mutateAsync({ item, patch }); });
    try {
      await waitFor(() => expect(requests).toHaveLength(1));
      act(() => {
        useCanvasStore.getState().updateDraft(item.id, patch);
        client.setQueryData<Canvas>(["canvas", PROJECT], { items: [{ ...item, x: 900, version: 5 }] });
      });
      const later = useCanvasStore.getState().drafts[item.id];
      expect(later).toEqual(original);
      expect(later).not.toBe(original);
      await act(async () => { pending.release(); await saving; });
      expect(useCanvasStore.getState().drafts[item.id]).toBe(later);
      expect(useCanvasStore.getState().saveState).not.toBe("saved");
      expect(client.getQueryData<Canvas>(["canvas", PROJECT])?.items[0]).toMatchObject({ version: 5, x: 900 });
      expect(result.current.editing.canUndo).toBe(true);
    } finally { pending.release(); await saving; }
  });
});
