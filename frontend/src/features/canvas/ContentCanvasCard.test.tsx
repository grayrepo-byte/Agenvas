import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Artifact, CanvasItem } from "../../shared/api/client";
import { server } from "../../test/server";
import { ContentCanvasCard } from "./ContentCanvasCard";

vi.mock("@xyflow/react", () => ({ Position: { Top: "top" },
  NodeToolbar: ({ children, isVisible }: { children: ReactNode; isVisible?: boolean }) =>
    isVisible !== false ? children : null }));

const CREATED_AT = "2026-09-26T00:00:00Z";
function artifact(kind: Artifact["kind"], content: NonNullable<Artifact["currentVersion"]>["content"]): Artifact {
  return { id: "artifact-hidden-id", projectId: "project-hidden-id", kind, title: "创作内容",
    currentVersionId: "version-hidden-id", version: 3, createdAt: CREATED_AT, updatedAt: CREATED_AT,
    currentVersion: { id: "version-hidden-id", versionNo: 2, schemaVersion: 2,
      content, createdByKind: "USER", createdAt: CREATED_AT, inputReferences: [] } };
}

function itemFor(value: Artifact): CanvasItem {
  return { id: "item-hidden-id", subjectType: "ARTIFACT", subjectId: value.id,
    title: value.title, x: 20, y: 40, width: 280, height: 180, zIndex: 1,
    groupId: null, locked: false, version: 0, artifact: value, agent: null };
}

function showCard(value: Artifact, selected = true, locked = false) {
  const onEdit = vi.fn();
  const onInspect = vi.fn();
  const result = render(<QueryClientProvider client={createQueryClient()}>
    <ContentCanvasCard artifact={value} item={itemFor(value)} selected={selected} locked={locked}
      onEdit={onEdit} onInspect={onInspect}><span data-testid="resize-control" /></ContentCanvasCard>
  </QueryClientProvider>);
  return { ...result, onEdit, onInspect };
}

describe("ContentCanvasCard", () => {
  it("shows actual text by default and opens direct editing from the toolbar", async () => {
    const text = `第一段\n${"很长的正文。".repeat(120)}\n最后一句也保留。`;
    showCard(artifact("TEXT", { format: "PLAIN_TEXT", text }));
    expect(screen.getByRole("region", { name: "文字正文" }).textContent).toBe(text);
    expect(screen.queryByRole("textbox", { name: "内容" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "编辑内容" }));
    const editor = screen.getByRole("textbox", { name: "内容" });
    expect(editor).toHaveValue(text);
    expect(editor).toHaveAttribute("data-content-editor-focus", "true");
    expect(screen.getByRole("button", { name: "保存新版本" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /v2/ })).toBeInTheDocument();
    expect(screen.getByTestId("resize-control")).toBeInTheDocument();
    expect(screen.queryByText(/artifact-hidden-id|version-hidden-id|project-hidden-id/)).not.toBeInTheDocument();
  });

  it("renders Markdown as safe source text without executing markup", async () => {
    const { container } = showCard(artifact("TEXT", { format: "MARKDOWN", text: "# 标题\n<script>private()</script>" }));
    expect(screen.getByText(/# 标题/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "编辑内容" }));
    expect(screen.getByRole("textbox", { name: "内容" })).toHaveValue("# 标题\n<script>private()</script>");
    expect(screen.getByRole("combobox", { name: "文字格式" })).toHaveValue("MARKDOWN");
    expect(container.querySelector("script")).toBeNull();
  });

  it("does not offer a detached editor for an invalid text artifact without a version", () => {
    const empty = { ...artifact("TEXT", { format: "PLAIN_TEXT", text: "" }),
      currentVersion: null, currentVersionId: null };
    const { onEdit } = showCard(empty);
    expect(screen.getByText("写下想法，让创作开始")).toBeInTheDocument();
    expect(screen.getByText("暂无版本")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "编辑内容" })).toBeDisabled();
    expect(onEdit).not.toHaveBeenCalled();
  });

  it("shows character description and appearance with reference counts instead of IDs", () => {
    const value = artifact("CHARACTER", { name: "小狐狸", description: "好奇的旅人", appearance: "红色围巾与白尾尖",
      referenceVersionIds: ["reference-hidden-id"] });
    value.currentVersion!.inputReferences = [{ versionId: "reference-hidden-id", role: "referenceImage", order: 0, kind: "IMAGE" }];
    const { container } = showCard(value);
    expect(screen.getByRole("heading", { name: "小狐狸" })).toBeInTheDocument();
    expect(screen.getByText("好奇的旅人")).toBeInTheDocument();
    expect(screen.getByText("红色围巾与白尾尖")).toBeInTheDocument();
    expect(screen.getByText("1 个引用")).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/reference-hidden-id|referenceVersionIds|"appearance"/);
  });

  it("presents every scene field without serialized JSON", () => {
    const { container } = showCard(artifact("SCENE", { name: "森林入口", location: "松林边缘", timeOfDay: "黄昏",
      lighting: "暖色侧光", style: "水彩质感", referenceVersionIds: [] }));
    const body = screen.getByRole("region", { name: "场景正文" });
    for (const text of ["地点", "松林边缘", "时间", "黄昏", "光线", "暖色侧光", "风格", "水彩质感"]) {
      expect(within(body).getByText(text)).toBeInTheDocument();
    }
    expect(container.textContent).not.toMatch(/timeOfDay|referenceVersionIds|[{}]/);
  });

  it("shows shot description, action, camera and current integer seconds", () => {
    const { container } = showCard(artifact("SHOT", { order: 1, durationSeconds: 5, description: "穿过森林",
      camera: "缓慢推近", action: "抬头看向远方", characterVersionIds: [], sceneVersionId: "scene-hidden-id" }));
    expect(screen.getByText("穿过森林")).toBeInTheDocument();
    expect(screen.getByText("缓慢推近")).toBeInTheDocument();
    expect(screen.getByText("抬头看向远方")).toBeInTheDocument();
    expect(screen.getByLabelText("镜头时长：5 秒")).toBeInTheDocument();
    expect(container.textContent).not.toContain("scene-hidden-id");
  });

  it("preserves fractional historical shot duration without rounding", () => {
    showCard(artifact("SHOT", { order: 1, durationMs: 1250, description: "旧镜头", camera: "静止",
      action: "回头", characterVersionIds: [], sceneVersionId: "scene-hidden-id" }));
    expect(screen.getByLabelText("镜头时长：历史 1.25 秒")).toBeInTheDocument();
    expect(screen.queryByLabelText("镜头时长：1 秒")).not.toBeInTheDocument();
  });

  it("uses the toolbar action for direct output editing and keeps details available", () => {
    const { onEdit, onInspect } = showCard(artifact("TEXT", { format: "PLAIN_TEXT", text: "正文" }), true, true);
    fireEvent.click(screen.getByRole("button", { name: "编辑内容" }));
    expect(screen.getByRole("textbox", { name: "内容" })).toHaveValue("正文");
    fireEvent.click(screen.getByRole("button", { name: "卡片详情" }));
    expect(onEdit).not.toHaveBeenCalled();
    expect(onInspect).toHaveBeenCalledOnce();
    expect(screen.getByRole("article")).toHaveClass("is-selected");
    expect(screen.getByRole("article")).toHaveAccessibleName("创作内容 · 文字 · 已锁定");
    expect(screen.queryByRole("button", { name: /生成|改写|删除/ })).not.toBeInTheDocument();
  });

  it("hides contextual controls when the card is not selected", () => {
    showCard(artifact("TEXT", { format: "PLAIN_TEXT", text: "可阅读内容" }), false);
    expect(screen.queryByRole("button", { name: "编辑内容" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "卡片详情" })).not.toBeInTheDocument();
    expect(screen.getByRole("region", { name: "文字正文" })).toHaveTextContent("可阅读内容");
  });

  it("saves inline text changes as a new immutable version", async () => {
    let revision: unknown;
    const value = artifact("TEXT", { format: "PLAIN_TEXT", text: "旧正文" });
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/revisions", async ({ request }) => {
        revision = await request.json();
        return HttpResponse.json({ ...value, version: 4, currentVersionId: "version-3",
          currentVersion: { ...value.currentVersion!, id: "version-3", versionNo: 3,
            content: { format: "MARKDOWN", text: "节点内新正文" } } }, { status: 201 });
      }),
    );
    showCard(value);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "编辑内容" }));
    const editor = screen.getByRole("textbox", { name: "内容" });
    await user.clear(editor);
    await user.type(editor, "节点内新正文");
    await user.selectOptions(screen.getByRole("combobox", { name: "文字格式" }), "MARKDOWN");
    await user.click(screen.getByRole("button", { name: "保存新版本" }));
    await waitFor(() => expect(revision).toEqual({ expectedVersion: 3, title: "创作内容",
      content: { format: "MARKDOWN", text: "节点内新正文" } }));
    expect(await screen.findByText("新版本已保存")).toBeVisible();
  });

  it("opens version history from the node tag and switches with artifact CAS", async () => {
    let selection: unknown;
    const value = artifact("TEXT", { format: "PLAIN_TEXT", text: "第二版" });
    server.use(
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/versions", () =>
        HttpResponse.json({ items: [value.currentVersion,
          { ...value.currentVersion!, id: "version-1", versionNo: 1, content: {
            format: "PLAIN_TEXT", text: "第一版" } }] })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/select-version", async ({ request }) => {
        selection = await request.json();
        return HttpResponse.json(value);
      }),
    );
    showCard(value);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /v2/ }));
    const menu = await screen.findByRole("menu", { name: "文字版本" });
    await user.click(within(menu).getByRole("menuitem", { name: /v1/ }));
    await waitFor(() => expect(selection).toEqual({ versionId: "version-1", expectedVersion: 3 }));
  });

  it("keeps a dirty inline draft and blocks version switching", async () => {
    const value = artifact("TEXT", { format: "PLAIN_TEXT", text: "第二版" });
    server.use(http.get("/api/v1/projects/:projectId/artifacts/:artifactId/versions", () =>
      HttpResponse.json({ items: [value.currentVersion,
        { ...value.currentVersion!, id: "version-1", versionNo: 1 }] })));
    showCard(value);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "编辑内容" }));
    await user.type(screen.getByRole("textbox", { name: "内容" }), "，尚未保存");
    await user.click(screen.getByRole("button", { name: /v2/ }));
    const menu = await screen.findByRole("menu", { name: "文字版本" });
    expect(within(menu).getByText("请先保存或退出编辑，再切换版本。")).toBeVisible();
    expect(within(menu).getByRole("menuitem", { name: /v1/ })).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "内容" })).toHaveValue("第二版，尚未保存");
  });
});
