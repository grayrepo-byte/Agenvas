import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { RunHistoryPanel } from "./RunHistoryPanel";

describe("RunHistoryPanel", () => {
  it("pages persisted runs and shows safe plan/task outcomes for a selected run", async () => {
    const projectId = crypto.randomUUID();
    const agentId = crypto.randomUUID();
    const latestId = crypto.randomUUID();
    const olderId = crypto.randomUUID();
    server.use(
      http.get(`/api/v1/projects/${projectId}/runs`, ({ request }) => {
        const url = new URL(request.url);
        expect(url.searchParams.get("agentId")).toBe(agentId);
        return HttpResponse.json(url.searchParams.has("cursor")
          ? { items: [{ id: olderId, agentInstanceId: agentId, status: "SUCCEEDED",
            instruction: "旧任务", createdAt: "2026-09-22T10:00:00Z",
            updatedAt: "2026-09-22T10:05:00Z", completedAt: "2026-09-22T10:05:00Z" }],
            nextCursor: null }
          : { items: [{ id: latestId, agentInstanceId: agentId, status: "FAILED",
            instruction: "新任务", createdAt: "2026-09-23T10:00:00Z",
            updatedAt: "2026-09-23T10:05:00Z", completedAt: "2026-09-23T10:05:00Z" }],
            nextCursor: "opaque-cursor" });
      }),
      http.get(`/api/v1/projects/${projectId}/runs/${latestId}/plans`, () =>
        HttpResponse.json([{ id: crypto.randomUUID(), stage: "IMAGE", revision: 1,
          status: "APPROVED", steps: [{ stepKey: "image-1" }] }])),
      http.get(`/api/v1/projects/${projectId}/runs/${latestId}/tasks`, () =>
        HttpResponse.json([{ id: crypto.randomUUID(), kind: "IMAGE_GENERATION",
          stepKey: "image-1", status: "FAILED", errorCode: "PROVIDER_TIMEOUT",
          input: { privatePrompt: "must not render" } }])),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}>
      <RunHistoryPanel agentId={agentId} projectId={projectId} />
    </QueryClientProvider>);

    await user.click(await screen.findByRole("button", { name: /FAILED.*新任务/ }));
    const history = screen.getByRole("region", { name: "运行记录" });
    expect(await within(history).findByText(/IMAGE · 第 1 版 · APPROVED/)).toBeInTheDocument();
    expect(await within(history).findByText(/IMAGE_GENERATION · image-1 · FAILED · PROVIDER_TIMEOUT/))
      .toBeInTheDocument();
    expect(history).toHaveTextContent("技术重试只核对原 Provider 请求");
    expect(history).toHaveTextContent("可能产生额外成本的新生成尝试");
    expect(history).toHaveTextContent("UNKNOWN 请先核对原请求");
    expect(history).not.toHaveTextContent("must not render");
    await user.click(screen.getByRole("button", { name: "下一页" }));
    expect(await screen.findByText("旧任务")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "上一页" }));
    expect(await screen.findByText("新任务")).toBeInTheDocument();
  });
});
