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

function showCard() {
  const onUpload = vi.fn();
  const onInspect = vi.fn();
  render(<QueryClientProvider client={createQueryClient()}>
    <MediaCanvasCard artifact={artifact} selected locked={false} onEdit={vi.fn()}
      onUpload={onUpload} onInspect={onInspect}>{null}</MediaCanvasCard>
  </QueryClientProvider>);
  return { onUpload, onInspect };
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
});
