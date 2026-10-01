import type { components, paths } from "./schema";

export type SetupStatus = paths["/api/v1/auth/setup-status"]["get"]["responses"][200]["content"]["application/json"];
export type CurrentUser = components["schemas"]["CurrentUser"];
export type SetupRequest = components["schemas"]["SetupRequest"];
export type LoginRequest = components["schemas"]["LoginRequest"];
export type ChangePasswordRequest = components["schemas"]["ChangePasswordRequest"];
export type Project = components["schemas"]["Project"];
export type ProjectList = components["schemas"]["ProjectList"];
export type ProjectSnapshot = components["schemas"]["ProjectSnapshot"];
export type ProjectEvent = components["schemas"]["ProjectEvent"];
export type CreateProjectRequest = components["schemas"]["CreateProjectRequest"];
export type UpdateProjectRequest = components["schemas"]["UpdateProjectRequest"];
export type Artifact = components["schemas"]["Artifact"];
export type ArtifactList = components["schemas"]["ArtifactList"];
export type MediaDraft = components["schemas"]["MediaDraft"];
export type SaveMediaDraftRequest = components["schemas"]["SaveMediaDraftRequest"];
export type RestoreMediaDraftVersionInputsRequest = components["schemas"]["RestoreMediaDraftVersionInputsRequest"];
export type RemoveMediaDraftMediaInputRequest = components["schemas"]["RemoveMediaDraftMediaInputRequest"];
export type RunMediaDraftRequest = components["schemas"]["RunMediaDraftRequest"];
export type RunImageOperationRequest = components["schemas"]["RunImageOperationRequest"];
export type RunTextGenerationRequest = components["schemas"]["RunTextGenerationRequest"];
export type DirectMediaQueueStatus = components["schemas"]["DirectMediaQueueStatus"];
export type Asset = components["schemas"]["Asset"];
export type ArtifactVersionList = components["schemas"]["ArtifactVersionList"];
export type CreateArtifactRequest = components["schemas"]["CreateArtifactRequest"];
export type ReviseArtifactRequest = components["schemas"]["ReviseArtifactRequest"];
export type UploadCanvasItemVersionRequest = components["schemas"]["UploadCanvasItemVersionRequest"];
export type Canvas = components["schemas"]["Canvas"];
export type CanvasItem = components["schemas"]["CanvasItem"];
export type SelectMediaVersionRequest = components["schemas"]["SelectMediaVersionRequest"];
export type CanvasCommand = components["schemas"]["CanvasCommand"];
export type CanvasConnection = components["schemas"]["CanvasConnection"];
export type CanvasConnectionList = components["schemas"]["CanvasConnectionList"];
export type CreateCanvasConnectionRequest = components["schemas"]["CreateCanvasConnectionRequest"];
export type DisconnectCanvasConnectionRequest = components["schemas"]["DisconnectCanvasConnectionRequest"];
export type CanvasConnectionResult = components["schemas"]["CanvasConnectionResult"];
export type DuplicateCanvasItemRequest = components["schemas"]["DuplicateCanvasItemRequest"];
export type DuplicateCanvasItemResponse = components["schemas"]["DuplicateCanvasItemResponse"];
export type Agent = components["schemas"]["Agent"];
export type AgentList = components["schemas"]["AgentList"];
export type CreateAgentRequest = components["schemas"]["CreateAgentRequest"];
export type UpdateAgentRequest = components["schemas"]["UpdateAgentRequest"];
export type AgentRun = components["schemas"]["AgentRun"];
export type AgentRunList = components["schemas"]["AgentRunList"];
export type AgentConversation = components["schemas"]["AgentConversation"];
export type AgentConversationList = components["schemas"]["AgentConversationList"];
export type RunAction = components["schemas"]["RunAction"];
export type RunPreflight = components["schemas"]["RunPreflight"];
export type CreateRunRequest = components["schemas"]["CreateRunRequest"];
export type Task = components["schemas"]["Task"];
export type ManualUnknownAttemptRequest = components["schemas"]["ManualUnknownAttemptRequest"];
export type UsageEntry = components["schemas"]["UsageEntry"];
export type LlmSettings = components["schemas"]["LlmSettings"];
export type SystemDiagnostics = components["schemas"]["SystemDiagnostics"];
export type DebugSettings = components["schemas"]["DebugSettings"];
export type CallDebug = components["schemas"]["CallDebug"];
export type DebugBody = components["schemas"]["DebugBody"];
export type UpdateDebugSettingsRequest = NonNullable<paths["/api/v1/settings/debug"]["put"]["requestBody"]>["content"]["application/json"];
export type CallLog = components["schemas"]["CallLog"];
export type CallLogPage = components["schemas"]["CallLogPage"];
export type CallLogFilters = NonNullable<paths["/api/v1/call-logs"]["get"]["parameters"]["query"]>;
export type ReplaceLlmSettingsRequest = components["schemas"]["ReplaceLlmSettingsRequest"];
export type DiagnoseLlmRequest = components["schemas"]["DiagnoseLlmRequest"];
export type RunningHubDefinition = components["schemas"]["RunningHubDefinition"];
export type RunningHubField = components["schemas"]["RunningHubField"];
export type RunningHubImportRequest = components["schemas"]["RunningHubImportRequest"];
export type RunningHubImportPreview = components["schemas"]["RunningHubImportPreview"];
export type MediaSettings = components["schemas"]["MediaSettings"];
export type MediaConnection = components["schemas"]["MediaConnection"];
export type MediaCapability = components["schemas"]["MediaCapability"];
export type ImageGenerationParameters = components["schemas"]["ImageGenerationParameters"];
export type CreateMediaConnectionRequest = components["schemas"]["CreateMediaConnectionRequest"];
export type UpdateMediaConnectionRequest = components["schemas"]["UpdateMediaConnectionRequest"];
export type CreateMediaCapabilityRequest = components["schemas"]["CreateMediaCapabilityRequest"];
export type UpdateMediaCapabilityRequest = components["schemas"]["UpdateMediaCapabilityRequest"];
export type SetMediaDefaultRequest = components["schemas"]["SetMediaDefaultRequest"];
type CsrfToken = components["schemas"]["CsrfToken"];
type Problem = components["schemas"]["Problem"];

let csrfToken: CsrfToken | undefined;

/** Reads a server-filtered audit page; this never contacts a model or media Provider. */
export async function listCallLogs(filters: CallLogFilters): Promise<CallLogPage> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== "") params.set(key, String(value));
  }
  return readJson<CallLogPage>(`/api/v1/call-logs?${params}`, "无法读取调用日志");
}

export async function getDebugSettings(): Promise<DebugSettings> {
  return readJson<DebugSettings>("/api/v1/settings/debug", "无法读取 debug 模式设置");
}

export async function updateDebugSettings(request: UpdateDebugSettingsRequest): Promise<DebugSettings> {
  return writeJson<DebugSettings>("/api/v1/settings/debug", {
    method: "PUT", body: JSON.stringify(request),
  });
}

export async function getCallDebug(id: string): Promise<CallDebug> {
  return readJson<CallDebug>(`/api/v1/call-logs/${encodeURIComponent(id)}/debug`, "无法读取调用正文");
}

/**
 * Same-origin private URLs retain session authorization without storing any media key.
 * Archived 480px previews: video cards use the cover frame, image cards stay on the original.
 */
export function assetThumbnailUrl(projectId: string, assetId: string): string {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/assets/${encodeURIComponent(assetId)}/thumbnail`;
}

/**
 * Private original bytes; image cards and reference previews load these directly so a resized
 * node stays sharp. Image previews are still archived for later list-style use.
 */
export function assetContentUrl(projectId: string, assetId: string): string {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/assets/${encodeURIComponent(assetId)}/content`;
}

/** Retrieves persisted, owner-scoped metadata before constructing a video trim interval. */
export async function getAssetMetadata(projectId: string, assetId: string): Promise<Asset> {
  return readJson<Asset>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/assets/${encodeURIComponent(assetId)}`,
    "无法读取素材时长",
  );
}

/** Uploads real image bytes with the session CSRF token; the browser supplies the multipart boundary. */
export async function uploadImageAsset(projectId: string, file: File): Promise<Asset> {
  if (file.size > 20 * 1024 * 1024) {
    throw new ApiError(413, "ASSET_TOO_LARGE", "图片不能超过 20 MiB。", false);
  }
  const form = new FormData();
  form.append("file", file);
  const token = await getCsrfToken();
  const response = await fetch(`/api/v1/projects/${encodeURIComponent(projectId)}/assets`, {
    method: "POST",
    credentials: "same-origin",
    headers: {
      Accept: "application/json, application/problem+json",
      [token.headerName]: token.token,
    },
    body: form,
  });
  if (!response.ok) throw await apiError(response, "图片上传未完成");
  return (await response.json()) as Asset;
}

/** Session-protected metadata download; the manifest contains no signed media URLs. */
export async function uploadAudioAsset(projectId: string, file: File): Promise<Asset> {
  if (file.size > 50 * 1024 * 1024) {
    throw new ApiError(413, "ASSET_TOO_LARGE", "音频不能超过 50 MiB。", false);
  }
  const form = new FormData();
  form.append("file", file);
  const token = await getCsrfToken();
  const response = await fetch(`/api/v1/projects/${encodeURIComponent(projectId)}/assets/audio`, {
    method: "POST",
    credentials: "same-origin",
    headers: {
      Accept: "application/json, application/problem+json",
      [token.headerName]: token.token,
    },
    body: form,
  });
  if (!response.ok) throw await apiError(response, "音频上传未完成");
  return (await response.json()) as Asset;
}

const MAX_VIDEO_UPLOAD_BYTES = 500 * 1024 * 1024;
/** MP4 uploads use actual backend decoding and the selected per-asset storage destination. */
export async function uploadVideoAsset(projectId: string, file: File): Promise<Asset> {
  if (file.size > MAX_VIDEO_UPLOAD_BYTES) {
    throw new ApiError(413, "ASSET_TOO_LARGE", "视频不能超过 500 MiB。", false);
  }
  const form = new FormData();
  form.append("file", file);
  const token = await getCsrfToken();
  const response = await fetch(`/api/v1/projects/${encodeURIComponent(projectId)}/assets/video`, {
    method: "POST",
    credentials: "same-origin",
    headers: {
      Accept: "application/json, application/problem+json",
      [token.headerName]: token.token,
    },
    body: form,
  });
  if (!response.ok) throw await apiError(response, "视频上传未完成");
  return (await response.json()) as Asset;
}

/** Session-protected metadata download; the manifest contains no signed media URLs. */
export function projectExportManifestUrl(projectId: string): string {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/export-manifest`;
}

/** Reads durable quantities without treating unknown external cost as zero. */
export async function listProjectUsage(projectId: string): Promise<UsageEntry[]> {
  return readJson<UsageEntry[]>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/usage`,
    "无法读取项目用量记录",
  );
}

/** Stable API error carrying the ProblemDetail code used by UI decisions. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryable: boolean,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

/** Reads the public one-time setup state. */
export async function getSetupStatus(): Promise<SetupStatus> {
  return readJson<SetupStatus>("/api/v1/auth/setup-status", "无法读取系统初始化状态");
}

/** Creates the sole administrator. The bootstrap secret is sent once and never persisted. */
export async function setupAdministrator(
  input: SetupRequest & { bootstrapSecret: string },
): Promise<CurrentUser> {
  const { bootstrapSecret, ...body } = input;
  return writeJson<CurrentUser>("/api/v1/auth/setup", {
    method: "POST",
    headers: { "X-Agenvas-Bootstrap-Secret": bootstrapSecret },
    body: JSON.stringify(body),
  });
}

/** Opens a server-side login session and discards the pre-login CSRF token. */
export async function login(input: LoginRequest): Promise<CurrentUser> {
  const user = await writeJson<CurrentUser>("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify(input),
  });
  csrfToken = undefined;
  return user;
}

/** Loads the authenticated administrator represented by the session cookie. */
export async function getCurrentUser(): Promise<CurrentUser> {
  return readJson<CurrentUser>("/api/v1/auth/me", "登录状态已失效");
}

/** Reads only masked administrator LLM configuration metadata. */
export async function getLlmSettings(): Promise<LlmSettings> {
  return readJson<LlmSettings>("/api/v1/settings/llm", "无法读取模型配置");
}

/** Media settings expose only public connection and capability metadata. */
export async function getMediaSettings(): Promise<MediaSettings> {
  return readJson<MediaSettings>("/api/v1/settings/media-connections", "无法读取媒体配置");
}

export async function createMediaConnection(input: CreateMediaConnectionRequest,
  idempotencyKey: string): Promise<MediaSettings> {
  return writeJson<MediaSettings>("/api/v1/settings/media-connections", {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey }, body: JSON.stringify(input),
  });
}

export async function updateMediaConnection(connectionId: string,
  input: UpdateMediaConnectionRequest): Promise<MediaSettings> {
  return writeJson<MediaSettings>(`/api/v1/settings/media-connections/${encodeURIComponent(connectionId)}`, {
    method: "PUT", body: JSON.stringify(input),
  });
}

export async function previewRunningHubImport(connectionId: string, input: RunningHubImportRequest): Promise<RunningHubImportPreview> {
  return writeJson<RunningHubImportPreview>(`/api/v1/settings/media-connections/${encodeURIComponent(connectionId)}/runninghub/preview`,
    { method: "POST", body: JSON.stringify(input) });
}

export async function createMediaCapability(connectionId: string,
  input: CreateMediaCapabilityRequest, idempotencyKey: string): Promise<MediaSettings> {
  return writeJson<MediaSettings>(`/api/v1/settings/media-connections/${encodeURIComponent(connectionId)}/capabilities`, {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey }, body: JSON.stringify(input),
  });
}

export async function updateMediaCapability(connectionId: string, capabilityId: string,
  input: UpdateMediaCapabilityRequest): Promise<MediaSettings> {
  return writeJson<MediaSettings>(`/api/v1/settings/media-connections/${encodeURIComponent(connectionId)}/capabilities/${encodeURIComponent(capabilityId)}`, {
    method: "PUT", body: JSON.stringify(input),
  });
}

export async function setMediaDefault(kind: "IMAGE_GENERATION" | "VIDEO_GENERATION" | "AUDIO_GENERATION",
  input: SetMediaDefaultRequest): Promise<MediaSettings> {
  return writeJson<MediaSettings>(`/api/v1/settings/media-defaults/${kind}`, {
    method: "PUT", body: JSON.stringify(input),
  });
}

/** Reads only local, non-billable installation checks and aggregate Task statuses. */
export async function getSystemDiagnostics(): Promise<SystemDiagnostics> {
  return readJson<SystemDiagnostics>("/api/v1/settings/diagnostics", "无法读取系统诊断");
}

/** Sends a replacement key once over the authenticated, CSRF-protected session. */
export async function replaceLlmSettings(input: ReplaceLlmSettingsRequest): Promise<LlmSettings> {
  return writeJson<LlmSettings>("/api/v1/settings/llm", {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

/** Explicit billable probe; only the selected version and cost acknowledgement are sent. */
export async function diagnoseLlmSettings(input: DiagnoseLlmRequest): Promise<LlmSettings> {
  return writeJson<LlmSettings>("/api/v1/settings/llm/diagnose", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** Invalidates the current session. */
export async function logout(): Promise<void> {
  await writeEmpty("/api/v1/auth/logout", { method: "POST" });
  csrfToken = undefined;
}

/** Changes the password while keeping only the current session alive. */
export async function changePassword(input: ChangePasswordRequest): Promise<void> {
  await writeEmpty("/api/v1/auth/change-password", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** Lists an owner-scoped project page. */
export async function listProjects(options: {
  includeArchived?: boolean;
  cursor?: string;
  limit?: number;
} = {}): Promise<ProjectList> {
  const query = new URLSearchParams();
  if (options.includeArchived) query.set("includeArchived", "true");
  if (options.cursor) query.set("cursor", options.cursor);
  if (options.limit) query.set("limit", String(options.limit));
  const suffix = query.size > 0 ? `?${query.toString()}` : "";
  return readJson<ProjectList>(`/api/v1/projects${suffix}`, "无法读取项目列表");
}

/** Creates a project under the current session owner. */
export async function createProject(input: CreateProjectRequest): Promise<Project> {
  return writeJson<Project>("/api/v1/projects", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** Reads one owner-scoped project. */
export async function getProject(projectId: string): Promise<Project> {
  return readJson<Project>(`/api/v1/projects/${projectId}`, "无法读取项目");
}

/** Loads workspace entities and the exclusive event replay cursor from one database snapshot. */
export async function getProjectSnapshot(projectId: string): Promise<ProjectSnapshot> {
  return readJson<ProjectSnapshot>(
    `/api/v1/projects/${projectId}/snapshot`,
    "无法读取项目快照",
  );
}

/** Renames or reconfigures a project with optimistic concurrency. */
export async function updateProject(
  projectId: string,
  input: UpdateProjectRequest,
): Promise<Project> {
  return writeJson<Project>(`/api/v1/projects/${projectId}`, {
    method: "PATCH",
    body: JSON.stringify(input),
  });
}

/** Archives a project and returns its new version. */
export async function archiveProject(project: Project): Promise<Project> {
  return writeJson<Project>(`/api/v1/projects/${project.id}/archive`, {
    method: "POST",
    body: JSON.stringify({ expectedVersion: project.version }),
  });
}

/** Creates a stable Artifact and its first validated immutable revision. */
export async function createArtifact(
  projectId: string,
  input: CreateArtifactRequest,
  idempotencyKey: string,
): Promise<Artifact> {
  return writeJson<Artifact>(`/api/v1/projects/${projectId}/artifacts`, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify(input),
  });
}

/** Loads an Artifact with its resource-library default immutable revision. */
export async function getArtifact(projectId: string, artifactId: string): Promise<Artifact> {
  return readJson<Artifact>(
    `/api/v1/projects/${projectId}/artifacts/${artifactId}`,
    "无法读取产物",
  );
}

/** Lists project resources independently of which CanvasItems are currently visible. */
export async function listArtifacts(projectId: string): Promise<ArtifactList> {
  return readJson<ArtifactList>(`/api/v1/projects/${projectId}/artifacts`, "无法读取项目资源");
}

/** Loads one media card's editable generation input independently of its selected result. */
export async function getMediaDraft(projectId: string, canvasItemId: string): Promise<MediaDraft> {
  return readJson<MediaDraft>(
    `/api/v1/projects/${projectId}/canvas-items/${canvasItemId}/media-draft`,
    "无法读取媒体草稿",
  );
}

/** Saves the complete working draft with its own optimistic version. */
export async function saveMediaDraft(projectId: string, canvasItemId: string,
  input: SaveMediaDraftRequest): Promise<MediaDraft> {
  return writeJson<MediaDraft>(
    `/api/v1/projects/${projectId}/canvas-items/${canvasItemId}/media-draft`,
    { method: "PUT", body: JSON.stringify(input) },
  );
}

/** Deliberately replaces a card draft from immutable provenance; historical lines are not restored. */
export async function restoreMediaDraftVersionInputs(projectId: string, canvasItemId: string,
  input: RestoreMediaDraftVersionInputsRequest): Promise<MediaDraft> {
  return writeJson<MediaDraft>(
    `/api/v1/projects/${projectId}/canvas-items/${canvasItemId}/media-draft/restore-version-inputs`,
    { method: "POST", body: JSON.stringify(input) },
  );
}

/** Atomically removes an exact image input together with every owning canvas line and mention. */
export async function removeMediaDraftMediaInput(projectId: string, canvasItemId: string,
  versionId: string, input: RemoveMediaDraftMediaInputRequest): Promise<MediaDraft> {
  return writeJson<MediaDraft>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/canvas-items/${encodeURIComponent(canvasItemId)}`
      + `/media-draft/media-inputs/${encodeURIComponent(versionId)}/remove`,
    { method: "POST", body: JSON.stringify(input) },
  );
}

/** Lists exact-version CanvasItem relationships; the server remains the topology source of truth. */
export async function listCanvasConnections(projectId: string): Promise<CanvasConnectionList> {
  return readJson<CanvasConnectionList>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/canvas/connections`,
    "无法读取画布连线",
  );
}

/** Atomically creates a line and the corresponding target input source. */
export async function createCanvasConnection(projectId: string,
  input: CreateCanvasConnectionRequest): Promise<CanvasConnectionResult> {
  return writeJson<CanvasConnectionResult>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/canvas/connections`,
    { method: "POST", body: JSON.stringify(input) },
  );
}

/** Atomically removes one media or Agent image line and returns the updated target state. */
export async function disconnectCanvasConnection(projectId: string, connectionId: string,
  input: DisconnectCanvasConnectionRequest): Promise<CanvasConnectionResult> {
  return writeJson<CanvasConnectionResult>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/canvas/connections/${encodeURIComponent(connectionId)}/disconnect`,
    { method: "POST", body: JSON.stringify(input) },
  );
}

/** Copies one persisted media branch while intentionally dropping tasks and topology ownership. */
export async function duplicateCanvasItem(projectId: string, sourceItemId: string,
  input: DuplicateCanvasItemRequest): Promise<DuplicateCanvasItemResponse> {
  return writeJson<DuplicateCanvasItemResponse>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/canvas/items/${encodeURIComponent(sourceItemId)}/duplicate`,
    { method: "POST", body: JSON.stringify(input) },
  );
}

/** Result history belonging to one media node. */
export async function listCanvasMediaVersions(projectId: string, itemId: string): Promise<ArtifactVersionList> {
  return readJson<ArtifactVersionList>(
    `/api/v1/projects/${projectId}/canvas/items/${itemId}/media-versions`, "无法读取媒体版本");
}

export async function selectCanvasMediaVersion(projectId: string, itemId: string,
  input: SelectMediaVersionRequest): Promise<CanvasItem> {
  return writeJson<CanvasItem>(
    `/api/v1/projects/${projectId}/canvas/items/${itemId}/select-media-version`,
    { method: "POST", body: JSON.stringify(input) });
}

/** The saved draft is fixed into a direct media Task on one explicit click. */
export async function runMediaDraft(projectId: string, artifactId: string,
  input: RunMediaDraftRequest, idempotencyKey: string): Promise<Task> {
  return writeJson<Task>(
    `/api/v1/projects/${projectId}/artifacts/${artifactId}/run`,
    { method: "POST", headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(input) },
  );
}

/** Pins the visible image, creates a connected result branch, and dispatches post-processing. */
export async function runImageOperation(projectId: string, artifactId: string,
  input: RunImageOperationRequest, idempotencyKey: string): Promise<Task> {
  return writeJson<Task>(
    `/api/v1/projects/${projectId}/artifacts/${artifactId}/image-operations`,
    { method: "POST", headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(input) },
  );
}

export async function listDirectMediaTasks(projectId: string, artifactId: string,
  canvasItemId: string): Promise<Task[]> {
  const query = new URLSearchParams({ canvasItemId });
  return readJson<Task[]>(`/api/v1/projects/${projectId}/artifacts/${artifactId}/run?${query}`,
    "无法读取卡片任务");
}

/** Pins the visible text version and sends one prompt to the configured text model. */
export async function runDirectTextGeneration(projectId: string, artifactId: string,
  input: RunTextGenerationRequest, idempotencyKey: string): Promise<Task> {
  return writeJson<Task>(
    `/api/v1/projects/${projectId}/artifacts/${artifactId}/text-generations`,
    { method: "POST", headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(input) },
  );
}

export async function listDirectTextTasks(projectId: string, artifactId: string): Promise<Task[]> {
  return readJson<Task[]>(
    `/api/v1/projects/${projectId}/artifacts/${artifactId}/text-generations`,
    "无法读取文字生成任务",
  );
}

export async function cancelQueuedDirectMediaTask(projectId: string, taskId: string): Promise<Task> {
  return writeJson<Task>(`/api/v1/projects/${projectId}/tasks/${taskId}/cancel-queued`,
    { method: "POST" });
}

export async function getDirectMediaQueueStatus(projectId: string,
  taskId: string): Promise<DirectMediaQueueStatus> {
  return readJson<DirectMediaQueueStatus>(
    `/api/v1/projects/${projectId}/tasks/${taskId}/queue`, "无法读取排队状态");
}

/** Appends and selects a complete content revision with optimistic concurrency. */
export async function reviseArtifact(
  projectId: string,
  artifactId: string,
  input: ReviseArtifactRequest,
): Promise<Artifact> {
  return writeJson<Artifact>(
    `/api/v1/projects/${projectId}/artifacts/${artifactId}/revisions`,
    { method: "POST", body: JSON.stringify(input) },
  );
}

/** Lists immutable revision history newest first. */
export async function listArtifactVersions(
  projectId: string,
  artifactId: string,
): Promise<ArtifactVersionList> {
  return readJson<ArtifactVersionList>(
    `/api/v1/projects/${projectId}/artifacts/${artifactId}/versions`,
    "无法读取版本历史",
  );
}

/** Changes the resource-library default without changing any card's selected version. */
export async function setArtifactResourceDefaultVersion(
  projectId: string,
  artifactId: string,
  versionId: string,
  expectedVersion: number,
): Promise<Artifact> {
  return writeJson<Artifact>(
    `/api/v1/projects/${projectId}/artifacts/${artifactId}/set-default-version`,
    {
      method: "POST",
      body: JSON.stringify({ versionId, expectedVersion }),
    },
  );
}

/** Fills an empty media node, or derives a new node when the source already has a result. */
export async function uploadCanvasItemVersion(
  projectId: string,
  canvasItemId: string,
  input: UploadCanvasItemVersionRequest,
): Promise<CanvasItem> {
  return writeJson<CanvasItem>(
    `/api/v1/projects/${projectId}/canvas-items/${canvasItemId}/upload-version`,
    { method: "POST", body: JSON.stringify(input) },
  );
}

/** Loads the authoritative canvas projection for refresh recovery. */
export async function listCanvasItems(projectId: string): Promise<Canvas> {
  return readJson<Canvas>(`/api/v1/projects/${projectId}/canvas/items`, "无法读取画布");
}

/** Atomically applies one or more presentation-only canvas commands. */
export async function applyCanvasCommands(
  projectId: string,
  commands: CanvasCommand[],
): Promise<Canvas> {
  return writeJson<Canvas>(`/api/v1/projects/${projectId}/canvas/commands`, {
    method: "POST",
    body: JSON.stringify({ commands }),
  });
}

/** Lists persistent Agent card configurations and only their explicitly bound inputs. */
export async function listAgents(projectId: string): Promise<AgentList> {
  return readJson<AgentList>(`/api/v1/projects/${projectId}/agents`, "无法读取 Agent 列表");
}

/** Creates an idle Creator Agent; creation does not start a model run. */
export async function createAgent(
  projectId: string,
  input: CreateAgentRequest,
): Promise<Agent> {
  return writeJson<Agent>(`/api/v1/projects/${projectId}/agents`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** Replaces Agent display configuration and exact-version bindings using CAS. */
export async function updateAgent(
  projectId: string,
  agentId: string,
  input: UpdateAgentRequest,
): Promise<Agent> {
  return writeJson<Agent>(`/api/v1/projects/${projectId}/agents/${agentId}`, {
    method: "PATCH",
    body: JSON.stringify(input),
  });
}

/** Reads current trusted model status, pinned inputs and server policy before user consent. */
export async function getRunPreflight(projectId: string, agentId: string,
  conversationId?: string): Promise<RunPreflight> {
  const params = new URLSearchParams({ agentId });
  if (conversationId) params.set("conversationId", conversationId);
  return readJson<RunPreflight>(
    `/api/v1/projects/${projectId}/runs/preflight?${params}`,
    "无法核对运行前配置",
  );
}

/** Reads durable conversations and the Agent's selected conversation without loading model traces. */
export async function listAgentConversations(projectId: string, agentId: string,
  cursor?: string, limit = 20): Promise<AgentConversationList> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (cursor) params.set("cursor", cursor);
  return readJson<AgentConversationList>(
    `/api/v1/projects/${projectId}/agents/${agentId}/conversations?${params}`, "无法读取会话记录");
}

/** Creates and selects an empty conversation; reuse the same key after an uncertain response. */
export async function createAgentConversation(projectId: string, agentId: string,
  idempotencyKey: string): Promise<AgentConversation> {
  return writeJson<AgentConversation>(`/api/v1/projects/${projectId}/agents/${agentId}/conversations`, {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey },
  });
}

/** Changes only the selected conversation, leaving any active Run in its original conversation. */
export async function selectAgentConversation(projectId: string, agentId: string,
  conversationId: string): Promise<AgentConversation> {
  return writeJson<AgentConversation>(
    `/api/v1/projects/${projectId}/agents/${agentId}/conversations/${conversationId}/select`, { method: "POST" });
}

/** Lists one conversation's messages newest first using the durable Run turn order. */
export async function listConversationRuns(projectId: string, agentId: string,
  conversationId: string, cursor?: string, limit = 20): Promise<AgentRunList> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (cursor) params.set("cursor", cursor);
  return readJson<AgentRunList>(
    `/api/v1/projects/${projectId}/agents/${agentId}/conversations/${conversationId}/runs?${params}`,
    "无法读取会话消息");
}

/** Creates or exactly replays one durable Run command under a project activity slot. */
export async function createRun(
  projectId: string,
  idempotencyKey: string,
  input: CreateRunRequest,
): Promise<AgentRun> {
  return writeJson<AgentRun>(`/api/v1/projects/${projectId}/runs`, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify(input),
  });
}

/** Loads one owner-scoped Run and its immutable creation snapshots. */
export async function getRun(projectId: string, runId: string): Promise<AgentRun> {
  return readJson<AgentRun>(`/api/v1/projects/${projectId}/runs/${runId}`, "无法读取运行状态");
}

/** Lists only committed, server-authored action summaries for one owned Run. */
export async function listRunActions(projectId: string, runId: string): Promise<RunAction[]> {
  return readJson<RunAction[]>(
    `/api/v1/projects/${projectId}/runs/${runId}/actions`,
    "无法读取执行动作",
  );
}

/** Lists one Agent's durable Run summaries without model-private messages. */
export async function listAgentRuns(projectId: string, agentId: string,
  cursor?: string, limit = 20): Promise<AgentRunList> {
  const params = new URLSearchParams({ agentId, limit: String(limit) });
  if (cursor) params.set("cursor", cursor);
  return readJson<AgentRunList>(`/api/v1/projects/${projectId}/runs?${params}`,
    "无法读取运行记录");
}

/** Stops future orchestration; external work may still require later reconciliation. */
export async function cancelRun(projectId: string, runId: string): Promise<AgentRun> {
  return writeJson<AgentRun>(`/api/v1/projects/${projectId}/runs/${runId}/cancel`, {
    method: "POST",
  });
}

/** Reads durable Task state; lease ownership remains an internal worker concern. */
export async function getTask(projectId: string, taskId: string): Promise<Task> {
  return readJson<Task>(`/api/v1/projects/${projectId}/tasks/${taskId}`, "无法读取任务状态");
}

/** Starts a separately reserved attempt for a task whose result is unknown. */
export async function createManualUnknownAttempt(projectId: string, taskId: string,
  key: string, request: ManualUnknownAttemptRequest): Promise<Task> {
  return writeJson<Task>(`/api/v1/projects/${projectId}/tasks/${taskId}/new-attempt`, {
    method: "POST",
    headers: { "Idempotency-Key": key },
    body: JSON.stringify(request),
  });
}

/** Loads all task outcomes for one owned Run. */
export async function listRunTasks(projectId: string, runId: string): Promise<Task[]> {
  return readJson<Task[]>(`/api/v1/projects/${projectId}/runs/${runId}/tasks`,
    "无法读取运行任务");
}

async function readJson<T>(path: string, fallbackMessage: string): Promise<T> {
  const response = await fetch(path, {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  });
  if (!response.ok) {
    throw await apiError(response, fallbackMessage);
  }
  return (await response.json()) as T;
}

async function writeJson<T>(path: string, init: RequestInit): Promise<T> {
  const response = await write(path, init);
  return (await response.json()) as T;
}

async function writeEmpty(path: string, init: RequestInit): Promise<void> {
  await write(path, init);
}

async function write(path: string, init: RequestInit): Promise<Response> {
  const token = await getCsrfToken();
  const response = await fetch(path, {
    ...init,
    credentials: "same-origin",
    headers: {
      Accept: "application/json, application/problem+json",
      "Content-Type": "application/json",
      [token.headerName]: token.token,
      ...init.headers,
    },
  });
  if (!response.ok) {
    throw await apiError(response, "请求未完成");
  }
  return response;
}

async function getCsrfToken(): Promise<CsrfToken> {
  if (csrfToken) {
    return csrfToken;
  }
  csrfToken = await readJson<CsrfToken>("/api/v1/auth/csrf", "无法建立安全会话");
  return csrfToken;
}

async function apiError(response: Response, fallbackMessage: string): Promise<ApiError> {
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/problem+json")) {
    const problem = (await response.json()) as Problem;
    return new ApiError(
      response.status,
      problem.code,
      problem.detail ?? problem.title,
      problem.retryable,
    );
  }
  return new ApiError(response.status, "HTTP_ERROR", fallbackMessage, response.status >= 500);
}

export type SystemLogSnapshot = components["schemas"]["SystemLogSnapshot"];
export type SystemLogStream = components["schemas"]["SystemLogEntry"]["stream"];

/** Reads bounded, sanitized Java console output; no generated work is triggered. */
export async function listSystemLogs(input: { stream?: SystemLogStream; search?: string; limit: number }): Promise<SystemLogSnapshot> {
  const params = new URLSearchParams({ limit: String(input.limit) });
  if (input.stream) params.set("stream", input.stream);
  if (input.search) params.set("search", input.search);
  return readJson<SystemLogSnapshot>(`/api/v1/settings/system-logs?${params}`, "无法读取系统日志");
}

export type StorageSettings = components["schemas"]["StorageSettings"];
export type StorageProvider = components["schemas"]["StorageProvider"];
export type CreateStorageProfileRequest = components["schemas"]["CreateStorageProfileRequest"];
export type RotateStorageCredentialsRequest = components["schemas"]["RotateStorageCredentialsRequest"];
export async function getStorageSettings(): Promise<StorageSettings> {
  return readJson<StorageSettings>("/api/v1/settings/storage", "无法读取存储配置");
}
export async function createStorageProfile(input: CreateStorageProfileRequest): Promise<StorageSettings> {
  return writeJson<StorageSettings>("/api/v1/settings/storage/profiles", {
    method: "POST", body: JSON.stringify(input),
  });
}
export async function activateStorageProfile(input: components["schemas"]["ActivateStorageProfileRequest"]): Promise<StorageSettings> {
  return writeJson<StorageSettings>("/api/v1/settings/storage/active", {
    method: "PUT", body: JSON.stringify(input),
  });
}
export async function rotateStorageCredentials(id: string, input: RotateStorageCredentialsRequest): Promise<StorageSettings> {
  return writeJson<StorageSettings>(`/api/v1/settings/storage/profiles/${encodeURIComponent(id)}/credentials`, {
    method: "PUT", body: JSON.stringify(input),
  });
}
