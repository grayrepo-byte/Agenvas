import userEvent from "@testing-library/user-event";
import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent,render,screen,waitFor,within } from "@testing-library/react";
import { http,HttpResponse } from "msw";
import type { ReactNode } from "react";
import { beforeEach,describe,expect,it,vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Artifact,CanvasItem } from "../../shared/api/client";
import { changeControl,clickControl } from "../../test/controls";
import { server } from "../../test/server";
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

function showCard(shownArtifact: Artifact = artifact, onCardClick = vi.fn()) {
  const onEdit = vi.fn();
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  const card = (selected: boolean, toolbarVisible = true) => <QueryClientProvider client={client}>
    <div onClick={onCardClick}>
      <MediaCanvasCard artifact={shownArtifact} item={itemFor(shownArtifact)} selected={selected}
        toolbarVisible={toolbarVisible} locked={false} onEdit={onEdit}>{null}</MediaCanvasCard>
    </div>
  </QueryClientProvider>;
  const view = render(card(true));
  return { onEdit, onCardClick,
    setToolbarState: (selected: boolean, toolbarVisible = true) => view.rerender(card(selected, toolbarVisible)) };
}

describe("MediaCanvasCard", () => {
  it.each(["IMAGE", "VIDEO", "AUDIO"] as const)("removes card details from %s toolbars", (kind) => {
    showCard({ ...artifact, kind });
    expect(screen.getByLabelText("媒体卡片操作")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "卡片详情" })).not.toBeInTheDocument();
  });

  beforeEach(() => {
    server.use(
      http.get("/api/v1/projects/project-1/assets/:assetId", ({ params }) =>
        HttpResponse.json({ id: params.assetId, width: 1920, height: 1080 })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({ connections: [], defaults: [] })),
      http.get("/api/v1/projects/project-1/canvas-items/item-1/media-draft", () => HttpResponse.json({
        projectId: artifact.projectId, canvasItemId: "item-1", prompt: "", displayMode: "DRAFT",
        parameters: {}, videoInputMode: null, mediaInputs: [], mentions: [],
        durationSeconds: null, capabilityId: null, version: 0,
        createdAt: artifact.createdAt, updatedAt: artifact.updatedAt,
      })),
      http.get("/api/v1/projects/project-1/artifacts/image-1/run", () => HttpResponse.json([])),
    );
  });

  describe.each(["deselect", "hide toolbar"] as const)("when closing via %s", (closeMode) => {
    beforeEach(() => {
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
      );
    });

    function showResultCard() {
      const card = showCard({ ...artifact, resourceDefaultVersionId: "image-version", resourceDefaultVersion: {
        id: "image-version", versionNo: 1, schemaVersion: 1,
        content: { sourceType: "UPLOAD", assetId: "image-asset" }, inputReferences: [],
        createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
      } });
      return {
        closeToolbar: () => card.setToolbarState(closeMode !== "deselect", closeMode !== "hide toolbar"),
        reopenToolbar: () => card.setToolbarState(true),
      };
    }

    it.each([
      ["表情调整", "表情调整"], ["图层分离", "图层分离"], ["画笔标注", "画笔标注"],
      ["移除背景", "移除背景"], ["局部擦除", "局部擦除"], ["视角调整", "视角调整"],
      ["AI 扩图", "AI 扩图"], ["高清放大", "高清放大"], ["裁剪", "裁剪图片"],
      ["重新打光", "打光"], ["智能编辑", "智能编辑图片"], ["脸部三视图", "脸部三视图"],
    ])("resets the %s panel before selecting the node again", async (label, dialogName) => {
      const { closeToolbar, reopenToolbar } = showResultCard();
      await screen.findByRole("img", { name: "湖边 的预览" });
      if (label !== "智能编辑") {
        await clickControl(screen.getByRole("button", { name: "扩展" }));
        if (label === "脸部三视图") {
          await clickControl(screen.getByRole("menuitem", { name: "三视图" }));
          await clickControl(screen.getByRole("menuitem", { name: /脸部三视图/ }));
        } else await clickControl(screen.getByRole("menuitem", { name: new RegExp(`${label}.*(?:AI|本地)`) }));
      } else await clickControl(screen.getByRole("button", { name: "智能编辑" }));
      expect(screen.getByRole("dialog", { name: dialogName })).toBeInTheDocument();
      closeToolbar();
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      reopenToolbar();
      expect(screen.getByLabelText("媒体卡片操作")).toBeInTheDocument();
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "扩展" })).toHaveAttribute("aria-expanded", "false");
      expect(screen.getByLabelText("媒体卡片操作").closest(".react-flow__node-toolbar"))
        .not.toHaveClass("artifact-card-toolbar-raised");
    });

    it("resets the extension menu and its three-view submenu", async () => {
      const { closeToolbar, reopenToolbar } = showResultCard();
      await clickControl(await screen.findByRole("button", { name: "扩展" }));
      await clickControl(screen.getByRole("menuitem", { name: "三视图" }));
      expect(screen.getByRole("menu", { name: "三视图类型" })).toBeInTheDocument();
      closeToolbar();
      reopenToolbar();
      expect(screen.queryByLabelText("图片扩展功能")).not.toBeInTheDocument();
      expect(screen.queryByRole("menu", { name: "三视图类型" })).not.toBeInTheDocument();
      await clickControl(screen.getByRole("button", { name: "扩展" }));
      expect(screen.queryByRole("menu", { name: "三视图类型" })).not.toBeInTheDocument();
    });

    it("keeps the version menu closed after selecting the node again", async () => {
      server.use(http.get("/api/v1/projects/project-1/canvas/items/item-1/media-versions", () =>
        HttpResponse.json({ items: [1, 2].map((versionNo) => ({
          id: versionNo === 1 ? "image-version" : "image-version-2", versionNo, schemaVersion: 1,
          content: { sourceType: "UPLOAD", assetId: "image-asset" }, inputReferences: [],
          createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
        })) })));
      const { closeToolbar, reopenToolbar } = showResultCard();
      await clickControl(await screen.findByRole("button", { name: "版本 v1" }));
      expect(await screen.findByText("2 个版本")).toBeInTheDocument();
      closeToolbar();
      reopenToolbar();
      expect(screen.queryByRole("menu", { name: "媒体版本" })).not.toBeInTheDocument();
    });

    it("clears a failed operation and its form when the toolbar closes", async () => {
      server.use(
        http.get("/api/v1/auth/csrf", () => HttpResponse.json({
          headerName: "X-XSRF-TOKEN", token: "test",
        })),
        http.post("/api/v1/projects/project-1/artifacts/image-1/image-operations", () =>
          HttpResponse.json({ status: 502, code: "PROVIDER_ERROR", detail: "模拟处理失败" },
            { status: 502, headers: { "Content-Type": "application/problem+json" } })),
      );
      const { closeToolbar, reopenToolbar } = showResultCard();
      await screen.findByRole("img", { name: "湖边 的预览" });
      await clickControl(screen.getByRole("button", { name: "扩展" }));
      await clickControl(screen.getByRole("menuitem", { name: /高清放大.*本地/ }));
      await changeControl(screen.getByRole("combobox", { name: "放大倍数" }), { target: { value: "4" } });
      await clickControl(screen.getByRole("button", { name: "开始处理" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("模拟处理失败");
      closeToolbar();
      reopenToolbar();
      await clickControl(screen.getByRole("button", { name: "扩展" }));
      await clickControl(screen.getByRole("menuitem", { name: /高清放大.*本地/ }));
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
      expect(screen.getByRole("combobox", { name: "放大倍数" })).toHaveValue("2");
    });

    it("dismisses the panel without losing or resubmitting an in-flight operation", async () => {
      let submissions = 0;
      let finishSubmission = () => {};
      const submitted = new Promise<void>((resolve) => { finishSubmission = resolve; });
      server.use(
        http.get("/api/v1/settings/media-connections", () => HttpResponse.json({
          connections: [{ id: "openai", enabled: true, platform: "OPENAI", capabilities: [{
            id: "ai-capability", name: "GPT Image", enabled: true,
            kind: "IMAGE_GENERATION", maxReferenceImages: 1,
          }] }], defaults: [],
        })),
        http.get("/api/v1/auth/csrf", () => HttpResponse.json({
          headerName: "X-XSRF-TOKEN", token: "test",
        })),
        http.post("/api/v1/projects/project-1/artifacts/image-1/image-operations", async () => {
          submissions += 1;
          await submitted;
          return HttpResponse.json({ id: "expression-task", status: "READY", errorCode: null });
        }),
      );
      const { closeToolbar, reopenToolbar } = showResultCard();
      await screen.findByRole("img", { name: "湖边 的预览" });
      await clickControl(screen.getByRole("button", { name: "扩展" }));
      await clickControl(screen.getByRole("menuitem", { name: /表情调整.*AI/ }));
      await waitFor(() => expect(screen.getByRole("combobox", { name: "图片能力" }))
        .toHaveValue("ai-capability"));
      await changeControl(screen.getByRole("textbox", { name: "目标表情" }), { target: { value: "微笑" } });
      await clickControl(screen.getByRole("button", { name: "开始处理" }));
      await waitFor(() => expect(submissions).toBe(1));
      try {
        closeToolbar();
        reopenToolbar();
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(screen.getByRole("button", { name: "智能编辑" })).toBeDisabled();
      } finally {
        finishSubmission();
      }
      await waitFor(() => expect(screen.getByRole("button", { name: "智能编辑" })).toBeEnabled());
      expect(submissions).toBe(1);
    });
  });

  it("offers upload into the empty card and keeps image operations disabled until a source exists", async () => {
    const { onCardClick } = showCard();
    const pickerClick = vi.spyOn(HTMLInputElement.prototype, "click").mockImplementation(() => undefined);
    await clickControl(await screen.findByRole("button", { name: "上传图片" }));
    expect(pickerClick).toHaveBeenCalledOnce();
    expect(onCardClick).not.toHaveBeenCalled();
    pickerClick.mockRestore();
    await clickControl(screen.getByRole("button", { name: "扩展" }));
    expect(screen.getByLabelText("媒体卡片操作").closest(".react-flow__node-toolbar"))
      .toHaveClass("artifact-card-toolbar-raised");
    expect(screen.getByRole("menuitem", { name: /高清放大.*本地/ })).toHaveAttribute("aria-disabled", "true");
    await userEvent.setup().keyboard("{Escape}");
    expect(screen.queryByLabelText("图片扩展功能")).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "扩展" })).toHaveFocus());
    expect(screen.queryByRole("button", { name: "卡片详情" })).not.toBeInTheDocument();
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

    await clickControl(await screen.findByRole("button", { name: "深度提取" }));
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

    await clickControl(await screen.findByRole("button", { name: "扩展" }));
    await clickControl(screen.getByRole("menuitem", { name: /裁剪.*本地/ }));
    expect(screen.getByRole("dialog", { name: "裁剪图片" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "当前图片裁剪预览" })).toHaveAttribute("src",
      "/api/v1/projects/project-1/assets/image-asset/content");
    await changeControl(screen.getByRole("combobox", { name: "裁剪比例" }), {
      target: { value: "1:1" },
    });
    await clickControl(screen.getByRole("button", { name: "确定" }));

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

    await clickControl(await screen.findByRole("button", { name: "扩展" }));
    await clickControl(screen.getByRole("menuitem", { name: /重新打光.*AI/ }));
    expect(screen.getByRole("dialog", { name: "打光" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "当前图片打光预览" })).toHaveAttribute("src",
      "/api/v1/projects/project-1/assets/image-asset/content");
    await clickControl(screen.getByRole("button", { name: "月光" }));
    await changeControl(screen.getByRole("textbox", { name: "补充打光描述" }), {
      target: { value: "让人物轮廓更清晰" },
    });
    await clickControl(screen.getByRole("button", { name: "开始打光" }));

    await waitFor(() => expect(request).toEqual(expect.objectContaining({
      canvasItemId: "item-1", sourceVersionId: "image-version", operation: "RELIGHT",
      capabilityId: "relight-capability", instruction: "让人物轮廓更清晰",
      parameters: { lightingPreset: "MOONLIGHT", brightness: -24,
        colorTemperature: 8200, lightX: 0.15, lightY: 0.75 },
    })));
  });

  it("exposes model operations and the local brush editor", async () => {
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
          supportsTransparentBackground: true, supportsImageMask: true,
        }] }], defaults: [],
      })),
    );
    showCard({ ...artifact, resourceDefaultVersionId: "image-version", resourceDefaultVersion: {
      id: "image-version", versionNo: 1, schemaVersion: 1,
      content: { sourceType: "UPLOAD", assetId: "image-asset" }, inputReferences: [],
      createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
    } });

    await clickControl(await screen.findByRole("button", { name: "扩展" }));
    expect(screen.getByRole("menuitem", { name: "三视图" })).toBeEnabled();
    expect(screen.getByRole("menuitem", { name: /画笔标注.*本地/ })).toBeEnabled();
    for (const label of ["图层分离", "表情调整", "移除背景", "局部擦除", "视角调整"]) {
      expect(screen.getByRole("menuitem", { name: new RegExp(`${label}.*AI`) })).toBeEnabled();
    }
    expect(screen.queryByText("后续")).not.toBeInTheDocument();
  });

  it("opens the full smart editor and submits ordered project references with its prompt", async () => {
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
          id: "smart-capability", name: "GPT Image", enabled: true,
          adapterId: "OPENAI_GPT_IMAGE_2", settings: {},
          kind: "IMAGE_GENERATION", maxReferenceImages: 4,
          supportsTransparentBackground: true, supportsImageMask: true,
        }] }], defaults: [],
      })),
      http.get("/api/v1/projects/project-1/artifacts", () => HttpResponse.json({ items: [{
        id: "reference-artifact", projectId: "project-1", kind: "IMAGE", title: "海边参考",
        resourceDefaultVersionId: "reference-version", resourceDefaultVersion: {
          id: "reference-version", versionNo: 1, schemaVersion: 1,
          content: { sourceType: "UPLOAD", assetId: "reference-asset" }, inputReferences: [],
          createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
        }, version: 0, createdAt: artifact.createdAt, updatedAt: artifact.updatedAt,
      }] })),
      http.get("/api/v1/projects/project-1/canvas/items", () => HttpResponse.json({ items: [{
        id: "reference-item", subjectType: "ARTIFACT", subjectId: "reference-artifact",
        title: "海边参考", x: 0, y: 0, width: 400, height: 300, zIndex: 0,
        groupId: null, locked: false, selectedVersionId: "reference-version",
        selectedVersion: {
          id: "reference-version", versionNo: 1, schemaVersion: 1,
          content: { sourceType: "UPLOAD", assetId: "reference-asset" }, inputReferences: [],
          createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
        }, version: 0, artifact: {
          id: "reference-artifact", projectId: "project-1", kind: "IMAGE", title: "海边参考",
          resourceDefaultVersionId: "reference-version", resourceDefaultVersion: null,
          version: 0, createdAt: artifact.createdAt, updatedAt: artifact.updatedAt,
        }, agent: null,
      }] })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test",
      })),
      http.post("/api/v1/projects/project-1/artifacts/image-1/image-operations", async ({ request: incoming }) => {
        request = await incoming.json();
        return HttpResponse.json({ id: "smart-task", status: "READY", errorCode: null });
      }),
    );
    showCard({ ...artifact, resourceDefaultVersionId: "image-version", resourceDefaultVersion: {
      id: "image-version", versionNo: 1, schemaVersion: 1,
      content: { sourceType: "UPLOAD", assetId: "image-asset" }, inputReferences: [],
      createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
    } });

    await clickControl(await screen.findByRole("button", { name: "智能编辑" }));
    expect(screen.getByRole("dialog", { name: "智能编辑图片" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "涂抹" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "框选" })).toBeEnabled();
    await clickControl(screen.getByRole("button", { name: "引用" }));
    await clickControl(await screen.findByRole("button", { name: "海边参考" }));
    await changeControl(screen.getByRole("textbox", { name: "智能编辑提示词" }), {
      target: { value: "把背景替换成参考图中的海边，人物保持不变" },
    });
    await clickControl(screen.getByRole("button", { name: "开始智能编辑" }));

    await waitFor(() => expect(request).toEqual(expect.objectContaining({
      operation: "SMART_EDIT", instruction: "把背景替换成参考图中的海边，人物保持不变",
      capabilityId: "smart-capability", referenceVersionIds: ["reference-version"],
      maskAssetId: null, parameters: {},
    })));
  });

  it("chooses a dedicated three-view type and freezes it into the task", async () => {
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
          id: "three-view-capability", name: "GPT Image", enabled: true,
          kind: "IMAGE_GENERATION", maxReferenceImages: 1,
          supportsTransparentBackground: true, supportsImageMask: true,
        }] }], defaults: [],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test",
      })),
      http.post("/api/v1/projects/project-1/artifacts/image-1/image-operations", async ({ request: incoming }) => {
        request = await incoming.json();
        return HttpResponse.json({ id: "three-view-task", status: "READY", errorCode: null });
      }),
    );
    showCard({ ...artifact, resourceDefaultVersionId: "image-version", resourceDefaultVersion: {
      id: "image-version", versionNo: 1, schemaVersion: 1,
      content: { sourceType: "UPLOAD", assetId: "image-asset" }, inputReferences: [],
      createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
    } });

    await clickControl(await screen.findByRole("button", { name: "扩展" }));
    const threeViewEntry = screen.getByRole("menuitem", { name: "三视图" });
    fireEvent.pointerEnter(threeViewEntry);
    await clickControl(threeViewEntry);
    expect(screen.getByRole("menu", { name: "三视图类型" })).toBeInTheDocument();
    for (const label of ["角色三视图", "脸部三视图", "道具三视图", "场景宫格图"]) {
      expect(screen.getByRole("menuitem", { name: new RegExp(label) })).toBeEnabled();
    }
    await clickControl(screen.getByRole("menuitem", { name: /脸部三视图/ }));
    expect(screen.getByRole("dialog", { name: "脸部三视图" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /脸部三视图/ })).toBeChecked();
    await changeControl(screen.getByRole("textbox", { name: "主体说明（可选）" }), {
      target: { value: "保留发饰和妆容" },
    });
    await clickControl(screen.getByRole("button", { name: "开始处理" }));

    await waitFor(() => expect(request).toEqual(expect.objectContaining({
      canvasItemId: "item-1", sourceVersionId: "image-version", operation: "THREE_VIEW",
      capabilityId: "three-view-capability", instruction: "保留发饰和妆容",
      parameters: { aspectRatio: "16:9", threeViewType: "FACE" },
    })));
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
          supportsTransparentBackground: true, supportsImageMask: true,
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

    await clickControl(await screen.findByRole("button", { name: "扩展" }));
    await clickControl(screen.getByRole("menuitem", { name: /表情调整.*AI/ }));
    const submit = screen.getByRole("button", { name: "开始处理" });
    expect(submit).toBeDisabled();
    await changeControl(screen.getByRole("textbox", { name: "目标表情" }), {
      target: { value: "自然微笑，嘴唇闭合" },
    });
    await clickControl(submit);

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
            kind: "IMAGE_GENERATION", maxReferenceImages: 1, supportsTransparentBackground: false,
            supportsImageMask: false },
          { id: "transparent-capability", name: "Transparent model", enabled: true,
            kind: "IMAGE_GENERATION", maxReferenceImages: 1, supportsTransparentBackground: true,
            supportsImageMask: true },
        ] }], defaults: [],
      })),
    );
    showCard({ ...artifact, resourceDefaultVersionId: "image-version", resourceDefaultVersion: {
      id: "image-version", versionNo: 1, schemaVersion: 1,
      content: { sourceType: "UPLOAD", assetId: "image-asset" }, inputReferences: [],
      createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
    } });

    await clickControl(await screen.findByRole("button", { name: "扩展" }));
    await clickControl(screen.getByRole("menuitem", { name: /图层分离.*AI/ }));
    const capability = screen.getByRole("combobox", { name: "图片能力" });
    expect(capability).toHaveValue("transparent-capability");
    expect(screen.queryByRole("option", { name: "Opaque model" })).not.toBeInTheDocument();
    await changeControl(screen.getByRole("combobox", { name: "输出图层" }), {
      target: { value: "BACKGROUND" },
    });
    await userEvent.setup().click(screen.getByRole("combobox", { name: "图片能力" }));
    expect(screen.getByRole("option", { name: "Opaque model" })).toBeInTheDocument();
  });

  describe("image enlargement", () => {
    beforeEach(() => {
      server.use(
        http.get("/api/v1/projects/project-1/canvas-items/item-1/media-draft", () => HttpResponse.json({
          projectId: artifact.projectId, canvasItemId: "item-1", displayMode: "RESULT", version: 0,
        })),
        http.get("/api/v1/projects/project-1/assets/image-asset", () => HttpResponse.json({
          id: "image-asset", width: 1200, height: 800,
        })),
      );
    });

    function showImage() {
      return showCard({ ...artifact, resourceDefaultVersionId: "image-version", resourceDefaultVersion: {
        id: "image-version", versionNo: 1, schemaVersion: 1,
        content: { sourceType: "UPLOAD", assetId: "image-asset" }, inputReferences: [],
        createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
      } });
    }

    it.each(["button", "escape", "backdrop"] as const)("opens a modal and closes via %s with focus restored", async (method) => {
      const { onCardClick } = showImage();
      const expand = await screen.findByRole("button", { name: "放大图片" });
      expand.focus();
      await clickControl(expand);
      const dialog = screen.getByRole("dialog", { name: "湖边 的原图预览" });
      expect(dialog).toHaveAttribute("aria-modal", "true");
      expect(screen.queryByRole("link", { name: "打开原图" })).not.toBeInTheDocument();
      expect(within(dialog).getByRole("img", { name: "湖边 的原图" })).toHaveAttribute("src",
        "/api/v1/projects/project-1/assets/image-asset/content");
      const close = within(dialog).getByRole("button", { name: "关闭图片预览" });
      expect(close).toHaveFocus();
      fireEvent.keyDown(close, { key: "Tab" });
      expect(close).toHaveFocus();
      fireEvent.keyDown(close, { key: "Tab", shiftKey: true });
      expect(close).toHaveFocus();
      await clickControl(within(dialog).getByRole("img"));
      expect(dialog).toBeInTheDocument();
      if (method === "button") await clickControl(close);
      else if (method === "escape") fireEvent.keyDown(close, { key: "Escape" });
      else await clickControl(dialog.parentElement!);
      expect(screen.queryByRole("dialog", { name: "湖边 的原图预览" })).not.toBeInTheDocument();
      expect(expand).toHaveFocus();
      expect(onCardClick).not.toHaveBeenCalled();
    });

    it("handles image loading failure and retry without passing canvas shortcuts through", async () => {
      showImage();
      await clickControl(await screen.findByRole("button", { name: "放大图片" }));
      const dialog = screen.getByRole("dialog", { name: "湖边 的原图预览" });
      expect(within(dialog).getByRole("status", { name: "正在加载图片" })).toBeInTheDocument();
      fireEvent.error(within(dialog).getByRole("img"));
      expect(within(dialog).getByRole("alert")).toHaveTextContent("图片加载失败");
      const retry = within(dialog).getByRole("button", { name: "重试加载图片" });
      retry.focus();
      fireEvent.keyDown(retry, { key: "Tab" });
      expect(within(dialog).getByRole("button", { name: "关闭图片预览" })).toHaveFocus();
      const canvasShortcut = vi.fn();
      document.addEventListener("keydown", canvasShortcut);
      try {
        fireEvent.keyDown(retry, { key: "Delete" });
        expect(canvasShortcut).not.toHaveBeenCalled();
      } finally {
        document.removeEventListener("keydown", canvasShortcut);
      }
      await clickControl(retry);
      expect(within(dialog).queryByRole("alert")).not.toBeInTheDocument();
      fireEvent.load(within(dialog).getByRole("img"));
      expect(within(dialog).queryByRole("status")).not.toBeInTheDocument();
    });
  });

  it.each(["IMAGE", "VIDEO"] as const)("keeps the full %s preview available when original dimensions fail and allows retry", async (kind) => {
    let metadataFailed = true;
    server.use(
      http.get("/api/v1/projects/project-1/canvas-items/item-1/media-draft", () => HttpResponse.json({
        projectId: artifact.projectId, canvasItemId: "item-1", displayMode: "RESULT", version: 0,
      })),
      http.get("/api/v1/projects/project-1/assets/image-asset", () => metadataFailed
        ? HttpResponse.json({ detail: "Unavailable" }, { status: 503 })
        : HttpResponse.json({ id: "image-asset", width: 2400, height: 1600 })),
    );
    showCard({ ...artifact, kind, resourceDefaultVersionId: "image-version", resourceDefaultVersion: {
      id: "image-version", versionNo: 1, schemaVersion: 1, content: { sourceType: "UPLOAD", assetId: "image-asset" },
      inputReferences: [], createdByKind: "USER", runId: null, createdAt: artifact.createdAt,
    } });
    expect(await screen.findByRole("alert")).toHaveTextContent(kind === "IMAGE" ? "图片尺寸读取失败" : "视频尺寸读取失败");
    const previewName = kind === "IMAGE" ? "湖边 的预览" : "湖边 的视频封面";
    expect(screen.getByRole("img", { name: previewName })).toHaveAttribute("src",
      `/api/v1/projects/project-1/assets/image-asset/${kind === "IMAGE" ? "content" : "thumbnail"}`);
    metadataFailed = false;
    await clickControl(screen.getByRole("button", { name: "重试尺寸" }));
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    expect(screen.getByRole("img", { name: previewName })).toBeInTheDocument();
  });

  it.each(["RUNNING", "SUBMITTING", "WAITING_PROVIDER"] as const)(
    "shows %s as generation activity without a fabricated percentage", async (status) => {
      server.use(http.get("/api/v1/projects/project-1/artifacts/image-1/run", () => HttpResponse.json([
        { id: "task-1", status, errorCode: null },
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
    await clickControl(await screen.findByRole("button", { name: "生成视频" }));
    expect(onEdit).toHaveBeenCalledOnce();
    expect(screen.queryByRole("button", { name: /上传/ })).not.toBeInTheDocument();
  });

  it("loads only the video poster until explicit playback, then handles loading, retry and close", async () => {
    const videoArtifact: Artifact = { ...artifact, kind: "VIDEO", resourceDefaultVersionId: "video-version",
      resourceDefaultVersion: { id: "video-version", versionNo: 1, schemaVersion: 1,
        content: { assetId: "video-asset", prompt: "A camera movement",
          workflowVersion: "mock-video-v1", sourceTaskId: "video-task", parameters: { mock: true } },
        inputReferences: [], createdByKind: "TASK", runId: null, createdAt: artifact.createdAt } };
    server.use(http.get("/api/v1/projects/project-1/canvas-items/item-1/media-draft", () => HttpResponse.json({
      projectId: artifact.projectId, canvasItemId: "item-1", prompt: "湖面慢慢推进", displayMode: "RESULT",
      parameters: {}, videoInputMode: "START_END", mediaInputs: [], mentions: [],
      durationSeconds: 5, capabilityId: null, version: 1,
      createdAt: artifact.createdAt, updatedAt: artifact.updatedAt,
    })));
    showCard(videoArtifact);
    const poster = await screen.findByRole("img", { name: "湖边 的视频封面" });
    expect(poster.getAttribute("src")).toContain("/thumbnail");
    expect(screen.queryByLabelText("湖边 的视频")).not.toBeInTheDocument();
    expect(screen.getByText("演示视频")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "打开视频文件" })).toHaveAttribute("target", "_blank");

    await clickControl(screen.getByRole("button", { name: "播放视频" }));
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
    await clickControl(screen.getByRole("button", { name: "重试播放" }));
    expect(screen.getByLabelText("湖边 的视频")).not.toBe(video);
    await clickControl(screen.getByRole("button", { name: "关闭视频预览" }));
    expect(screen.queryByLabelText("湖边 的视频")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "播放视频" })).toBeInTheDocument();
  });

  it("routes an unresolved video task to the editor without resubmitting it", async () => {
    server.use(http.get("/api/v1/projects/project-1/artifacts/image-1/run", () => HttpResponse.json([
      { id: "video-task", status: "UNKNOWN", errorCode: "SUBMISSION_UNKNOWN" },
    ])));
    const { onEdit } = showCard({ ...artifact, kind: "VIDEO" });
    await clickControl(await screen.findByRole("button", { name: "查看任务" }));
    expect(onEdit).toHaveBeenCalledOnce();
    expect(screen.queryByRole("button", { name: "生成视频" })).not.toBeInTheDocument();
  });
});
