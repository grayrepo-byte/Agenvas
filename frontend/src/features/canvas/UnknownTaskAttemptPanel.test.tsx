import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { UnknownTaskAttemptPanel } from "./UnknownTaskAttemptPanel";

describe("UnknownTaskAttemptPanel", () => {
  it("requires an explicit duplicate-cost acknowledgement before a new attempt", async () => {
    const user = userEvent.setup();
    let created = 0;
    server.use(
      http.get("/api/v1/projects/:projectId/tasks/:taskId/attempts", () =>
        HttpResponse.json([{ id: "attempt-risk", taskId: "task-risk", status: "UNKNOWN",
          requestKey: "5499f944-b17d-4ae7-b907-2ce29495fb4b",
          reconcilable: false, providerRequestId: null, replacementTaskId: null,
          createdAt: "2026-09-24T00:00:00Z", updatedAt: "2026-09-24T00:00:00Z" }]),
      ),
      http.get("/api/v1/auth/csrf", () =>
        HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" }),
      ),
      http.post("/api/v1/projects/:projectId/tasks/:taskId/new-attempt", async ({ request }) => {
        expect(request.headers.get("Idempotency-Key")).toBeTruthy();
        expect(request.headers.get("X-XSRF-TOKEN")).toBe("test-token");
        expect(await request.json()).toEqual({ expectedTaskVersion: 7,
          riskAcknowledgement: "ACCEPT_POSSIBLE_DUPLICATE_COST" });
        created += 1;
        return HttpResponse.json({ id: "new-task" });
      }),
    );
    render(<QueryClientProvider client={createQueryClient()}>
      <UnknownTaskAttemptPanel projectId="project-1" taskId="task-risk" taskVersion={7}
        planned cancelRequested={false} />
    </QueryClientProvider>);
    await user.click(screen.getByRole("button", { name: "查看提交账本并处理重试" }));
    const button = await screen.findByRole("button", { name: "明确风险后创建新尝试" });
    expect(button).toBeDisabled();
    expect(created).toBe(0);
    await user.click(screen.getByRole("checkbox"));
    await user.click(button);
    expect(await screen.findByRole("status")).toHaveTextContent("新尝试已创建：new-task");
    expect(created).toBe(1);
  });

  it("queries only the original candidate and keeps missing evidence UNKNOWN", async () => {
    const user = userEvent.setup();
    let lookups = 0;
    server.use(
      http.get("/api/v1/projects/:projectId/tasks/:taskId/attempts", () =>
        HttpResponse.json([{ id: "attempt-1", taskId: "task-1", status: "UNKNOWN",
          requestKey: "5499f944-b17d-4ae7-b907-2ce29495fb4b",
          candidateRequestId: "5499f944-b17d-4ae7-b907-2ce29495fb4b",
          reconcilable: true,
          providerRequestId: null, createdAt: "2026-09-24T00:00:00Z",
          updatedAt: "2026-09-24T00:00:00Z" }]),
      ),
      http.get("/api/v1/auth/csrf", () =>
        HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" }),
      ),
      http.post("/api/v1/projects/:projectId/tasks/:taskId/reconcile", ({ request }) => {
        expect(request.headers.get("X-XSRF-TOKEN")).toBe("test-token");
        lookups += 1;
        return HttpResponse.json({ outcome: "NO_EVIDENCE", taskId: "task-1",
          taskStatus: "UNKNOWN", providerRequestId: null });
      }),
    );
    render(<QueryClientProvider client={createQueryClient()}>
      <UnknownTaskAttemptPanel projectId="project-1" taskId="task-1" taskVersion={2} planned cancelRequested={false} />
    </QueryClientProvider>);
    expect(lookups).toBe(0);
    await user.click(screen.getByRole("button", { name: "查看提交账本并处理重试" }));
    expect(await screen.findByText(/可核对的候选 Provider ID/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "查询原 Provider 请求" }));
    expect(await screen.findByRole("status")).toHaveTextContent("任务仍为 UNKNOWN");
    expect(lookups).toBe(1);
    expect(screen.queryByText(/已找到原请求/)).not.toBeInTheDocument();
  });

  it("does not offer reconciliation for canceled or legacy attempts", async () => {
    const user = userEvent.setup();
    server.use(http.get("/api/v1/projects/:projectId/tasks/:taskId/attempts", () =>
      HttpResponse.json([{ id: "attempt-1", taskId: "task-1", status: "UNKNOWN",
        requestKey: "5499f944-b17d-4ae7-b907-2ce29495fb4b",
        candidateRequestId: null, reconcilable: false, providerRequestId: null,
        createdAt: "2026-09-24T00:00:00Z", updatedAt: "2026-09-24T00:00:00Z" }]),
    ));
    const { unmount } = render(<QueryClientProvider client={createQueryClient()}>
      <UnknownTaskAttemptPanel projectId="project-1" taskId="task-1" taskVersion={2} planned cancelRequested={false} />
    </QueryClientProvider>);
    await user.click(screen.getByRole("button", { name: "查看提交账本并处理重试" }));
    expect(await screen.findByText(/提交关联键/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "查询原 Provider 请求" })).not.toBeInTheDocument();
    server.use(http.get("/api/v1/projects/:projectId/tasks/:taskId/attempts", () =>
      HttpResponse.json([{ id: "attempt-2", taskId: "task-2", status: "UNKNOWN",
        requestKey: "5499f944-b17d-4ae7-b907-2ce29495fb4b",
        candidateRequestId: "5499f944-b17d-4ae7-b907-2ce29495fb4b",
        reconcilable: true,
        providerRequestId: null, createdAt: "2026-09-24T00:00:00Z",
        updatedAt: "2026-09-24T00:00:00Z" }]),
    ));
    unmount();
    render(<QueryClientProvider client={createQueryClient()}>
      <UnknownTaskAttemptPanel projectId="project-1" taskId="task-2" taskVersion={2} planned cancelRequested />
    </QueryClientProvider>);
    await user.click(screen.getByRole("button", { name: "查看提交账本并处理重试" }));
    expect(await screen.findByText(/可核对的候选 Provider ID/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "查询原 Provider 请求" })).not.toBeInTheDocument();
  });
});
