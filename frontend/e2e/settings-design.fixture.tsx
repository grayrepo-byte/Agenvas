import { QueryClientProvider } from "@tanstack/react-query";
import { createRoot } from "react-dom/client";
import { MemoryRouter, Navigate, Route, Routes } from "react-router";
import { createQueryClient } from "../src/app/queryClient";
import { CallLogsPage } from "../src/features/settings/CallLogsPage";
import { LlmSettingsPage } from "../src/features/settings/LlmSettingsPage";
import { MediaFunctionSettingsPage } from "../src/features/settings/MediaFunctionSettingsPage";
import { MediaSettingsPage } from "../src/features/settings/MediaSettingsPage";
import { StorageSettingsPage } from "../src/features/settings/StorageSettingsPage";
import { SystemLogsPage } from "../src/features/settings/SystemLogsPage";
import { SystemSettingsPage } from "../src/features/settings/SystemSettingsPage";
import { LibraryPage } from "../src/features/library/LibraryPage";
import { SkillsPage } from "../src/features/skills/SkillsPage";
import { workspaceDesignResponse } from "./workspace-design-data";
import type {
  CallDebug, CallLog, CallLogRetentionSettings, DebugSettings, LlmSettings, MediaCapability, MediaConnection,
  MediaFunctionSetting, MediaSettings, MediaStyle, MediaTemplate, PromptDefinition, StorageProfile, StorageProvider, StorageSettings,
  SystemLogSnapshot,
} from "../src/shared/api/client";
import { setLocale } from "../src/shared/i18n";
import { mediaFunctionChoices } from "../src/shared/mediaFunctions";
import { imageFunctionSettings, imageFunctionsFixture, imageWorkflowCapability } from "../src/test/imageFunctionsFixture";
import { klingProfiles } from "../src/test/klingProfiles";
import { nativeProfiles } from "../src/test/nativeProfiles";
import { videoFunctionCapability, videoFunctionsFixture } from "../src/test/videoFunctionsFixture";
import "../src/shared/ui/design-tokens.css";
import "../src/shared/ui/PageTheme.css";
import "../src/styles.css";

/**
 * Browser-only design fixture. Every account, model configuration and audit entry below
 * is synthetic. Fetch is intercepted before the real application mounts; no request
 * reaches a backend or Provider. Writes live only in this module's memory.
 * MemoryRouter keeps the fixture URL stable across navigation and development reloads.
 * Open /e2e/settings-design.html?route=/settings/providers (or another settings route).
 */
const now = "2026-10-20T08:30:00Z";
setLocale("zh");
const requestedRoute = new URLSearchParams(window.location.search).get("route");
const routes = new Set(["providers", "llm", "media", "functions", "storage", "calls", "logs", "general"].map((route) => `/settings/${route}`));
routes.add("/library"); routes.add("/skills");
const initialRoute = requestedRoute && routes.has(requestedRoute.split("?")[0] ?? "") ? requestedRoute : "/settings/providers";

let llm: LlmSettings = {
  configured: true, version: 4, endpoint: "https://api.example.com/v1", modelId: "creative-director-model",
  keyMask: "••••DEMO", toolCallingVerified: false, updatedAt: now,
};
let retention: CallLogRetentionSettings = { retentionDays: 90, version: 2 };
let debug: DebugSettings = { debugMode: false, version: 1 };
const imageSettings = imageFunctionsFixture();
const videoSettings = videoFunctionsFixture();
const cloudImage = imageSettings.connections[1]!;
const local = imageSettings.connections[0]!;
local.capabilities.push(...videoSettings.connections[0]!.capabilities);
cloudImage.id = "synthetic-openai";
cloudImage.name = "OpenAI · 创作图片";
cloudImage.keyMask = "••••DEMO";
cloudImage.capabilities[0]!.name = "GPT Image · 视觉创作";
cloudImage.capabilities[0]!.supportedImageAspectRatios = ["AUTO", "1:1", "16:9", "9:16"];
cloudImage.capabilities[0]!.supportedImageResolutions = ["1K", "2K", "4K"];
cloudImage.capabilities[0]!.supportedImageQualities = ["low", "medium", "high"];
cloudImage.capabilities[0]!.settings = { defaultParameters: { resolution: "2K", quality: "high" }, pricing: { amount: "0.08", currency: "USD", unit: "IMAGE" } };
const workflow = videoSettings.connections[1]!;
workflow.name = "RunningHub · 制作工作流";
workflow.keyMask = "••••DEMO";
workflow.capabilities.push(imageWorkflowCapability());
workflow.capabilities[0]!.name = "视频超分 · 4K 修复";
workflow.capabilities[1]!.name = "图片超分 · 细节增强";
let media: MediaSettings = {
  defaults: [
    { kind: "IMAGE_GENERATION", capabilityId: "ai-capability", version: 2 },
    { kind: "VIDEO_GENERATION", capabilityId: "synthetic-video", version: 1 },
  ],
  connections: [cloudImage, {
    id: "synthetic-google", name: "Google · 影像实验", platform: "GOOGLE", enabled: true,
    version: 2, connectionVersion: 1, origin: null, keyMask: "••••DEMO", connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
    capabilities: [videoFunctionCapability({ id: "synthetic-video", name: "Veo · 叙事镜头", adapterId: "GOOGLE_VEO", kind: "VIDEO_GENERATION",
      minimumSeconds: 4, maximumSeconds: 8, maxReferenceImages: 3, supportedVideoInputModes: ["TEXT", "START_END", "GENERAL_REFERENCE"], defaultVideoInputMode: "TEXT",
      settings: { defaultDurationSeconds: 8, defaultParameters: { aspectRatio: "16:9", videoResolution: "1080p" } } })],
  }, workflow, {
    id: "synthetic-comfy", name: "ComfyUI · 本地实验室", platform: "COMFYUI", enabled: true,
    version: 1, connectionVersion: 1, origin: "http://comfy.example.test/[configured-path]", keyMask: null,
    connectivityStatus: "NOT_CHECKED", realGenerationTested: false, capabilities: [],
  }, {
    id: "synthetic-ark", name: "火山引擎 · 备用连接", platform: "ARK", enabled: false,
    version: 3, connectionVersion: 2, origin: null, keyMask: "••••DEMO", connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
    capabilities: [videoFunctionCapability({ id: "synthetic-disabled", name: "Seedream · 备用图片", adapterId: "ARK_SEEDREAM_IMAGE", kind: "IMAGE_GENERATION", enabled: false })],
  }, local],
};
let functions: MediaFunctionSetting[] = [
  ...imageFunctionSettings().map((entry, index) => index === 1 || index === 3 ? { ...entry, capabilityId: null } : entry),
  { operation: "VIDEO_UPSCALE", capabilityId: "upscale-cap", version: 3 },
  { operation: "VIDEO_DEPTH_MAP", capabilityId: "depth-cap", version: 1 },
  { operation: "VIDEO_EXTRACT_AUDIO", capabilityId: "audio-cap", version: 1 },
];
let storage: StorageSettings = {
  version: 3, activeProfileId: null, relayProfileId: "synthetic-s3", llmRelayEnabled: true, imageRelayEnabled: false,
  profiles: [{ id: "synthetic-s3", name: "团队媒体归档", provider: "S3", endpoint: "https://storage.example.com",
    region: "ap-east-1", bucket: "synthetic-creative-assets", keyPrefix: "agenvas", pathStyle: false,
    accessKeyMask: "••••DEMO", createdAt: now, inUse: false }, {
    id: "synthetic-nas", name: "工作室 NAS", provider: "WEBDAV", endpoint: "https://nas.example.com/dav/media",
    region: "", bucket: "", keyPrefix: "archive", pathStyle: false, accessKeyMask: "••••DEMO", createdAt: now, inUse: true,
  }],
};
let nextStorageProfile = 1;
let prompts: PromptDefinition[] = [
  { id: "synthetic-director", key: "agent.director", kind: "AGENT", name: "导演 Agent", description: "将创作意图转化为清晰的故事结构与镜头语言。",
    content: "你是创作项目的导演。\n\n先理解用户的目标、素材与限制，再制定清晰的创作计划。\n只使用明确授权的工具；在媒体生成前提出可审查的提案。\n\n所有内容为设计预览使用的合成提示词。", builtIn: true, version: 2, createdAt: now, updatedAt: now },
  { id: "synthetic-copywriter", key: "agent.copywriter", kind: "AGENT", name: "创意文案", description: "打磨叙事、人物与表达节奏。",
    content: "根据当前项目背景编写简洁、有画面感的文案。保持角色与信息的一致性。", builtIn: false, version: 1, createdAt: now, updatedAt: now },
  { id: "synthetic-image-prompt", key: "function.image-prompt", kind: "FUNCTION", name: "图片提示词优化", description: "将画面描述整理为可复用的图片提示词。",
    content: "从主体、场景、光线、构图与材质五个维度整理画面描述。", builtIn: true, version: 1, createdAt: now, updatedAt: now },
];
let styles: MediaStyle[] = [
  { id: "synthetic-cinema", name: "电影叙事", category: "影像", enabled: true, builtIn: true, version: 1, thumbnailUrl: null, promptSuffix: "Cinematic composition, natural lighting, subtle film grain." },
  { id: "synthetic-watercolor", name: "水彩手绘", category: "插画", enabled: true, builtIn: true, version: 1, thumbnailUrl: null, promptSuffix: "Watercolor illustration, soft pigment edges, textured paper." },
  { id: "synthetic-product", name: "极简产品", category: "商业", enabled: true, builtIn: false, version: 2, thumbnailUrl: null, promptSuffix: "Minimal studio product photography, clean background, precise materials." },
  { id: "synthetic-retro", name: "复古胶片", category: "影像", enabled: false, builtIn: false, version: 1, thumbnailUrl: null, promptSuffix: "Vintage color film, warm highlights, soft contrast." },
];
let templates: MediaTemplate[] = [
  { id: "synthetic-still", name: "自然光人物肖像", targetKind: "IMAGE", scope: "SYSTEM", prompt: "人物位于窗边，自然光照亮面部，浅景深，克制而真实的色彩。", images: [], version: 1, createdAt: now, updatedAt: now },
  { id: "synthetic-camera", name: "电影感环境镜头", targetKind: "VIDEO", scope: "SYSTEM", prompt: "缓慢向前推进，保持主体构图，柔和的环境光，自然连续的运动。", images: [], version: 1, createdAt: now, updatedAt: now },
];
const calls: CallLog[] = Array.from({ length: 12 }, (_, index) => {
  const kind = (["LLM", "IMAGE", "VIDEO", "IMAGE"] as const)[index % 4]!;
  const status = (["SUCCEEDED", "SUCCEEDED", "SUCCEEDED", "FAILED", "UNKNOWN", "SUCCEEDED"] as const)[index % 6]!;
  const started = new Date(new Date(now).getTime() - index * 181000);
  const durationMs = kind === "LLM" ? 1824 : kind === "VIDEO" ? 3451 : 2267;
  return { id: `synthetic-call-${index + 1}`, projectId: "synthetic-project", projectTitle: index % 2 ? "秋日短片 · 光与回忆" : "品牌视觉探索",
    taskId: null, runId: null, kind, operation: kind === "LLM" ? "CHAT" : "SUBMIT", status,
    taskStatus: kind === "LLM" ? null : status, provider: kind === "LLM" ? "OpenAI compatible" : kind === "VIDEO" ? "Google" : "OpenAI",
    model: kind === "LLM" ? "creative-director-model" : kind === "VIDEO" ? "veo-3.1" : "gpt-image-2",
    traceId: `synthetic-trace-${index + 1}`, providerRequestId: `synthetic-request-${index + 1}`,
    errorCode: status === "FAILED" ? "PROVIDER_RATE_LIMITED" : status === "UNKNOWN" ? "PROVIDER_RESPONSE_UNKNOWN" : null,
    startedAt: started.toISOString(), respondedAt: new Date(started.getTime() + durationMs).toISOString(), durationMs, historical: false, mock: true };
});
const systemLogs: SystemLogSnapshot = {
  processId: "synthetic-process", startedAt: "2026-10-20T06:00:00Z", checkedAt: now,
  capacity: 2000, retainedCount: 9, droppedCount: 0, matchedCount: 9,
  entries: [
    "INFO  AgenvasApplication — Started AgenvasApplication in 3.842 seconds",
    "INFO  DatabaseConnection — PostgreSQL connection pool is ready",
    "INFO  StorageService — Local media archive is available",
    "INFO  TaskScheduler — Recovery scan completed; no expired leases",
    "INFO  MediaCatalog — Loaded synthetic media capability catalog",
    "WARN  ProviderAdapter — Synthetic rate limit response; polling deferred",
    "INFO  EventPublisher — Project event stream is up to date",
    "INFO  CallLogCleanup — Retention policy: 90 days",
    "INFO  SystemDiagnostics — Local diagnostics snapshot refreshed",
  ].map((message, index) => ({ sequence: index + 1, recordedAt: new Date(new Date(now).getTime() - (9 - index) * 3000).toISOString(),
    stream: index === 5 ? "STDERR" : "STDOUT", message, truncated: false })),
};

function stringField(input: Record<string, unknown>, field: string, fallback = "") {
  return typeof input[field] === "string" ? input[field] : fallback;
}
function boolField(input: Record<string, unknown>, field: string, fallback: boolean) {
  return typeof input[field] === "boolean" ? input[field] : fallback;
}
function error(message: string, status = 422, code = "SYNTHETIC_PREVIEW_UNSUPPORTED") {
  return Response.json({ title: "Synthetic fixture request", status, code, detail: message },
    { status, headers: { "Content-Type": "application/problem+json" } });
}

const storageLocationFields = ["provider", "endpoint", "region", "bucket", "keyPrefix", "pathStyle"] as const;
type StorageLocation = Pick<StorageProfile, "name" | typeof storageLocationFields[number]>;
const storageProviders: readonly StorageProvider[] = ["ALIYUN_OSS", "TENCENT_COS", "S3", "SMB", "WEBDAV"];
const isRemoteFile = (provider: StorageProvider) => provider === "SMB" || provider === "WEBDAV";
const invalidStorage = (message: string) => error(message, 400, "STORAGE_CONFIG_INVALID");
const missingStorage = () => error("此合成存储连接不存在。", 404, "STORAGE_PROFILE_NOT_FOUND");
const referencedStorage = () => error("此连接已被素材引用，不能迁移位置或删除；仍可修改名称和凭证。", 409, "STORAGE_PROFILE_IN_USE");

/** Representative protocol validation; no endpoint is contacted and usable credentials are never retained. */
function storageLocation(body: Record<string, unknown>): StorageLocation | Response {
  const provider = storageProviders.find((value) => value === body.provider);
  if (!provider) return invalidStorage("请选择有效的存储类型。");
  const name = stringField(body, "name").trim();
  const endpoint = stringField(body, "endpoint").trim();
  const region = stringField(body, "region").trim();
  const bucket = stringField(body, "bucket").trim();
  const keyPrefix = stringField(body, "keyPrefix").trim().replace(/^\/+|\/+$/g, "");
  const pathStyle = boolField(body, "pathStyle", false);
  if (!name || name.length > 120) return invalidStorage("连接名称须为 1–120 个字符。");
  if (keyPrefix.length > 120 || !/^[A-Za-z0-9_/-]*$/.test(keyPrefix) || keyPrefix.includes("//")) return invalidStorage("请填写有效的对象前缀。");
  if (pathStyle && provider !== "S3") return invalidStorage("只有 S3 连接支持 Path Style。");
  try {
    const parsed = new URL(endpoint);
    if (parsed.protocol !== (provider === "SMB" ? "smb:" : "https:") || !parsed.hostname || parsed.username || parsed.password
        || parsed.search || parsed.hash || parsed.port === "0") return invalidStorage("连接地址不能包含凭证、查询参数或片段。");
    if (isRemoteFile(provider)) {
      if (region || bucket || pathStyle || (provider === "SMB" && (!parsed.pathname || parsed.pathname === "/"))) return invalidStorage("远程文件连接须填写共享目录，不使用 Region、Bucket 或 Path Style。");
    } else if (parsed.pathname && parsed.pathname !== "/") return invalidStorage("对象存储 Endpoint 须为不带路径的 HTTPS 地址。");
  } catch { return invalidStorage("连接地址格式无效。"); }
  if (!isRemoteFile(provider) && (!/^[a-z0-9][a-z0-9-]{0,79}$/.test(region) || !/^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/.test(bucket)
      || bucket.includes("..") || /^[0-9.]+$/.test(bucket))) return invalidStorage("Region 或 Bucket 格式无效。");
  return { name, provider, endpoint: endpoint.replace(/\/+$/, ""), region, bucket, keyPrefix, pathStyle };
}

function validStorageCredentials(provider: StorageProvider, body: Record<string, unknown>) {
  const id = stringField(body, "accessKeyId");
  const secret = stringField(body, "secretAccessKey");
  if (!isRemoteFile(provider)) return /^[A-Za-z0-9_-]{4,128}$/.test(id) && /^[!-~]{8,4096}$/.test(secret);
  const hasControl = (value: string) => [...value].some((character) => { const code = character.codePointAt(0)!; return code < 32 || code === 127; });
  return !!id.trim() && id.length <= 128 && !!secret && secret.length <= 4096 && !hasControl(id) && !hasControl(secret)
    && (provider !== "WEBDAV" || !id.includes(":")) && (provider !== "SMB" || !id.includes("\\") || /^[^\\]+\\[^\\]+$/.test(id));
}

function storageResponse(url: URL, method: string, body: Record<string, unknown>) {
  const path = url.pathname;
  if (path === "/api/v1/settings/storage" && method === "GET") return Response.json(storage);
  const profilesPath = "/api/v1/settings/storage/profiles";
  const profileParts = path.startsWith(`${profilesPath}/`) ? path.slice(profilesPath.length + 1).split("/") : [];
  const create = path === profilesPath && method === "POST";
  const update = profileParts.length === 1 && method === "PUT";
  const remove = profileParts.length === 1 && method === "DELETE";
  const rotate = profileParts.length === 2 && profileParts[1] === "credentials" && method === "PUT";
  const activate = path === "/api/v1/settings/storage/active" && method === "PUT";
  const relay = path === "/api/v1/settings/storage/relay" && method === "PUT";
  if (!create && !update && !remove && !rotate && !activate && !relay) return error("此合成存储操作未实现。", 405);
  const expectedVersion = remove ? Number(url.searchParams.get("expectedVersion") ?? Number.NaN) : body.expectedVersion;
  if (typeof expectedVersion !== "number" || !Number.isSafeInteger(expectedVersion) || expectedVersion < 0) return invalidStorage("配置版本无效。");
  if (expectedVersion !== storage.version) return error("存储配置已变化，请载入最新配置后重试。", 409, "STORAGE_VERSION_CONFLICT");
  const profile = storage.profiles.find((value) => value.id === profileParts[0]);
  if ((update || remove || rotate) && !profile) return missingStorage();
  if (activate || relay) {
    if (body.profileId !== null && typeof body.profileId !== "string") return invalidStorage("请选择连接或本地存储。");
    const selected = storage.profiles.find((value) => value.id === body.profileId);
    if (body.profileId !== null && !selected) return missingStorage();
    if (relay && selected && isRemoteFile(selected.provider)) return invalidStorage("SMB 和 WebDAV 不支持媒体中继。");
    if (relay && (typeof body.llmRelayEnabled !== "boolean" || typeof body.imageRelayEnabled !== "boolean")) return invalidStorage("请提供中继功能开关。");
    storage = activate ? { ...storage, version: storage.version + 1, activeProfileId: body.profileId as string | null }
      : { ...storage, version: storage.version + 1, relayProfileId: body.profileId as string | null,
        llmRelayEnabled: boolField(body, "llmRelayEnabled", storage.llmRelayEnabled), imageRelayEnabled: boolField(body, "imageRelayEnabled", storage.imageRelayEnabled) };
  } else if (remove && profile) {
    if (profile.inUse) return referencedStorage();
    storage = { ...storage, version: storage.version + 1, profiles: storage.profiles.filter((value) => value.id !== profile.id),
      activeProfileId: storage.activeProfileId === profile.id ? null : storage.activeProfileId,
      relayProfileId: storage.relayProfileId === profile.id ? null : storage.relayProfileId };
  } else if (rotate && profile) {
    if (!validStorageCredentials(profile.provider, body)) return invalidStorage("请填写有效且完整的凭证。");
    const accessKeyMask = `••••${stringField(body, "accessKeyId").slice(-4)}`;
    storage = { ...storage, version: storage.version + 1, profiles: storage.profiles.map((value) => value.id === profile.id ? { ...value, accessKeyMask } : value) };
  } else if (create || update) {
    const location = storageLocation(body);
    if (location instanceof Response) return location;
    if (profile?.inUse && storageLocationFields.some((field) => location[field] !== profile[field])) return referencedStorage();
    if (profile?.id === storage.relayProfileId && isRemoteFile(location.provider)) return invalidStorage("请先停用该连接的媒体中继，再切换存储协议。");
    if (location.provider === "ALIYUN_OSS" && location.region.startsWith("oss-")
        && (!profile || profile.provider !== location.provider || profile.region !== location.region)) return invalidStorage("OSS Region 不应包含 oss- 前缀。");
    const hasCredentials = !!stringField(body, "accessKeyId").trim() || !!stringField(body, "secretAccessKey").trim();
    if ((create || hasCredentials || profile?.provider !== location.provider) && !validStorageCredentials(location.provider, body)) return invalidStorage("请填写有效且完整的凭证；切换存储协议时须重新输入。");
    const saved: StorageProfile = { ...location, id: profile?.id ?? `synthetic-storage-${nextStorageProfile++}`,
      createdAt: profile?.createdAt ?? now, inUse: profile?.inUse ?? false,
      accessKeyMask: hasCredentials ? `••••${stringField(body, "accessKeyId").slice(-4)}` : profile?.accessKeyMask ?? "••••DEMO" };
    storage = { ...storage, version: storage.version + 1,
      profiles: create ? [...storage.profiles, saved] : storage.profiles.map((value) => value.id === saved.id ? saved : value) };
  }
  return Response.json(storage);
}

window.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), window.location.href);
  const path = url.pathname;
  const method = init?.method ?? (input instanceof Request ? input.method : "GET");
  const rawBody = typeof init?.body === "string" ? init.body : null;
  const body: Record<string, unknown> = rawBody ? JSON.parse(rawBody) as Record<string, unknown> : {};
  const workspace = workspaceDesignResponse(url, method, body);
  if (workspace) return workspace;
  if (path === "/api/v1/auth/me") return Response.json({ id: "synthetic-admin", loginName: "演示管理员", role: "ADMIN" });
  if (path === "/api/v1/auth/csrf") return Response.json({ headerName: "X-CSRF-TOKEN", token: "synthetic-preview" });
  if (path === "/api/v1/auth/logout" || path === "/api/v1/auth/change-password") return new Response(null, { status: 204 });
  if (path === "/api/v1/settings/llm") {
    if (method === "PUT") llm = { ...llm, endpoint: stringField(body, "endpoint"), modelId: stringField(body, "modelId"), configured: true, toolCallingVerified: false, version: llm.version + 1, updatedAt: now };
    return Response.json(llm);
  }
  if (path === "/api/v1/settings/llm/diagnose") { llm = { ...llm, toolCallingVerified: true }; return Response.json(llm); }
  if (path === "/api/v1/settings/diagnostics") return Response.json({
    checkedAt: now, database: "AVAILABLE", storage: "AVAILABLE", llmMode: "CONFIGURED", llmConfigured: llm.configured,
    llmToolCallingVerified: llm.toolCallingVerified, mediaMode: "CONFIGURED", imageConfigured: true, videoConfigured: true,
    recentErrors: [{ status: "FAILED", count: 2, lastAt: now }, { status: "UNKNOWN", count: 1, lastAt: now }],
  });
  if (path === "/api/v1/settings/media-profiles") return Response.json({ profiles: nativeProfiles });
  if (path === "/api/v1/settings/kling/profiles") return Response.json({ profiles: klingProfiles });
  if (path === "/api/v1/settings/media-connections") {
    if (method === "POST") {
      const platform = media.connections.find((connection) => connection.platform === body.platform)?.platform ?? "OPENAI";
      const connection: MediaConnection = { id: `synthetic-connection-${media.connections.length + 1}`, name: stringField(body, "name"), platform,
        enabled: true, version: 1, connectionVersion: 1, origin: stringField(body, "origin") || null, keyMask: "••••DEMO",
        connectivityStatus: "NOT_CHECKED", realGenerationTested: false, capabilities: [] };
      media = { ...media, connections: [...media.connections, connection] };
    }
    return Response.json(media);
  }
  if (path.startsWith("/api/v1/settings/media-connections/")) {
    const [, , , , , connectionId, section, capabilityId] = path.split("/");
    media = { ...media, connections: media.connections.flatMap((connection) => {
      if (connection.id !== connectionId) return [connection];
      if (method === "DELETE" && !section) return [];
      if (section === "capabilities") {
        let capabilities = connection.capabilities;
        if (method === "POST") capabilities = [...capabilities, videoFunctionCapability({ id: `synthetic-capability-${Date.now()}`, name: stringField(body, "name"), adapterId: stringField(body, "adapterId") })];
        else if (method === "DELETE") capabilities = capabilities.filter((capability) => capability.id !== capabilityId);
        else capabilities = capabilities.map((capability): MediaCapability => capability.id === capabilityId
          ? { ...capability, name: stringField(body, "name", capability.name), enabled: boolField(body, "enabled", capability.enabled), version: capability.version + 1 } : capability);
        return [{ ...connection, capabilities, version: connection.version + 1 }];
      }
      return [{ ...connection, name: stringField(body, "name", connection.name), enabled: boolField(body, "enabled", connection.enabled), version: connection.version + 1 }];
    }) };
    return Response.json(media);
  }
  if (path.startsWith("/api/v1/settings/media-defaults/")) {
    media.defaults = media.defaults.map((entry) => entry.kind === path.split("/").at(-1)
      ? { ...entry, capabilityId: stringField(body, "capabilityId"), version: entry.version + 1 } : entry);
    return Response.json(media);
  }
  if (path === "/api/v1/settings/media-functions" && method === "GET") return Response.json(functions);
  if (path.startsWith("/api/v1/settings/media-functions/") && method === "PUT") {
    const setting = functions.find((entry) => entry.operation === path.split("/").at(-1));
    if (!setting) return error("此合成功能不存在。", 404, "MEDIA_FUNCTION_NOT_FOUND");
    if (body.expectedVersion !== setting.version) return error("功能配置已变化，请载入最新配置后重试。", 409, "MEDIA_FUNCTION_CONFLICT");
    if (body.capabilityId !== null && (typeof body.capabilityId !== "string"
        || !mediaFunctionChoices(media, setting.operation).some(({ capability }) => capability.id === body.capabilityId))) return error("所选能力不兼容此功能，或连接与能力已停用。", 400, "MEDIA_FUNCTION_INCOMPATIBLE");
    functions = functions.map((entry) => entry.operation === setting.operation
      ? { ...entry, capabilityId: body.capabilityId as string | null, version: entry.version + 1 } : entry);
    return Response.json(functions);
  }
  if (path === "/api/v1/settings/storage" || path.startsWith("/api/v1/settings/storage/")) return storageResponse(url, method, body);
  if (path === "/api/v1/settings/call-log-retention/cleanup") return Response.json({ cleanedExecutions: 8, batchLimitReached: false });
  if (path === "/api/v1/settings/call-log-retention") {
    if (method === "PUT") retention = { version: retention.version + 1, retentionDays: typeof body.retentionDays === "number" ? body.retentionDays : null };
    return Response.json(retention);
  }
  if (path === "/api/v1/settings/debug") {
    if (method === "PUT") debug = { debugMode: boolField(body, "debugMode", false), version: debug.version + 1 };
    return Response.json(debug);
  }
  if (path === "/api/v1/settings/system-logs") {
    const entries = systemLogs.entries.filter((entry) => (!url.searchParams.get("stream") || entry.stream === url.searchParams.get("stream"))
      && entry.message.toLowerCase().includes((url.searchParams.get("search") ?? "").toLowerCase()));
    return Response.json({ ...systemLogs, matchedCount: entries.length, entries });
  }
  if (path === "/api/v1/call-logs") {
    const from = url.searchParams.get("from");
    const to = url.searchParams.get("to");
    const page = Number(url.searchParams.get("page") ?? 0);
    const size = Number(url.searchParams.get("size") ?? 20);
    if (!Number.isSafeInteger(page) || page < 0 || !Number.isSafeInteger(size) || size < 1) return error("分页参数无效。", 400);
    const filtered = calls.filter((call) => ["kind", "status", "operation", "projectId", "traceId"].every((field) => !url.searchParams.get(field)
      || call[field as "kind" | "status" | "operation" | "projectId" | "traceId"] === url.searchParams.get(field))
      && (!from || call.startedAt >= from) && (!to || call.startedAt <= to));
    return Response.json({ items: filtered.slice(page * size, (page + 1) * size), page, size, totalElements: filtered.length, totalPages: Math.ceil(filtered.length / size) });
  }
  if (path.startsWith("/api/v1/call-logs/") && path.endsWith("/debug")) {
    const call = calls.find((entry) => entry.id === path.split("/")[4]);
    if (!call) return error("此合成调用不存在。", 404, "CALL_LOG_NOT_FOUND");
    const details: CallDebug = { id: call.id, captured: false, exchanges: [], llmStream: null };
    return Response.json(details);
  }
  if (path === "/api/v1/agent-presets") return Response.json({ items: prompts.filter((prompt) => prompt.kind === "AGENT").map(({ key, name }) => ({ key, name })) });
  if (path.startsWith("/api/v1/settings/prompts")) {
    if (method === "POST") {
      const prompt: PromptDefinition = { id: `synthetic-prompt-${prompts.length}`, key: stringField(body, "key"), name: stringField(body, "name"),
        description: stringField(body, "description"), content: stringField(body, "content"), kind: body.kind === "FUNCTION" ? "FUNCTION" : "AGENT",
        builtIn: false, version: 1, createdAt: now, updatedAt: now };
      prompts = [...prompts, prompt]; return Response.json(prompt);
    }
    if (method === "PUT") {
      prompts = prompts.map((prompt) => prompt.id === path.split("/").at(-1) ? { ...prompt, name: stringField(body, "name"),
        description: stringField(body, "description"), content: stringField(body, "content"), version: prompt.version + 1 } : prompt);
      return Response.json(prompts.find((prompt) => prompt.id === path.split("/").at(-1)));
    }
    if (method === "DELETE") { prompts = prompts.filter((prompt) => prompt.id !== path.split("/").at(-1)); return new Response(null, { status: 204 }); }
    return Response.json({ items: prompts });
  }
  if (path.startsWith("/api/v1/settings/media-styles")) {
    if (method === "POST") {
      const style: MediaStyle = { id: `synthetic-style-${styles.length}`, name: stringField(body, "name"), category: stringField(body, "category"),
        promptSuffix: stringField(body, "promptSuffix"), enabled: boolField(body, "enabled", true), builtIn: false, version: 1, thumbnailUrl: null };
      styles = [...styles, style]; return Response.json(style);
    }
    if (method === "PUT") {
      styles = styles.map((style) => style.id === path.split("/").at(-1) ? { ...style, name: stringField(body, "name", style.name),
        category: stringField(body, "category", style.category), promptSuffix: stringField(body, "promptSuffix", style.promptSuffix), enabled: boolField(body, "enabled", style.enabled), version: style.version + 1 } : style);
      return Response.json(styles.find((style) => style.id === path.split("/").at(-1)));
    }
    return Response.json(styles);
  }
  if (path === "/api/v1/media-templates") return Response.json({ items: templates.filter((template) => !url.searchParams.get("targetKind") || template.targetKind === url.searchParams.get("targetKind")) });
  if (path.startsWith("/api/v1/settings/media-templates")) {
    if (method === "DELETE") { templates = templates.filter((template) => template.id !== path.split("/").at(-1)); return new Response(null, { status: 204 }); }
    const template: MediaTemplate = { id: `synthetic-template-${templates.length}`, name: stringField(body, "name"), prompt: stringField(body, "prompt"),
      targetKind: body.targetKind === "VIDEO" ? "VIDEO" : "IMAGE", scope: "SYSTEM", images: [], version: 1, createdAt: now, updatedAt: now };
    templates = [...templates, template]; return Response.json(template);
  }
  if (path === "/api/v1/projects" || path === "/api/v1/skills") return Response.json({ items: [], total: 0, nextCursor: null });
  if (path === "/api/v1/media-styles") return Response.json(styles);
  return error(`This preview has no synthetic response for ${method} ${path}`);
};

const root = document.getElementById("root");
if (!root) throw new Error("Settings design fixture root is missing");
createRoot(root).render(<QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={[initialRoute]}><Routes>
  <Route path="/settings/providers" element={<LlmSettingsPage />} />
  <Route path="/settings/llm" element={<LlmSettingsPage />} />
  <Route path="/settings/media" element={<MediaSettingsPage />} />
  <Route path="/settings/functions" element={<MediaFunctionSettingsPage />} />
  <Route path="/settings/storage" element={<StorageSettingsPage />} />
  <Route path="/settings/calls" element={<CallLogsPage />} />
  <Route path="/settings/logs" element={<SystemLogsPage />} />
  <Route path="/settings/general" element={<SystemSettingsPage />} />
  <Route path="/library" element={<LibraryPage />} />
  <Route path="/skills" element={<SkillsPage />} />
  <Route path="*" element={<Navigate to="/settings/providers" replace />} />
</Routes></MemoryRouter></QueryClientProvider>);
