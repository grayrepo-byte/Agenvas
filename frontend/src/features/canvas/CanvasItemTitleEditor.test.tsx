import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import type { Artifact, CanvasEditReplayRequest, CanvasEditRequest, CanvasItem } from "../../shared/api/client";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { CanvasItemTitleEditor } from "./CanvasItemTitleEditor";
import { CanvasEditingContext, useCanvasEditing } from "./useCanvasEditing";

const artifact: Artifact = {
  id: "artifact-title-id",
  projectId: "project-title-id",
  kind: "IMAGE",
  title: "资源名称",
  resourceDefaultVersionId: null,
  resourceDefaultVersion: null,
  version: 7,
  createdAt: "2026-09-27T00:00:00Z",
  updatedAt: "2026-09-27T00:00:00Z",
};
const item: CanvasItem = {
  id: "canvas-title-id",
  subjectType: "ARTIFACT",
  subjectId: artifact.id,
  title: "新图片",
  x: 20,
  y: 40,
  width: 280,
  height: 180,
  zIndex: 1,
  groupId: null,
  locked: false, selectedVersionId: null, selectedVersion: null,
  version: 3,
  artifact,
};

function EditableTitle() {
  const editing = useCanvasEditing("project-title-id");
  return <CanvasEditingContext.Provider value={editing.execute}>
    <CanvasItemTitleEditor projectId="project-title-id" item={item} kindLabel="图片" />
  </CanvasEditingContext.Provider>;
}

function showTitle() {
  return render(<QueryClientProvider client={createQueryClient()}>
    <EditableTitle />
  </QueryClientProvider>);
}

/** Exercise the global receipt retry separately from the title field's own mutation. */
function RetriableTitle() {
  const editing = useCanvasEditing("project-title-id");
  return <CanvasEditingContext.Provider value={editing.execute}>
    <CanvasItemTitleEditor projectId="project-title-id" item={item} kindLabel="图片" />
    <button disabled={!editing.error || editing.busy} onClick={() => { void editing.retry().catch(() => {}); }}>重试本次操作</button>
    <button disabled={!editing.canUndo} onClick={() => { void editing.replay("UNDO").catch(() => {}); }}>撤销标题操作</button>
  </CanvasEditingContext.Provider>;
}

function showRetriableTitle() {
  const requests: CanvasEditRequest[] = [];
  const replays: CanvasEditReplayRequest[] = [];
  server.use(
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "title-token" })),
    http.post("/api/v1/projects/:projectId/canvas/edits", async ({ request }) => {
      const input = await request.json() as CanvasEditRequest;
      requests.push(input);
      if (requests.length === 1) return HttpResponse.json({ code: "TEMPORARILY_UNAVAILABLE", detail: "Synthetic unavailable" },
        { status: 503, headers: { "Content-Type": "application/problem+json" } });
      return HttpResponse.json({ items: [{ ...item, title: input.itemChanges![0]!.title, version: requests.length + 2 }], groups: [],
        receipt: { id: input.commandId, operation: input.operation, revision: 0, state: "APPLIED" } });
    }),
    http.post("/api/v1/projects/:projectId/canvas/edits/:receiptId/replay", async ({ request, params }) => {
      const input = await request.json() as CanvasEditReplayRequest;
      replays.push(input);
      return HttpResponse.json({ items: [{ ...item, version: 5 }], groups: [],
        receipt: { id: params.receiptId, operation: "DISPLAY", revision: input.expectedRevision + 1, state: "UNDONE" } });
    }),
  );
  render(<QueryClientProvider client={createQueryClient()}><RetriableTitle /></QueryClientProvider>);
  return { requests, replays };
}

describe("CanvasItemTitleEditor", () => {
  it("edits one node title with CanvasItem CAS and leaves the Artifact name unchanged", async () => {
    let requestBody: unknown;
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "title-token",
      })),
      http.post("/api/v1/projects/:projectId/canvas/edits", async ({ request }) => {
        const input = await request.json() as CanvasEditRequest;
        requestBody = input;
        return HttpResponse.json({ items: [{ ...item, title: "九宫格自拍", version: 4 }], groups: [],
          receipt: { id: input.commandId, operation: input.operation, revision: 0, state: "APPLIED" } });
      }),
    );
    showTitle();
    const user = userEvent.setup();
    await user.dblClick(screen.getByRole("button", { name: "重命名图片：新图片" }));
    const editor = screen.getByRole("textbox", { name: "图片标题" });
    expect(editor).toHaveFocus();
    expect(editor).toHaveClass("artifact-card-title-input");
    await user.clear(editor);
    await user.type(editor, "  九宫格自拍  {Enter}");
    await waitFor(() => expect(requestBody).toEqual({ commandId: expect.any(String), operation: "DISPLAY", itemChanges: [{
      itemId: item.id, expectedVersion: 3, title: "九宫格自拍", x: 20, y: 40, width: 280, height: 180,
      zIndex: 1, groupId: null, locked: false,
    }] }));
    expect(await screen.findByRole("button", { name: "重命名图片：九宫格自拍" })).toBeVisible();
    expect(artifact.title).toBe("资源名称");
  });

  it("keeps invalid and failed drafts in edit mode while Escape cancels without saving", async () => {
    let requests = 0;
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "title-token",
      })),
      http.post("/api/v1/projects/:projectId/canvas/edits", () => {
        requests += 1;
        return HttpResponse.json({ title: "冲突", status: 409,
          code: "CANVAS_VERSION_CONFLICT", detail: "卡片已被其他操作更新。",
          traceId: "trace", retryable: false }, { status: 409,
          headers: { "Content-Type": "application/problem+json" } });
      }),
    );
    showTitle();
    const user = userEvent.setup();
    await user.dblClick(screen.getByRole("button", { name: "重命名图片：新图片" }));
    const editor = screen.getByRole("textbox", { name: "图片标题" });
    await user.clear(editor);
    fireEvent.blur(editor);
    expect(await screen.findByRole("alert")).toHaveTextContent("标题不能为空");
    expect(editor).toHaveValue("");
    expect(requests).toBe(0);

    await user.type(editor, "保留的草稿{Enter}");
    expect(await screen.findByRole("alert")).toHaveTextContent("标题保存失败，请重试");
    expect(editor).toHaveValue("保留的草稿");
    expect(requests).toBe(1);
    await user.keyboard("{Escape}");
    expect(screen.getByRole("button", { name: "重命名图片：新图片" })).toBeVisible();
    expect(requests).toBe(1);
  });

  it("supports keyboard editing without sending unchanged titles", async () => {
    let requests = 0;
    server.use(http.post("/api/v1/projects/:projectId/canvas/edits", () => {
      requests += 1;
      return HttpResponse.json({ items: [item] });
    }));
    showTitle();
    const button = screen.getByRole("button", { name: "重命名图片：新图片" });
    button.focus();
    await userEvent.keyboard("{F2}");
    await userEvent.keyboard("{Enter}");
    expect(screen.getByRole("button", { name: "重命名图片：新图片" })).toBeVisible();
    expect(requests).toBe(0);
  });

  it("acknowledges a globally retried title, closes its draft, and records one undo receipt", async () => {
    const { requests, replays } = showRetriableTitle();
    const user = userEvent.setup();
    await user.dblClick(screen.getByRole("button", { name: "重命名图片：新图片" }));
    const editor = screen.getByRole("textbox", { name: "图片标题" });
    await user.clear(editor);
    await user.type(editor, "重试后的标题{Enter}");
    expect(await screen.findByRole("alert")).toHaveTextContent("标题保存失败，请重试");
    expect(editor).toHaveValue("重试后的标题");
    expect(screen.getByRole("button", { name: "撤销标题操作" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "重试本次操作" }));
    expect(await screen.findByRole("button", { name: "重命名图片：重试后的标题" })).toBeVisible();
    expect(screen.queryByRole("textbox", { name: "图片标题" })).not.toBeInTheDocument();
    expect(requests).toHaveLength(2);
    expect(requests[1]).toEqual(requests[0]);
    const undo = screen.getByRole("button", { name: "撤销标题操作" });
    expect(undo).toBeEnabled();
    await user.click(undo);
    await waitFor(() => expect(undo).toBeDisabled());
    expect(replays).toHaveLength(1);
    expect(replays[0]).toMatchObject({ direction: "UNDO", expectedRevision: 0 });
  });

  it("retains a newer title draft after globally retrying the frozen title and advances the next save's CAS", async () => {
    const { requests } = showRetriableTitle();
    const user = userEvent.setup();
    await user.dblClick(screen.getByRole("button", { name: "重命名图片：新图片" }));
    const editor = screen.getByRole("textbox", { name: "图片标题" });
    await user.clear(editor);
    await user.type(editor, "原先提交的标题{Enter}");
    expect(await screen.findByRole("alert")).toHaveTextContent("标题保存失败，请重试");
    await user.clear(editor);
    await user.type(editor, "失败后继续编辑的新标题");
    await user.click(screen.getByRole("button", { name: "重试本次操作" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "撤销标题操作" })).toBeEnabled());
    expect(screen.getByRole("textbox", { name: "图片标题" })).toHaveValue("失败后继续编辑的新标题");
    expect(requests).toHaveLength(2);
    expect(requests[1]).toEqual(requests[0]);
    expect(requests[1]?.itemChanges?.[0]).toMatchObject({ expectedVersion: 3, title: "原先提交的标题" });
    await user.click(screen.getByRole("textbox", { name: "图片标题" }));
    await user.keyboard("{Enter}");
    expect(await screen.findByRole("button", { name: "重命名图片：失败后继续编辑的新标题" })).toBeVisible();
    expect(requests).toHaveLength(3);
    expect(requests[2]?.itemChanges?.[0]).toMatchObject({ expectedVersion: 4, title: "失败后继续编辑的新标题" });
    expect(requests[2]?.commandId).not.toBe(requests[1]?.commandId);
  });
});
