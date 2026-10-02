import { QueryClientProvider, useQuery } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { listRunMediaApprovals, type AgentMediaApproval, type AgentMediaApprovalDecision } from "../../shared/api/client";
import { server } from "../../test/server";
import { AgentMediaApprovalCard } from "./AgentMediaApprovalCard";

const PROJECT_ID = "approval-project";
const RUN_ID = "approval-run";
const APPROVAL_ID = "approval-1";
const APPROVAL_URL = `/api/v1/projects/${PROJECT_ID}/runs/${RUN_ID}/media-approvals`;
const NOW = "2026-10-02T00:00:00Z";
const REFERENCE_VERSION = "synthetic-reference-version";

function approval(overrides: Partial<AgentMediaApproval> = {}): AgentMediaApproval {
  return {
    id: APPROVAL_ID, projectId: PROJECT_ID, runId: RUN_ID, operationId: "operation-1",
    status: "PENDING", version: 0, taskIds: [], result: null,
    createdAt: NOW, expiresAt: "2026-10-03T00:00:00Z", executionDeadline: null,
    outputs: [{ kind: "VIDEO", title: "海岸短片", artifactId: "artifact-1", canvasItemId: "item-1", draftVersion: 0,
      preview: { prompt: "海浪缓缓拍打礁石。", adapterId: "MOCK_VIDEO", capabilityId: "capability-1",
        priceUnknown: true, durationSeconds: 5, videoInputMode: "GENERAL_REFERENCE",
        parameters: { aspectRatio: "16:9", seed: 42 },
        mediaInputs: [{ versionId: REFERENCE_VERSION, role: "REFERENCE" }] } }], ...overrides,
  };
}

function mountCard(initial: AgentMediaApproval, disabled = false) {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  function ConnectedCard() {
    const current = useQuery({ queryKey: ["run-media-approvals", PROJECT_ID, RUN_ID],
      queryFn: () => listRunMediaApprovals(PROJECT_ID, RUN_ID), initialData: [initial] });
    const item = current.data[0];
    return item ? <AgentMediaApprovalCard projectId={PROJECT_ID} runId={RUN_ID} approval={item} disabled={disabled} /> : null;
  }
  server.use(
    http.get(APPROVAL_URL, () => HttpResponse.json([initial])),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic-csrf-token" })),
  );
  render(<QueryClientProvider client={client}><ConnectedCard /></QueryClientProvider>);
  return client;
}

describe("AgentMediaApprovalCard", () => {
  it("shows the frozen prompt, parameters, inputs and unknown cost without exposing provider configuration", () => {
    const pending = approval();
    const output = pending.outputs[0];
    if (!output) throw new Error("Synthetic approval output is required");
    output.preview.endpoint = "PRIVATE_PROVIDER_ENDPOINT";
    output.preview.apiKey = "PRIVATE_PROVIDER_KEY";
    output.preview.parameters = { aspectRatio: "16:9", seed: 42, nested: { apiKey: "PRIVATE_NESTED_KEY", quality: "high" } };
    mountCard(pending);
    const card = screen.getByRole("region", { name: "批准媒体生成 · 1 项" });
    expect(within(card).getByText("海浪缓缓拍打礁石。")).toBeVisible();
    expect(within(card).getByText("MOCK_VIDEO")).toBeVisible();
    expect(within(card).getByText("16:9")).toBeVisible();
    expect(within(card).getByText("42")).toBeVisible();
    expect(within(card).getByText(REFERENCE_VERSION)).toBeVisible();
    expect(within(card).getByText("费用未知")).toBeVisible();
    expect(within(card).getByRole("button", { name: "批准并生成" })).toBeEnabled();
    for (const secret of ["PRIVATE_PROVIDER_ENDPOINT", "PRIVATE_PROVIDER_KEY", "PRIVATE_NESTED_KEY"]) {
      expect(card).not.toHaveTextContent(secret);
    }
  });

  it("submits the whole batch once with its version and idempotency key while both decisions are disabled", async () => {
    const pending = approval();
    const first = pending.outputs[0];
    if (!first) throw new Error("Synthetic approval output is required");
    pending.outputs.push({ ...first, kind: "AUDIO", title: "海岸旁白", canvasItemId: "item-2", artifactId: "artifact-2" });
    let current = pending;
    const bodies: AgentMediaApprovalDecision[] = [];
    const keys: (string | null)[] = [];
    let finish: (() => void) | undefined;
    const received = new Promise<void>((resolve) => { finish = resolve; });
    mountCard(pending);
    server.use(
      http.get(APPROVAL_URL, () => HttpResponse.json([current])),
      http.post(`${APPROVAL_URL}/${APPROVAL_ID}/decision`, async ({ request }) => {
        bodies.push(await request.json() as AgentMediaApprovalDecision);
        keys.push(request.headers.get("Idempotency-Key"));
        expect(request.headers.get("X-XSRF-TOKEN")).toBeTruthy();
        await received;
        current = { ...pending, status: "APPROVED", version: 1, taskIds: ["task-1", "task-2"] };
        return HttpResponse.json(current);
      }),
    );
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "批准并生成" }));
    await waitFor(() => expect(bodies).toHaveLength(1));
    const submitting = screen.getByRole("button", { name: "正在提交…" });
    expect(submitting).toBeDisabled();
    expect(screen.getByRole("button", { name: "拒绝" })).toBeDisabled();
    fireEvent.click(submitting);
    expect(bodies).toEqual([{ expectedVersion: 0, decision: "APPROVE" }]);
    expect(keys[0]).toBeTruthy();
    if (!finish) throw new Error("Submission barrier was not created");
    finish();
    expect(await screen.findByText("已批准 · 生成中")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "批准并生成" })).not.toBeInTheDocument();
    expect(bodies).toHaveLength(1);
  });

  it("reuses the same idempotency key when retrying a failed decision", async () => {
    const pending = approval();
    const keys: (string | null)[] = [];
    mountCard(pending);
    server.use(http.post(`${APPROVAL_URL}/${APPROVAL_ID}/decision`, ({ request }) => {
      keys.push(request.headers.get("Idempotency-Key"));
      return keys.length === 1 ? HttpResponse.json({ title: "合成服务暂不可用", code: "SYNTHETIC_UNAVAILABLE", detail: "合成服务暂不可用" }, { status: 503 })
        : HttpResponse.json({ ...pending, status: "APPROVED", version: 1, taskIds: ["task-1"] });
    }));
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "批准并生成" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("请求未完成");
    await waitFor(() => expect(screen.getByRole("button", { name: "批准并生成" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "批准并生成" }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
  });

  it("refreshes a 409 conflict while preserving a visible explanation", async () => {
    const pending = approval();
    let current = pending;
    mountCard(pending);
    server.use(
      http.get(APPROVAL_URL, () => HttpResponse.json([current])),
      http.post(`${APPROVAL_URL}/${APPROVAL_ID}/decision`, () => {
        current = { ...pending, status: "CANCELED", version: 1 };
        return HttpResponse.json({ code: "AGENT_MEDIA_APPROVAL_CONFLICT", detail: "Synthetic version conflict" }, { status: 409 });
      }),
    );
    await userEvent.setup().click(screen.getByRole("button", { name: "批准并生成" }));
    expect(await screen.findByText("已取消")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("审批版本或生成输入已变化。已刷新当前状态，请重新核对。");
    expect(screen.queryByRole("button", { name: "批准并生成" })).not.toBeInTheDocument();
  });

  it("rejects the batch and retains the rejected state without a generation action", async () => {
    const pending = approval();
    let current = pending;
    const bodies: AgentMediaApprovalDecision[] = [];
    mountCard(pending);
    server.use(
      http.get(APPROVAL_URL, () => HttpResponse.json([current])),
      http.post(`${APPROVAL_URL}/${APPROVAL_ID}/decision`, async ({ request }) => {
        bodies.push(await request.json() as AgentMediaApprovalDecision);
        current = { ...pending, status: "REJECTED", version: 1 };
        return HttpResponse.json(current);
      }),
    );
    await userEvent.setup().click(screen.getByRole("button", { name: "拒绝" }));
    expect(await screen.findByText("已拒绝")).toBeInTheDocument();
    expect(bodies).toEqual([{ expectedVersion: 0, decision: "REJECT" }]);
    expect(screen.queryByRole("button", { name: "批准并生成" })).not.toBeInTheDocument();
  });

  it("shows UNKNOWN as a settled failure with external cost information and no automatic retry action", () => {
    mountCard(approval({ status: "FAILED", version: 2, taskIds: ["unknown-task"], result: {
      schemaVersion: 1, status: "FAILED", tasks: [{ taskId: "unknown-task", kind: "VIDEO_GENERATION", status: "UNKNOWN" }],
    } }));
    expect(screen.getByText("生成未完成")).toBeInTheDocument();
    expect(screen.getByText("结果未知")).toBeInTheDocument();
    expect(screen.getByText(/部分任务结果未知/)).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("disables approval actions for a stopped or historical run", () => {
    mountCard(approval(), true);
    expect(screen.getByRole("button", { name: "批准并生成" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "拒绝" })).toBeDisabled();
  });
});
