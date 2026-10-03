import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { Task } from "../../shared/api/client";
import { AgentModelRetryNotice } from "./AgentModelRetryNotice";

const retry = { schemaVersion: 1 as const, retryCount: 2, maxRetries: 10 as const,
  firstFailureAt: "2026-10-04T01:00:00Z", deadlineAt: "2026-10-04T01:05:00Z", lastErrorCode: "LLM_CALL_TIMEOUT" as const };
const task: Task = { id: "retry-task", projectId: "project-1", runId: "run-1", stepKey: "agent-turn-1",
  kind: "AGENT_TURN", status: "READY", cancelRequested: false, input: { prompt: "private-test-input" },
  output: { modelRetry: retry }, attemptNo: 3, nextActionAt: "2026-10-04T01:00:04Z", version: 1,
  createdAt: "2026-10-04T01:00:00Z", updatedAt: "2026-10-04T01:00:00Z" };

describe("AgentModelRetryNotice", () => {
  it("shows the safe failure, scheduled retry and persistent deadline", () => {
    render(<AgentModelRetryNotice task={task} />);
    expect(screen.getByRole("status")).toHaveTextContent("等待自动重试 · 重试 2/10");
    expect(screen.getByRole("status")).toHaveTextContent("模型响应等待超时");
    expect(screen.getByText(/^下次重试：/)).toHaveTextContent(new Date(task.nextActionAt).toLocaleTimeString("zh-CN"));
    expect(screen.getByText(/^重试截止：/)).toHaveTextContent(new Date(retry.deadlineAt).toLocaleTimeString("zh-CN"));
    expect(screen.getByRole("status")).not.toHaveTextContent("private-test-input");
  });
  it("changes to retrying when the server claims the attempt", () => {
    render(<AgentModelRetryNotice task={{ ...task, status: "RUNNING" }} />);
    expect(screen.getByRole("status")).toHaveTextContent("正在重试模型调用");
    expect(screen.queryByText(/^下次重试：/)).not.toBeInTheDocument();
  });
  it.each(["SUCCEEDED", "FAILED", "CANCELED", "UNKNOWN"] as const)("hides historical metadata in %s", (status) => {
    render(<AgentModelRetryNotice task={{ ...task, status }} />);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
  it("never treats a media request as an automatically retryable model", () => {
    render(<AgentModelRetryNotice task={{ ...task, kind: "IMAGE_GENERATION" }} />);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
});
