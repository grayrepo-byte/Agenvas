import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Artifact, Task } from "../../shared/api/client";
import { server } from "../../test/server";
import { TextGenerationEditor } from "./TextGenerationEditor";
import { useCanvasStore } from "./canvasStore";

const CREATED_AT = "2026-09-27T00:00:00Z";
const artifact: Artifact = {
  id: "artifact-1",
  projectId: "project-1",
  kind: "TEXT",
  title: "Notes",
  resourceDefaultVersionId: "version-2",
  version: 7,
  createdAt: CREATED_AT,
  updatedAt: CREATED_AT,
  resourceDefaultVersion: {
    id: "version-2",
    versionNo: 2,
    schemaVersion: 2,
    content: { format: "PLAIN_TEXT", text: "现有正文" },
    createdByKind: "USER",
    createdAt: CREATED_AT,
    inputReferences: [],
  },
};

function task(status: Task["status"]): Task {
  return {
    id: "task-1",
    projectId: "project-1",
    runId: null,
    stepKey: "direct-text:key",
    kind: "TEXT_GENERATION",
    status,
    cancelRequested: false,
    input: { prompt: "补成三段" },
    output:
      status === "SUCCEEDED" ? { artifactId: "artifact-1", artifactVersionId: "version-3", selected: true } : null,
    providerRequestId: null,
    attemptNo: 1,
    nextActionAt: CREATED_AT,
    version: 1,
    errorCode: null,
    createdAt: CREATED_AT,
    updatedAt: CREATED_AT,
    completedAt: status === "SUCCEEDED" ? CREATED_AT : null,
  };
}

function renderEditor(selectedArtifact = artifact, canvasItemId = "canvas-1", client = createQueryClient()) {
  return render(
    <QueryClientProvider client={client}>
      <TextGenerationEditor artifact={selectedArtifact} canvasItemId={canvasItemId} />
    </QueryClientProvider>,
  );
}

function configuredTasks(readTasks: () => Task[] = () => []) {
  server.use(
    http.get("/api/v1/settings/llm", () => HttpResponse.json({ configured: true, modelId: "qwen-text" })),
    http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({ llmMode: "CONFIGURED" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
    http.get("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", () =>
      HttpResponse.json(readTasks()),
    ),
  );
}

describe("TextGenerationEditor", () => {
  beforeEach(() => useCanvasStore.getState().clearTextGenerationDrafts());
  it("restores each node's own failure after another copy submits the same artifact", async () => {
    configuredTasks();
    server.use(
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", async ({ request }) => {
        const body = (await request.json()) as { prompt: string };
        return HttpResponse.json(
          { title: `提交失败：${body.prompt}` },
          {
            status: 503,
            headers: { "Content-Type": "application/problem+json" },
          },
        );
      }),
    );
    const client = createQueryClient();
    const user = userEvent.setup();
    let view = renderEditor(artifact, "canvas-1", client);
    await screen.findByText("qwen-text");
    await user.type(screen.getByRole("textbox"), "第一张卡片");
    await user.click(screen.getByRole("button", { name: "生成文字" }));
    await screen.findByText("提交失败：第一张卡片");
    view.unmount();
    view = renderEditor(artifact, "canvas-2", client);
    await user.type(screen.getByRole("textbox"), "第二张卡片");
    await user.click(screen.getByRole("button", { name: "生成文字" }));
    await screen.findByText("提交失败：第二张卡片");
    view.unmount();
    renderEditor(artifact, "canvas-1", client);
    expect(screen.getByRole("textbox")).toHaveValue("第一张卡片");
    expect(await screen.findByText("提交失败：第一张卡片")).toBeVisible();
    expect(screen.queryByText("提交失败：第二张卡片")).not.toBeInTheDocument();
  });
  it("retains memory-only prompts through closing and isolates projects and canvas nodes without writing content", async () => {
    configuredTasks();
    const client = createQueryClient();
    const user = userEvent.setup();
    let view = renderEditor(artifact, "canvas-1", client);
    await screen.findByText("qwen-text");
    const prompt = screen.getByRole("textbox", { name: "文字生成提示词" });
    await user.type(prompt, "第一张卡片的未提交想法");
    expect(prompt).toHaveAccessibleDescription("提示词仅保留在当前页面会话中；刷新或退出登录后清空。");
    view.unmount();
    view = renderEditor(artifact, "canvas-2", client);
    expect(screen.getByRole("textbox")).toHaveValue("");
    await user.type(screen.getByRole("textbox"), "同一素材的另一张卡片");
    view.unmount();
    view = renderEditor({ ...artifact, projectId: "project-2" }, "canvas-1", client);
    expect(screen.getByRole("textbox")).toHaveValue("");
    await user.type(screen.getByRole("textbox"), "另一项目的想法");
    view.unmount();
    view = renderEditor(artifact, "canvas-1", client);
    expect(screen.getByRole("textbox")).toHaveValue("第一张卡片的未提交想法");
    expect(artifact.resourceDefaultVersion?.content).toEqual({ format: "PLAIN_TEXT", text: "现有正文" });
    view.unmount();
    useCanvasStore.getState().clearTextGenerationDrafts();
    renderEditor(artifact, "canvas-1", client);
    expect(screen.getByRole("textbox")).toHaveValue("");
  });

  it("retains Chinese composition input even when closing before compositionend or switching rapidly", async () => {
    configuredTasks();
    const client = createQueryClient();
    const view = renderEditor(artifact, "canvas-1", client);
    await screen.findByText("qwen-text");
    const prompt = screen.getByRole("textbox");
    fireEvent.compositionStart(prompt);
    fireEvent.change(prompt, { target: { value: "创作" } });
    fireEvent.compositionUpdate(prompt, { data: "创作" });
    view.rerender(
      <QueryClientProvider client={client}>
        <TextGenerationEditor artifact={artifact} canvasItemId="canvas-2" />
      </QueryClientProvider>,
    );
    expect(screen.getByRole("textbox")).toHaveValue("");
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "第二张" } });
    view.rerender(
      <QueryClientProvider client={client}>
        <TextGenerationEditor artifact={artifact} canvasItemId="canvas-1" />
      </QueryClientProvider>,
    );
    expect(screen.getByRole("textbox")).toHaveValue("创作");
    fireEvent.compositionEnd(screen.getByRole("textbox"), { data: "创作" });
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "创作海边清晨" } });
    view.unmount();
    renderEditor(artifact, "canvas-1", client);
    expect(screen.getByRole("textbox")).toHaveValue("创作海边清晨");
  });

  it("keeps an in-flight submission locked after closing and preserves edits made before it completes", async () => {
    let tasks: Task[] = [];
    let finish: (() => void) | undefined;
    const submitted: unknown[] = [];
    const pending = new Promise<void>(resolve => {
      finish = resolve;
    });
    configuredTasks(() => tasks);
    server.use(
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", async ({ request }) => {
        submitted.push(await request.json());
        await pending;
        tasks = [task("READY")];
        return HttpResponse.json(tasks[0]);
      }),
    );
    const client = createQueryClient();
    const user = userEvent.setup();
    let view = renderEditor(artifact, "canvas-1", client);
    await screen.findByText("qwen-text");
    await user.type(screen.getByRole("textbox"), "第一次请求");
    await user.dblClick(screen.getByRole("button", { name: "生成文字" }));
    await waitFor(() => expect(submitted).toHaveLength(1));
    view.unmount();
    view = renderEditor(artifact, "canvas-1", client);
    expect(screen.getByRole("textbox")).toHaveValue("第一次请求");
    expect(screen.getByRole("button", { name: "正在提交文字生成" })).toBeDisabled();
    await user.clear(screen.getByRole("textbox"));
    await user.type(screen.getByRole("textbox"), "下一次的想法");
    view.unmount();
    view = renderEditor(artifact, "canvas-2", client);
    expect(screen.getByRole("button", { name: "正在提交文字生成" })).toBeDisabled();
    finish?.();
    await screen.findByText("正在生成文字…");
    view.unmount();
    renderEditor(artifact, "canvas-1", client);
    expect(screen.getByRole("textbox")).toHaveValue("下一次的想法");
    expect(screen.getByRole("button", { name: "生成文字" })).toBeDisabled();
    expect(submitted).toEqual([
      { prompt: "第一次请求", expectedArtifactVersion: 7, expectedCurrentVersionId: "version-2" },
    ]);
  });

  it.each([503, 409])(
    "restores a %s submission failure and reuses the pinned request only after explicit retry",
    async status => {
      configuredTasks();
      const submitted: { key: string | null; body: unknown }[] = [];
      server.use(
        http.post("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", async ({ request }) => {
          submitted.push({ key: request.headers.get("Idempotency-Key"), body: await request.json() });
          return submitted.length === 1
            ? HttpResponse.json(
                { title: "提交失败", detail: "合成提交失败" },
                { status, headers: { "Content-Type": "application/problem+json" } },
              )
            : HttpResponse.json(task("READY"));
        }),
      );
      const client = createQueryClient();
      const user = userEvent.setup();
      const view = renderEditor(artifact, "canvas-1", client);
      await screen.findByText("qwen-text");
      await user.type(screen.getByRole("textbox"), "保留并重试");
      await user.click(screen.getByRole("button", { name: "生成文字" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("合成提交失败");
      view.unmount();
      renderEditor({ ...artifact, version: 8, resourceDefaultVersionId: "version-3" }, "canvas-1", client);
      expect(screen.getByRole("textbox")).toHaveValue("保留并重试");
      expect(screen.getByRole("alert")).toHaveTextContent("合成提交失败");
      expect(submitted).toHaveLength(1);
      await user.click(screen.getByRole("button", { name: "生成文字" }));
      await waitFor(() => expect(submitted).toHaveLength(2));
      expect(submitted[1]).toEqual(submitted[0]);
    },
  );

  it("finishes a closed editor submission in its original project without changing another project's prompt", async () => {
    let finish: (() => void) | undefined;
    let accepted = false;
    let posts = 0;
    const pending = new Promise<void>(resolve => {
      finish = resolve;
    });
    configuredTasks();
    server.use(
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", ({ params }) =>
        HttpResponse.json(params.projectId === artifact.projectId && accepted ? [task("READY")] : []),
      ),
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", async ({ params }) => {
        expect(params.projectId).toBe(artifact.projectId);
        posts++;
        await pending;
        accepted = true;
        return HttpResponse.json(task("READY"));
      }),
    );
    const client = createQueryClient();
    const user = userEvent.setup();
    const view = renderEditor(artifact, "canvas-1", client);
    await screen.findByText("qwen-text");
    await user.type(screen.getByRole("textbox"), "原项目的请求");
    await user.click(screen.getByRole("button", { name: "生成文字" }));
    await waitFor(() => expect(posts).toBe(1));
    view.unmount();
    renderEditor({ ...artifact, projectId: "project-2" }, "canvas-1", client);
    await user.type(screen.getByRole("textbox"), "另一项目的草稿");
    await waitFor(() => expect(screen.getByRole("button", { name: "生成文字" })).toBeEnabled());
    finish?.();
    await waitFor(() => expect(client.isMutating()).toBe(0));
    expect(screen.getByRole("textbox")).toHaveValue("另一项目的草稿");
    expect(client.getQueryData(["direct-text-tasks", artifact.projectId, artifact.id])).toBeDefined();
    expect(posts).toBe(1);
  });

  it("clears an old submission error on editing and pins the next explicit attempt to the current version", async () => {
    configuredTasks();
    const requests: { key: string | null; body: unknown }[] = [];
    server.use(
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", async ({ request }) => {
        requests.push({ key: request.headers.get("Idempotency-Key"), body: await request.json() });
        return HttpResponse.json(
          { title: "提交失败" },
          { status: 503, headers: { "Content-Type": "application/problem+json" } },
        );
      }),
    );
    const client = createQueryClient();
    const user = userEvent.setup();
    const view = renderEditor(artifact, "canvas-1", client);
    await screen.findByText("qwen-text");
    await user.type(screen.getByRole("textbox"), "原来的想法");
    await user.click(screen.getByRole("button", { name: "生成文字" }));
    await screen.findByRole("alert");
    view.unmount();
    renderEditor({ ...artifact, version: 8, resourceDefaultVersionId: "version-3" }, "canvas-1", client);
    await user.clear(screen.getByRole("textbox"));
    await user.type(screen.getByRole("textbox"), "新的想法");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(requests).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: "生成文字" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[1]?.key).not.toBe(requests[0]?.key);
    expect(requests[1]?.body).toEqual({
      prompt: "新的想法",
      expectedArtifactVersion: 8,
      expectedCurrentVersionId: "version-3",
    });
  });

  it("keeps failed task prompts after reopening but uses a new key only for a new explicit generation", async () => {
    let tasks: Task[] = [];
    const keys: (string | null)[] = [];
    configuredTasks(() => tasks);
    server.use(
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", ({ request }) => {
        keys.push(request.headers.get("Idempotency-Key"));
        tasks = [task("FAILED")];
        return HttpResponse.json(tasks[0]);
      }),
    );
    const client = createQueryClient();
    const user = userEvent.setup();
    const view = renderEditor(artifact, "canvas-1", client);
    await screen.findByText("qwen-text");
    await user.type(screen.getByRole("textbox"), "任务失败也不丢输入");
    await user.click(screen.getByRole("button", { name: "生成文字" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "生成文字" })).toBeEnabled());
    view.unmount();
    renderEditor(artifact, "canvas-1", client);
    expect(screen.getByRole("textbox")).toHaveValue("任务失败也不丢输入");
    expect(keys).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: "生成文字" }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[1]).not.toBe(keys[0]);
  });
  it("guides missing text configuration to Provider settings and reloads without generating or losing the prompt", async () => {
    let configured = false;
    let generations = 0;
    server.use(
      http.get("/api/v1/settings/llm", () =>
        HttpResponse.json({ configured, version: 0, modelId: configured ? "configured-text" : null }),
      ),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({ llmMode: "CONFIGURED" })),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", () => HttpResponse.json([])),
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", () => {
        generations++;
        return HttpResponse.json(task("READY"));
      }),
    );
    renderEditor();
    const user = userEvent.setup();
    const link = await screen.findByRole("link", { name: "配置文字模型（新标签页）" });
    expect(link).toHaveAttribute("href", "/settings/providers");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
    const prompt = screen.getByRole("textbox", { name: "文字生成提示词" });
    await user.type(prompt, "我的未提交创意");
    expect(screen.getByRole("button", { name: "生成文字" })).toBeDisabled();
    configured = true;
    await user.click(screen.getByRole("button", { name: "重新读取配置" }));
    expect(await screen.findByText("configured-text")).toBeVisible();
    expect(screen.queryByRole("link", { name: "配置文字模型（新标签页）" })).not.toBeInTheDocument();
    expect(prompt).toHaveValue("我的未提交创意");
    expect(screen.getByRole("button", { name: "生成文字" })).toBeEnabled();
    expect(generations).toBe(0);
  });

  it("does not misreport Mock mode or a configuration read error as a missing model", async () => {
    let failing = false;
    server.use(
      http.get("/api/v1/settings/llm", () =>
        failing ? HttpResponse.json({ title: "读取失败" }, { status: 503 }) : HttpResponse.json({ configured: false }),
      ),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({ llmMode: "MOCK" })),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", () => HttpResponse.json([])),
    );
    const view = renderEditor();
    expect(await screen.findByText("Mock 文字演示")).toBeVisible();
    expect(screen.queryByRole("link", { name: "配置文字模型（新标签页）" })).not.toBeInTheDocument();
    view.unmount();
    failing = true;
    renderEditor();
    await screen.findByRole("alert");
    expect(screen.queryByRole("link", { name: "配置文字模型（新标签页）" })).not.toBeInTheDocument();
  });

  it("pins prompt and visible artifact version when submitting the configured model", async () => {
    let body: unknown;
    let key: string | null = null;
    let tasks: Task[] = [];
    server.use(
      http.get("/api/v1/settings/llm", () =>
        HttpResponse.json({
          configured: true,
          version: 4,
          endpoint: "https://model.example/v1",
          modelId: "qwen-text",
          keyMask: "****",
          toolCallingVerified: false,
          updatedAt: CREATED_AT,
        }),
      ),
      http.get("/api/v1/settings/diagnostics", () =>
        HttpResponse.json({
          checkedAt: CREATED_AT,
          database: "AVAILABLE",
          storage: "AVAILABLE",
          llmMode: "CONFIGURED",
          llmConfigured: true,
          llmToolCallingVerified: false,
          mediaMode: "MOCK",
          imageConfigured: true,
          videoConfigured: true,
          recentErrors: [],
        }),
      ),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", () => HttpResponse.json(tasks)),
      http.get("/api/v1/auth/csrf", () =>
        HttpResponse.json({
          headerName: "X-XSRF-TOKEN",
          token: "test-token",
        }),
      ),
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", async ({ request }) => {
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
    await waitFor(() =>
      expect(body).toEqual({ prompt: "补成三段", expectedArtifactVersion: 7, expectedCurrentVersionId: "version-2" }),
    );
    expect(key).toBeTruthy();
    expect(await screen.findByText("正在生成文字…")).toBeInTheDocument();
  });

  it("explains when a finished result stays in history after a concurrent edit", async () => {
    server.use(
      http.get("/api/v1/settings/llm", () =>
        HttpResponse.json({
          configured: false,
          version: 0,
          endpoint: null,
          modelId: null,
          keyMask: null,
          toolCallingVerified: false,
          updatedAt: null,
        }),
      ),
      http.get("/api/v1/settings/diagnostics", () =>
        HttpResponse.json({
          checkedAt: CREATED_AT,
          database: "AVAILABLE",
          storage: "AVAILABLE",
          llmMode: "MOCK",
          llmConfigured: true,
          llmToolCallingVerified: true,
          mediaMode: "MOCK",
          imageConfigured: true,
          videoConfigured: true,
          recentErrors: [],
        }),
      ),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", () =>
        HttpResponse.json([
          {
            ...task("SUCCEEDED"),
            output: {
              artifactId: "artifact-1",
              artifactVersionId: "version-3",
              selected: false,
            },
          },
        ]),
      ),
    );
    renderEditor();
    expect(await screen.findByText("Mock 文字演示")).toBeInTheDocument();
    expect(await screen.findByText("生成完成；卡片内容已变化，结果保存在版本历史中。")).toBeInTheDocument();
  });

  it.each(["UNKNOWN", "BLOCKED", "CANCELED"] as const)("shows %s without a generating indicator", async status => {
    server.use(
      http.get("/api/v1/settings/llm", () => HttpResponse.json({ configured: false })),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({ llmMode: "MOCK" })),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", () =>
        HttpResponse.json([task(status)]),
      ),
    );
    renderEditor();
    const message = status === "UNKNOWN" ? "结果未知" : status === "BLOCKED" ? "已阻断" : "本次文字生成已取消。";
    expect(await screen.findByText(message)).toBeVisible();
    expect(screen.queryByText("正在生成文字…")).not.toBeInTheDocument();
  });
});
