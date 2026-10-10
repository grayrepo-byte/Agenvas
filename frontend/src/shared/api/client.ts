import { getLocale, t } from "../i18n";
import type { components, paths, operations } from "./schema";

export const HTTP_STATUS = {
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  CONFLICT: 409,
  INTERNAL_SERVER_ERROR: 500,
} as const;

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
export type ResourceResult = components["schemas"]["ResourceResult"];
export type ResourceFilters = NonNullable<paths["/api/v1/resources"]["get"]["parameters"]["query"]>;

/** Account-wide immutable results, paged by the server rather than fetched project by project. */
export function listResources(filters: ResourceFilters): Promise<components["schemas"]["ResourcePage"]> {
  const query = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => { if (value !== undefined) query.set(key, String(value)); });
  return readJson(`/api/v1/resources?${query}`, t("resources.loadFailed"));
}
export type MediaDraft = components["schemas"]["MediaDraft"];
export type SaveMediaDraftRequest = components["schemas"]["SaveMediaDraftRequest"];
export type RemoveMediaDraftMediaInputRequest = components["schemas"]["RemoveMediaDraftMediaInputRequest"];
export type RunMediaDraftRequest = components["schemas"]["RunMediaDraftRequest"];
export type RunImageOperationRequest = components["schemas"]["RunImageOperationRequest"];
export type ImageOperation = RunImageOperationRequest["operation"];
export type VideoOperation = components["schemas"]["VideoOperation"];
export type MediaFunction = components["schemas"]["MediaFunction"];
export type MediaFunctionSetting = components["schemas"]["MediaFunctionSetting"];
export type RunVideoOperationRequest = components["schemas"]["RunVideoOperationRequest"];
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
export type AgentMediaApproval = components["schemas"]["AgentMediaApproval"];
export type AgentMediaApprovalDecision = components["schemas"]["AgentMediaApprovalDecisionRequest"];
export type AssistantTurnStreamProjection = components["schemas"]["AssistantTurnStream"];
export type AgentTurnStreamEventPayload = components["schemas"]["AgentTurnStreamEventPayload"];
export type RunPreflight = components["schemas"]["RunPreflight"];
export type CreateRunRequest = components["schemas"]["CreateRunRequest"];
export type Task = components["schemas"]["Task"];
export type ManualUnknownAttemptRequest = components["schemas"]["ManualUnknownAttemptRequest"];
export type LlmSettings = components["schemas"]["LlmSettings"];
export type SystemDiagnostics = components["schemas"]["SystemDiagnostics"];
export type CallLogCleanupResult = components["schemas"]["CallLogCleanupResult"];
export type CleanupCallLogsRequest = NonNullable<paths["/api/v1/settings/call-log-retention/cleanup"]["post"]["requestBody"]>["content"]["application/json"];
export type CallLogRetentionSettings = components["schemas"]["CallLogRetentionSettings"];
export type UpdateCallLogRetentionRequest = NonNullable<paths["/api/v1/settings/call-log-retention"]["put"]["requestBody"]>["content"]["application/json"];
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
export type ComfyUiGraph = components["schemas"]["ComfyUiGraph"];
export type ComfyUiWorkflowDefinition = components["schemas"]["ComfyUiWorkflowDefinition"];
export type MediaCapability = components["schemas"]["MediaCapability"];
export type ImageGenerationParameters = components["schemas"]["ImageGenerationParameters"];
export type MediaStyleSummary = components["schemas"]["MediaStyleSummary"];
export type MediaStyle = components["schemas"]["MediaStyle"];
export type CreateMediaStyleRequest = components["schemas"]["CreateMediaStyleRequest"];
export type UpdateMediaStyleRequest = components["schemas"]["UpdateMediaStyleRequest"];
export type CreateMediaConnectionRequest = components["schemas"]["CreateMediaConnectionRequest"];
export type UpdateMediaConnectionRequest = components["schemas"]["UpdateMediaConnectionRequest"];
export type CreateMediaCapabilityRequest = components["schemas"]["CreateMediaCapabilityRequest"];
export type UpdateMediaCapabilityRequest = components["schemas"]["UpdateMediaCapabilityRequest"];
export type SetMediaDefaultRequest = components["schemas"]["SetMediaDefaultRequest"];
type CsrfToken = components["schemas"]["CsrfToken"];
type Problem = components["schemas"]["Problem"];

let csrfToken: CsrfToken | undefined;

const MEBIBYTE = 1024 * 1024;
const MAX_IMAGE_UPLOAD_BYTES = 20 * MEBIBYTE;
const MAX_AUDIO_UPLOAD_BYTES = 50 * MEBIBYTE;
const MAX_VIDEO_UPLOAD_BYTES = 500 * MEBIBYTE;
const MAX_MEDIA_STYLE_UPLOAD_BYTES = 5 * MEBIBYTE;

/** Catalog responses contain display metadata only; prompt injection remains server-side. */
export async function listMediaStyles(): Promise<MediaStyleSummary[]> {
  return readJson<MediaStyleSummary[]>("/api/v1/media-styles", t("styles.loadFailed"));
}

export async function getMediaStyleSettings(): Promise<MediaStyle[]> {
  return readJson<MediaStyle[]>("/api/v1/settings/media-styles", t("styles.loadFailed"));
}

export async function createMediaStyle(request: CreateMediaStyleRequest): Promise<MediaStyle> {
  return writeJson<MediaStyle>("/api/v1/settings/media-styles", { method: "POST", body: JSON.stringify(request) });
}

export async function updateMediaStyle(id: string, request: UpdateMediaStyleRequest): Promise<MediaStyle> {
  return writeJson<MediaStyle>(`/api/v1/settings/media-styles/${encodeURIComponent(id)}`, {
    method: "PUT", body: JSON.stringify(request),
  });
}

export async function uploadMediaStyleThumbnail(id: string, file: File, expectedVersion: number): Promise<MediaStyle> {
  if (file.size > MAX_MEDIA_STYLE_UPLOAD_BYTES) throw new ApiError(413, "STYLE_THUMBNAIL_TOO_LARGE", t("styles.previewSizeLimit"), false);
  const form = new FormData();
  form.append("file", file);
  form.append("expectedVersion", String(expectedVersion));
  return writeJson<MediaStyle>(`/api/v1/settings/media-styles/${encodeURIComponent(id)}/thumbnail`, {
    method: "POST", body: form,
  }, t("styles.uploadFailed"));
}

/** Reads a server-filtered audit page; this never contacts a model or media Provider. */
export async function listCallLogs(filters: CallLogFilters): Promise<CallLogPage> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== "") params.set(key, String(value));
  }
  return readJson<CallLogPage>(`/api/v1/call-logs?${params}`, t("api.errors.callLogsUnavailable"));
}

export async function cleanupCallLogs(request: CleanupCallLogsRequest): Promise<CallLogCleanupResult> {
  return writeJson<CallLogCleanupResult>("/api/v1/settings/call-log-retention/cleanup", {
    method: "POST", body: JSON.stringify(request),
  });
}

export async function getCallLogRetentionSettings(): Promise<CallLogRetentionSettings> {
  return readJson<CallLogRetentionSettings>("/api/v1/settings/call-log-retention", t("api.errors.retentionSettingsUnavailable"));
}
export async function updateCallLogRetentionSettings(request: UpdateCallLogRetentionRequest): Promise<CallLogRetentionSettings> {
  return writeJson<CallLogRetentionSettings>("/api/v1/settings/call-log-retention", {
    method: "PUT", body: JSON.stringify(request),
  });
}

export async function getDebugSettings(): Promise<DebugSettings> {
  return readJson<DebugSettings>("/api/v1/settings/debug", t("api.errors.debugSettingsUnavailable"));
}

export async function updateDebugSettings(request: UpdateDebugSettingsRequest): Promise<DebugSettings> {
  return writeJson<DebugSettings>("/api/v1/settings/debug", {
    method: "PUT", body: JSON.stringify(request),
  });
}

export async function getCallDebug(id: string): Promise<CallDebug> {
  return readJson<CallDebug>(`/api/v1/call-logs/${encodeURIComponent(id)}/debug`, t("api.errors.callBodyUnavailable"));
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
    t("api.errors.assetDurationUnavailable"),
  );
}

/** Uploads real image bytes with the session CSRF token; the browser supplies the multipart boundary. */
export async function uploadImageAsset(projectId: string, file: File): Promise<Asset> {
  return uploadAsset(`/api/v1/projects/${encodeURIComponent(projectId)}/assets`, file,
    MAX_IMAGE_UPLOAD_BYTES, t("api.errors.imageSizeLimit"), t("api.errors.imageUploadFailed"));
}

/** Uploads audio with the same session protection and backend decoding as images. */
export async function uploadAudioAsset(projectId: string, file: File): Promise<Asset> {
  return uploadAsset(`/api/v1/projects/${encodeURIComponent(projectId)}/assets/audio`, file,
    MAX_AUDIO_UPLOAD_BYTES, t("api.errors.audioSizeLimit"), t("api.errors.audioUploadFailed"));
}

/** MP4 uploads use actual backend decoding and the selected per-asset storage destination. */
export async function uploadVideoAsset(projectId: string, file: File): Promise<Asset> {
  return uploadAsset(`/api/v1/projects/${encodeURIComponent(projectId)}/assets/video`, file,
    MAX_VIDEO_UPLOAD_BYTES, t("api.errors.videoSizeLimit"), t("api.errors.videoUploadFailed"));
}

async function uploadAsset(path: string, file: File, maxBytes: number,
  sizeError: string, failureMessage: string): Promise<Asset> {
  if (file.size > maxBytes) throw new ApiError(413, "ASSET_TOO_LARGE", sizeError, false);
  const form = new FormData();
  form.append("file", file);
  return writeJson<Asset>(path, { method: "POST", body: form }, failureMessage);
}

/** Session-protected metadata download; the manifest contains no signed media URLs. */
export function projectExportManifestUrl(projectId: string): string {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/export-manifest`;
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
  return readJson<SetupStatus>("/api/v1/auth/setup-status", t("api.errors.setupStatusUnavailable"));
}

/** Creates the sole administrator; the server permanently closes setup after success. */
export async function setupAdministrator(
  input: SetupRequest,
): Promise<CurrentUser> {
  return writeJson<CurrentUser>("/api/v1/auth/setup", {
    method: "POST",
    body: JSON.stringify(input),
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
  return readJson<CurrentUser>("/api/v1/auth/me", t("api.errors.sessionExpired"));
}

/** Reads only masked administrator LLM configuration metadata. */
export async function getLlmSettings(): Promise<LlmSettings> {
  return readJson<LlmSettings>("/api/v1/settings/llm", t("api.errors.modelSettingsUnavailable"));
}

/** Media settings expose only public connection and capability metadata. */
export async function getAutoDlWorkflowCatalog(): Promise<operations["listAutoDlWorkflows"]["responses"][200]["content"]["application/json"]> {
  return readJson("/api/v1/settings/autodl-workflows", t("api.errors.workflowCatalogUnavailable"));
}
export async function previewAutoDlWorkflow(workflowId: string, source?: object): Promise<components["schemas"]["AutoDlWorkflowDefinition"]> {
  return writeJson("/api/v1/settings/autodl-workflows/preview", {
    method: "POST", body: JSON.stringify({ workflowId, ...(source ? { source } : {}) }),
  });
}

export async function getMediaSettings(): Promise<MediaSettings> {
  return readJson<MediaSettings>("/api/v1/settings/media-connections", t("api.errors.mediaSettingsUnavailable"));
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

export async function previewComfyWorkflow(connectionId: string, workflowJson: string): Promise<ComfyUiGraph> {
  return writeJson<ComfyUiGraph>(`/api/v1/settings/media-connections/${encodeURIComponent(connectionId)}/comfyui/preview`, {
    method: "POST", body: JSON.stringify({ workflowJson }),
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

export async function deleteMediaCapability(connectionId: string, capabilityId: string,
  expectedVersion: number): Promise<MediaSettings> {
  return writeJson<MediaSettings>(`/api/v1/settings/media-connections/${encodeURIComponent(connectionId)}/capabilities/${encodeURIComponent(capabilityId)}?expectedVersion=${expectedVersion}`, {
    method: "DELETE",
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
  return readJson<SystemDiagnostics>("/api/v1/settings/diagnostics", t("api.errors.diagnosticsUnavailable"));
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
  return readJson<ProjectList>(`/api/v1/projects${suffix}`, t("api.errors.projectsUnavailable"));
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
  return readJson<Project>(`/api/v1/projects/${projectId}`, t("api.errors.projectUnavailable"));
}

/** Loads workspace entities and the exclusive event replay cursor from one database snapshot. */
export async function getProjectSnapshot(projectId: string): Promise<ProjectSnapshot> {
  return readJson<ProjectSnapshot>(
    `/api/v1/projects/${projectId}/snapshot`,
    t("api.errors.snapshotUnavailable"),
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
    t("api.errors.artifactUnavailable"),
  );
}

/** Lists project resources independently of which CanvasItems are currently visible. */
export async function listArtifacts(projectId: string): Promise<ArtifactList> {
  return readJson<ArtifactList>(`/api/v1/projects/${projectId}/artifacts`, t("api.errors.projectResourcesUnavailable"));
}

/** Loads one media card's editable generation input independently of its selected result. */
export async function getMediaDraft(projectId: string, canvasItemId: string): Promise<MediaDraft> {
  return readJson<MediaDraft>(
    `/api/v1/projects/${projectId}/canvas-items/${canvasItemId}/media-draft`,
    t("api.errors.mediaDraftUnavailable"),
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
    t("api.errors.relationsUnavailable"),
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
    `/api/v1/projects/${projectId}/canvas/items/${itemId}/media-versions`, t("api.errors.mediaVersionsUnavailable"));
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

export function getMediaFunctions(): Promise<MediaFunctionSetting[]> {
  return readJson("/api/v1/settings/media-functions", t("api.errors.mediaSettingsUnavailable"));
}

export function updateMediaFunction(operation: MediaFunction, expectedVersion: number, capabilityId: string | null): Promise<MediaFunctionSetting[]> {
  return writeJson(`/api/v1/settings/media-functions/${operation}`, {
    method: "PUT", body: JSON.stringify({ expectedVersion, capabilityId }),
  });
}

export function runVideoOperation(projectId: string, artifactId: string, input: RunVideoOperationRequest, commandKey: string): Promise<Task> {
  return writeJson(`/api/v1/projects/${projectId}/artifacts/${artifactId}/video-operations`, {
    method: "POST", headers: { "Idempotency-Key": commandKey }, body: JSON.stringify(input),
  });
}

/** Card media history includes direct requests and user-approved Agent tasks. */
export async function listDirectMediaTasks(projectId: string, artifactId: string,
  canvasItemId: string): Promise<Task[]> {
  const query = new URLSearchParams({ canvasItemId });
  return readJson<Task[]>(`/api/v1/projects/${projectId}/artifacts/${artifactId}/run?${query}`,
    t("api.errors.cardTasksUnavailable"));
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
    t("api.errors.textTasksUnavailable"),
  );
}

export async function cancelQueuedDirectMediaTask(projectId: string, taskId: string): Promise<Task> {
  return writeJson<Task>(`/api/v1/projects/${projectId}/tasks/${taskId}/cancel-queued`,
    { method: "POST" });
}

export async function getDirectMediaQueueStatus(projectId: string,
  taskId: string): Promise<DirectMediaQueueStatus> {
  return readJson<DirectMediaQueueStatus>(
    `/api/v1/projects/${projectId}/tasks/${taskId}/queue`, t("api.errors.queueUnavailable"));
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
    t("api.errors.versionHistoryUnavailable"),
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
  return readJson<Canvas>(`/api/v1/projects/${projectId}/canvas/items`, t("api.errors.canvasUnavailable"));
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
  return readJson<AgentList>(`/api/v1/projects/${projectId}/agents`, t("api.errors.agentsUnavailable"));
}

/** Creates an idle Creator Agent; creation does not start a model run. */
export async function createAgent(
  projectId: string,
  input: CreateAgentRequest,
  idempotencyKey?: string,
): Promise<Agent> {
  return writeJson<Agent>(`/api/v1/projects/${projectId}/agents`, {
    method: "POST",
    ...(idempotencyKey ? { headers: { "Idempotency-Key": idempotencyKey } } : {}),
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
  conversationId?: string, skillSelection?: SkillSelection): Promise<RunPreflight> {
  if (skillSelection) return writeJson<RunPreflight>(`/api/v1/projects/${projectId}/runs/preflight`, {
    method: "POST", body: JSON.stringify({ agentId, conversationId, skillSelection }),
  });
  const params = new URLSearchParams({ agentId });
  if (conversationId) params.set("conversationId", conversationId);
  return readJson<RunPreflight>(
    `/api/v1/projects/${projectId}/runs/preflight?${params}`,
    t("api.errors.preflightUnavailable"),
  );
}

/** Reads durable conversations and the Agent's selected conversation without loading model traces. */
export async function listAgentConversations(projectId: string, agentId: string,
  cursor?: string, limit = 20): Promise<AgentConversationList> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (cursor) params.set("cursor", cursor);
  return readJson<AgentConversationList>(
    `/api/v1/projects/${projectId}/agents/${agentId}/conversations?${params}`, t("api.errors.conversationsUnavailable"));
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
    t("api.errors.conversationMessagesUnavailable"));
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

/** Lists only committed, server-authored action summaries for one owned Run. */
export async function listRunActions(projectId: string, runId: string): Promise<RunAction[]> {
  return readJson<RunAction[]>(
    `/api/v1/projects/${projectId}/runs/${runId}/actions`,
    t("api.errors.actionsUnavailable"),
  );
}

/** Reads frozen, owner-scoped media proposals without contacting a Provider. */
export async function listRunMediaApprovals(projectId: string, runId: string): Promise<AgentMediaApproval[]> {
  return readJson<AgentMediaApproval[]>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/runs/${encodeURIComponent(runId)}/media-approvals`,
    t("api.errors.actionsUnavailable"),
  );
}

/** An explicit user decision authorizes one frozen batch under CAS and command deduplication. */
export async function decideRunMediaApproval(projectId: string, runId: string, approvalId: string,
  body: AgentMediaApprovalDecision, idempotencyKey: string): Promise<AgentMediaApproval> {
  return writeJson<AgentMediaApproval>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/runs/${encodeURIComponent(runId)}` +
      `/media-approvals/${encodeURIComponent(approvalId)}/decision`,
    { method: "POST", headers: { "Idempotency-Key": idempotencyKey }, body: JSON.stringify(body) },
  );
}

/** Stops future orchestration; external work may still require later reconciliation. */
export async function cancelRun(projectId: string, runId: string): Promise<AgentRun> {
  return writeJson<AgentRun>(`/api/v1/projects/${projectId}/runs/${runId}/cancel`, {
    method: "POST",
  });
}

/** Reads durable Task state; lease ownership remains an internal worker concern. */
export async function getTask(projectId: string, taskId: string): Promise<Task> {
  return readJson<Task>(`/api/v1/projects/${projectId}/tasks/${taskId}`, t("api.errors.taskStatusUnavailable"));
}

/** Creates a separately reserved generation attempt for an unknown or accepted blocked direct task. */
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
    t("api.errors.runTasksUnavailable"));
}

async function readJson<T>(path: string, fallbackMessage: string): Promise<T> {
  const response = await apiFetch(path, {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  });
  if (!response.ok) {
    throw await apiError(response, fallbackMessage);
  }
  return (await response.json()) as T;
}

async function writeJson<T>(path: string, init: RequestInit,
  failureMessage = t("api.errors.requestFailed")): Promise<T> {
  const response = await write(path, init, failureMessage);
  return (await response.json()) as T;
}

async function writeEmpty(path: string, init: RequestInit): Promise<void> {
  await write(path, init);
}

async function write(path: string, init: RequestInit,
  failureMessage = t("api.errors.requestFailed")): Promise<Response> {
  const token = await getCsrfToken();
  const response = await apiFetch(path, {
    ...init,
    credentials: "same-origin",
    headers: {
      Accept: "application/json, application/problem+json",
      // Multipart boundaries are generated by the browser, including for library uploads.
      ...(init.body instanceof FormData ? {} : { "Content-Type": "application/json" }),
      [token.headerName]: token.token,
      ...init.headers,
    },
  });
  if (!response.ok) {
    throw await apiError(response, failureMessage);
  }
  return response;
}

async function getCsrfToken(): Promise<CsrfToken> {
  if (csrfToken) {
    return csrfToken;
  }
  csrfToken = await readJson<CsrfToken>("/api/v1/auth/csrf", t("api.errors.sessionUnavailable"));
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
  return new ApiError(response.status, "HTTP_ERROR", fallbackMessage, response.status >= HTTP_STATUS.INTERNAL_SERVER_ERROR);
}

export type SystemLogSnapshot = components["schemas"]["SystemLogSnapshot"];
export type SystemLogStream = components["schemas"]["SystemLogEntry"]["stream"];

/** Reads bounded, sanitized Java console output; no generated work is triggered. */
export async function listSystemLogs(input: { stream?: SystemLogStream; search?: string; limit: number }): Promise<SystemLogSnapshot> {
  const params = new URLSearchParams({ limit: String(input.limit) });
  if (input.stream) params.set("stream", input.stream);
  if (input.search) params.set("search", input.search);
  return readJson<SystemLogSnapshot>(`/api/v1/settings/system-logs?${params}`, t("api.errors.systemLogsUnavailable"));
}

export type LibraryEntry = components["schemas"]["LibraryEntry"];
export type LibraryCategory = components["schemas"]["LibraryCategory"];
export type LibrarySort = components["schemas"]["LibrarySort"];
export type LibraryPage = components["schemas"]["LibraryPage"];
export type LibraryCommand = components["schemas"]["LibraryCommand"];
export type LibrarySource = components["schemas"]["LibrarySource"];
export type SaveLibraryRequest = components["schemas"]["SaveLibraryRequest"];
export type ImportLibraryRequest = components["schemas"]["ImportLibraryRequest"];
export type ReferenceLibraryRequest = components["schemas"]["ReferenceLibraryRequest"];
export type UpdateLibraryRequest = components["schemas"]["UpdateLibraryRequest"];
export type LibraryFilters = NonNullable<paths["/api/v1/library/entries"]["get"]["parameters"]["query"]>;
export function listLibraryEntries(filters: LibraryFilters): Promise<LibraryPage> {
  const query = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => { if (value !== undefined) query.set(key, String(value)); });
  return readJson(`/api/v1/library/entries?${query}`, t("api.errors.libraryUnavailable"));
}
export function getLibraryEntry(id: string): Promise<LibraryEntry> {
  return readJson(`/api/v1/library/entries/${id}`, t("api.errors.libraryEntryUnavailable"));
}
export function libraryContentUrl(id: string) { return `/api/v1/library/entries/${id}/content`; }
export function libraryThumbnailUrl(id: string) { return `/api/v1/library/entries/${id}/thumbnail`; }
export function getLibrarySource(project: string, item: string): Promise<LibrarySource> {
  return readJson(`/api/v1/projects/${project}/canvas-items/${item}/library-saves`, t("api.errors.selectedResultUnavailable"));
}
export function saveLibraryEntry(project: string, item: string, request: SaveLibraryRequest): Promise<LibraryCommand> {
  return writeJson(`/api/v1/projects/${project}/canvas-items/${item}/library-saves`, { method: "POST", body: JSON.stringify(request) });
}
export function importLibraryEntry(project: string, request: ImportLibraryRequest): Promise<LibraryCommand> {
  return writeJson(`/api/v1/projects/${project}/library-imports`, { method: "POST", body: JSON.stringify(request) });
}
export function referenceLibraryEntry(project: string, item: string, request: ReferenceLibraryRequest): Promise<LibraryCommand> {
  return writeJson(`/api/v1/projects/${project}/canvas-items/${item}/library-references`, { method: "POST", body: JSON.stringify(request) });
}
export function getLibraryCommand(id: string): Promise<LibraryCommand> {
  return readJson(`/api/v1/library/commands/${id}`, t("api.errors.transferStatusUnavailable"));
}
export function retryLibraryCommand(id: string): Promise<LibraryCommand> {
  return writeJson(`/api/v1/library/commands/${id}/retry`, { method: "POST" });
}
export function updateLibraryEntry(id: string, request: UpdateLibraryRequest): Promise<LibraryEntry> {
  return writeJson(`/api/v1/library/entries/${id}`, { method: "PATCH", body: JSON.stringify(request) });
}
export function setLibraryTrash(id: string, expectedVersion: number, restore = false): Promise<LibraryEntry> {
  return writeJson(`/api/v1/library/entries/${id}/${restore ? "restore" : "trash"}`, { method: "POST", body: JSON.stringify({ expectedVersion }) });
}
export function deleteLibraryEntry(id: string, expectedVersion: number): Promise<void> {
  return writeEmpty(`/api/v1/library/entries/${id}?expectedVersion=${expectedVersion}`, { method: "DELETE" });
}

export type MediaTemplate = components["schemas"]["MediaTemplate"];
export type MediaTemplateImage = components["schemas"]["MediaTemplateImage"];
export type WriteMediaTemplateRequest = components["schemas"]["CreateMediaTemplateRequest"] | components["schemas"]["UpdateMediaTemplateRequest"];
export type MediaTemplateImport = components["schemas"]["MediaTemplateImport"];
export type MediaTemplateKind = MediaTemplate["targetKind"];
export type MediaTemplateScope = MediaTemplate["scope"];
export type ThirdPartyPromptEntry = components["schemas"]["ThirdPartyPromptEntry"];
export type ThirdPartyPromptSource = components["schemas"]["ThirdPartyPromptSource"];
export type ThirdPartyPromptPage = components["schemas"]["ThirdPartyPromptPage"];
export type ThirdPartyPromptReference = components["schemas"]["ThirdPartyPromptReference"];
export function listThirdPartyPromptSources(): Promise<components["schemas"]["ThirdPartyPromptSources"]> {
  return readJson("/api/v1/media-templates/third-party/sources", t("templates.loadFailed"));
}
export function listThirdPartyPrompts(kind: MediaTemplateKind, sourceId: string, query: string, offset: number): Promise<ThirdPartyPromptPage> {
  const params = new URLSearchParams({ targetKind: kind, query, offset: String(offset), limit: "50" });
  if (sourceId) params.set("sourceId", sourceId);
  return readJson(`/api/v1/media-templates/third-party?${params}`, t("templates.loadFailed"));
}
export function createThirdPartyPromptSource(input: components["schemas"]["CreateThirdPartyPromptSource"]): Promise<ThirdPartyPromptSource> {
  return writeJson("/api/v1/settings/media-template-sources", { method: "POST", body: JSON.stringify(input) });
}
export function setThirdPartyPromptSource(source: ThirdPartyPromptSource, enabled: boolean): Promise<ThirdPartyPromptSource> {
  return writeJson(`/api/v1/settings/media-template-sources/${encodeURIComponent(source.id)}`, { method: "PATCH", body: JSON.stringify({ enabled, expectedVersion: source.version }) });
}
export function syncThirdPartyPromptSource(id: string): Promise<components["schemas"]["ThirdPartyPromptSync"]> {
  return writeJson(`/api/v1/settings/media-template-sources/${encodeURIComponent(id)}/sync`, { method: "POST" });
}
export function importThirdPartyPrompt(projectId: string, entry: ThirdPartyPromptEntry, commandKey: string): Promise<MediaTemplateImport> {
  return writeJson(`/api/v1/projects/${encodeURIComponent(projectId)}/media-templates/third-party/import`,
    { method: "POST", body: JSON.stringify({ promptId: entry.id, expectedVersion: entry.version, commandKey }) });
}

/** Templates contain reusable text and independently archived images; they never run a Provider. */
export async function listMediaTemplates(targetKind?: MediaTemplateKind, system = false): Promise<MediaTemplate[]> {
  const query = new URLSearchParams();
  if (targetKind) query.set("targetKind", targetKind);
  if (system) query.set("scope", "SYSTEM");
  const result = await readJson<components["schemas"]["MediaTemplateList"]>(`/api/v1/media-templates?${query}`, t("templates.loadFailed"));
  return result.items;
}
export function saveMediaTemplate(input: WriteMediaTemplateRequest, scope: MediaTemplateScope,
  id?: string): Promise<MediaTemplate> {
  const path = `/api/v1/${scope === "SYSTEM" ? "settings/" : ""}media-templates`;
  return writeJson(id ? `${path}/${encodeURIComponent(id)}` : path,
    { method: id ? "PATCH" : "POST", body: JSON.stringify(input) });
}
export function deleteMediaTemplate(template: MediaTemplate): Promise<void> {
  return writeEmpty(`/api/v1/${template.scope === "SYSTEM" ? "settings/" : ""}media-templates/${encodeURIComponent(template.id)}?expectedVersion=${template.version}`,
    { method: "DELETE" });
}
export function uploadMediaTemplateImage(file: File): Promise<MediaTemplateImage> {
  const body = new FormData(); body.append("file", file);
  return writeJson("/api/v1/media-templates/images", { method: "POST", body });
}
export function copyMediaTemplateImage(projectId: string, versionId: string): Promise<MediaTemplateImage> {
  return writeJson("/api/v1/media-templates/images/from-version",
    { method: "POST", body: JSON.stringify({ projectId, versionId }) });
}
export function deleteMediaTemplateImage(imageId: string): Promise<void> {
  return writeEmpty(`/api/v1/media-templates/images/${encodeURIComponent(imageId)}`, { method: "DELETE" });
}
export function importMediaTemplate(projectId: string, template: MediaTemplate,
  commandKey: string): Promise<MediaTemplateImport> {
  return writeJson(`/api/v1/projects/${encodeURIComponent(projectId)}/media-templates/${encodeURIComponent(template.id)}/import`,
    { method: "POST", body: JSON.stringify({ expectedTemplateVersion: template.version, commandKey }) });
}
/** Replaces a complete draft and its connected reference sources in one CAS transaction. */
export function replaceMediaDraftInputs(projectId: string, canvasItemId: string,
  input: SaveMediaDraftRequest): Promise<MediaDraft> {
  return writeJson(`/api/v1/projects/${encodeURIComponent(projectId)}/canvas-items/${encodeURIComponent(canvasItemId)}/media-draft/replace-inputs`,
    { method: "POST", body: JSON.stringify(input) });
}
export async function uploadLibraryEntry(request: { file: File; kind: "IMAGE" | "VIDEO" | "AUDIO"; name: string; category: LibraryCategory; commandKey: string }): Promise<LibraryCommand> {
  const body = new FormData(); Object.entries(request).forEach(([key, value]) => body.append(key, value));
  return writeJson<LibraryCommand>("/api/v1/library/uploads", { method: "POST", body }, t("api.errors.libraryUploadFailed"));
}

export type MediaRelaySettingsRequest = components["schemas"]["MediaRelaySettingsRequest"];
export type StorageSettings = components["schemas"]["StorageSettings"];
export type StorageProvider = components["schemas"]["StorageProvider"];
export type CreateStorageProfileRequest = components["schemas"]["CreateStorageProfileRequest"];
export type UpdateStorageProfileRequest = components["schemas"]["UpdateStorageProfileRequest"];
export type StorageProfile = components["schemas"]["StorageProfile"];
export type RotateStorageCredentialsRequest = components["schemas"]["RotateStorageCredentialsRequest"];
export async function getStorageSettings(): Promise<StorageSettings> {
  return readJson<StorageSettings>("/api/v1/settings/storage", t("api.errors.storageSettingsUnavailable"));
}
export async function createStorageProfile(input: CreateStorageProfileRequest): Promise<StorageSettings> {
  return writeJson<StorageSettings>("/api/v1/settings/storage/profiles", {
    method: "POST", body: JSON.stringify(input),
  });
}
export async function updateStorageProfile(id: string, input: UpdateStorageProfileRequest): Promise<StorageSettings> {
  return writeJson<StorageSettings>(`/api/v1/settings/storage/profiles/${encodeURIComponent(id)}`, {
    method: "PUT", body: JSON.stringify(input),
  });
}
export async function deleteStorageProfile(id: string, expectedVersion: number): Promise<StorageSettings> {
  return writeJson<StorageSettings>(`/api/v1/settings/storage/profiles/${encodeURIComponent(id)}?expectedVersion=${expectedVersion}`, {
    method: "DELETE",
  });
}
export async function activateStorageProfile(input: components["schemas"]["ActivateStorageProfileRequest"]): Promise<StorageSettings> {
  return writeJson<StorageSettings>("/api/v1/settings/storage/active", {
    method: "PUT", body: JSON.stringify(input),
  });
}
export async function activateMediaRelayProfile(input: components["schemas"]["MediaRelaySettingsRequest"]): Promise<StorageSettings> {
  return writeJson<StorageSettings>("/api/v1/settings/storage/relay", {
    method: "PUT", body: JSON.stringify(input),
  });
}
export async function rotateStorageCredentials(id: string, input: RotateStorageCredentialsRequest): Promise<StorageSettings> {
  return writeJson<StorageSettings>(`/api/v1/settings/storage/profiles/${encodeURIComponent(id)}/credentials`, {
    method: "PUT", body: JSON.stringify(input),
  });
}

/** All JSON and multipart requests negotiate the same language as the UI. */
function apiFetch(path: string, init: RequestInit): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("Accept-Language", getLocale());
  return fetch(path, { ...init, headers });
}

export type CreativeSkill = components["schemas"]["CreativeSkill"];
export type SkillDraft = components["schemas"]["SkillDraft"];
export type SkillVersion = components["schemas"]["SkillVersion"];
export type SkillVersionSummary = components["schemas"]["SkillVersionSummary"];
export type SkillOperation = components["schemas"]["SkillOperation"];
export type SkillSelection = components["schemas"]["SkillSelection"];
export type AgentSkillBinding = components["schemas"]["AgentSkillBinding"];
export type SaveAgentSkillBindingRequest = components["schemas"]["SaveAgentSkillBindingRequest"];
export type SaveSkillDraftRequest = NonNullable<paths["/api/v1/skills/{skillId}/draft"]["put"]["requestBody"]>["content"]["application/json"];

export function listSkills(query = "", cursor?: string, trash = false) {
  const params = new URLSearchParams({ query });
  if (cursor) params.set("cursor", cursor);
  if (trash) params.set("trash", "true");
  return readJson<components["schemas"]["SkillPage"]>(`/api/v1/skills?${params}`, t("api.errors.requestFailed"));
}
export function createSkill(title: string) {
  return writeJson<CreativeSkill>("/api/v1/skills", { method: "POST", body: JSON.stringify({ title }) });
}
export function getSkill(skillId: string) {
  return readJson<CreativeSkill>(`/api/v1/skills/${skillId}`, t("api.errors.requestFailed"));
}
export function updateSkill(skillId: string, request: components["schemas"]["UpdateSkillRequest"]) {
  return writeJson<CreativeSkill>(`/api/v1/skills/${skillId}`, { method: "PATCH", body: JSON.stringify(request) });
}
export function getSkillDraft(skillId: string) {
  return readJson<SkillDraft>(`/api/v1/skills/${skillId}/draft`, t("api.errors.requestFailed"));
}
export function saveSkillDraft(skillId: string, request: SaveSkillDraftRequest) {
  return writeJson<SkillDraft>(`/api/v1/skills/${skillId}/draft`, { method: "PUT", body: JSON.stringify(request) });
}
export function publishSkillVersion(skillId: string, expectedDraftVersion: number, commandKey: string) {
  return writeJson<SkillOperation>(`/api/v1/skills/${skillId}/versions`, { method: "POST", body: JSON.stringify({ expectedDraftVersion, commandKey }) });
}
export function getSkillOperation(operationId: string) {
  return readJson<SkillOperation>(`/api/v1/skills/operations/${operationId}`, t("api.errors.requestFailed"));
}
export function retrySkillOperation(operationId: string) {
  return writeJson<SkillOperation>(`/api/v1/skills/operations/${operationId}/retry`, { method: "POST" });
}
export function listSkillVersions(skillId: string) {
  return readJson<components["schemas"]["SkillVersionList"]>(`/api/v1/skills/${skillId}/versions`, t("api.errors.requestFailed"));
}
export function getSkillVersion(skillId: string, versionId: string) {
  return readJson<SkillVersion>(`/api/v1/skills/${skillId}/versions/${versionId}`, t("api.errors.requestFailed"));
}
export function copySkillVersion(skillId: string, versionId: string, expectedDraftVersion: number) {
  return writeJson<SkillDraft>(`/api/v1/skills/${skillId}/versions/${versionId}/copy`, { method: "POST", body: JSON.stringify({ expectedDraftVersion }) });
}
export function getAgentSkillBinding(projectId: string, agentId: string) {
  return readJson<AgentSkillBinding>(`/api/v1/projects/${projectId}/agents/${agentId}/skill-binding`, t("api.errors.requestFailed"));
}
export function saveAgentSkillBinding(projectId: string, agentId: string, request: components["schemas"]["SaveAgentSkillBindingRequest"], key: string) {
  return writeJson<AgentSkillBinding>(`/api/v1/projects/${projectId}/agents/${agentId}/skill-binding`, {
    method: "PUT", headers: { "Idempotency-Key": key }, body: JSON.stringify(request),
  });
}
export function copySkill(skillId: string, skillVersionId: string, title: string) {
  return writeJson<CreativeSkill>(`/api/v1/skills/${skillId}/copy`, { method: "POST", body: JSON.stringify({title,skillVersionId}) });
}
export function skillAssetThumbnailUrl(skillId: string, versionId: string, alias: string) {
  return `/api/v1/skills/${encodeURIComponent(skillId)}/versions/${encodeURIComponent(versionId)}/assets/${encodeURIComponent(alias)}/thumbnail`;
}

export function getRun(projectId: string, runId: string) {
  return readJson<AgentRun>(`/api/v1/projects/${projectId}/runs/${runId}`, t("api.errors.requestFailed"));
}

export type PromptDefinition = components["schemas"]["PromptDefinition"];
export type AgentPreset = components["schemas"]["AgentPreset"];
export function listPrompts() {
  return readJson<components["schemas"]["PromptList"]>("/api/v1/settings/prompts", t("prompts.loadFailed"));
}
export function createPrompt(input: components["schemas"]["CreatePromptRequest"]) {
  return writeJson<PromptDefinition>("/api/v1/settings/prompts", { method: "POST", body: JSON.stringify(input) });
}
export function updatePrompt(id: string, input: components["schemas"]["UpdatePromptRequest"]) {
  return writeJson<PromptDefinition>(`/api/v1/settings/prompts/${id}`, { method: "PUT", body: JSON.stringify(input) });
}
export function deletePrompt(id: string, expectedVersion: number) {
  return writeEmpty(`/api/v1/settings/prompts/${id}?expectedVersion=${expectedVersion}`, { method: "DELETE" });
}
export function listAgentPresets() {
  return readJson<components["schemas"]["AgentPresetList"]>("/api/v1/agent-presets", t("prompts.loadFailed"));
}
