import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { selectValue } from "../../test/controls";
import { server } from "../../test/server";
import { MediaStylePicker } from "./MediaStylePicker";

describe("MediaStylePicker", () => {
  it("filters by category, excludes disabled styles and selects without exposing prompts", async () => {
    const choose = vi.fn(); const close = vi.fn();
    server.use(http.get("/api/v1/media-styles", () => HttpResponse.json([
      { id: "photo", name: "写实摄影", category: "摄影", enabled: true, version: 1, thumbnailUrl: "/photo.png", builtIn: true },
      { id: "ink", name: "水墨", category: "绘画", enabled: true, version: 1, thumbnailUrl: "/ink.png", builtIn: true },
      { id: "retired", name: "停用风格", category: "绘画", enabled: false, version: 1, thumbnailUrl: null, builtIn: false },
    ])));
    render(<QueryClientProvider client={createQueryClient()}><MediaStylePicker selected="ink" onSelect={choose} onClose={close} /></QueryClientProvider>);
    expect(await screen.findByRole("button", { name: "水墨" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByRole("button", { name: "停用风格" })).not.toBeInTheDocument();
    await selectValue(screen.getByRole("combobox", { name: "分类" }), "摄影");
    const modal = screen.getByRole("dialog");
    expect(within(modal).queryByRole("button", { name: "水墨" })).not.toBeInTheDocument();
    await userEvent.setup().click(within(modal).getByRole("button", { name: "写实摄影" }));
    expect(choose).toHaveBeenCalledWith("photo"); expect(close).toHaveBeenCalledOnce();
    expect(modal).not.toHaveTextContent("promptSuffix");
  });

  it("retains the clear action when catalog loading fails and retries", async () => {
    let fail = true;
    server.use(http.get("/api/v1/media-styles", () => fail
      ? HttpResponse.json({ status: 503, code: "UNAVAILABLE" }, { status: 503 }) : HttpResponse.json([])));
    const choose = vi.fn();
    render(<QueryClientProvider client={createQueryClient()}><MediaStylePicker selected="missing" onSelect={choose} onClose={vi.fn()} /></QueryClientProvider>);
    expect(await screen.findByRole("alert")).toHaveTextContent("风格读取失败");
    fail = false;
    await userEvent.setup().click(screen.getByRole("button", { name: "重试" }));
    await screen.findByText("暂无可用风格");
    await userEvent.setup().click(screen.getByRole("button", { name: "无风格" }));
    expect(choose).toHaveBeenCalledWith(null);
  });
});
