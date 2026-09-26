import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import type { Artifact } from "../../shared/api/client";
import { MediaCanvasCard } from "./MediaCanvasCard";

// React Flow positions the toolbar; this component test exercises its actual controls and media state.
vi.mock("@xyflow/react", () => ({ Position: { Top: "top" },
  NodeToolbar: ({ children, isVisible }: { children: ReactNode; isVisible?: boolean }) => isVisible !== false ? children : null }));

const artifact: Artifact = { id: "image-1", projectId: "project-1", kind: "IMAGE", title: "湖边",
  currentVersionId: null, currentVersion: null, version: 0,
  createdAt: "2026-09-26T00:00:00Z", updatedAt: "2026-09-26T00:00:00Z" };

function showCard(shownArtifact: Artifact = artifact) {
  const onUpload = vi.fn();
  const onInspect = vi.fn();
  const onEdit = vi.fn();
  render(<QueryClientProvider client={createQueryClient()}>
    <MediaCanvasCard artifact={shownArtifact} selected locked={false} onEdit={onEdit}
      onUpload={onUpload} onInspect={onInspect}>{null}</MediaCanvasCard>
  </QueryClientProvider>);
  return { onUpload, onInspect, onEdit };
}

describe("MediaCanvasCard", () => {
  beforeEach(() => {
    server.use(
      http.get("/api/v1/projects/project-1/artifacts/image-1/draft", () => HttpResponse.json({
        projectId: artifact.projectId, artifactId: artifact.id, prompt: "", displayMode: "DRAFT",
        inputImageVersionId: null, durationSeconds: null, capabilityId: null, version: 0,
        createdAt: artifact.createdAt, updatedAt: artifact.updatedAt,
      })),
      http.get("/api/v1/projects/project-1/artifacts/image-1/run", () => HttpResponse.json([])),
    );
  });

  it("offers upload into the empty card and keeps unsupported extensions visibly disabled", async () => {
    const { onUpload, onInspect } = showCard();
    fireEvent.click(await screen.findByRole("button", { name: "上传图片" }));
    expect(onUpload).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByRole("button", { name: "扩展" }));
    expect(screen.getByRole("button", { name: /高清放大.*未接入/ })).toBeDisabled();
    fireEvent.keyDown(screen.getByRole("button", { name: "扩展" }), { key: "Escape" });
    expect(screen.queryByLabelText("图片扩展功能")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "扩展" })).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "卡片详情" }));
    expect(onInspect).toHaveBeenCalledOnce();
  });

  it("shows a real running task as a loader without a fabricated percentage", async () => {
    server.use(http.get("/api/v1/projects/project-1/artifacts/image-1/run", () => HttpResponse.json([
      { id: "task-1", status: "WAITING_PROVIDER", errorCode: null },
    ])));
    showCard();
    expect(await screen.findByText("正在生成")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "上传图片" })).not.toBeInTheDocument();
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
  });

  it("shows UNKNOWN as a reconciliation state instead of a running animation", async () => {
    server.use(http.get("/api/v1/projects/project-1/artifacts/image-1/run", () => HttpResponse.json([
      { id: "task-1", status: "UNKNOWN", errorCode: "SUBMISSION_UNKNOWN" },
    ])));
    showCard();
    expect(await screen.findByText("结果待核对")).toBeInTheDocument();
    expect(screen.getByText("请在编辑区核对原请求")).toBeInTheDocument();
    expect(screen.queryByText("正在生成")).not.toBeInTheDocument();
  });

  it("keeps the canceled task visible on the draft card", async () => {
    server.use(http.get("/api/v1/projects/project-1/artifacts/image-1/run", () => HttpResponse.json([
      { id: "task-1", status: "CANCELED", errorCode: null },
    ])));
    showCard();
    expect(await screen.findByText("已取消")).toBeInTheDocument();
    expect(screen.queryByText("正在生成")).not.toBeInTheDocument();
  });

  it("opens video generation from an empty surface without offering unsupported upload", async () => {
    const { onEdit } = showCard({ ...artifact, kind: "VIDEO" });
    fireEvent.click(await screen.findByRole("button", { name: "生成视频" }));
    expect(onEdit).toHaveBeenCalledOnce();
    expect(screen.queryByRole("button", { name: /上传/ })).not.toBeInTheDocument();
  });

  it("loads only the video poster until explicit playback, then handles loading, retry and close", async () => {
    const videoArtifact: Artifact = { ...artifact, kind: "VIDEO", currentVersionId: "video-version",
      currentVersion: { id: "video-version", versionNo: 1, schemaVersion: 1,
        content: { assetId: "video-asset", prompt: "A camera movement", providerConfigVersion: 1,
          workflowVersion: "mock-video-v1", sourceTaskId: "video-task", parameters: { mock: true } },
        inputReferences: [], createdByKind: "TASK", runId: null, createdAt: artifact.createdAt } };
    server.use(http.get("/api/v1/projects/project-1/artifacts/image-1/draft", () => HttpResponse.json({
      projectId: artifact.projectId, artifactId: artifact.id, prompt: "湖面慢慢推进", displayMode: "RESULT",
      inputImageVersionId: "image-version", durationSeconds: 5, capabilityId: null, version: 1,
      createdAt: artifact.createdAt, updatedAt: artifact.updatedAt,
    })));
    showCard(videoArtifact);
    const poster = await screen.findByRole("img", { name: "湖边 的视频封面" });
    expect(poster.getAttribute("src")).toContain("/thumbnail");
    expect(screen.queryByLabelText("湖边 的视频")).not.toBeInTheDocument();
    expect(screen.getByText("演示视频")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "播放视频" }));
    const video = screen.getByLabelText("湖边 的视频");
    expect(video).toHaveAttribute("controls");
    expect(video).toHaveAttribute("autoplay");
    expect(video.getAttribute("src")).toContain("/content");
    expect(screen.getByRole("status", { name: "正在加载视频" })).toBeInTheDocument();
    fireEvent.canPlay(video);
    expect(screen.queryByRole("status", { name: "正在加载视频" })).not.toBeInTheDocument();
    fireEvent.waiting(video);
    expect(screen.getByRole("status", { name: "正在加载视频" })).toBeInTheDocument();
    fireEvent.error(video);
    expect(screen.getByRole("alert")).toHaveTextContent("视频播放失败");
    expect(screen.queryByRole("status", { name: "正在加载视频" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "重试播放" }));
    expect(screen.getByLabelText("湖边 的视频")).not.toBe(video);
    fireEvent.click(screen.getByRole("button", { name: "关闭视频预览" }));
    expect(screen.queryByLabelText("湖边 的视频")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "播放视频" })).toBeInTheDocument();
  });

  it("routes an unresolved video task to the editor without resubmitting it", async () => {
    server.use(http.get("/api/v1/projects/project-1/artifacts/image-1/run", () => HttpResponse.json([
      { id: "video-task", status: "UNKNOWN", errorCode: "SUBMISSION_UNKNOWN" },
    ])));
    const { onEdit } = showCard({ ...artifact, kind: "VIDEO" });
    fireEvent.click(await screen.findByRole("button", { name: "查看任务" }));
    expect(onEdit).toHaveBeenCalledOnce();
    expect(screen.queryByRole("button", { name: "生成视频" })).not.toBeInTheDocument();
  });
});
