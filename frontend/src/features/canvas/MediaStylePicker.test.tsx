import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { selectValue } from "../../test/controls";
import { server } from "../../test/server";
import { seededStyles } from "../../test/seededCatalogs";
import { setLocale } from "../../shared/i18n";
import { MediaStylePicker } from "./MediaStylePicker";

describe("MediaStylePicker", () => {
  it("localizes the real seeded catalog, searches its labels and keeps category selection across locales", async () => {
    setLocale("en");
    expect(seededStyles).toHaveLength(8);
    const choose = vi.fn();
    server.use(http.get("/api/v1/media-styles", () => HttpResponse.json(seededStyles)));
    render(<QueryClientProvider client={createQueryClient()}><MediaStylePicker selected={null} onSelect={choose} onClose={vi.fn()} /></QueryClientProvider>);
    await screen.findByRole("button", { name: "Photorealistic photography" });
    expect(screen.getByRole("dialog").textContent).not.toMatch(/\p{Script=Han}/u);
    for (const image of screen.getAllByRole("img")) expect(image.getAttribute("alt")).not.toMatch(/\p{Script=Han}/u);
    const user = userEvent.setup();
    await user.type(screen.getByRole("searchbox", { name: "Search styles" }), "watercolor");
    expect(screen.getByRole("button", { name: "Watercolor illustration" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cinematic" })).not.toBeInTheDocument();
    await user.clear(screen.getByRole("searchbox", { name: "Search styles" }));
    await user.click(screen.getByRole("combobox", { name: "Category" }));
    await user.click(screen.getByRole("option", { name: "Painting" }));
    expect(screen.getByRole("button", { name: "Chinese ink wash" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cinematic" })).not.toBeInTheDocument();
    act(() => setLocale("ru"));
    expect(screen.getByRole("button", { name: "Китайская живопись тушью" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Акварельная иллюстрация" })).toBeInTheDocument();
    act(() => setLocale("en"));
    await user.click(screen.getByRole("button", { name: "Chinese ink wash" }));
    expect(choose).toHaveBeenCalledWith(seededStyles[5]!.id);
  });

  it("keeps customized and unrecognized Chinese style names and categories literal", async () => {
    setLocale("en");
    const styles = [
      { ...seededStyles[0]!, name: "我的中文摄影", category: "我的中文分类" },
      { ...seededStyles[1]!, id: "custom", builtIn: false },
      { ...seededStyles[2]!, id: "unknown" },
    ];
    server.use(http.get("/api/v1/media-styles", () => HttpResponse.json(styles)));
    render(<QueryClientProvider client={createQueryClient()}><MediaStylePicker selected={null} onSelect={vi.fn()} onClose={vi.fn()} /></QueryClientProvider>);
    await screen.findByRole("button", { name: "我的中文摄影" });
    for (const style of styles) expect(screen.getByRole("button", { name: style.name })).toBeInTheDocument();
    const user = userEvent.setup(); await user.click(screen.getByRole("combobox", { name: "Category" }));
    for (const style of styles) expect(screen.getByRole("option", { name: style.category })).toBeInTheDocument();
  });

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
    await selectValue(screen.getByRole("combobox", { name: "分类" }), JSON.stringify(["literal", "摄影"]));
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
