import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { KeyframeSelectionPanel } from "./KeyframeSelectionPanel";

describe.each([undefined, "chat"] as const)("KeyframeSelectionPanel (%s)", (presentation) => {
  it("requires an explicit click before persisting a completed image version", async () => {
    let saved = 0;
    server.use(
      http.get("/api/v1/projects/:projectId/runs/:runId/tasks", () => HttpResponse.json([{
        id: "task-1", kind: "IMAGE_GENERATION", status: "SUCCEEDED",
        input: { shotArtifactId: "shot-1", shotVersionId: "shot-version-1", prompt: "晨光" },
        output: { artifactId: "image-1", artifactVersionId: "image-version-1" },
      }])),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId", () => HttpResponse.json({
        id: "image-1", currentVersion: { id: "image-version-1", content: { assetId: "asset-1" } },
      })),
      http.get("/api/v1/projects/:projectId/runs/:runId/shots/:shotId/keyframe-selection",
        () => HttpResponse.json({ title: "未选择", code: "RESOURCE_NOT_FOUND", retryable: false },
          { status: 404, headers: { "content-type": "application/problem+json" } })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/projects/:projectId/runs/:runId/shots/:shotId/keyframe-selection",
        async ({ request }) => {
          expect(await request.json()).toEqual({
            shotVersionId: "shot-version-1", imageArtifactId: "image-1",
            imageVersionId: "image-version-1", expectedVersion: null,
          });
          saved += 1;
          return HttpResponse.json({ imageVersionId: "image-version-1", version: 0 });
        }),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}>
      <KeyframeSelectionPanel presentation={presentation} projectId="project-1" runId="run-1" />
    </QueryClientProvider>);
    expect(await screen.findByText(/图片版本 image-version-1/)).toBeInTheDocument();
    expect(saved).toBe(0);
    await user.click(await screen.findByRole("button", { name: "选为此镜头关键帧" }));
    expect(saved).toBe(1);
    expect(await screen.findByRole("button", { name: "已选为关键帧" })).toBeDisabled();
  });

  it("hides keyframe choices after video tasks have fixed their inputs", async () => {
    server.use(http.get("/api/v1/projects/:projectId/runs/:runId/tasks", () => HttpResponse.json([
      { id: "image-task", kind: "IMAGE_GENERATION", status: "SUCCEEDED",
        input: { shotArtifactId: "shot-1", shotVersionId: "shot-version-1" },
        output: { artifactId: "image-1", artifactVersionId: "image-version-1" } },
      { id: "video-task", kind: "VIDEO_GENERATION", status: "READY",
        input: { imageVersionId: "image-version-1" }, output: null },
    ])));
    render(<QueryClientProvider client={createQueryClient()}>
      <KeyframeSelectionPanel presentation={presentation} projectId="project-1" runId="run-1" />
    </QueryClientProvider>);
    expect(await screen.findByText("正在读取关键帧结果…")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText("正在读取关键帧结果…")).not.toBeInTheDocument());
    expect(screen.queryByRole("region", { name: "选择镜头关键帧" })).not.toBeInTheDocument();
  });
});
