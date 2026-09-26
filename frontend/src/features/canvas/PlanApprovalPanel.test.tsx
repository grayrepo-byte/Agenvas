import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { PlanApprovalPanel } from "./PlanApprovalPanel";

describe("PlanApprovalPanel", () => {
  beforeEach(() => {
    server.use(http.get("/api/v1/projects/:projectId/plans/:planId/steps/:stepKey/candidates",
      () => HttpResponse.json([])));
  });
  it("hides an empty chat approval card while keeping real read failures visible", async () => {
    server.use(http.get("/api/v1/projects/:projectId/runs/:runId/plans", () => HttpResponse.json([])));
    const client = createQueryClient();
    render(<QueryClientProvider client={client}>
      <PlanApprovalPanel projectId="project-1" runId="run-1" />
    </QueryClientProvider>);
    await waitFor(() => expect(client.getQueryState(["plans", "project-1", "run-1"])?.status).toBe("success"));
    expect(screen.queryByRole("region", { name: "待审批执行计划" })).not.toBeInTheDocument();
    server.use(http.get("/api/v1/projects/:projectId/runs/:runId/plans", () =>
      HttpResponse.json({ code: "PLAN_UNAVAILABLE", detail: "无法读取" }, { status: 503 })));
    await client.invalidateQueries({ queryKey: ["plans", "project-1", "run-1"] });
    expect(await screen.findByRole("alert")).toHaveTextContent("计划读取失败");
  });

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
        expect(await request.json()).toEqual({ planHash: hash,
          confirmedStepKeys: ["shot-1", "shot-2", "shot-3"] });
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
    expect(screen.getByRole("button", { name: "确认执行此计划" })).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: /确认镜头 shot-1/ }));
    await user.click(screen.getByRole("checkbox", { name: /确认镜头 shot-2/ }));
    await user.click(screen.getByRole("checkbox", { name: /确认镜头 shot-3/ }));
    expect(screen.getByRole("button", { name: "确认执行此计划" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "确认执行此计划" }));
    expect(approvals).toBe(1);
  });

  it("clears step confirmation and uses the new hash after a capability change", async () => {
    const firstCapability = "00000000-0000-4000-8000-000000000102";
    const secondCapability = "00000000-0000-4000-8000-000000000104";
    const original = {
      id: "plan-original", projectId: "project-1", runId: "run-1", revision: 1,
      stage: "IMAGE", status: "PENDING", objective: "为镜头制作关键帧",
      steps: [{ stepKey: "shot-1", kind: "IMAGE_GENERATION", shotArtifactId: "shot-1",
        shotVersionId: "version-1", imageVersionId: null, input: { prompt: "晨光" },
        binding: { connectionId: "mock", connectionVersion: 1, capabilityId: firstCapability,
          capabilityVersion: 1, adapterId: "MOCK_IMAGE", mappingSha256: "a".repeat(64) } }],
      estimate: { imageCount: 1, videoCount: 0, costSource: "MOCK_UNPRICED" },
      workflowVersion: "mock-image-v1", providerConfigVersion: 1, planHash: "a".repeat(64),
    };
    const revised = { ...original, id: "plan-revised", revision: 2, planHash: "b".repeat(64),
      steps: [{ ...original.steps[0]!, binding: {
        ...original.steps[0]!.binding, capabilityId: secondCapability } }] };
    let current = original;
    let approvals = 0;
    server.use(
      http.get("/api/v1/projects/:projectId/runs/:runId/plans", () => HttpResponse.json([current])),
      http.get("/api/v1/projects/:projectId/plans/:planId/steps/:stepKey/candidates", () =>
        HttpResponse.json([firstCapability, secondCapability].map((capabilityId, index) => ({
          binding: { ...original.steps[0]!.binding, capabilityId },
          connectionName: "Mock", capabilityName: `图片能力 ${index + 1}`,
          kind: "IMAGE_GENERATION", minimumSeconds: 0, maximumSeconds: 0,
          realGenerationTested: false,
        })))),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/projects/:projectId/plans/:planId/steps/:stepKey/revise", async ({ request }) => {
        expect(await request.json()).toEqual({ expectedPlanHash: original.planHash,
          capabilityId: secondCapability, inputPatch: {} });
        current = revised;
        return HttpResponse.json(revised);
      }),
      http.post("/api/v1/projects/:projectId/plans/:planId/approve", async ({ request }) => {
        expect(await request.json()).toEqual({ planHash: revised.planHash,
          confirmedStepKeys: ["shot-1"] });
        approvals += 1;
        return HttpResponse.json({ approvalId: "approval-1", plan: { ...revised, status: "APPROVED" },
          tasks: [], replayed: false });
      }),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}>
      <PlanApprovalPanel projectId="project-1" runId="run-1" />
    </QueryClientProvider>);

    await user.click(await screen.findByRole("checkbox", { name: /确认镜头 shot-1/ }));
    expect(screen.getByRole("button", { name: "确认执行此计划" })).toBeEnabled();
    await user.selectOptions(screen.getByRole("combobox", { name: "生成能力" }), secondCapability);
    await waitFor(() => expect(screen.getByText(/计划修订 2/)).toBeInTheDocument());
    expect(screen.getByRole("checkbox", { name: /确认镜头 shot-1/ })).not.toBeChecked();
    expect(screen.getByRole("button", { name: "确认执行此计划" })).toBeDisabled();
    expect(approvals).toBe(0);
    await user.click(screen.getByRole("checkbox", { name: /确认镜头 shot-1/ }));
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
