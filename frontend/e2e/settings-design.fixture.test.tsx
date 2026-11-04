import { act, fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterAll, beforeAll, expect, it, vi } from "vitest";

const preview = vi.hoisted(() => ({ root: null as { unmount(): void } | null }));
vi.mock("react-dom/client", async (importOriginal) => {
  const original = await importOriginal<typeof import("react-dom/client")>();
  return { ...original, createRoot: (...args: Parameters<typeof original.createRoot>) => {
    const root = original.createRoot(...args);
    preview.root = root;
    return root;
  } };
});

const originalFetch = window.fetch;
const originalUrl = window.location.href;
afterAll(async () => {
  await act(async () => preview.root?.unmount());
  document.getElementById("root")?.remove();
  window.fetch = originalFetch;
  window.history.replaceState(null, "", originalUrl);
});

beforeAll(async () => {
  window.history.replaceState(null, "", "/e2e/settings-design.html?route=/settings/media");
  const container = document.createElement("div");
  container.id = "root";
  document.body.append(container);
  // Import the actual browser entry, including its routes and isolated fetch handler.
  await act(async () => { await import("./settings-design.fixture"); });
  await screen.findByRole("heading", { name: "媒体连接与能力" });
}, 30_000);

it("opens director settings from the studio preview navigation and saves synthetic settings", async () => {
  const user = userEvent.setup();
  await user.click(screen.getByRole("link", { name: "导演设置" }));
  expect(await screen.findByRole("heading", { name: "导演设置" })).toBeVisible();
  expect(screen.getByRole("link", { name: "导演设置" })).toHaveAttribute("aria-current", "page");
  expect(screen.getAllByRole("spinbutton")).toHaveLength(9);
  const threshold = screen.getByLabelText("自动压缩阈值（%）");
  expect(threshold).toHaveValue(50);
  fireEvent.change(threshold, { target: { value: "25" } });
  await user.click(screen.getByRole("button", { name: "保存导演设置" }));
  await screen.findByText("导演设置已保存，无需重启。");
  await user.click(screen.getByRole("link", { name: "Provider 配置" }));
  await screen.findByRole("heading", { name: "Provider 配置" });
  await user.click(screen.getByRole("link", { name: "导演设置" }));
  expect(await screen.findByLabelText("自动压缩阈值（%）")).toHaveValue(25);
});
