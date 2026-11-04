import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { ApiError } from "../../shared/api/client";
import { server } from "../../test/server";
import { UnknownTaskRetryPanel } from "./UnknownTaskRetryPanel";

describe("UnknownTaskRetryPanel", () => {
  it.each([false, true])("creates a new attempt from a single retry (compact: %s)", async (compact) => {
    const user = userEvent.setup();
    let created = 0;
    let idempotencyKey: string | null = null;
    server.use(
      http.get("/api/v1/auth/csrf", () =>
        HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" }),
      ),
      http.post("/api/v1/projects/:projectId/tasks/:taskId/new-attempt", async ({ request }) => {
        idempotencyKey ??= request.headers.get("Idempotency-Key");
        expect(request.headers.get("X-XSRF-TOKEN")).toBe("test-token");
        expect(await request.json()).toEqual({ expectedTaskVersion: 7 });
        created += 1;
        return HttpResponse.json({ id: "new-task" });
      }),
    );
    render(<QueryClientProvider client={createQueryClient()}>
      <UnknownTaskRetryPanel compact={compact} projectId="project-1" taskId="task-risk" taskVersion={7} />
    </QueryClientProvider>);
    expect(screen.getByText("结果未知")).toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("已创建新任务：new-task。")).toBeVisible();
    expect(created).toBe(1);
    expect(idempotencyKey).toBeTruthy();
  });

  it("reports a failed retry without offering reconciliation", async () => {
    const user = userEvent.setup();
    server.use(
      http.get("/api/v1/auth/csrf", () =>
        HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" }),
      ),
      http.post("/api/v1/projects/:projectId/tasks/:taskId/new-attempt", () =>
        HttpResponse.json({ title: "Conflict", status: 409 }, { status: 409 }),
      ),
    );
    render(<QueryClientProvider client={createQueryClient()}>
      <UnknownTaskRetryPanel projectId="project-1" taskId="task-1" taskVersion={2} />
    </QueryClientProvider>);
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("重试失败");
    expect(screen.queryByRole("button", { name: "查询原 Provider 请求" })).not.toBeInTheDocument();
  });

  it.each([false, true])("isolates commands and feedback when the displayed task changes (compact: %s)", async (compact) => {
    const queryClient = createQueryClient();
    const requests: Array<{ task: string; key: string | null }> = [];
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post("/api/v1/projects/:projectId/tasks/:taskId/new-attempt", ({ params, request }) => {
        const task = String(params.taskId), key = request.headers.get("Idempotency-Key"); requests.push({ task, key });
        if (requests.some((previous) => previous.key === key && previous.task !== task)) return HttpResponse.json({ code: "UNKNOWN_RETRY_CONFLICT" }, { status: 409 });
        return HttpResponse.json({ id: `replacement-${task}` });
      }));
    const panel = (taskId: string) => <QueryClientProvider client={queryClient}><UnknownTaskRetryPanel compact={compact} projectId="project-one" taskId={taskId} taskVersion={7} /></QueryClientProvider>;
    const { rerender } = render(panel("task-one"));
    await userEvent.click(screen.getByRole("button", { name: "重试" }));
    await screen.findByText("已创建新任务：replacement-task-one。");
    rerender(panel("task-two"));
    expect(screen.queryByText("已创建新任务：replacement-task-one。")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("已创建新任务：replacement-task-two。")).toBeVisible();
    expect(requests).toHaveLength(2); expect(requests[0]?.key).not.toBe(requests[1]?.key);
  });

  it.each(["network", 408, 503] as const)("freezes the original task version after %s acknowledgement failure", async (failure) => {
    const queryClient = createQueryClient();
    const requests: Array<{ key: string | null; body: unknown }> = [];
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post("/api/v1/projects/:projectId/tasks/:taskId/new-attempt", async ({ request }) => {
        const input = { key: request.headers.get("Idempotency-Key"), body: await request.json() }; requests.push(input);
        if (requests.length === 1) return failure === "network" ? HttpResponse.error() : HttpResponse.json({ code: "HTTP_ERROR", retryable: true }, { status: failure });
        if (JSON.stringify(input) !== JSON.stringify(requests[0])) return HttpResponse.json({ code: "UNKNOWN_RETRY_CONFLICT" }, { status: 409 });
        return HttpResponse.json({ id: "accepted-replacement" });
      }));
    const panel = (taskVersion: number) => <QueryClientProvider client={queryClient}><UnknownTaskRetryPanel projectId="project-one" taskId="task-one" taskVersion={taskVersion} /></QueryClientProvider>;
    const { rerender } = render(panel(7));
    await userEvent.click(screen.getByRole("button", { name: "重试" })); await screen.findByRole("alert");
    rerender(panel(8));
    await userEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("已创建新任务：accepted-replacement。")).toBeVisible();
    expect(requests).toHaveLength(2); expect(requests[1]).toEqual(requests[0]);
  });

  it("uses the refreshed version and a new command after an explicit conflict", async () => {
    const queryClient = createQueryClient(); const requests: Array<{ key: string | null; body: unknown }> = [];
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post("/api/v1/projects/:projectId/tasks/:taskId/new-attempt", async ({ request }) => {
        requests.push({ key: request.headers.get("Idempotency-Key"), body: await request.json() });
        return requests.length === 1 ? HttpResponse.json({ code: "UNKNOWN_RETRY_CONFLICT" }, { status: 409 }) : HttpResponse.json({ id: "new-version-attempt" });
      }));
    const panel = (taskVersion: number) => <QueryClientProvider client={queryClient}><UnknownTaskRetryPanel projectId="project-one" taskId="task-one" taskVersion={taskVersion} /></QueryClientProvider>;
    const { rerender } = render(panel(7));
    await userEvent.click(screen.getByRole("button", { name: "重试" })); await screen.findByRole("alert");
    rerender(panel(8)); await userEvent.click(screen.getByRole("button", { name: "重试" }));
    await screen.findByText("已创建新任务：new-version-attempt。");
    expect(requests[1]?.body).toEqual({ expectedTaskVersion: 8 }); expect(requests[1]?.key).not.toBe(requests[0]?.key);
  });

  it("retains the accepted command when a post-acceptance refresh callback fails", async () => {
    const queryClient = createQueryClient(); const requests: Array<{ key: string | null; body: unknown }> = []; let changed = 0;
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post("/api/v1/projects/:projectId/tasks/:taskId/new-attempt", async ({ request }) => {
        const input = { key: request.headers.get("Idempotency-Key"), body: await request.json() }; requests.push(input);
        if (requests.length > 1 && JSON.stringify(input) !== JSON.stringify(requests[0])) return HttpResponse.json({ code: "UNKNOWN_RETRY_CONFLICT" }, { status: 409 });
        return HttpResponse.json({ id: "accepted-before-refresh" });
      }));
    const onChanged = async () => { if (++changed === 1) throw new ApiError(409, "SYNTHETIC_REFRESH_CONFLICT", "Synthetic follow-up refresh rejected", false); };
    const panel = (taskVersion: number) => <QueryClientProvider client={queryClient}><UnknownTaskRetryPanel projectId="project-one" taskId="task-one" taskVersion={taskVersion} onChanged={onChanged} /></QueryClientProvider>;
    const { rerender } = render(panel(7));
    await userEvent.click(screen.getByRole("button", { name: "重试" })); await screen.findByRole("alert");
    rerender(panel(8)); await userEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("已创建新任务：accepted-before-refresh。")).toBeVisible();
    expect(requests).toHaveLength(2); expect(requests[1]).toEqual(requests[0]);
  });

  it("keeps a late previous-task result out of the new task panel", async () => {
    const queryClient = createQueryClient(); let release = () => {}, calls = 0;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post("/api/v1/projects/:projectId/tasks/:taskId/new-attempt", async () => { calls++; await gate; return HttpResponse.json({ id: "late-old-replacement" }); }));
    const panel = (taskId: string) => <QueryClientProvider client={queryClient}><UnknownTaskRetryPanel compact projectId="project-one" taskId={taskId} taskVersion={7} /></QueryClientProvider>;
    const { rerender } = render(panel("task-one"));
    await userEvent.click(screen.getByRole("button", { name: "重试" })); await waitFor(() => expect(calls).toBe(1));
    rerender(panel("task-two"));
    expect(screen.getByRole("button", { name: "重试" })).toBeEnabled();
    await act(async () => release());
    await waitFor(() => expect(queryClient.isMutating()).toBe(0));
    expect(screen.queryByText("已创建新任务：late-old-replacement。")).not.toBeInTheDocument();
  });
});
