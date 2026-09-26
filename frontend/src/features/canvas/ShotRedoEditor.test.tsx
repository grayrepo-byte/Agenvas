import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { VersionedArtifact } from "./versionedArtifact";
import { server } from "../../test/server";
import { ShotRedoEditor } from "./ShotRedoEditor";

function revisedShot(base: VersionedArtifact, patch: Record<string, string | number>): VersionedArtifact {
  return { ...base, version: base.version + 1, currentVersionId: "shot-version-new", currentVersion: {
    ...base.currentVersion, id: "shot-version-new", versionNo: base.currentVersion.versionNo + 1, content: { ...base.currentVersion.content, ...patch },
  } };
}

function sampleShot(): VersionedArtifact {
  return { id: "shot-1", projectId: "project-1", kind: "SHOT", title: "Opening shot", version: 3,
    currentVersionId: "shot-version-1", createdAt: "2026-09-23T00:00:00Z", updatedAt: "2026-09-23T00:00:00Z",
    currentVersion: { id: "shot-version-1", versionNo: 1, schemaVersion: 2,
      content: { order: 1, durationSeconds: 5, description: "Opening shot", camera: "Wide", action: "Walk",
        characterVersionIds: [], sceneVersionId: "scene-version-1" },
      inputReferences: [], createdByKind: "USER", runId: null, createdAt: "2026-09-23T00:00:00Z" } };
}

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
        return HttpResponse.json({ shot: revisedShot(shot, { durationSeconds: 2 }), scene: null }, { status: 201 });
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
    expect(screen.getByLabelText("描述")).toBeVisible();
    expect(screen.getByLabelText("描述")).toHaveAttribute("data-content-editor-focus", "true");
    screen.getByLabelText("描述").focus();
    expect(screen.getByLabelText("描述")).toHaveFocus();
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
        return HttpResponse.json({ shot: revisedShot(shot, { description: "New second shot" }), scene: {} }, { status: 201 });
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

  it("pins an edited shot to its original version after a remote revision and reloads only explicitly", async () => {
    const shot = sampleShot();
    const latest = revisedShot(shot, { description: "Remote new shot", durationSeconds: 8 });
    const requests: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId", () => HttpResponse.json(latest)),
      http.post("/api/v1/projects/:projectId/shots/:shotId/revisions", async ({ request }) => {
        requests.push(await request.json());
        return HttpResponse.json({ title: "冲突", detail: "镜头版本已变化", code: "SHOT_VERSION_CONFLICT" },
          { status: 409, headers: { "Content-Type": "application/problem+json" } });
      }),
    );
    const client = createQueryClient();
    const editor = render(<QueryClientProvider client={client}><ShotRedoEditor artifact={shot} /></QueryClientProvider>);
    const user = userEvent.setup();
    expect(screen.getByRole("button", { name: "保存局部修改" })).toBeDisabled();
    await user.clear(screen.getByLabelText("描述"));
    await user.type(screen.getByLabelText("描述"), "My local shot");
    await user.type(screen.getByLabelText("新场景时间（可选）"), "黄昏");
    editor.rerender(<QueryClientProvider client={client}><ShotRedoEditor artifact={latest} /></QueryClientProvider>);
    expect(screen.getByLabelText("描述")).toHaveValue("My local shot");
    expect(screen.getByLabelText("时长（秒）")).toHaveValue(5);
    await user.click(screen.getByRole("button", { name: "保存局部修改" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("输入已保留");
    expect(requests).toEqual([{ expectedShotVersionId: shot.currentVersionId, expectedShotArtifactVersion: 3,
      description: "My local shot", camera: "Wide", action: "Walk", durationSeconds: 5, scene: { timeOfDay: "黄昏" } }]);
    expect(screen.getByLabelText("新场景时间（可选）")).toHaveValue("黄昏");
    await user.click(screen.getByRole("button", { name: "载入最新版本" }));
    await waitFor(() => expect(screen.getByLabelText("描述")).toHaveValue("Remote new shot"));
    expect(screen.getByLabelText("时长（秒）")).toHaveValue(8);
    expect(screen.getByLabelText("新场景时间（可选）")).toHaveValue("");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("disables repeated saving and preserves all local fields when saving fails", async () => {
    let attempts = 0;
    let finish: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => { finish = resolve; });
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post("/api/v1/projects/:projectId/shots/:shotId/revisions", async () => {
        attempts++; await gate;
        return HttpResponse.json({ title: "暂时失败", detail: "镜头保存不可用" },
          { status: 503, headers: { "Content-Type": "application/problem+json" } });
      }),
    );
    render(<QueryClientProvider client={createQueryClient()}><ShotRedoEditor artifact={sampleShot()} /></QueryClientProvider>);
    const user = userEvent.setup();
    await user.clear(screen.getByLabelText("动作")); await user.type(screen.getByLabelText("动作"), "Run quickly");
    await user.type(screen.getByLabelText("新场景时间（可选）"), "夜晚");
    await user.click(screen.getByRole("button", { name: "保存局部修改" }));
    await waitFor(() => expect(attempts).toBe(1));
    const description = screen.getByLabelText("描述");
    expect(description).toBeDisabled();
    expect(screen.getByRole("button", { name: "保存新版本中…" })).toBeDisabled();
    if (!(description instanceof HTMLTextAreaElement) || !description.form) throw new Error("Missing editor form");
    fireEvent.submit(description.form);
    expect(attempts).toBe(1);
    if (!finish) throw new Error("Missing save gate");
    finish();
    expect(await screen.findByRole("alert")).toHaveTextContent("镜头保存不可用 输入已保留。");
    expect(screen.getByLabelText("动作")).toHaveValue("Run quickly");
    expect(screen.getByLabelText("新场景时间（可选）")).toHaveValue("夜晚");
    expect(screen.getByRole("button", { name: "保存局部修改" })).toBeEnabled();
  });
});
