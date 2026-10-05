import { QueryClientProvider } from "@tanstack/react-query";
import { render,screen,waitFor,within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http,HttpResponse } from "msw";
import { MemoryRouter,Route,Routes } from "react-router";
import { beforeEach,describe,expect,it,vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { CallLog,CallLogPage,Task } from "../../shared/api/client";
import { selectValue } from "../../test/controls";
import { server } from "../../test/server";
import { CallLogsPage } from "./CallLogsPage";

const PROJECT_ID = "b567877f-f57e-477a-a941-c61241542738";
const TASK_ID = "f23290b2-e6f1-4864-8dce-fca53d0203cb";
const LOG: CallLog = {
  id: "call-1", projectId: PROJECT_ID, projectTitle: "审计测试项目", taskId: null, runId: "run-1",
  kind: "IMAGE", operation: "SUBMIT", status: "SUCCEEDED", taskStatus: "SUCCEEDED",
  provider: "test-provider", model: "test-model", traceId: "trace-123", providerRequestId: "provider-request-789", errorCode: null,
  startedAt: "2026-09-26T01:00:00Z", respondedAt: "2026-09-26T01:00:02.125Z", durationMs: 2125, historical: false, mock: true,
};
const TASK: Task = {
  id: TASK_ID, projectId: PROJECT_ID, runId: null, stepKey: "direct-image", kind: "IMAGE_GENERATION", status: "UNKNOWN",
  cancelRequested: false, input: {}, attemptNo: 1, nextActionAt: "2026-09-26T01:00:00Z", version: 7,
  createdAt: "2026-09-26T01:00:00Z", updatedAt: "2026-09-26T01:00:00Z",
};
function page(items: CallLog[] = [LOG], overrides: Partial<CallLogPage> = {}): CallLogPage {
  return { items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0, ...overrides };
}
function showPage(path = "/settings/calls") {
  const client = createQueryClient();
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/settings/calls" element={<CallLogsPage />} />
    <Route path="/login" element={<h1>登录页</h1>} />
    <Route path="/projects/:projectId" element={<h1>项目画布</h1>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return client;
}

async function chooseDateTime(label: string, value: string) {
  const user = userEvent.setup();
  const date = new Date(value);
  await user.click(screen.getByRole("button", { name: label }));
  const dialog = screen.getByRole("dialog", { name: label });
  const findDay = () => [...dialog.querySelectorAll<HTMLButtonElement>("button[data-day]")]
    .find((button) => button.dataset.day === date.toLocaleDateString());
  let day = findDay();
  for (let month = 0; !day && month < 24; month++) {
    await user.click(within(dialog).getByRole("button", { name: date < new Date() ? "上个月" : "下个月" }));
    day = findDay();
  }
  if (!day) throw new Error(`Calendar day unavailable: ${value}`);
  await user.click(day);
  const time = screen.getByRole("textbox", { name: `${label}（时:分:秒）` });
  await user.clear(time);
  await user.type(time, value.slice(11));
}

describe("CallLogsPage", () => {
  beforeEach(() => server.use(http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" }))));

  it("fetches raw details only after opening the modal and clears them on close", async () => {
    const reads = vi.fn();
    server.use(http.get("/api/v1/call-logs", () => HttpResponse.json(page())),
      http.get("/api/v1/call-logs/:id/debug", ({ params }) => {
        reads(); return HttpResponse.json({ id: params.id, captured: false, exchanges: [] });
      }));
    const client = showPage();
    await screen.findByText("test-model");
    expect(reads).not.toHaveBeenCalled();
    const table = screen.getByRole("table");
    expect(within(table).getAllByRole("row", { hidden: true })).toHaveLength(2);
    await userEvent.setup().click(screen.getByRole("button", { name: "查看调用详情 call-1" }));
    await screen.findByText(/本次调用尚无 debug 正文/);
    expect(reads).toHaveBeenCalledTimes(1);
    const dialog = screen.getByRole("dialog", { name: "调用详情" });
    expect(within(table).getAllByRole("row", { hidden: true })).toHaveLength(2);
    expect(within(table).queryByRole("region", { name: "调用内容" })).not.toBeInTheDocument();
    await userEvent.setup().click(within(dialog).getByRole("button", { name: "关闭" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await waitFor(() => expect(client.getQueryData(["call-debug", "call-1"])).toBeUndefined());
    expect(screen.getByRole("button", { name: "查看调用详情 call-1" })).toHaveFocus();
  });

  it("shows separate start/response time and duration, and shows actual correlation IDs in a modal", async () => {
    server.use(http.get("/api/v1/call-logs", () => HttpResponse.json(page())));
    showPage();
    expect(await screen.findByText("test-model")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "调用时间" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "响应时间" })).toBeInTheDocument();
    expect(document.querySelector(`time[datetime="${LOG.respondedAt}"]`)).toBeInTheDocument();
    expect(screen.getByText(`${LOG.durationMs?.toLocaleString()} ms`)).toBeInTheDocument();
    expect(screen.getByText("Mock 模拟调用")).toBeInTheDocument();
    expect(screen.queryByText(LOG.traceId!)).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "查看调用详情 call-1" }));
    expect(screen.getByText("trace-123")).toBeInTheDocument();
    expect(screen.getByText("provider-request-789")).toBeInTheDocument();
    expect(within(screen.getByRole("dialog", { name: "调用详情" })).getByRole("link", { name: "审计测试项目" })).toHaveAttribute("href", `/projects/${PROJECT_ID}`);
    expect(screen.queryByRole("button", { name: "重试" })).not.toBeInTheDocument();
  });

  it("shows a generation ID once and separates local and provider correlation IDs", async () => {
    server.use(
      http.get("/api/v1/call-logs", () => HttpResponse.json(page([{ ...LOG, kind: "LLM", operation: "CHAT", mock: false }]))),
      http.get("/api/v1/call-logs/call-1/debug", () => HttpResponse.json({ id: "call-1", captured: true,
        exchanges: [{ method: "POST", url: "https://provider.invalid/chat/completions", responseStatus: 200,
          responseIdentifiers: { "x-trace-id": "synthetic-platform-trace", "x-request-id": "synthetic-platform-request" },
          requestBody: null, responseBody: null }],
        llmStream: { metrics: { schemaVersion: 2, firstChunkMs: 1, firstTextMs: 2, durationMs: 3, chunkCount: 1,
          promptTokens: 1, completionTokens: 1, totalTokens: 2, model: LOG.model, responseId: LOG.providerRequestId,
          finishReasons: ["STOP"], status: "COMPLETED", errorCode: null }, content: {
            response: JSON.stringify({ id: LOG.providerRequestId, model: LOG.model,
              choices: [{ message: { role: "assistant", content: "synthetic answer" }, finish_reason: "stop" }] }), truncated: false } },
      })),
    );
    const user = userEvent.setup();
    showPage();
    await user.click(await screen.findByRole("button", { name: "查看调用详情 call-1" }));
    await screen.findByRole("button", { name: "展开 Completion" });
    expect(screen.getAllByText(LOG.providerRequestId!)).toHaveLength(1);
    expect(screen.getAllByText("生成 ID")).toHaveLength(1);
    expect(screen.getByText("本地 Trace ID")).toBeInTheDocument();
    expect(screen.getByText("第三方关联 ID（x-trace-id）")).toBeInTheDocument();
    expect(screen.getByText("synthetic-platform-trace")).toBeInTheDocument();
    expect(screen.getByText("synthetic-platform-request")).toBeInTheDocument();
    const copy = vi.spyOn(navigator.clipboard, "writeText");
    await user.click(within(screen.getByText("synthetic-platform-trace").closest("dd")!).getByRole("button", { name: "复制" }));
    expect(copy).toHaveBeenCalledWith("synthetic-platform-trace");
  });

  it("sends applied filters and pagination to the server, resetting page when filters change", async () => {
    const requests: URLSearchParams[] = [];
    server.use(http.get("/api/v1/call-logs", ({ request }) => {
      const params = new URL(request.url).searchParams;
      requests.push(params);
      return HttpResponse.json(page([LOG], { page: Number(params.get("page")), totalElements: 21, totalPages: 2 }));
    }));
    const user = userEvent.setup();
    showPage(`/settings/calls?projectId=${PROJECT_ID}`);
    await screen.findByText("test-model");
    expect(requests[0]?.get("projectId")).toBe(PROJECT_ID);
    expect(requests[0]?.get("size")).toBe("20");
    await user.click(screen.getByRole("button", { name: "下一页" }));
    await screen.findByText("第 2 页 / 共 2 页 · 每页 20 条");
    expect(requests.at(-1)?.get("page")).toBe("1");
    await selectValue(screen.getByLabelText("调用类型"), "VIDEO");
    await selectValue(screen.getByLabelText("调用状态"), "UNKNOWN");
    await user.type(screen.getByLabelText("Trace ID"), "trace-filter");
    await chooseDateTime("开始时间", "2026-09-25T08:00:00");
    await chooseDateTime("结束时间", "2026-09-26T09:00:00");
    await user.click(screen.getByRole("button", { name: "筛选日志" }));
    await waitFor(() => expect(requests.at(-1)?.get("traceId")).toBe("trace-filter"));
    expect(Object.fromEntries(requests.at(-1)!)).toEqual({ projectId: PROJECT_ID, kind: "VIDEO", status: "UNKNOWN", traceId: "trace-filter",
      from: new Date("2026-09-25T08:00:00").toISOString(), to: new Date("2026-09-26T09:00:00").toISOString(), page: "0", size: "20" });
    await user.click(screen.getByRole("button", { name: "清空筛选" }));
    await waitFor(() => expect(Object.fromEntries(requests.at(-1)!)).toEqual({ page: "0", size: "20" }));
  });

  it.each(["", "trace-filter"])("fetches fresh logs when submitting unchanged filters (%s)", async (traceId) => {
    const reads = vi.fn();
    let model = "test-model";
    server.use(http.get("/api/v1/call-logs", () => {
      reads();
      return HttpResponse.json(page([{ ...LOG, model }]));
    }));
    const user = userEvent.setup();
    showPage();
    await screen.findByText("test-model");
    if (traceId) {
      await user.type(screen.getByLabelText("Trace ID"), traceId);
      await user.click(screen.getByRole("button", { name: "筛选日志" }));
      await waitFor(() => expect(reads).toHaveBeenCalledTimes(2));
    }

    const previousReads = reads.mock.calls.length;
    model = "latest-model";
    await user.click(screen.getByRole("button", { name: "筛选日志" }));
    expect(await screen.findByText("latest-model")).toBeInTheDocument();
    expect(reads).toHaveBeenCalledTimes(previousReads + 1);
  });

  it("resets to the first page and reads it once when filtering from a later page", async () => {
    const requestedPages: string[] = [];
    let firstPageModel = "first-page-model";
    server.use(http.get("/api/v1/call-logs", ({ request }) => {
      const requestedPage = new URL(request.url).searchParams.get("page")!;
      requestedPages.push(requestedPage);
      return HttpResponse.json(page([{ ...LOG, model: requestedPage === "0" ? firstPageModel : "second-page-model" }],
        { page: Number(requestedPage), totalElements: 21, totalPages: 2 }));
    }));
    const user = userEvent.setup();
    showPage();
    await screen.findByText("first-page-model");
    await user.click(screen.getByRole("button", { name: "下一页" }));
    await screen.findByText("second-page-model");
    expect(screen.getByText("第 2 页 / 共 2 页 · 每页 20 条")).toBeInTheDocument();

    firstPageModel = "latest-first-page-model";
    await user.click(screen.getByRole("button", { name: "筛选日志" }));
    expect(await screen.findByText("latest-first-page-model")).toBeInTheDocument();
    expect(screen.getByText("第 1 页 / 共 2 页 · 每页 20 条")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "上一页" })).toBeDisabled();
    await waitFor(() => expect(screen.getByRole("button", { name: "筛选日志" })).toBeEnabled());
    expect(requestedPages).toEqual(["0", "1", "0"]);
  });

  it("reads fresh logs once when returning to a recently queried filter", async () => {
    const requestedTraceIds: string[] = [];
    let firstFilterModel = "first-filter-model";
    server.use(http.get("/api/v1/call-logs", ({ request }) => {
      const traceId = new URL(request.url).searchParams.get("traceId")!;
      requestedTraceIds.push(traceId);
      return HttpResponse.json(page([{ ...LOG, model: traceId === "trace-first" ? firstFilterModel : "second-filter-model" }]));
    }));
    const user = userEvent.setup();
    showPage("/settings/calls?traceId=trace-first");
    await screen.findByText("first-filter-model");
    await user.clear(screen.getByLabelText("Trace ID"));
    await user.type(screen.getByLabelText("Trace ID"), "trace-second");
    await user.click(screen.getByRole("button", { name: "筛选日志" }));
    await screen.findByText("second-filter-model");

    firstFilterModel = "latest-first-filter-model";
    await user.clear(screen.getByLabelText("Trace ID"));
    await user.type(screen.getByLabelText("Trace ID"), "trace-first");
    await user.click(screen.getByRole("button", { name: "筛选日志" }));
    expect(await screen.findByText("latest-first-filter-model")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "筛选日志" })).toBeEnabled());
    expect(requestedTraceIds).toEqual(["trace-first", "trace-second", "trace-first"]);
  });

  it("disables filter actions and preserves records while an explicit read is pending", async () => {
    let finishRead: (() => void) | undefined;
    const pendingRead = new Promise<void>((resolve) => { finishRead = resolve; });
    const reads = vi.fn();
    server.use(http.get("/api/v1/call-logs", async () => {
      reads();
      if (reads.mock.calls.length === 1) return HttpResponse.json(page());
      await pendingRead;
      return HttpResponse.json(page([{ ...LOG, model: "latest-model" }]));
    }));
    const user = userEvent.setup();
    showPage();
    await screen.findByText("test-model");
    expect(screen.queryByRole("button", { name: "刷新日志" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "筛选日志" }));
    expect(await screen.findByText("正在更新调用日志")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "筛选日志" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "清空筛选" })).toBeDisabled();
    expect(screen.getByText("test-model")).toBeInTheDocument();
    expect(reads).toHaveBeenCalledTimes(2);

    finishRead?.();
    expect(await screen.findByText("latest-model")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "筛选日志" })).toBeEnabled());
    expect(screen.getByRole("button", { name: "清空筛选" })).toBeEnabled();
    expect(screen.queryByText("正在更新调用日志")).not.toBeInTheDocument();
    expect(screen.queryByText("test-model")).not.toBeInTheDocument();
    expect(reads).toHaveBeenCalledTimes(2);
  });

  it("restores local time from URL filters and clears an individual calendar filter", async () => {
    const requests: URLSearchParams[] = [];
    const from = new Date("2026-09-25T08:12:34").toISOString();
    const to = new Date("2026-09-26T09:23:45").toISOString();
    server.use(http.get("/api/v1/call-logs", ({ request }) => {
      requests.push(new URL(request.url).searchParams);
      return HttpResponse.json(page());
    }));
    const user = userEvent.setup();
    showPage(`/settings/calls?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
    await screen.findByText("test-model");
    expect(screen.getByRole("textbox", { name: "开始时间（时:分:秒）" })).toHaveValue("08:12:34");
    expect(screen.getByRole("textbox", { name: "结束时间（时:分:秒）" })).toHaveValue("09:23:45");
    expect(document.querySelector('input[type="datetime-local"]')).toBeNull();
    await user.click(screen.getByRole("button", { name: "筛选日志" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests.at(-1)?.get("from")).toBe(from);
    expect(requests.at(-1)?.get("to")).toBe(to);
    await user.click(screen.getByRole("button", { name: "开始时间" }));
    await user.keyboard("{Escape}");
    expect(screen.getByRole("button", { name: "开始时间" })).toHaveFocus();
    await user.click(screen.getByRole("button", { name: "开始时间" }));
    await user.click(within(screen.getByRole("dialog", { name: "开始时间" })).getByRole("button", { name: "清空日期" }));
    expect(screen.getByRole("textbox", { name: "开始时间（时:分:秒）" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "筛选日志" }));
    await waitFor(() => expect(requests.at(-1)?.has("from")).toBe(false));
    expect(requests.at(-1)?.get("to")).toBe(to);
  });

  it("keeps an invalid time draft without sending a request", async () => {
    const reads = vi.fn();
    server.use(http.get("/api/v1/call-logs", () => { reads(); return HttpResponse.json(page()); }));
    showPage(`/settings/calls?from=${encodeURIComponent(new Date("2026-09-25T08:00:00").toISOString())}`);
    await screen.findByText("test-model");
    const user = userEvent.setup();
    const time = screen.getByRole("textbox", { name: "开始时间（时:分:秒）" });
    await user.clear(time);
    await user.type(time, "25:00:00");
    await user.click(screen.getByRole("button", { name: "筛选日志" }));
    expect(time).toBeInvalid();
    expect(time).toHaveValue("25:00:00");
    expect(reads).toHaveBeenCalledTimes(1);
  });

  it("rejects an inverted date range before issuing another read", async () => {
    const reads = vi.fn();
    server.use(http.get("/api/v1/call-logs", () => { reads(); return HttpResponse.json(page()); }));
    showPage();
    await screen.findByText("test-model");
    await chooseDateTime("开始时间", "2026-09-27T08:00:00");
    await chooseDateTime("结束时间", "2026-09-26T08:00:00");
    await userEvent.setup().click(screen.getByRole("button", { name: "筛选日志" }));
    expect(screen.getByRole("alert")).toHaveTextContent("结束时间不能早于开始时间");
    expect(reads).toHaveBeenCalledTimes(1);
  });

  it("keeps historical unavailable timing and IDs distinct from zero and live running states", async () => {
    server.use(http.get("/api/v1/call-logs", () => HttpResponse.json(page([
      { ...LOG, id: "legacy", historical: true, mock: false, operation: "LEGACY", status: "UNKNOWN", respondedAt: null, durationMs: null, traceId: null, providerRequestId: null },
      { ...LOG, id: "running", status: "RUNNING", respondedAt: null, durationMs: null },
    ]))));
    showPage();
    expect(await screen.findByText("历史记录")).toBeInTheDocument();
    expect(screen.getByText("等待响应")).toBeInTheDocument();
    expect(within(screen.getByRole("table")).getByText("未知")).toBeInTheDocument();
    expect(screen.queryByText("0 ms")).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "查看调用详情 legacy" }));
    expect(screen.getByText(/历史记录只保留当时已保存的信息/)).toBeInTheDocument();
    expect(screen.queryByText("trace-123")).not.toBeInTheDocument();
  });

  it("shows loading, retries a failed read, and preserves data when refreshing fails", async () => {
    let respond: (() => void) | undefined;
    const started = new Promise<void>((resolve) => { respond = resolve; });
    let failed = true;
    server.use(http.get("/api/v1/call-logs", async () => {
      await started;
      return failed ? HttpResponse.json({ title: "Unavailable", status: 503 }, { status: 503 }) : HttpResponse.json(page());
    }));
    showPage();
    expect(await screen.findByText("正在读取调用日志")).toBeInTheDocument();
    respond?.();
    expect(await screen.findByRole("alert")).toHaveTextContent("读取调用日志失败");
    failed = false;
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "重试读取日志" }));
    expect(await screen.findByText("test-model")).toBeInTheDocument();
    failed = true;
    await user.click(screen.getByRole("button", { name: "筛选日志" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("保留上次读取的记录");
    expect(screen.getByText("test-model")).toBeInTheDocument();
  });

  it("renders an empty page without a next page", async () => {
    server.use(http.get("/api/v1/call-logs", () => HttpResponse.json(page([]))));
    showPage();
    expect(await screen.findByText("没有匹配的调用记录")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "下一页" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "上一页" })).toBeDisabled();
  });

  it.each([400, 401, 403])("handles invalid filters and unauthorized audit responses (%s)", async (status) => {
    server.use(http.get("/api/v1/call-logs", () => HttpResponse.json({ title: "Denied", status }, { status })));
    showPage();
    if (status === 401) expect(await screen.findByRole("heading", { name: "登录页" })).toBeInTheDocument();
    else if (status === 400) expect(await screen.findByRole("alert")).toHaveTextContent("筛选条件无效");
    else expect(await screen.findByRole("alert")).toHaveTextContent("无权查看调用日志");
    expect(screen.queryByText("test-model")).not.toBeInTheDocument();
  });

  it("shows the related task as read-only and never offers UNKNOWN recovery", async () => {
    const attemptReads = vi.fn();
    const created = vi.fn();
    server.use(
      http.get("/api/v1/call-logs", () => HttpResponse.json(page([{ ...LOG, status: "UNKNOWN", taskStatus: "UNKNOWN", taskId: TASK_ID }]))),
      http.get(`/api/v1/projects/${PROJECT_ID}/tasks/${TASK_ID}`, () => HttpResponse.json(TASK)),
      http.get(`/api/v1/projects/${PROJECT_ID}/tasks/${TASK_ID}/attempts`, () => { attemptReads(); return HttpResponse.json([]); }),
      http.post(`/api/v1/projects/${PROJECT_ID}/tasks/${TASK_ID}/new-attempt`, () => { created(); return HttpResponse.json({ ...TASK, id: "new-task", status: "READY" }); }),
    );
    showPage();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "查看调用详情 call-1" }));
    const related = await screen.findByRole("region", { name: "关联任务" });
    expect(await within(related).findByText("当前状态：未知")).toBeInTheDocument();
    expect(within(related).getByRole("link", { name: "前往项目" })).toHaveAttribute("href", `/projects/${PROJECT_ID}`);
    expect(screen.queryByRole("button", { name: "重试" })).not.toBeInTheDocument();
    expect(attemptReads).not.toHaveBeenCalled();
    expect(created).not.toHaveBeenCalled();
  });

  it("closes the Prompt modal with Escape while keeping call details and scroll locked", async () => {
    server.use(http.get("/api/v1/call-logs", () => HttpResponse.json(page([{ ...LOG, kind: "LLM", operation: "CHAT" }]))),
      http.get("/api/v1/call-logs/call-1/debug", () => HttpResponse.json({ id: "call-1", captured: true,
        exchanges: [{ method: "POST", url: "https://provider.invalid/v1/chat/completions", responseStatus: 200,
          requestBody: { content: '{"messages":[{"role":"user","content":"hello"}]}', encoding: "UTF8", truncated: false },
          responseBody: null }] })));
    const user = userEvent.setup();
    showPage();
    await user.click(await screen.findByRole("button", { name: "查看调用详情 call-1" }));
    const expand = await screen.findByRole("button", { name: "展开 Prompt" });
    await user.click(expand);
    const prompt = screen.getByRole("dialog", { name: "Prompt" });
    await user.click(within(prompt).getByRole("searchbox"));
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: "Prompt" })).not.toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "调用详情" })).toBeInTheDocument();
    expect(document.body).toHaveAttribute("data-scroll-locked");
    expect(expand).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(document.body.style.overflow).toBe("");
    expect(screen.getByRole("button", { name: "查看调用详情 call-1" })).toHaveFocus();
  });

  it("reads the related task's latest state instead of the logged status", async () => {
    server.use(
      http.get("/api/v1/call-logs", () => HttpResponse.json(page([{ ...LOG, status: "UNKNOWN", taskStatus: "UNKNOWN", taskId: TASK_ID }]))),
      http.get(`/api/v1/projects/${PROJECT_ID}/tasks/${TASK_ID}`, () => HttpResponse.json({ ...TASK, status: "WAITING_PROVIDER" })),
    );
    showPage();
    await userEvent.setup().click(await screen.findByRole("button", { name: "查看调用详情 call-1" }));
    expect(await screen.findByText("当前状态：等待外部结果")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "重试" })).not.toBeInTheDocument();
  });
});
