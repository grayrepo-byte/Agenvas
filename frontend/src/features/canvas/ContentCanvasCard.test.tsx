import { fireEvent, render, screen, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import type { Artifact } from "../../shared/api/client";
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

function showCard(value: Artifact, selected = true, locked = false) {
  const onEdit = vi.fn();
  const onInspect = vi.fn();
  const result = render(<ContentCanvasCard artifact={value} selected={selected} locked={locked}
    onEdit={onEdit} onInspect={onInspect}><span data-testid="resize-control" /></ContentCanvasCard>);
  return { ...result, onEdit, onInspect };
}

describe("ContentCanvasCard", () => {
  it("keeps the complete long text in a selectable scrolling region", () => {
    const text = `第一段\n${"很长的正文。".repeat(120)}\n最后一句也保留。`;
    showCard(artifact("TEXT", { format: "PLAIN_TEXT", text }));
    const body = screen.getByRole("region", { name: "文字正文" });
    expect(body).toHaveClass("nodrag", "nowheel", "nopan", "content-card-body");
    expect(body).toHaveAttribute("tabindex", "0");
    expect(body.querySelector("p")?.textContent).toBe(text);
    expect(screen.getByText("v2")).toBeInTheDocument();
    expect(screen.getByTestId("resize-control")).toBeInTheDocument();
    expect(screen.queryByText(/artifact-hidden-id|version-hidden-id|project-hidden-id/)).not.toBeInTheDocument();
  });

  it("renders Markdown as safe source text without executing markup", () => {
    const { container } = showCard(artifact("TEXT", { format: "MARKDOWN", text: "# 标题\n<script>private()</script>" }));
    expect(screen.getByRole("heading", { name: "Markdown" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "文字正文" })).toHaveTextContent("<script>private()</script>");
    expect(container.querySelector("script")).toBeNull();
  });

  it("offers a real edit action in the empty text state", () => {
    const empty = { ...artifact("TEXT", { format: "PLAIN_TEXT", text: "" }),
      currentVersion: null, currentVersionId: null };
    const { onEdit } = showCard(empty);
    expect(screen.getByText("写下想法，让创作开始")).toBeInTheDocument();
    expect(screen.getByText("暂无版本")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "编辑文字" }));
    expect(onEdit).toHaveBeenCalledOnce();
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

  it("exposes working toolbar callbacks and a locked presentation without inventing actions", () => {
    const { onEdit, onInspect } = showCard(artifact("TEXT", { format: "PLAIN_TEXT", text: "正文" }), true, true);
    fireEvent.click(screen.getByRole("button", { name: "编辑内容" }));
    fireEvent.click(screen.getByRole("button", { name: "卡片详情" }));
    expect(onEdit).toHaveBeenCalledOnce();
    expect(onInspect).toHaveBeenCalledOnce();
    expect(screen.getByRole("article")).toHaveClass("is-selected");
    expect(screen.getByRole("article")).toHaveAccessibleName("创作内容 · 文字 · 已锁定");
    expect(screen.queryByRole("button", { name: /生成|改写|删除/ })).not.toBeInTheDocument();
  });

  it("hides contextual controls when the card is not selected", () => {
    showCard(artifact("TEXT", { format: "PLAIN_TEXT", text: "可阅读内容" }), false);
    expect(screen.queryByRole("button", { name: "编辑内容" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "卡片详情" })).not.toBeInTheDocument();
    expect(screen.getByText("可阅读内容")).toBeInTheDocument();
  });
});
