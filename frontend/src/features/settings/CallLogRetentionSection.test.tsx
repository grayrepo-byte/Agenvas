import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { CallLogRetentionSection } from "./CallLogRetentionSection";
import type { UpdateCallLogRetentionRequest } from "../../shared/api/client";

const PATH = "/api/v1/settings/call-log-retention";
function show() {
  server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "csrf" })));
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><Routes>
    <Route path="/" element={<CallLogRetentionSection enabled />} /><Route path="/login" element={<h1>登录页</h1>} />
  </Routes></MemoryRouter></QueryClientProvider>);
}
async function loaded() {
  const select = screen.getByRole("combobox", { name: "保留时长" });
  await waitFor(() => expect(select).toBeEnabled()); return select;
}

describe("CallLogRetentionSection", () => {
  it("defaults to permanent and saves 30, 90, custom and permanent policies with CAS", async () => {
    const write = vi.fn();
    let version = 1;
    server.use(http.put(PATH, async ({ request }) => {
      const body = await request.json() as UpdateCallLogRetentionRequest; write(body);
      return HttpResponse.json({ retentionDays: body.retentionDays, version: ++version });
    }));
    show(); const user = userEvent.setup(); const select = await loaded();
    expect(select).toHaveValue("forever");
    expect(screen.getByText(/模型回合、工具执行与 Provider 提交账本/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "保存保留设置" })).toBeDisabled();
    for (const [mode, days] of [["30", 30], ["90", 90], ["custom", 17], ["forever", null]] as const) {
      await user.selectOptions(select, mode);
      if (mode === "custom") { const input = screen.getByRole("spinbutton"); await user.clear(input); await user.type(input, "17"); }
      await user.click(screen.getByRole("button", { name: "保存保留设置" }));
      await screen.findByText("日志保留设置已保存。");
      expect(write).toHaveBeenLastCalledWith({ retentionDays: days, expectedVersion: version - 1 });
    }
  });
  it("validates custom integers before writing and preserves selection on a failed save", async () => {
    const writes = vi.fn();
    server.use(http.put(PATH, () => { writes(); return HttpResponse.json({ status: 503 }, { status: 503 }); }));
    show(); const user = userEvent.setup(); await user.selectOptions(await loaded(), "custom");
    const input = screen.getByRole("spinbutton"); const save = screen.getByRole("button", { name: "保存保留设置" });
    for (const value of ["", "0", "3651", "1.5"]) {
      await user.clear(input); if (value) await user.type(input, value);
      expect(save).toBeDisabled();
    }
    expect(writes).not.toHaveBeenCalled();
    // Presets must still work after an invalid custom draft.
    await user.selectOptions(screen.getByRole("combobox"), "90"); expect(save).toBeEnabled();
    await user.click(save); await screen.findByText("保存日志保留设置失败");
    expect(screen.getByRole("combobox")).toHaveValue("90");
  });
  it("keeps a custom draft through a conflict and refetches the version before retrying", async () => {
    let version = 1; const writes = vi.fn();
    server.use(http.get(PATH, () => HttpResponse.json({ retentionDays: null, version })),
      http.put(PATH, async ({ request }) => {
        const body = await request.json() as UpdateCallLogRetentionRequest; writes(body);
        if (body.expectedVersion === 1) { version = 2; return HttpResponse.json({ status: 409, code: "VERSION_CONFLICT" }, { status: 409 }); }
        return HttpResponse.json({ retentionDays: body.retentionDays, version: 3 });
      }));
    show(); const user = userEvent.setup(); await user.selectOptions(await loaded(), "custom");
    const input = screen.getByRole("spinbutton"); await user.clear(input); await user.type(input, "42");
    await user.click(screen.getByRole("button", { name: "保存保留设置" }));
    await screen.findByText("日志保留设置已变化"); expect(input).toHaveValue(42);
    expect(screen.getByRole("button", { name: "保存保留设置" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "重新读取设置" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "保存保留设置" })).toBeEnabled());
    expect(input).toHaveValue(42);
    await user.click(screen.getByRole("button", { name: "保存保留设置" })); await screen.findByText("日志保留设置已保存。");
    expect(writes).toHaveBeenLastCalledWith({ retentionDays: 42, expectedVersion: 2 });
  });
  it("blocks forbidden reads and redirects expired sessions", async () => {
    server.use(http.get(PATH, () => HttpResponse.json({ status: 403 }, { status: 403 })));
    show(); await screen.findByText("无权修改日志保留设置"); expect(screen.getByRole("combobox")).toBeDisabled();
  });
  it("redirects an expired session", async () => {
    server.use(http.get(PATH, () => HttpResponse.json({ status: 401 }, { status: 401 })));
    show(); expect(await screen.findByRole("heading", { name: "登录页" })).toBeInTheDocument();
  });
});
