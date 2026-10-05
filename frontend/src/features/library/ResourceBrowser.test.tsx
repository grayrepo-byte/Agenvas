import { QueryClientProvider } from "@tanstack/react-query";
import { render,screen,waitFor,within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http,HttpResponse } from "msw";
import { MemoryRouter } from "react-router";
import { expect,it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { ResourceResult } from "../../shared/api/client";
import { server } from "../../test/server";
import { LibraryPage } from "./LibraryPage";
import { ResourceBrowser } from "./ResourceBrowser";

const image: ResourceResult = { versionId: "image-v2", artifactId: "image", projectId: "project-a", projectName: "项目甲",
  title: "未收藏图片", kind: "IMAGE", versionNo: 2, content: { sourceType: "UPLOAD", assetId: "asset-image" }, createdAt: "2026-10-04T00:00:00Z" };
const oldImage: ResourceResult = { ...image, versionId: "image-v1", versionNo: 1, title: "旧图片", projectName: "项目乙", createdAt: "2026-10-03T00:00:00Z" };
const hiddenText: ResourceResult = { ...image, versionId: "text-v1", title: "隐藏文字", kind: "TEXT", content: { format: "PLAIN_TEXT", text: "不显示" } };
const audio: ResourceResult = { ...image, versionId: "audio-v1", title: "音频结果", kind: "AUDIO", content: { sourceType: "UPLOAD", assetId: "asset-audio" } };

function renderBrowser() {
  return render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><ResourceBrowser /></MemoryRouter></QueryClientProvider>);
}

it("opens all resources from the asset page without requiring library saves", async () => {
  let reads = 0;
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
    http.get("/api/v1/library/entries", () => HttpResponse.json({ items: [], total: 0, categoryCounts: {}, nextCursor: null })),
    http.get("/api/v1/resources", () => { reads += 1; return HttpResponse.json({ items: [image], nextCursor: null }); }),
  );
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LibraryPage /></MemoryRouter></QueryClientProvider>);
  const user = userEvent.setup();
  expect(reads).toBe(0);
  await user.click(await screen.findByRole("tab", { name: "全部资源" }));
  await user.click(await screen.findByRole("button", { name: "查看 未收藏图片 · v2" }));
  const detail = screen.getByRole("dialog");
  expect(within(detail).getByRole("img", { name: image.title })).toHaveAttribute("src", "/api/v1/projects/project-a/assets/asset-image/content");
  expect(within(detail).getByRole("link", { name: "下载资源" })).toHaveAttribute("href", "/api/v1/projects/project-a/assets/asset-image/content");
  expect(within(detail).getByRole("link", { name: "下载资源" })).toHaveAttribute("download", image.title);
  expect(within(detail).getByRole("link", { name: "打开来源项目" })).toHaveAttribute("href", "/projects/project-a");
});

it("loads older exact versions and sends search and type filters to the server", async () => {
  const requests: URL[] = [];
  server.use(http.get("/api/v1/resources", ({ request }) => {
    const url = new URL(request.url); requests.push(url);
    return HttpResponse.json(url.searchParams.has("cursor") ? { items: [oldImage], nextCursor: null } : { items: [image], nextCursor: "older" });
  }));
  renderBrowser();
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "加载更多资源" }));
  await user.click(await screen.findByRole("button", { name: "查看 旧图片 · v1" }));
  const detail = screen.getByRole("dialog");
  expect(within(detail).getByRole("link", { name: "下载资源" })).toHaveAttribute("download", "旧图片");
  await user.click(within(detail).getByRole("button", { name: "关闭窗口" }));
  await user.type(screen.getByRole("searchbox", { name: "搜索资源" }), "项目乙");
  await user.click(screen.getByRole("combobox", { name: "媒体类型" }));
  await user.click(screen.getByRole("option", { name: "图片" }));
  await waitFor(() => expect(requests.some((url) => url.searchParams.get("query") === "项目乙" && url.searchParams.get("kind") === "IMAGE")).toBe(true));
  expect(requests.some((url) => url.searchParams.get("cursor") === "older")).toBe(true);
});

it("retries a failed next page while preserving already loaded resources", async () => {
  let failed = false;
  server.use(http.get("/api/v1/resources", ({ request }) => {
    if (!new URL(request.url).searchParams.has("cursor")) return HttpResponse.json({ items: [image], nextCursor: "older" });
    if (!failed) { failed = true; return HttpResponse.json({ detail: "暂时无法读取", code: "UNAVAILABLE" }, { status: 503 }); }
    return HttpResponse.json({ items: [oldImage], nextCursor: null });
  }));
  renderBrowser();
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "加载更多资源" }));
  await screen.findByRole("alert");
  expect(screen.getByRole("button", { name: "查看 未收藏图片 · v2" })).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "重试读取资源" }));
  await screen.findByRole("button", { name: "查看 旧图片 · v1" });
});

it("shows audio placeholders, previews and downloads while hiding only text", async () => {
  let empty = true;
  const video: ResourceResult = { ...image, versionId: "video-v1", title: "短片", kind: "VIDEO", content: { sourceType: "UPLOAD", assetId: "asset-video" } };
  const requests: URL[] = [];
  server.use(http.get("/api/v1/resources", ({ request }) => {
    requests.push(new URL(request.url));
    return HttpResponse.json({ items: empty ? [] : [video, image, hiddenText, audio], nextCursor: null });
  }));
  renderBrowser();
  const user = userEvent.setup();
  await screen.findByText("在画布中完成的结果会自动显示在这里。");
  empty = false;
  await user.click(screen.getByRole("button", { name: "刷新资源" }));
  await user.click(await screen.findByRole("button", { name: "查看 短片 · v2" }));
  expect(screen.getByRole("dialog").querySelector("video")).not.toHaveAttribute("autoplay");
  await user.click(screen.getByRole("button", { name: "关闭窗口" }));
  expect(screen.queryByRole("button", { name: /隐藏文字/ })).not.toBeInTheDocument();
  const audioCard = screen.getByRole("button", { name: "查看 音频结果 · v2" });
  expect(audioCard.querySelector("img")).toBeNull();
  expect(audioCard.querySelector(".library-card-preview svg")).toBeInTheDocument();
  await user.click(audioCard);
  const detail = screen.getByRole("dialog");
  expect(detail.querySelector("audio")).toHaveAttribute("src", "/api/v1/projects/project-a/assets/asset-audio/content");
  expect(detail.querySelector("audio")).toHaveAttribute("controls");
  expect(detail.querySelector("audio")).not.toHaveAttribute("autoplay");
  expect(within(detail).getByRole("link", { name: "下载资源" })).toHaveAttribute("href", "/api/v1/projects/project-a/assets/asset-audio/content");
  await user.click(within(detail).getByRole("button", { name: "关闭窗口" }));
  await user.click(screen.getByRole("combobox", { name: "媒体类型" }));
  expect(screen.getByRole("option", { name: "图片" })).toBeInTheDocument();
  expect(screen.getByRole("option", { name: "视频" })).toBeInTheDocument();
  expect(screen.queryByRole("option", { name: "文字" })).not.toBeInTheDocument();
  await user.click(screen.getByRole("option", { name: "音频" }));
  await waitFor(() => expect(requests.some((url) => url.searchParams.get("kind") === "AUDIO")).toBe(true));
});
