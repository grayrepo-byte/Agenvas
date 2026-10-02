import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Artifact, Task } from "../../shared/api/client";
import { server } from "../../test/server";
import { TextGenerationEditor } from "./TextGenerationEditor";

const CREATED_AT = "2026-09-27T00:00:00Z";
const artifact: Artifact = {
  id: "artifact-1", projectId: "project-1", kind: "TEXT", title: "Notes",
  resourceDefaultVersionId: "version-2", version: 7, createdAt: CREATED_AT, updatedAt: CREATED_AT,
  resourceDefaultVersion: { id: "version-2", versionNo: 2, schemaVersion: 2,
    content: { format: "PLAIN_TEXT", text: "现有正文" }, createdByKind: "USER",
    createdAt: CREATED_AT, inputReferences: [] },
};

function task(status: Task["status"]): Task {
  return { id: "task-1", projectId: "project-1", runId: null,
    stepKey: "direct-text:key", kind: "TEXT_GENERATION", status, cancelRequested: false,
    input: { prompt: "补成三段" }, output: status === "SUCCEEDED"
      ? { artifactId: "artifact-1", artifactVersionId: "version-3", selected: true } : null,
    providerRequestId: null, attemptNo: 1, nextActionAt: CREATED_AT,
    version: 1, errorCode: null, createdAt: CREATED_AT, updatedAt: CREATED_AT,
    completedAt: status === "SUCCEEDED" ? CREATED_AT : null };
}

function renderEditor() {
  return render(<QueryClientProvider client={createQueryClient()}>
    <TextGenerationEditor artifact={artifact} />
  </QueryClientProvider>);
}

describe("TextGenerationEditor", () => {
  it("pins prompt and visible artifact version when submitting the configured model", async () => {
    let body: unknown;
    let key: string | null = null;
    let tasks: Task[] = [];
    server.use(
      http.get("/api/v1/settings/llm", () => HttpResponse.json({ configured: true,
        version: 4, endpoint: "https://model.example/v1", modelId: "qwen-text",
        keyMask: "****", toolCallingVerified: false, updatedAt: CREATED_AT })),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({ checkedAt: CREATED_AT,
        database: "AVAILABLE", storage: "AVAILABLE", llmMode: "CONFIGURED",
        llmConfigured: true, llmToolCallingVerified: false, mediaMode: "MOCK",
        imageConfigured: true, videoConfigured: true, recentErrors: [] })),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", () =>
        HttpResponse.json(tasks)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations",
        async ({ request }) => {
          body = await request.json();
          key = request.headers.get("Idempotency-Key");
          tasks = [task("READY")];
          return HttpResponse.json(tasks[0]);
        }),
    );
    renderEditor();
    const user = userEvent.setup();
    expect(await screen.findByText("qwen-text")).toBeInTheDocument();
    await user.type(screen.getByLabelText("文字生成提示词"), "补成三段");
    await user.click(screen.getByRole("button", { name: "生成文字" }));
    await waitFor(() => expect(body).toEqual({ prompt: "补成三段",
      expectedArtifactVersion: 7, expectedCurrentVersionId: "version-2" }));
    expect(key).toBeTruthy();
    expect(await screen.findByText("正在生成文字…")).toBeInTheDocument();
  });

  it("explains when a finished result stays in history after a concurrent edit", async () => {
    server.use(
      http.get("/api/v1/settings/llm", () => HttpResponse.json({ configured: false,
        version: 0, endpoint: null, modelId: null, keyMask: null,
        toolCallingVerified: false, updatedAt: null })),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({ checkedAt: CREATED_AT,
        database: "AVAILABLE", storage: "AVAILABLE", llmMode: "MOCK",
        llmConfigured: true, llmToolCallingVerified: true, mediaMode: "MOCK",
        imageConfigured: true, videoConfigured: true, recentErrors: [] })),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", () =>
        HttpResponse.json([{ ...task("SUCCEEDED"), output: {
          artifactId: "artifact-1", artifactVersionId: "version-3", selected: false } }])),
    );
    renderEditor();
    expect(await screen.findByText("Mock 文字演示")).toBeInTheDocument();
    expect(await screen.findByText("生成完成；卡片内容已变化，结果保存在版本历史中。"))
      .toBeInTheDocument();
  });
});
