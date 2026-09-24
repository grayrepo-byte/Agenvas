import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { PlanApprovalPanel } from "./PlanApprovalPanel";

describe("PlanApprovalPanel", () => {
  it("shows the frozen scope and sends the displayed hash only after an explicit click", async () => {
    const hash = "a".repeat(64);
    const plan = {
      id: "00000000-0000-0000-0000-000000000011",
      projectId: "project-1",
      runId: "run-1",
      revision: 1,
      stage: "IMAGE",
      status: "PENDING",
      objective: "为三个镜头制作关键帧",
      steps: [1, 2, 3].map((number) => ({ stepKey: `shot-${number}`, kind: "IMAGE_GENERATION",
        shotVersionId: `shot-version-${number}`, imageVersionId: null,
        input: { prompt: "晨光中的街道" } })),
      estimate: { imageCount: 3, videoCount: 0, costSource: "MOCK_UNPRICED" },
      workflowVersion: "mock-image-v1",
      providerConfigVersion: 1,
      planHash: hash,
    };
    let approvals = 0;
    server.use(
      http.get("/api/v1/projects/:projectId/runs/:runId/plans", () => HttpResponse.json([plan])),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/projects/:projectId/plans/:planId/approve", async ({ request }) => {
        expect(await request.json()).toEqual({ planHash: hash });
        approvals += 1;
        return HttpResponse.json({ approvalId: "approval-1", plan: { ...plan, status: "APPROVED" },
          tasks: [1, 2, 3].map((number) => ({ id: `task-${number}`, kind: "IMAGE_GENERATION" })), replayed: false });
      }),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}>
      <PlanApprovalPanel projectId="project-1" runId="run-1" />
    </QueryClientProvider>);

    expect(await screen.findByText("为三个镜头制作关键帧")).toBeInTheDocument();
    expect(screen.getByText(/本次将创建图片任务 3 个、视频任务 0 个/)).toBeInTheDocument();
    expect(screen.getByText(/MOCK_UNPRICED/)).toBeInTheDocument();
    expect(approvals).toBe(0);
    await user.click(screen.getByRole("button", { name: "确认执行此计划" }));
    expect(approvals).toBe(1);
  });

  it("blocks approval when the server's estimate differs from its executable steps", async () => {
    const plan = {
      id: "plan-2", projectId: "project-1", runId: "run-1", revision: 1,
      stage: "VIDEO", status: "PENDING", objective: "制作视频",
      steps: [{ stepKey: "shot-1", kind: "VIDEO_GENERATION", shotVersionId: "shot-version-1",
        imageVersionId: "image-version-1", input: { prompt: "街道" } }],
      estimate: { imageCount: 0, videoCount: 2, costSource: "MOCK_UNPRICED" },
      workflowVersion: "mock-video-v1", providerConfigVersion: 1, planHash: "b".repeat(64),
    };
    let approvals = 0;
    server.use(
      http.get("/api/v1/projects/:projectId/runs/:runId/plans", () => HttpResponse.json([plan])),
      http.post("/api/v1/projects/:projectId/plans/:planId/approve", () => {
        approvals += 1;
        return HttpResponse.json({});
      }),
    );
    render(<QueryClientProvider client={createQueryClient()}>
      <PlanApprovalPanel projectId="project-1" runId="run-1" />
    </QueryClientProvider>);

    expect(await screen.findByText(/本次将创建图片任务 0 个、视频任务 1 个/)).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("计划步骤与预计生成数量不一致");
    expect(screen.getByRole("button", { name: "确认执行此计划" })).toBeDisabled();
    expect(approvals).toBe(0);
  });

  it("cancels the Run from the review panel without approving its plan", async () => {
    const plan = {
      id: "plan-3", projectId: "project-1", runId: "run-1", revision: 1,
      stage: "IMAGE", status: "PENDING", objective: "制作关键帧",
      steps: [{ stepKey: "shot-1", kind: "IMAGE_GENERATION", shotVersionId: "shot-version-1",
        imageVersionId: null, input: { prompt: "街道" } }],
      estimate: { imageCount: 1, videoCount: 0, costSource: "MOCK_UNPRICED" },
      workflowVersion: "mock-image-v1", providerConfigVersion: 1, planHash: "c".repeat(64),
    };
    let cancellations = 0;
    let approvals = 0;
    server.use(
      http.get("/api/v1/projects/:projectId/runs/:runId/plans", () => HttpResponse.json([plan])),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/projects/:projectId/plans/:planId/approve", () => {
        approvals += 1;
        return HttpResponse.json({});
      }),
      http.post("/api/v1/projects/:projectId/runs/:runId/cancel", ({ request }) => {
        expect(request.headers.get("X-XSRF-TOKEN")).toBe("test");
        cancellations += 1;
        return HttpResponse.json({ id: "run-1", status: "CANCEL_REQUESTED" });
      }),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}>
      <PlanApprovalPanel projectId="project-1" runId="run-1" />
    </QueryClientProvider>);

    await user.click(await screen.findByRole("button", { name: "取消本次 Run" }));
    expect(cancellations).toBe(1);
    expect(approvals).toBe(0);
  });

  it("keeps the plan available when cancellation fails", async () => {
    const plan = {
      id: "plan-4", projectId: "project-1", runId: "run-1", revision: 1,
      stage: "IMAGE", status: "PENDING", objective: "待确认关键帧",
      steps: [{ stepKey: "shot-1", kind: "IMAGE_GENERATION", shotVersionId: "shot-version-1",
        imageVersionId: null, input: { prompt: "街道" } }],
      estimate: { imageCount: 1, videoCount: 0, costSource: "MOCK_UNPRICED" },
      workflowVersion: "mock-image-v1", providerConfigVersion: 1, planHash: "d".repeat(64),
    };
    server.use(
      http.get("/api/v1/projects/:projectId/runs/:runId/plans", () => HttpResponse.json([plan])),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/projects/:projectId/runs/:runId/cancel", () =>
        HttpResponse.json({ title: "冲突", detail: "运行状态已变化" }, { status: 409 })),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}>
      <PlanApprovalPanel projectId="project-1" runId="run-1" />
    </QueryClientProvider>);

    await user.click(await screen.findByRole("button", { name: "取消本次 Run" }));
    expect(await screen.findByText(/取消失败/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "取消本次 Run" })).toBeEnabled();
    expect(screen.getByText("待确认关键帧")).toBeInTheDocument();
  });
});
