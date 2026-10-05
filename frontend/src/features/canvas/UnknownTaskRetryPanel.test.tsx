import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
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
});
