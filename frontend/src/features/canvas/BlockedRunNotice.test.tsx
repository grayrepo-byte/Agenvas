import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { BlockedRunNotice } from "./BlockedRunNotice";

describe.each([undefined, "chat"] as const)("BlockedRunNotice (%s)", (presentation) => {
  it("explains pinned model capability failure without showing private inputs", async () => {
    server.use(http.get("/api/v1/projects/:projectId/runs/:runId/tasks", () =>
      HttpResponse.json([{ id: "task-1", kind: "AGENT_TURN", status: "FAILED",
        errorCode: "LLM_CONFIG_UNAVAILABLE", input: { secret: "private-model-prompt" } }]),
    ));
    render(<QueryClientProvider client={createQueryClient()}>
      <BlockedRunNotice presentation={presentation} projectId="project-1" runId="run-1" />
    </QueryClientProvider>);
    expect(await screen.findByText(/固定的模型配置或工具调用能力不可用/)).toBeInTheDocument();
    expect(screen.getByText(/不会擅自切换到另一个模型/)).toBeInTheDocument();
    expect(screen.queryByText(/private-model-prompt/)).not.toBeInTheDocument();
  });

  it("keeps an unknown media blocker generic instead of inventing a model failure", async () => {
    server.use(http.get("/api/v1/projects/:projectId/runs/:runId/tasks", () =>
      HttpResponse.json([{ id: "task-2", kind: "IMAGE_GENERATION", status: "UNKNOWN",
        errorCode: "PROVIDER_SUBMISSION_UNKNOWN" }]),
    ));
    render(<QueryClientProvider client={createQueryClient()}>
      <BlockedRunNotice presentation={presentation} projectId="project-1" runId="run-2" />
    </QueryClientProvider>);
    expect(await screen.findByText(/UNKNOWN 核对提示/)).toBeInTheDocument();
    expect(screen.queryByText(/固定的模型配置或工具调用能力不可用/)).not.toBeInTheDocument();
  });

  it("explains the bounded structured-output repair failure", async () => {
    server.use(http.get("/api/v1/projects/:projectId/runs/:runId/tasks", () =>
      HttpResponse.json([{ id: "task-3", kind: "AGENT_TURN", status: "FAILED",
        errorCode: "MODEL_OUTPUT_INVALID" }]),
    ));
    render(<QueryClientProvider client={createQueryClient()}>
      <BlockedRunNotice presentation={presentation} projectId="project-1" runId="run-3" />
    </QueryClientProvider>);
    expect(await screen.findByText(/已达到两次修复或回合上限/)).toBeInTheDocument();
  });

  it("explains a durable model-turn ceiling without implying media is still polling", async () => {
    server.use(http.get("/api/v1/projects/:projectId/runs/:runId/tasks", () =>
      HttpResponse.json([{ id: "task-limit", kind: "AGENT_TURN", status: "FAILED",
        errorCode: "MODEL_TURN_LIMIT" }]),
    ));
    render(<QueryClientProvider client={createQueryClient()}>
      <BlockedRunNotice presentation={presentation} projectId="project-1" runId="run-limit" />
    </QueryClientProvider>);
    expect(await screen.findByText(/12 回合上限/)).toBeInTheDocument();
    expect(screen.getByText(/系统未再调用模型或自动重试/)).toBeInTheDocument();
  });

  it("requires a new approval when an unsubmitted media task is stale", async () => {
    server.use(http.get("/api/v1/projects/:projectId/runs/:runId/tasks", () =>
      HttpResponse.json([{ id: "task-4", kind: "IMAGE_GENERATION", status: "BLOCKED",
        errorCode: "TASK_INPUT_STALE" }]),
    ));
    render(<QueryClientProvider client={createQueryClient()}>
      <BlockedRunNotice presentation={presentation} projectId="project-1" runId="run-4" />
    </QueryClientProvider>);
    expect(await screen.findByText(/镜头、参考图或人工选定的关键帧版本已变化/)).toBeInTheDocument();
    expect(screen.getByText(/重新绑定当前镜头与所需素材后发起新 Run/)).toBeInTheDocument();
    expect(screen.getByText(/仍须分别由你审批/)).toBeInTheDocument();
    expect(screen.queryByText(/UNKNOWN 核对提示/)).not.toBeInTheDocument();
  });

  it("explains archived-project media blocking without claiming accepted work was canceled", async () => {
    server.use(http.get("/api/v1/projects/:projectId/runs/:runId/tasks", () =>
      HttpResponse.json([{ id: "task-5", kind: "IMAGE_GENERATION", status: "BLOCKED",
        errorCode: "TASK_PROJECT_ARCHIVED" }]),
    ));
    render(<QueryClientProvider client={createQueryClient()}>
      <BlockedRunNotice presentation={presentation} projectId="project-1" runId="run-5" />
    </QueryClientProvider>);
    expect(await screen.findByText(/项目已归档，尚未提交的媒体任务已阻断/)).toBeInTheDocument();
    expect(screen.getByText(/已受理的外部请求仍会核对/)).toBeInTheDocument();
    expect(screen.getByText(/TASK_PROJECT_ARCHIVED/)).toBeInTheDocument();
  });
});
