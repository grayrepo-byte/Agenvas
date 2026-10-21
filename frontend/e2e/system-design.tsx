import { QueryClientProvider } from "@tanstack/react-query";
import { createRoot } from "react-dom/client";
import { MemoryRouter, Navigate, Route, Routes } from "react-router";
import { createQueryClient } from "../src/app/queryClient";
import { LoginPage } from "../src/features/auth/LoginPage";
import { SetupPage } from "../src/features/auth/SetupPage";
import { ProjectsPage } from "../src/features/projects/ProjectsPage";
import type { CurrentUser, Project, ProjectList, SetupStatus } from "../src/shared/api/client";
import { setLocale } from "../src/shared/i18n";
import "../src/shared/ui/PageTheme.css";
import "../src/styles.css";

/**
 * Design-only preview of the real public entry screens and project library.
 * All accounts and projects are synthetic; every fetch is intercepted before mounting.
 * Writes live only in module memory, passwords are discarded, and nothing reaches a backend.
 *
 * /e2e/system-design.html?route=/login|/setup|/projects
 * Add &empty for an empty project library or &initialized for completed setup.
 * Canvas and settings have their own fixtures; this preview covers only these three routes.
 */
setLocale("zh");
const parameters = new URLSearchParams(window.location.search);
const requestedRoute = parameters.get("route");
const routes = new Set(["/login", "/setup", "/projects"]);
const initialRoute = requestedRoute && routes.has(requestedRoute) ? requestedRoute : "/projects";
const now = "2026-10-21T08:30:00Z";
let setupRequired = initialRoute === "/setup" && !parameters.has("initialized");
let signedIn = initialRoute === "/projects";
let user: CurrentUser = { id: "synthetic-admin", loginName: "studio-demo", role: "ADMIN" };
let createdCount = 0;

function project(id: string, name: string, aspectRatio: Project["aspectRatio"], updatedAt: string, archived = false): Project {
  return { id: `synthetic-${id}`, name, aspectRatio, status: archived ? "ARCHIVED" : "ACTIVE", version: 1,
    createdAt: "2026-10-12T03:00:00Z", updatedAt, archivedAt: archived ? updatedAt : null };
}

let projects: Project[] = parameters.has("empty") ? [] : [
  project("coastal-letter", "海岸来信 · 品牌短片", "LANDSCAPE_16_9", "2026-10-21T07:45:00Z"),
  project("objects-in-light", "光与物 · 产品视觉", "SQUARE_1_1", "2026-10-20T15:20:00Z"),
  project("city-fragments", "城市切片 · 竖屏叙事", "PORTRAIT_9_16", "2026-10-19T10:40:00Z"),
  project("autumn-campaign", "秋日提案 · 视觉探索", "LANDSCAPE_16_9", "2026-10-18T04:10:00Z"),
  project("everyday-poetry", "日常诗意 · 灵感手记", "PORTRAIT_9_16", "2026-10-17T12:15:00Z"),
  project("new-perspectives", "新的视角 · 概念实验", "SQUARE_1_1", "2026-10-16T09:30:00Z"),
  project("archived-study", "已归档 · 夏日习作", "LANDSCAPE_16_9", "2026-10-15T02:00:00Z", true),
];

function error(message: string, status = 422, code = "SYNTHETIC_PREVIEW_UNSUPPORTED") {
  return Response.json({ title: "Synthetic fixture request", status, code, detail: message },
    { status, headers: { "Content-Type": "application/problem+json" } });
}

function stringField(body: Record<string, unknown>, key: string): string {
  return typeof body[key] === "string" ? body[key] : "";
}

function isAspectRatio(value: unknown): value is Project["aspectRatio"] {
  return value === "LANDSCAPE_16_9" || value === "PORTRAIT_9_16" || value === "SQUARE_1_1";
}

async function requestBody(input: RequestInfo | URL, init?: RequestInit): Promise<Record<string, unknown>> {
  const text = typeof init?.body === "string" ? init.body : input instanceof Request ? await input.clone().text() : "";
  if (!text) return {};
  const parsed: unknown = JSON.parse(text);
  return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed as Record<string, unknown> : {};
}

window.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), window.location.href);
  const path = url.pathname;
  const method = (init?.method ?? (input instanceof Request ? input.method : "GET")).toUpperCase();
  let body: Record<string, unknown>;
  try { body = await requestBody(input, init); }
  catch { return error("合成预览的请求正文必须是 JSON 对象。", 400, "INVALID_REQUEST"); }

  if (path === "/api/v1/auth/csrf" && method === "GET") return Response.json({ headerName: "X-XSRF-TOKEN", token: "synthetic-preview" });
  if (path === "/api/v1/auth/setup-status" && method === "GET") {
    return Response.json({ setupRequired } satisfies SetupStatus);
  }
  if (path === "/api/v1/auth/setup" && method === "POST") {
    if (!setupRequired) return error("此合成工作区已经初始化。", 409, "SETUP_ALREADY_COMPLETED");
    user = { ...user, loginName: stringField(body, "loginName") || "studio-demo" };
    setupRequired = false;
    return Response.json(user);
  }
  if (path === "/api/v1/auth/login" && method === "POST") {
    // Any form-valid demo password succeeds; it is neither retained nor echoed.
    user = { ...user, loginName: stringField(body, "loginName") || "studio-demo" };
    signedIn = true;
    return Response.json(user);
  }
  if (path === "/api/v1/auth/logout" && method === "POST") {
    signedIn = false;
    return new Response(null, { status: 204 });
  }
  if (path === "/api/v1/auth/me" && method === "GET") {
    return signedIn ? Response.json(user) : error("请使用任意演示密码登录合成工作区。", 401, "UNAUTHORIZED");
  }
  if (!signedIn && path.startsWith("/api/v1/projects")) return error("合成会话尚未登录。", 401, "UNAUTHORIZED");

  if (path === "/api/v1/projects" && method === "GET") {
    const includeArchived = url.searchParams.get("includeArchived") === "true";
    const visible = projects.filter((entry) => includeArchived || entry.status === "ACTIVE")
      .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt));
    const requestedLimit = Number(url.searchParams.get("limit") ?? 100);
    const requestedOffset = Number(url.searchParams.get("cursor") ?? 0);
    const limit = Number.isSafeInteger(requestedLimit) && requestedLimit > 0 ? Math.min(requestedLimit, 100) : 100;
    const offset = Number.isSafeInteger(requestedOffset) && requestedOffset >= 0 ? requestedOffset : 0;
    return Response.json({ items: visible.slice(offset, offset + limit),
      nextCursor: offset + limit < visible.length ? String(offset + limit) : null } satisfies ProjectList);
  }
  if (path === "/api/v1/projects" && method === "POST") {
    const name = stringField(body, "name").trim();
    if (!name || !isAspectRatio(body.aspectRatio)) return error("请填写项目名称并选择画幅。", 400, "INVALID_REQUEST");
    const created: Project = { id: `synthetic-created-${++createdCount}`, name, aspectRatio: body.aspectRatio,
      status: "ACTIVE", version: 1, createdAt: now, updatedAt: now, archivedAt: null };
    projects = [created, ...projects];
    return Response.json(created, { status: 201 });
  }
  const projectMatch = /^\/api\/v1\/projects\/([^/]+)(\/archive)?$/.exec(path);
  if (projectMatch) {
    const existing = projects.find((entry) => entry.id === projectMatch[1]);
    if (!existing) return error("此合成项目不存在。", 404, "PROJECT_NOT_FOUND");
    if (method === "GET" && !projectMatch[2]) return Response.json(existing);
    if ((method === "PATCH" && !projectMatch[2]) || (method === "POST" && projectMatch[2])) {
      if (body.expectedVersion !== existing.version) return error("项目已在合成预览中变化，请刷新后重试。", 409, "PROJECT_CONFLICT");
      if (existing.status === "ARCHIVED") return error("已归档项目仅供查看。", 409, "PROJECT_ARCHIVED");
      const archiving = Boolean(projectMatch[2]);
      const name = stringField(body, "name").trim();
      if (!archiving && !name) return error("项目名称不能为空。", 400, "INVALID_REQUEST");
      const changed: Project = { ...existing, name: archiving ? existing.name : name,
        aspectRatio: isAspectRatio(body.aspectRatio) ? body.aspectRatio : existing.aspectRatio,
        status: archiving ? "ARCHIVED" : "ACTIVE", archivedAt: archiving ? now : null,
        version: existing.version + 1, updatedAt: now };
      projects = projects.map((entry) => entry.id === existing.id ? changed : entry);
      return Response.json(changed);
    }
  }
  return error(`This preview has no synthetic response for ${method} ${path}`);
};

const root = document.getElementById("root");
if (!root) throw new Error("System design fixture root is missing");
createRoot(root).render(<QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={[initialRoute]}><Routes>
  <Route path="/login" element={<LoginPage />} />
  <Route path="/setup" element={<SetupPage />} />
  <Route path="/projects" element={<ProjectsPage />} />
  <Route path="*" element={<Navigate to="/projects" replace />} />
</Routes></MemoryRouter></QueryClientProvider>);
