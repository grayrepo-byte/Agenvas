import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import type { Artifact, CanvasItem } from "../../shared/api/client";
import { MediaCanvasCard } from "./MediaCanvasCard";

// React Flow positions the toolbar; this component test exercises its actual controls and media state.
vi.mock("@xyflow/react", () => ({ Position: { Top: "top" },
  NodeToolbar: ({ children, isVisible, className }: {
    children: ReactNode; isVisible?: boolean; className?: string;
  }) => isVisible !== false ? <div className={`react-flow__node-toolbar ${className ?? ""}`}>{children}</div> : null }));

const artifact: Artifact = { id: "image-1", projectId: "project-1", kind: "IMAGE", title: "湖边",
  resourceDefaultVersionId: null, resourceDefaultVersion: null, version: 0,
  createdAt: "2026-09-26T00:00:00Z", updatedAt: "2026-09-26T00:00:00Z" };

function itemFor(shownArtifact: Artifact): CanvasItem {
  return { id: "item-1", subjectType: "ARTIFACT", subjectId: shownArtifact.id,
    title: shownArtifact.title, x: 20, y: 40, width: 280, height: 180, zIndex: 1,
    groupId: null, locked: false, selectedVersionId: shownArtifact.resourceDefaultVersionId,
    selectedVersion: shownArtifact.resourceDefaultVersion, version: 0, artifact: shownArtifact, agent: null };
}

function showCard(shownArtifact: Artifact = artifact) {
  const onUpload = vi.fn();
  const onInspect = vi.fn();
  const onEdit = vi.fn();
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  render(<QueryClientProvider client={client}>
    <MediaCanvasCard artifact={shownArtifact} item={itemFor(shownArtifact)} selected locked={false} onEdit={onEdit}
      onUpload={onUpload} onInspect={onInspect}>{null}</MediaCanvasCard>
  </QueryClientProvider>);
  return { onUpload, onInspect, onEdit };
}

describe("MediaCanvasCard", () => {
  beforeEach(() => {
    server.use(
      http.get("/api/v1/projects/project-1/canvas-items/item-1/media-draft", () => HttpResponse.json({
        projectId: artifact.projectId, canvasItemId: "item-1", prompt: "", displayMode: "DRAFT",
        parameters: {}, videoInputMode: null, imageInputs: [], mentions: [],
        durationSeconds: null, capabilityId: null, version: 0,
        createdAt: artifact.createdAt, updatedAt: artifact.updatedAt,
      })),
      http.get("/api/v1/projects/project-1/artifacts/image-1/run", () => HttpResponse.json([])),
    );
  });

  it("offers upload into the empty card and keeps image operations disabled until a source exists", async () => {
    const { onUpload, onInspect } = showCard();
    const pickerClick = vi.spyOn(HTMLInputElement.prototype, "click").mockImplementation(() => undefined);
    fireEvent.click(await screen.findByRole("button", { name: "上传图片" }));
    expect(pickerClick).toHaveBeenCalledOnce();
    pickerClick.mockRestore();
    const file = new File(["image"], "reference.webp", { type: "image/webp" });
    fireEvent.change(screen.getByLabelText("选择要上传的图片"), { target: { files: [file] } });
    expect(onUpload).toHaveBeenCalledWith(file);
    fireEvent.click(screen.getByRole("button", { name: "扩展" }));
    expect(screen.getByLabelText("图片扩展功能").closest(".react-flow__node-toolbar"))
      .toHaveClass("artifact-card-toolbar-raised");
    expect(screen.getByRole("button", { name: /高清放大.*本地/ })).toBeDisabled();
    fireEvent.keyDown(screen.getByRole("button", { name: "扩展" }), { key: "Escape" });
    expect(screen.queryByLabelText("图片扩展功能")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "扩展" })).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "卡片详情" }));
    expect(onInspect).toHaveBeenCalledOnce();
  });

  it("submits depth extraction against the exact visible image version", async () => {
    let request: unknown;
    server.use(
      http.get("/api/v1/projects/project-1/canvas-items/item-1/media-draft", () => HttpResponse.json({
        projectId: artifact.projectId, canvasItemId: "item-1", displayMode: "RESULT", version: 0,
      })),
      http.get("/api/v1/projects/project-1/assets/image-asset", () => HttpResponse.json({
        id: "image-asset", width: 1200, height: 800,
      })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({
        connections: [], defaults: [],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test",
      })),
      http.post("/api/v1/projects/project-1/artifacts/image-1/image-operations", async ({ request: incoming }) => {
        request = await incoming.json();
        return HttpResponse.json({ id: "depth-task", status: "READY", errorCode: null });
      }),
    );
    showCard({ ...artifact, resourceDefaultVersionId: "image-version", resourceDefaultVersion: {
      id: "image-version", versionNo: 1, schemaVersion: 1,
      content: { sourceType: "UPLOAD", assetId: "image-asset" }, inputReferences: [],
      createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
    } });

    fireEvent.click(await screen.findByRole("button", { name: "深度提取" }));
    await waitFor(() => expect(request).toEqual(expect.objectContaining({
      canvasItemId: "item-1", sourceVersionId: "image-version",
      expectedCanvasItemVersion: 0, operation: "DEPTH_MAP", parameters: {},
    })));
  });

  it("opens direct-manipulation cropping and submits the selected rectangle", async () => {
    let request: unknown;
    server.use(
      http.get("/api/v1/projects/project-1/canvas-items/item-1/media-draft", () => HttpResponse.json({
        projectId: artifact.projectId, canvasItemId: "item-1", displayMode: "RESULT", version: 0,
      })),
      http.get("/api/v1/projects/project-1/assets/image-asset", () => HttpResponse.json({
        id: "image-asset", width: 1200, height: 800,
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test",
      })),
      http.post("/api/v1/projects/project-1/artifacts/image-1/image-operations", async ({ request: incoming }) => {
        request = await incoming.json();
        return HttpResponse.json({ id: "crop-task", status: "READY", errorCode: null });
      }),
    );
    showCard({ ...artifact, resourceDefaultVersionId: "image-version", resourceDefaultVersion: {
      id: "image-version", versionNo: 1, schemaVersion: 1,
      content: { sourceType: "UPLOAD", assetId: "image-asset" }, inputReferences: [],
      createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
    } });

    fireEvent.click(await screen.findByRole("button", { name: "扩展" }));
    fireEvent.click(screen.getByRole("button", { name: /裁剪.*本地/ }));
    expect(screen.getByRole("dialog", { name: "裁剪图片" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "当前图片裁剪预览" })).toHaveAttribute("src",
      "/api/v1/projects/project-1/assets/image-asset/content");
    fireEvent.change(screen.getByRole("combobox", { name: "裁剪比例" }), {
      target: { value: "1:1" },
    });
    fireEvent.click(screen.getByRole("button", { name: "确定" }));

    await waitFor(() => expect(request).toEqual(expect.objectContaining({
      canvasItemId: "item-1", sourceVersionId: "image-version", operation: "CROP",
      parameters: { x: 0.22, y: 0.08, width: 0.56, height: 0.84 },
    })));
  });

  it("opens the dedicated relight design and submits its AI lighting controls", async () => {
    let request: unknown;
    server.use(
      http.get("/api/v1/projects/project-1/canvas-items/item-1/media-draft", () => HttpResponse.json({
        projectId: artifact.projectId, canvasItemId: "item-1", displayMode: "RESULT", version: 0,
      })),
      http.get("/api/v1/projects/project-1/assets/image-asset", () => HttpResponse.json({
        id: "image-asset", width: 1200, height: 800,
      })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({
        connections: [{ id: "openai", enabled: true, platform: "OPENAI", capabilities: [{
          id: "relight-capability", name: "GPT Image", enabled: true,
          kind: "IMAGE_GENERATION", maxReferenceImages: 1,
        }] }], defaults: [],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test",
      })),
      http.post("/api/v1/projects/project-1/artifacts/image-1/image-operations", async ({ request: incoming }) => {
        request = await incoming.json();
        return HttpResponse.json({ id: "relight-task", status: "READY", errorCode: null });
      }),
    );
    showCard({ ...artifact, resourceDefaultVersionId: "image-version", resourceDefaultVersion: {
      id: "image-version", versionNo: 1, schemaVersion: 1,
      content: { sourceType: "UPLOAD", assetId: "image-asset" }, inputReferences: [],
      createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
    } });

    fireEvent.click(await screen.findByRole("button", { name: "扩展" }));
    fireEvent.click(screen.getByRole("button", { name: /重新打光.*AI/ }));
    expect(screen.getByRole("dialog", { name: "打光" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "当前图片打光预览" })).toHaveAttribute("src",
      "/api/v1/projects/project-1/assets/image-asset/content");
    fireEvent.click(screen.getByRole("button", { name: "月光" }));
    fireEvent.change(screen.getByRole("textbox", { name: "补充打光描述" }), {
      target: { value: "让人物轮廓更清晰" },
    });
    fireEvent.click(screen.getByRole("button", { name: "开始打光" }));

    await waitFor(() => expect(request).toEqual(expect.objectContaining({
      canvasItemId: "item-1", sourceVersionId: "image-version", operation: "RELIGHT",
      capabilityId: "relight-capability", instruction: "让人物轮廓更清晰",
      parameters: { lightingPreset: "MOONLIGHT", brightness: -24,
        colorTemperature: 8200, lightX: 0.15, lightY: 0.75 },
    })));
  });

  it("exposes all seven AI image operations instead of disabled placeholders", async () => {
    server.use(
      http.get("/api/v1/projects/project-1/canvas-items/item-1/media-draft", () => HttpResponse.json({
        projectId: artifact.projectId, canvasItemId: "item-1", displayMode: "RESULT", version: 0,
      })),
      http.get("/api/v1/projects/project-1/assets/image-asset", () => HttpResponse.json({
        id: "image-asset", width: 1200, height: 800,
      })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({
        connections: [{ id: "openai", enabled: true, platform: "OPENAI", capabilities: [{
          id: "ai-capability", name: "GPT Image", enabled: true,
          kind: "IMAGE_GENERATION", maxReferenceImages: 1,
          supportsTransparentBackground: true,
        }] }], defaults: [],
      })),
    );
    showCard({ ...artifact, resourceDefaultVersionId: "image-version", resourceDefaultVersion: {
      id: "image-version", versionNo: 1, schemaVersion: 1,
      content: { sourceType: "UPLOAD", assetId: "image-asset" }, inputReferences: [],
      createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
    } });

    fireEvent.click(await screen.findByRole("button", { name: "扩展" }));
    for (const label of ["三视图", "图层分离", "表情调整", "画笔标注", "移除背景", "局部擦除", "视角调整"]) {
      expect(screen.getByRole("button", { name: new RegExp(`${label}.*AI`) })).toBeEnabled();
    }
    expect(screen.queryByText("后续")).not.toBeInTheDocument();
  });

  it("submits expression editing through the selected AI image capability", async () => {
    let request: unknown;
    server.use(
      http.get("/api/v1/projects/project-1/canvas-items/item-1/media-draft", () => HttpResponse.json({
        projectId: artifact.projectId, canvasItemId: "item-1", displayMode: "RESULT", version: 0,
      })),
      http.get("/api/v1/projects/project-1/assets/image-asset", () => HttpResponse.json({
        id: "image-asset", width: 1200, height: 800,
      })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({
        connections: [{ id: "openai", enabled: true, platform: "OPENAI", capabilities: [{
          id: "expression-capability", name: "GPT Image", enabled: true,
          kind: "IMAGE_GENERATION", maxReferenceImages: 1,
          supportsTransparentBackground: true,
        }] }], defaults: [],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test",
      })),
      http.post("/api/v1/projects/project-1/artifacts/image-1/image-operations", async ({ request: incoming }) => {
        request = await incoming.json();
        return HttpResponse.json({ id: "expression-task", status: "READY", errorCode: null });
      }),
    );
    showCard({ ...artifact, resourceDefaultVersionId: "image-version", resourceDefaultVersion: {
      id: "image-version", versionNo: 1, schemaVersion: 1,
      content: { sourceType: "UPLOAD", assetId: "image-asset" }, inputReferences: [],
      createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
    } });

    fireEvent.click(await screen.findByRole("button", { name: "扩展" }));
    fireEvent.click(screen.getByRole("button", { name: /表情调整.*AI/ }));
    const submit = screen.getByRole("button", { name: "开始处理" });
    expect(submit).toBeDisabled();
    fireEvent.change(screen.getByRole("textbox", { name: "目标表情" }), {
      target: { value: "自然微笑，嘴唇闭合" },
    });
    fireEvent.click(submit);

    await waitFor(() => expect(request).toEqual(expect.objectContaining({
      canvasItemId: "item-1", sourceVersionId: "image-version",
      operation: "EXPRESSION_EDIT", capabilityId: "expression-capability",
      instruction: "自然微笑，嘴唇闭合", parameters: {},
    })));
  });

  it("requires a transparent-capable model only for the foreground layer", async () => {
    server.use(
      http.get("/api/v1/projects/project-1/canvas-items/item-1/media-draft", () => HttpResponse.json({
        projectId: artifact.projectId, canvasItemId: "item-1", displayMode: "RESULT", version: 0,
      })),
      http.get("/api/v1/projects/project-1/assets/image-asset", () => HttpResponse.json({
        id: "image-asset", width: 1200, height: 800,
      })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({
        connections: [{ id: "providers", enabled: true, platform: "OPENAI", capabilities: [
          { id: "opaque-capability", name: "Opaque model", enabled: true,
            kind: "IMAGE_GENERATION", maxReferenceImages: 1, supportsTransparentBackground: false },
          { id: "transparent-capability", name: "Transparent model", enabled: true,
            kind: "IMAGE_GENERATION", maxReferenceImages: 1, supportsTransparentBackground: true },
        ] }], defaults: [],
      })),
    );
    showCard({ ...artifact, resourceDefaultVersionId: "image-version", resourceDefaultVersion: {
      id: "image-version", versionNo: 1, schemaVersion: 1,
      content: { sourceType: "UPLOAD", assetId: "image-asset" }, inputReferences: [],
      createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
    } });

    fireEvent.click(await screen.findByRole("button", { name: "扩展" }));
    fireEvent.click(screen.getByRole("button", { name: /图层分离.*AI/ }));
    const capability = screen.getByRole("combobox", { name: "图片能力" });
    expect(capability).toHaveValue("transparent-capability");
    expect(screen.queryByRole("option", { name: "Opaque model" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole("combobox", { name: "输出图层" }), {
      target: { value: "BACKGROUND" },
    });
    expect(screen.getByRole("option", { name: "Opaque model" })).toBeInTheDocument();
  });

  it("keeps the full preview available when original dimensions fail and allows retry", async () => {
    let metadataFailed = true;
    server.use(
      http.get("/api/v1/projects/project-1/canvas-items/item-1/media-draft", () => HttpResponse.json({
        projectId: artifact.projectId, canvasItemId: "item-1", displayMode: "RESULT", version: 0,
      })),
      http.get("/api/v1/projects/project-1/assets/image-asset", () => metadataFailed
        ? HttpResponse.json({ detail: "Unavailable" }, { status: 503 })
        : HttpResponse.json({ id: "image-asset", width: 2400, height: 1600 })),
    );
    showCard({ ...artifact, resourceDefaultVersionId: "image-version", resourceDefaultVersion: {
      id: "image-version", versionNo: 1, schemaVersion: 1, content: { sourceType: "UPLOAD", assetId: "image-asset" },
      inputReferences: [], createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
    } });
    expect(await screen.findByRole("alert")).toHaveTextContent("图片尺寸读取失败");
    expect(screen.getByRole("img", { name: "湖边 的预览" })).toHaveAttribute("src",
      "/api/v1/projects/project-1/assets/image-asset/content");
    metadataFailed = false;
    fireEvent.click(screen.getByRole("button", { name: "重试尺寸" }));
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    expect(screen.getByRole("img", { name: "湖边 的预览" })).toBeInTheDocument();
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

  it("shows UNKNOWN as an explicit retry state instead of a running animation", async () => {
    server.use(http.get("/api/v1/projects/project-1/artifacts/image-1/run", () => HttpResponse.json([
      { id: "task-1", status: "UNKNOWN", errorCode: "SUBMISSION_UNKNOWN" },
    ])));
    showCard();
    expect(await screen.findByText("结果未知")).toBeInTheDocument();
    expect(screen.getByText("可在编辑区重试")).toBeInTheDocument();
    expect(screen.queryByText("正在生成")).not.toBeInTheDocument();
  });

  it("explains why the result is unknown instead of showing only a machine code", async () => {
    server.use(http.get("/api/v1/projects/project-1/artifacts/image-1/run", () => HttpResponse.json([
      { id: "task-1", status: "UNKNOWN", errorCode: "PROVIDER_CALL_TIMEOUT" },
    ])));
    showCard();
    expect(await screen.findByText("结果未知")).toBeInTheDocument();
    expect(screen.getByText("调用超时，结果未知")).toBeInTheDocument();
    expect(screen.queryByText("PROVIDER_CALL_TIMEOUT")).not.toBeInTheDocument();
  });

  it("still shows an unregistered code rather than hiding the only clue", async () => {
    server.use(http.get("/api/v1/projects/project-1/artifacts/image-1/run", () => HttpResponse.json([
      { id: "task-1", status: "UNKNOWN", errorCode: "SOME_UNREGISTERED_CODE" },
    ])));
    showCard();
    expect(await screen.findByText("SOME_UNREGISTERED_CODE")).toBeInTheDocument();
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
    const videoArtifact: Artifact = { ...artifact, kind: "VIDEO", resourceDefaultVersionId: "video-version",
      resourceDefaultVersion: { id: "video-version", versionNo: 1, schemaVersion: 1,
        content: { assetId: "video-asset", prompt: "A camera movement", providerConfigVersion: 1,
          workflowVersion: "mock-video-v1", sourceTaskId: "video-task", parameters: { mock: true } },
        inputReferences: [], createdByKind: "TASK", runId: null, createdAt: artifact.createdAt } };
    server.use(http.get("/api/v1/projects/project-1/canvas-items/item-1/media-draft", () => HttpResponse.json({
      projectId: artifact.projectId, canvasItemId: "item-1", prompt: "湖面慢慢推进", displayMode: "RESULT",
      parameters: {}, videoInputMode: "START_END", imageInputs: [], mentions: [],
      durationSeconds: 5, capabilityId: null, version: 1,
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
