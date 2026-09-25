import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { VersionedArtifact } from "./versionedArtifact";
import { server } from "../../test/server";
import { ShotRedoEditor } from "./ShotRedoEditor";

describe("ShotRedoEditor", () => {
  it("shows the exact historical fraction and requires an explicit whole-second revision", async () => {
    const projectId = crypto.randomUUID();
    const shotId = crypto.randomUUID();
    const versionId = crypto.randomUUID();
    const requests: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/shots/:shotId/revisions", async ({ request }) => {
        requests.push(await request.json());
        return HttpResponse.json({ shot: {}, scene: null }, { status: 201 });
      }),
    );
    const shot: VersionedArtifact = {
      id: shotId, projectId, kind: "SHOT", title: "Legacy shot",
      currentVersionId: versionId, version: 1,
      createdAt: "2026-09-23T00:00:00Z", updatedAt: "2026-09-23T00:00:00Z",
      currentVersion: {
        id: versionId, versionNo: 1, schemaVersion: 1,
        content: { order: 1, durationMs: 1250, description: "Old shot",
          camera: "Wide", action: "Pour", characterVersionIds: [],
          sceneVersionId: crypto.randomUUID() },
        inputReferences: [], createdByKind: "USER", runId: null,
        createdAt: "2026-09-23T00:00:00Z",
      },
    };
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}><ShotRedoEditor artifact={shot} />
    </QueryClientProvider>);
    await user.click(screen.getByText("修改此镜头"));
    expect(screen.getByText(/需调整为整数秒/)).toBeVisible();
    expect(screen.getByLabelText("时长（秒）")).toHaveValue(1.25);
    expect(screen.getByRole("button", { name: "保存局部修改" })).toBeDisabled();
    await user.clear(screen.getByLabelText("时长（秒）"));
    await user.type(screen.getByLabelText("时长（秒）"), "2");
    await user.click(screen.getByRole("button", { name: "保存局部修改" }));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]).toMatchObject({ durationSeconds: 2, expectedShotVersionId: versionId });
  });

  it("sends a pinned second-shot revision and optional scene patch", async () => {
    const projectId = crypto.randomUUID();
    const shotId = crypto.randomUUID();
    const versionId = crypto.randomUUID();
    const requests: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/shots/:shotId/revisions", async ({ request }) => {
        requests.push(await request.json());
        return HttpResponse.json({ shot: {}, scene: {} }, { status: 201 });
      }),
    );
    const shot: VersionedArtifact = {
      id: shotId, projectId, kind: "SHOT", title: "Second shot",
      currentVersionId: versionId, version: 3,
      createdAt: "2026-09-23T00:00:00Z", updatedAt: "2026-09-23T00:00:00Z",
      currentVersion: {
        id: versionId, versionNo: 4, schemaVersion: 2,
        content: { order: 2, durationSeconds: 5, description: "Old shot",
          camera: "Wide", action: "Pour", characterVersionIds: [],
          sceneVersionId: crypto.randomUUID() },
        inputReferences: [], createdByKind: "USER", runId: null,
        createdAt: "2026-09-23T00:00:00Z",
      },
    };
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}><ShotRedoEditor artifact={shot} />
    </QueryClientProvider>);
    await user.click(screen.getByText("修改此镜头"));
    await user.clear(screen.getByLabelText("描述"));
    await user.type(screen.getByLabelText("描述"), "New second shot");
    await user.type(screen.getByLabelText("新场景时间（可选）"), "黄昏");
    await user.click(screen.getByRole("button", { name: "保存局部修改" }));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]).toMatchObject({
      expectedShotVersionId: versionId, expectedShotArtifactVersion: 3,
      description: "New second shot", camera: "Wide", action: "Pour",
      durationSeconds: 5, scene: { timeOfDay: "黄昏" },
    });
    expect(await screen.findByRole("status")).toHaveTextContent("媒体计划仍需单独审批");
  });
});
