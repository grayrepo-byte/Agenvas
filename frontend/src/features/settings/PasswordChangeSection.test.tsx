import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { server } from "../../test/server";
import { PasswordChangeSection } from "./PasswordChangeSection";

/** A confirmation typo cannot send credentials; success clears all three fields. */
describe("PasswordChangeSection", () => {
  it("validates confirmation and sends only the two contract fields", async () => {
    const submitted: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test",
      })),
      http.post("/api/v1/auth/change-password", async ({ request }) => {
        submitted.push(await request.json());
        return new HttpResponse(null, { status: 204 });
      }),
    );
    render(<PasswordChangeSection />);
    const user = userEvent.setup();
    const current = screen.getByLabelText("当前密码") as HTMLInputElement;
    const next = screen.getByLabelText("新密码") as HTMLInputElement;
    const confirm = screen.getByLabelText("确认新密码") as HTMLInputElement;
    await user.type(current, "old-password-123");
    await user.type(next, "new-password-123");
    await user.type(confirm, "different-1234");
    await user.click(screen.getByRole("button", { name: "修改密码" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("不一致");
    expect(submitted).toHaveLength(0);

    await user.clear(confirm);
    await user.type(confirm, "new-password-123");
    await user.click(screen.getByRole("button", { name: "修改密码" }));
    expect(await screen.findByRole("status")).toHaveTextContent("其他会话已失效");
    expect(submitted).toEqual([{ currentPassword: "old-password-123", newPassword: "new-password-123" }]);
    expect(current).toHaveValue("");
    expect(next).toHaveValue("");
    expect(confirm).toHaveValue("");
    expect(document.body).not.toHaveTextContent("new-password-123");
  });

  it("clears secrets after an authentication failure", async () => {
    const submitted = vi.fn();
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test",
      })),
      http.post("/api/v1/auth/change-password", () => {
        submitted();
        return HttpResponse.json({ title: "密码错误", status: 401,
          code: "INVALID_CREDENTIALS", detail: "当前密码错误。", retryable: false,
        }, { status: 401, headers: { "Content-Type": "application/problem+json" } });
      }),
    );
    render(<PasswordChangeSection />);
    const user = userEvent.setup();
    const current = screen.getByLabelText("当前密码") as HTMLInputElement;
    const next = screen.getByLabelText("新密码") as HTMLInputElement;
    const confirm = screen.getByLabelText("确认新密码") as HTMLInputElement;
    await user.type(current, "wrong-password");
    await user.type(next, "replacement-123");
    await user.type(confirm, "replacement-123");
    await user.click(screen.getByRole("button", { name: "修改密码" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("当前密码错误");
    expect(submitted).toHaveBeenCalledOnce();
    expect(current).toHaveValue("");
    expect(next).toHaveValue("");
    expect(confirm).toHaveValue("");
  });

  it("disables credential edits and duplicate submissions until the response arrives", async () => {
    let finish: (() => void) | undefined;
    const submitted = vi.fn();
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/auth/change-password", async () => {
        submitted();
        await new Promise<void>((resolve) => { finish = resolve; });
        return new HttpResponse(null, { status: 204 });
      }),
    );
    render(<PasswordChangeSection />);
    const user = userEvent.setup();
    const current = screen.getByLabelText("当前密码");
    const next = screen.getByLabelText("新密码");
    const confirm = screen.getByLabelText("确认新密码");
    await user.type(current, "old-password-123");
    await user.type(next, "replacement-123");
    await user.type(confirm, "replacement-123");
    await user.click(screen.getByRole("button", { name: "修改密码" }));
    expect(await screen.findByRole("status")).toHaveTextContent("正在修改密码");
    expect(current).toBeDisabled();
    expect(next).toBeDisabled();
    expect(confirm).toBeDisabled();
    expect(screen.getByRole("button", { name: "正在修改…" })).toBeDisabled();
    expect(submitted).toHaveBeenCalledOnce();
    await act(async () => { finish?.(); });
    expect(await screen.findByText("密码已修改，其他会话已失效。")).toBeInTheDocument();
    expect(current).toBeEnabled();
    expect(current).toHaveValue("");
  });

});
