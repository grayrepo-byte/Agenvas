import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import type { Artifact, CanvasItem } from "../../shared/api/client";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { CanvasItemTitleEditor } from "./CanvasItemTitleEditor";

const artifact: Artifact = {
  id: "artifact-title-id",
  projectId: "project-title-id",
  kind: "IMAGE",
  title: "资源名称",
  currentVersionId: null,
  currentVersion: null,
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
  locked: false,
  version: 3,
  artifact,
  agent: null,
};

function showTitle() {
  return render(<QueryClientProvider client={createQueryClient()}>
    <CanvasItemTitleEditor projectId="project-title-id" item={item} kindLabel="图片" />
  </QueryClientProvider>);
}

describe("CanvasItemTitleEditor", () => {
  it("edits one node title with CanvasItem CAS and leaves the Artifact name unchanged", async () => {
    let requestBody: unknown;
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "title-token",
      })),
      http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
        requestBody = await request.json();
        return HttpResponse.json({ items: [{ ...item, title: "九宫格自拍", version: 4 }] });
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
    await waitFor(() => expect(requestBody).toEqual({ commands: [{
      type: "UPDATE_TITLE", itemId: item.id, expectedVersion: 3, title: "九宫格自拍",
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
      http.post("/api/v1/projects/:projectId/canvas/commands", () => {
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
    server.use(http.post("/api/v1/projects/:projectId/canvas/commands", () => {
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
});
