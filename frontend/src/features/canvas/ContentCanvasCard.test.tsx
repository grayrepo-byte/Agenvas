import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent,render,screen,waitFor,within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http,HttpResponse } from "msw";
import { readFileSync } from "node:fs";
import type { ReactNode } from "react";
import { afterAll,beforeAll,describe,expect,it,vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Artifact,CanvasItem } from "../../shared/api/client";
import { clickControl,selectValue } from "../../test/controls";
import { server } from "../../test/server";
import { ContentCanvasCard } from "./ContentCanvasCard";

vi.mock("@xyflow/react", () => ({ Position: { Top: "top" },
  NodeToolbar: ({ children, isVisible }: { children: ReactNode; isVisible?: boolean }) =>
    isVisible !== false ? children : null }));

const CREATED_AT = "2026-09-26T00:00:00Z";
const contentCanvasCardStyles = readFileSync("src/features/canvas/ContentCanvasCard.css", "utf8");
const artifactCardFrameStyles = readFileSync("src/features/canvas/ArtifactCardFrame.css", "utf8");
const style = document.createElement("style");
beforeAll(() => {
  style.textContent = `${artifactCardFrameStyles}\n${contentCanvasCardStyles}`;
  document.head.append(style);
});
afterAll(() => style.remove());

function artifact(kind: Artifact["kind"], content: NonNullable<Artifact["resourceDefaultVersion"]>["content"]): Artifact {
  return { id: "artifact-hidden-id", projectId: "project-hidden-id", kind, title: "创作内容",
    resourceDefaultVersionId: "version-hidden-id", version: 3, createdAt: CREATED_AT, updatedAt: CREATED_AT,
    resourceDefaultVersion: { id: "version-hidden-id", versionNo: 2, schemaVersion: 2,
      content, createdByKind: "USER", createdAt: CREATED_AT, inputReferences: [] } };
}

function itemFor(value: Artifact): CanvasItem {
  return { id: "item-hidden-id", subjectType: "ARTIFACT", subjectId: value.id,
    title: value.title, x: 20, y: 40, width: 280, height: 180, zIndex: 1,
    groupId: null, locked: false, selectedVersionId: null, selectedVersion: null, version: 0, artifact: value, agent: null };
}

function showCard(value: Artifact, selected = true, locked = false) {
  const onInspect = vi.fn();
  const onCanvasDoubleClick = vi.fn();
  const client = createQueryClient();
  const card = (next: Artifact) => <QueryClientProvider client={client}>
    <div onDoubleClick={onCanvasDoubleClick}>
      <ContentCanvasCard artifact={next} item={itemFor(next)} selected={selected} locked={locked}
        onInspect={onInspect}><span data-testid="resize-control" /></ContentCanvasCard>
    </div>
  </QueryClientProvider>;
  const result = render(card(value));
  return { ...result, onInspect, onCanvasDoubleClick, rerenderArtifact: (next: Artifact) => result.rerender(card(next)) };
}

describe("ContentCanvasCard", () => {
  it("keeps unsaved text when closing editing and lets the user cancel leaving", async () => {
    showCard(artifact("TEXT", { format: "PLAIN_TEXT", text: "已有正文" }));
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "编辑内容" }));
    await user.type(screen.getByRole("textbox", { name: "内容" }), "，未保存输入");
    await user.click(screen.getByRole("button", { name: "退出内容编辑" }));
    expect(await screen.findByRole("dialog", { name: "有未保存的修改" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "继续编辑" }));
    expect(screen.getByRole("textbox", { name: "内容" })).toHaveValue("已有正文，未保存输入");
  });
  it("retains the text and pinned CAS version when save-and-exit conflicts", async () => {
    let revision: unknown;
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/revisions", async ({ request }) => {
        revision = await request.json();
        return HttpResponse.json({ code: "VERSION_CONFLICT", detail: "版本冲突" }, { status: 409 });
      }),
    );
    showCard(artifact("TEXT", { format: "PLAIN_TEXT", text: "原正文" }));
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "编辑内容" }));
    await user.type(screen.getByRole("textbox", { name: "内容" }), "，保留草稿");
    expect(screen.getByRole("status")).toHaveTextContent("有未保存的修改");
    await user.click(screen.getByRole("button", { name: "退出内容编辑" }));
    await user.click(screen.getByRole("button", { name: "保存并退出" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("内容有冲突，修改未保存");
    expect(screen.getByRole("textbox", { name: "内容" })).toHaveValue("原正文，保留草稿");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(revision).toEqual({ expectedVersion: 3, title: "创作内容",
      content: { format: "PLAIN_TEXT", text: "原正文，保留草稿" } });
  });

  it("places the version in the toolbar without a text tag and focuses content on every edit click", async () => {
    showCard(artifact("TEXT", { format: "PLAIN_TEXT", text: "已有正文" }));
    const toolbar = screen.getByLabelText("文字卡片操作");
    const version = within(toolbar).getByRole("button", { name: "版本 v2" });
    const versionStyle = getComputedStyle(version);
    const detailsStyle = getComputedStyle(within(toolbar).getByRole("button", { name: "卡片详情" }));
    for (const property of ["height", "padding", "background-color", "color", "font-size", "border-radius"]) {
      expect(versionStyle.getPropertyValue(property)).toBe(detailsStyle.getPropertyValue(property));
    }
    expect(screen.getByRole("article").querySelector(".content-card-chip")).toBeNull();
    const user = userEvent.setup();
    await user.click(within(toolbar).getByRole("button", { name: "编辑内容" }));
    const editor = screen.getByRole("textbox", { name: "内容" });
    expect(editor).toHaveFocus();
    await user.type(editor, "，本地输入");
    await user.click(within(toolbar).getByRole("button", { name: "卡片详情" }));
    await user.click(within(toolbar).getByRole("button", { name: "编辑内容" }));
    expect(editor).toHaveFocus();
    expect(editor).toHaveValue("已有正文，本地输入");
    expect(within(toolbar).getByRole("button", { name: /v2/ })).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /v2/ })).toHaveLength(1);
    expect(screen.getByRole("article").querySelector(".content-card-chip")).toBeNull();
  });

  it("keeps the toolbar version pinned to an unsaved draft until explicit reload", async () => {
    const value = artifact("TEXT", { format: "PLAIN_TEXT", text: "第二版" });
    const latest: Artifact = { ...value, version: 4, resourceDefaultVersionId: "version-3",
      resourceDefaultVersion: { ...value.resourceDefaultVersion!, id: "version-3", versionNo: 3,
        content: { format: "PLAIN_TEXT", text: "远端第三版" } } };
    server.use(http.get("/api/v1/projects/:projectId/artifacts/:artifactId", () => HttpResponse.json(latest)));
    const card = showCard(value);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "编辑内容" }));
    await user.type(screen.getByRole("textbox", { name: "内容" }), "，本地修改");
    card.rerenderArtifact(latest);
    expect(screen.getByRole("textbox", { name: "内容" })).toHaveValue("第二版，本地修改");
    expect(within(screen.getByLabelText("文字卡片操作")).getByRole("button", { name: /v2/ })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "载入最新版本" }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "内容" })).toHaveValue("远端第三版"));
    expect(within(screen.getByLabelText("文字卡片操作")).getByRole("button", { name: /v3/ })).toBeInTheDocument();
  });

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

  it.each([true, false])("edits on content double-click when selected=%s and focuses the existing text", async (selected) => {
    const { onCanvasDoubleClick, onInspect } = showCard(artifact("TEXT", { format: "PLAIN_TEXT", text: "双击编辑正文" }), selected);
    const user = userEvent.setup();
    await user.click(screen.getByText("双击编辑正文"));
    expect(screen.queryByRole("textbox", { name: "内容" })).not.toBeInTheDocument();
    await user.dblClick(screen.getByText("双击编辑正文"));
    const editor = screen.getByRole("textbox", { name: "内容" });
    expect(editor).toHaveValue("双击编辑正文");
    expect(editor).toHaveFocus();
    expect(onCanvasDoubleClick).not.toHaveBeenCalled();
    expect(onInspect).not.toHaveBeenCalled();
    await user.type(editor, "，未保存");
    await user.dblClick(editor);
    expect(editor).toHaveValue("双击编辑正文，未保存");
    await user.click(screen.getByRole("button", { name: "退出内容编辑" }));
    expect(await screen.findByRole("dialog", { name: "有未保存的修改" })).toBeVisible();
  });

  it("enters editing from an empty text card on double-click", async () => {
    showCard(artifact("TEXT", { format: "PLAIN_TEXT", text: "" }), false);
    await userEvent.dblClick(screen.getByText("写下想法，让创作开始"));
    const editor = screen.getByRole("textbox", { name: "内容" });
    expect(editor).toHaveValue("");
    expect(editor).toHaveFocus();
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
      resourceDefaultVersion: null, resourceDefaultVersionId: null };
    showCard(empty);
    expect(screen.getByText("写下想法，让创作开始")).toBeInTheDocument();
    expect(screen.getByText("暂无版本")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "编辑内容" })).toBeDisabled();
    fireEvent.doubleClick(screen.getByText("写下想法，让创作开始"));
    expect(screen.queryByRole("textbox", { name: "内容" })).not.toBeInTheDocument();
  });

  it("uses the toolbar action for direct output editing and keeps details available", async () => {
    const { onInspect } = showCard(artifact("TEXT", { format: "PLAIN_TEXT", text: "正文" }), true, true);
    await clickControl(screen.getByRole("button", { name: "编辑内容" }));
    expect(screen.getByRole("textbox", { name: "内容" })).toHaveValue("正文");
    await clickControl(screen.getByRole("button", { name: "卡片详情" }));
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
        return HttpResponse.json({ ...value, version: 4, resourceDefaultVersionId: "version-3",
          resourceDefaultVersion: { ...value.resourceDefaultVersion!, id: "version-3", versionNo: 3,
            content: { format: "MARKDOWN", text: "节点内新正文" } } }, { status: 201 });
      }),
    );
    showCard(value);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "编辑内容" }));
    const editor = screen.getByRole("textbox", { name: "内容" });
    await user.clear(editor);
    await user.type(editor, "节点内新正文");
    await selectValue(screen.getByRole("combobox", { name: "文字格式" }), "MARKDOWN");
    await user.click(screen.getByRole("button", { name: "保存新版本" }));
    await waitFor(() => expect(revision).toEqual({ expectedVersion: 3, title: "创作内容",
      content: { format: "MARKDOWN", text: "节点内新正文" } }));
    expect(await screen.findByText("新版本已保存")).toBeVisible();
    expect(within(screen.getByLabelText("文字卡片操作")).getByRole("button", { name: /v3/ })).toBeVisible();
  });

  it("opens version history from the toolbar and switches with artifact CAS", async () => {
    let selection: unknown;
    const value = artifact("TEXT", { format: "PLAIN_TEXT", text: "第二版" });
    server.use(
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/versions", () =>
        HttpResponse.json({ items: [value.resourceDefaultVersion,
          { ...value.resourceDefaultVersion!, id: "version-1", versionNo: 1, content: {
            format: "PLAIN_TEXT", text: "第一版" } }] })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/set-default-version", async ({ request }) => {
        selection = await request.json();
        return HttpResponse.json(value);
      }),
    );
    showCard(value);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /v2/ }));
    const menu = await screen.findByRole("menu", { name: "文字版本" });
    expect(menu.closest(".content-card-sources")).toBeNull(); // Portal avoids clipping by the card.
    await user.click(within(menu).getByRole("menuitem", { name: /v1/ }));
    await waitFor(() => expect(selection).toEqual({ versionId: "version-1", expectedVersion: 3 }));
  });

  it("keeps a dirty inline draft and blocks version switching", async () => {
    const value = artifact("TEXT", { format: "PLAIN_TEXT", text: "第二版" });
    server.use(http.get("/api/v1/projects/:projectId/artifacts/:artifactId/versions", () =>
      HttpResponse.json({ items: [value.resourceDefaultVersion,
        { ...value.resourceDefaultVersion!, id: "version-1", versionNo: 1 }] })));
    showCard(value);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "编辑内容" }));
    await user.type(screen.getByRole("textbox", { name: "内容" }), "，尚未保存");
    await user.click(screen.getByRole("button", { name: /v2/ }));
    const menu = await screen.findByRole("menu", { name: "文字版本" });
    expect(within(menu).getByText("请先保存或退出编辑，再切换版本。")).toBeVisible();
    expect(within(menu).getByRole("menuitem", { name: /v1/ })).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("textbox", { name: "内容" })).toHaveValue("第二版，尚未保存");
  });
});
