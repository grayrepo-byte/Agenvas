import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { CanvasItem } from "../../shared/api/client";
import { server } from "../../test/server";
import { MediaExportPanel } from "./MediaExportPanel";

describe("MediaExportPanel", () => {
  it("submits explicit version order and intervals, then offers private download", async () => {
    const projectId = crypto.randomUUID();
    const first = videoItem(projectId, "First", crypto.randomUUID());
    const second = videoItem(projectId, "Second", crypto.randomUUID());
    const submitted: unknown[] = [];
    const exportId = crypto.randomUUID();
    const outputAssetId = crypto.randomUUID();
    server.use(
      http.get("/api/v1/projects/:projectId/export-proposals", () => HttpResponse.json([])),
      http.get("/api/v1/projects/:projectId/exports", () => HttpResponse.json(submitted.length ? [{
        id: exportId, projectId, runId: null, kind: "MEDIA_EXPORT", status: "SUCCEEDED",
        cancelRequested: false, input: {}, output: { assetId: outputAssetId },
      }] : [])),
      http.get("/api/v1/projects/:projectId/usage", () => HttpResponse.json([{
        id: crypto.randomUUID(), entryType: "RESERVATION",
        quantity: { imageCount: 1, videoCount: 0, videoSeconds: "0.000", exportCount: 0,
          llmRequestCount: 0, inputTokens: null, outputTokens: null },
        costStatus: "UNKNOWN", costSource: "MOCK_UNPRICED",
        estimatedCost: "0.00", actualCost: null, currency: "USD",
      }, {
        id: crypto.randomUUID(), entryType: "SETTLEMENT",
        quantity: { imageCount: 0, videoCount: 0, videoSeconds: "0.000", exportCount: 1,
          llmRequestCount: 0, inputTokens: null, outputTokens: null },
        costStatus: "UNKNOWN", costSource: "LOCAL_UNPRICED",
        estimatedCost: null, actualCost: null, currency: null,
      }, {
        id: crypto.randomUUID(), entryType: "SETTLEMENT",
        quantity: { imageCount: 0, videoCount: 0, videoSeconds: "0.000", exportCount: 0,
          llmRequestCount: 1, inputTokens: 23, outputTokens: 7 },
        costStatus: "UNKNOWN", costSource: "PROVIDER_UNPRICED",
        estimatedCost: null, actualCost: null, currency: null,
      }])),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/exports", async ({ request }) => {
        submitted.push({ body: await request.json(), key: request.headers.get("Idempotency-Key") });
        return HttpResponse.json({ id: exportId, projectId, status: "READY" }, { status: 202 });
      }),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}>
      <MediaExportPanel projectId={projectId} items={[first, second]} />
    </QueryClientProvider>);
    expect(screen.getByRole("link", { name: "下载项目 JSON 与素材清单" }))
      .toHaveAttribute("href", `/api/v1/projects/${projectId}/export-manifest`);
    expect(await screen.findAllByText(/费用未知/)).toHaveLength(3);
    expect(screen.queryByText(/0\.00 USD/)).not.toBeInTheDocument();
    expect(await screen.findByText(/导出 1/)).toBeInTheDocument();
    expect(await screen.findByText(/输入 Token 23 · 输出 Token 7/)).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("选择视频"), first.artifact?.id ?? "");
    await user.click(screen.getByRole("button", { name: "添加" }));
    await user.selectOptions(screen.getByLabelText("选择视频"), second.artifact?.id ?? "");
    await user.click(screen.getByRole("button", { name: "添加" }));
    const rows = screen.getAllByRole("listitem").filter((element) => element.textContent?.includes("版本"));
    await user.click(within(rows[1]!).getByRole("button", { name: "上移" }));
    const reordered = screen.getAllByRole("listitem").filter((element) => element.textContent?.includes("版本"));
    expect(reordered[0]).toHaveTextContent("Second");
    await user.clear(within(reordered[0]!).getByLabelText("终点（秒）"));
    await user.type(within(reordered[0]!).getByLabelText("终点（秒）"), "1.25");
    await user.click(screen.getByRole("button", { name: "开始导出" }));

    await waitFor(() => expect(submitted).toHaveLength(1));
    expect(submitted[0]).toMatchObject({
      body: { segments: [
        { videoArtifactId: second.artifact?.id, videoVersionId: second.artifact?.currentVersionId,
          startMs: 0, endMs: 1250 },
        { videoArtifactId: first.artifact?.id, videoVersionId: first.artifact?.currentVersionId,
          startMs: 0, endMs: 5000 },
      ] },
    });
    expect(await screen.findByRole("link", { name: "下载 MP4" })).toHaveAttribute(
      "href", `/api/v1/projects/${projectId}/assets/${outputAssetId}/content`,
    );
  });

  it("requires a visible Agent proposal before authenticated approval starts export", async () => {
    const projectId = crypto.randomUUID();
    const proposalId = crypto.randomUUID();
    const taskId = crypto.randomUUID();
    const proposalHash = "a".repeat(64);
    const approvals: unknown[] = [];
    let status = "PENDING";
    const proposal = {
      id: proposalId, runId: crypto.randomUUID(), proposalHash, projectVersion: 0,
      approvedTaskId: null, createdAt: "2026-09-24T00:00:00Z", decidedAt: null,
      input: { schemaVersion: 1, aspectRatio: "LANDSCAPE_16_9",
        outputFormat: "SILENT_MP4_720P_24FPS", durationMs: 900,
        segments: [{ shotArtifactId: crypto.randomUUID(), shotVersionId: crypto.randomUUID(),
          videoArtifactId: crypto.randomUUID(), videoVersionId: crypto.randomUUID(),
          assetId: crypto.randomUUID(), assetSha256: "b".repeat(64), startMs: 0, endMs: 900 }] },
    };
    server.use(
      http.get("/api/v1/projects/:projectId/export-proposals", () =>
        HttpResponse.json([{ ...proposal, status }])),
      http.get("/api/v1/projects/:projectId/exports", () => HttpResponse.json(
        approvals.length ? [{ id: taskId, projectId, runId: null, kind: "MEDIA_EXPORT",
          status: "READY", cancelRequested: false, input: proposal.input, output: null }] : [])),
      http.get("/api/v1/projects/:projectId/usage", () => HttpResponse.json([])),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/export-proposals/:proposalId/approve",
        async ({ request }) => {
          approvals.push(await request.json());
          status = "APPROVED";
          return HttpResponse.json({ proposal: { ...proposal, status, approvedTaskId: taskId },
            task: { id: taskId, projectId, status: "READY" }, replayed: false });
        }),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}>
      <MediaExportPanel projectId={projectId} items={[]} />
    </QueryClientProvider>);
    expect(await screen.findByText(/待确认/)).toBeInTheDocument();
    expect(screen.getByText(/共 0.900 秒/)).toBeInTheDocument();
    expect(approvals).toHaveLength(0);
    await user.click(screen.getByRole("button", { name: "批准并开始导出" }));
    await waitFor(() => expect(approvals).toEqual([{ proposalHash }]));
    expect(await screen.findByText(/已批准/)).toBeInTheDocument();
    expect(await screen.findByText(new RegExp(`导出 ${taskId.slice(0, 8)}`))).toBeInTheDocument();
  });

  it("keeps a stale proposal visible and explains an approval conflict", async () => {
    const projectId = crypto.randomUUID();
    const proposalHash = "c".repeat(64);
    server.use(
      http.get("/api/v1/projects/:projectId/export-proposals", () => HttpResponse.json([{
        id: crypto.randomUUID(), runId: crypto.randomUUID(), status: "PENDING", proposalHash,
        projectVersion: 0, approvedTaskId: null, createdAt: "2026-09-24T00:00:00Z",
        decidedAt: null,
        input: { schemaVersion: 1, aspectRatio: "LANDSCAPE_16_9",
          outputFormat: "SILENT_MP4_720P_24FPS", durationMs: 900,
          segments: [{ shotArtifactId: crypto.randomUUID(), shotVersionId: crypto.randomUUID(),
            videoArtifactId: crypto.randomUUID(), videoVersionId: crypto.randomUUID(),
            assetId: crypto.randomUUID(), assetSha256: "d".repeat(64), startMs: 0, endMs: 900 }] },
      }])),
      http.get("/api/v1/projects/:projectId/exports", () => HttpResponse.json([])),
      http.get("/api/v1/projects/:projectId/usage", () => HttpResponse.json([])),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/export-proposals/:proposalId/approve", () =>
        HttpResponse.json({ title: "提案冲突", detail: "版本已变化", status: 409,
          code: "EXPORT_PROPOSAL_CONFLICT", retryable: false }, {
          status: 409, headers: { "Content-Type": "application/problem+json" },
        })),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}>
      <MediaExportPanel projectId={projectId} items={[]} />
    </QueryClientProvider>);
    await user.click(await screen.findByRole("button", { name: "批准并开始导出" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("提案或所引用的版本已变化");
    expect(screen.getByRole("button", { name: "拒绝" })).toBeEnabled();
  });
});

function videoItem(projectId: string, title: string, artifactId: string): CanvasItem {
  const versionId = crypto.randomUUID();
  return {
    id: crypto.randomUUID(), subjectType: "ARTIFACT", subjectId: artifactId,
    x: 0, y: 0, width: 300, height: 200, zIndex: 0, groupId: null,
    locked: false, version: 0, agent: null,
    artifact: {
      id: artifactId, projectId, kind: "VIDEO", title,
      currentVersionId: versionId, version: 0,
      createdAt: "2026-09-23T00:00:00Z", updatedAt: "2026-09-23T00:00:00Z",
      currentVersion: {
        id: versionId, versionNo: 1, schemaVersion: 1,
        content: { assetId: crypto.randomUUID(), prompt: "Mock clip",
          providerConfigVersion: 1, workflowVersion: "mock-v1", parameters: {},
          sourceTaskId: crypto.randomUUID() }, inputReferences: [],
        createdByKind: "USER", runId: null, createdAt: "2026-09-23T00:00:00Z",
      },
    },
  };
}
