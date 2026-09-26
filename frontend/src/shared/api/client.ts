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
export type RunMediaDraftRequest = components["schemas"]["RunMediaDraftRequest"];
export type DirectMediaQueueStatus = components["schemas"]["DirectMediaQueueStatus"];
export type Asset = components["schemas"]["Asset"];
export type ArtifactVersionList = components["schemas"]["ArtifactVersionList"];
export type CreateArtifactRequest = components["schemas"]["CreateArtifactRequest"];
export type ReviseArtifactRequest = components["schemas"]["ReviseArtifactRequest"];
export type Canvas = components["schemas"]["Canvas"];
export type CanvasItem = components["schemas"]["CanvasItem"];
export type CanvasCommand = components["schemas"]["CanvasCommand"];
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
export type ExecutionPlan = components["schemas"]["ExecutionPlan"];
export type ExecutionPlanApproval = components["schemas"]["ExecutionPlanApproval"];
export type ShotKeyframeSelection = components["schemas"]["ShotKeyframeSelection"];
export type SelectShotKeyframeRequest = components["schemas"]["SelectShotKeyframeRequest"];
export type Task = components["schemas"]["Task"];
export type ProviderAttempt = components["schemas"]["ProviderAttempt"];
export type ManualUnknownAttemptRequest = components["schemas"]["ManualUnknownAttemptRequest"];
export type ReconciliationResult = components["schemas"]["ReconciliationResult"];
export type ReviseShotForRedoRequest = components["schemas"]["ReviseShotForRedoRequest"];
export type ShotRedoResult = components["schemas"]["ShotRedoResult"];
export type CreateMediaExportRequest = components["schemas"]["CreateMediaExportRequest"];
export type ExportProposal = components["schemas"]["ExportProposal"];
export type ExportProposalApproval = components["schemas"]["ExportProposalApproval"];
export type UsageEntry = components["schemas"]["UsageEntry"];
export type LlmSettings = components["schemas"]["LlmSettings"];
export type SystemDiagnostics = components["schemas"]["SystemDiagnostics"];
export type ReplaceLlmSettingsRequest = components["schemas"]["ReplaceLlmSettingsRequest"];
export type DiagnoseLlmRequest = components["schemas"]["DiagnoseLlmRequest"];
export type MediaSettings = components["schemas"]["MediaSettings"];
export type MediaConnection = components["schemas"]["MediaConnection"];
export type MediaCapability = components["schemas"]["MediaCapability"];
export type CreateMediaConnectionRequest = components["schemas"]["CreateMediaConnectionRequest"];
export type UpdateMediaConnectionRequest = components["schemas"]["UpdateMediaConnectionRequest"];
export type CreateMediaCapabilityRequest = components["schemas"]["CreateMediaCapabilityRequest"];
export type UpdateMediaCapabilityRequest = components["schemas"]["UpdateMediaCapabilityRequest"];
export type UpdateMediaConcurrencyRequest = components["schemas"]["UpdateMediaConcurrencyRequest"];
export type SetMediaDefaultRequest = components["schemas"]["SetMediaDefaultRequest"];
export type MediaCapabilityCandidate = components["schemas"]["MediaCapabilityCandidate"];
export type ReviseExecutionPlanStepRequest = components["schemas"]["ReviseExecutionPlanStepRequest"];
type CsrfToken = components["schemas"]["CsrfToken"];
type Problem = components["schemas"]["Problem"];

let csrfToken: CsrfToken | undefined;

/** Same-origin private URLs retain session authorization without storing any media key. */
export function assetThumbnailUrl(projectId: string, assetId: string): string {
  return `/api/v1/projects/${encodeURIComponent(projectId)}/assets/${encodeURIComponent(assetId)}/thumbnail`;
}

/** Explicit original-file action; canvas cards should use assetThumbnailUrl instead. */
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

export async function updateMediaConcurrency(connectionId: string, capabilityId: string,
  input: UpdateMediaConcurrencyRequest): Promise<MediaSettings> {
  return writeJson<MediaSettings>(
    `/api/v1/settings/media-connections/${encodeURIComponent(connectionId)}/capabilities/${encodeURIComponent(capabilityId)}/concurrency`,
    { method: "PUT", body: JSON.stringify(input) },
  );
}

export async function setMediaDefault(kind: "IMAGE_GENERATION" | "VIDEO_GENERATION",
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

/** Loads an Artifact with its currently selected immutable revision. */
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

/** Loads the editable generation input independently of the selected media result. */
export async function getMediaDraft(projectId: string, artifactId: string): Promise<MediaDraft> {
  return readJson<MediaDraft>(
    `/api/v1/projects/${projectId}/artifacts/${artifactId}/draft`,
    "无法读取媒体草稿",
  );
}

/** Saves the complete working draft with its own optimistic version. */
export async function saveMediaDraft(projectId: string, artifactId: string,
  input: SaveMediaDraftRequest): Promise<MediaDraft> {
  return writeJson<MediaDraft>(
    `/api/v1/projects/${projectId}/artifacts/${artifactId}/draft`,
    { method: "PUT", body: JSON.stringify(input) },
  );
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

export async function listDirectMediaTasks(projectId: string, artifactId: string): Promise<Task[]> {
  return readJson<Task[]>(`/api/v1/projects/${projectId}/artifacts/${artifactId}/run`,
    "无法读取卡片任务");
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

/** Selects a historical revision without overwriting any content. */
export async function selectArtifactVersion(
  projectId: string,
  artifactId: string,
  versionId: string,
  expectedVersion: number,
): Promise<Artifact> {
  return writeJson<Artifact>(
    `/api/v1/projects/${projectId}/artifacts/${artifactId}/select-version`,
    {
      method: "POST",
      body: JSON.stringify({ versionId, expectedVersion }),
    },
  );
}

/** Revises one shot and, if requested, forks its shared scene reference atomically. */
export async function reviseShotForRedo(projectId: string, shotId: string,
  request: ReviseShotForRedoRequest): Promise<ShotRedoResult> {
  return writeJson<ShotRedoResult>(`/api/v1/projects/${projectId}/shots/${shotId}/revisions`, {
    method: "POST",
    body: JSON.stringify(request),
  });
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

/** Recovers every plan revision for an owned Run after page reload or SSE replay. */
export async function listExecutionPlans(projectId: string, runId: string): Promise<ExecutionPlan[]> {
  return readJson<ExecutionPlan[]>(
    `/api/v1/projects/${projectId}/runs/${runId}/plans`,
    "无法读取执行计划",
  );
}

/** Reads the frozen proposal shown to the approving user. */
export async function getExecutionPlan(projectId: string, planId: string): Promise<ExecutionPlan> {
  return readJson<ExecutionPlan>(
    `/api/v1/projects/${projectId}/plans/${planId}`,
    "无法读取执行计划",
  );
}

export async function listMediaCapabilityCandidates(projectId: string, planId: string,
  stepKey: string): Promise<MediaCapabilityCandidate[]> {
  return readJson<MediaCapabilityCandidate[]>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/plans/${encodeURIComponent(planId)}/steps/${encodeURIComponent(stepKey)}/candidates`,
    "无法读取可用媒体能力",
  );
}

export async function reviseExecutionPlanStep(projectId: string, planId: string,
  stepKey: string, input: ReviseExecutionPlanStepRequest): Promise<ExecutionPlan> {
  return writeJson<ExecutionPlan>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/plans/${encodeURIComponent(planId)}/steps/${encodeURIComponent(stepKey)}/revise`,
    { method: "POST", body: JSON.stringify(input) },
  );
}

/** Confirms exactly the plan hash rendered in the approval UI. */
export async function approveExecutionPlan(
  projectId: string,
  planId: string,
  planHash: string,
  confirmedStepKeys: string[],
): Promise<ExecutionPlanApproval> {
  return writeJson<ExecutionPlanApproval>(`/api/v1/projects/${projectId}/plans/${planId}/approve`, {
    method: "POST",
    body: JSON.stringify({ planHash, confirmedStepKeys }),
  });
}

/** Rejects a pending plan without authorizing media Tasks. */
export async function rejectExecutionPlan(projectId: string, planId: string): Promise<ExecutionPlan> {
  return writeJson<ExecutionPlan>(`/api/v1/projects/${projectId}/plans/${planId}/reject`, {
    method: "POST",
  });
}

/** Reads durable Task state; lease ownership remains an internal worker concern. */
export async function getTask(projectId: string, taskId: string): Promise<Task> {
  return readJson<Task>(`/api/v1/projects/${projectId}/tasks/${taskId}`, "无法读取任务状态");
}

/** Reads owner-scoped pre-network submission checkpoints for manual reconciliation. */
export async function listProviderAttempts(projectId: string, taskId: string): Promise<ProviderAttempt[]> {
  return readJson<ProviderAttempt[]>(
    `/api/v1/projects/${projectId}/tasks/${taskId}/attempts`, "无法读取提交账本");
}

/** Read-only provider lookup that can resume the original task but never resubmit generation. */
export async function reconcileUnknownTask(projectId: string, taskId: string): Promise<ReconciliationResult> {
  return writeJson<ReconciliationResult>(`/api/v1/projects/${projectId}/tasks/${taskId}/reconcile`, {
    method: "POST",
  });
}

/** Starts a separately reserved attempt only after the user accepts duplicate-cost risk. */
export async function createManualUnknownAttempt(projectId: string, taskId: string,
  key: string, request: ManualUnknownAttemptRequest): Promise<Task> {
  return writeJson<Task>(`/api/v1/projects/${projectId}/tasks/${taskId}/new-attempt`, {
    method: "POST",
    headers: { "Idempotency-Key": key },
    body: JSON.stringify(request),
  });
}

/** Loads all task outcomes, including completed keyframe outputs omitted from the active snapshot. */
export async function listRunTasks(projectId: string, runId: string): Promise<Task[]> {
  return readJson<Task[]>(`/api/v1/projects/${projectId}/runs/${runId}/tasks`,
    "无法读取运行任务");
}

/** Lists project-level exports independently of the active Agent Run. */
export async function listMediaExports(projectId: string): Promise<Task[]> {
  return readJson<Task[]>(`/api/v1/projects/${projectId}/exports`, "无法读取导出记录");
}

/** Shows model-proposed export inputs before any local encoder is authorized. */
export async function listExportProposals(projectId: string): Promise<ExportProposal[]> {
  return readJson<ExportProposal[]>(`/api/v1/projects/${projectId}/export-proposals`,
    "无法读取导出提案");
}

/** Only the authenticated browser can approve the exact proposal hash it displayed. */
export async function approveExportProposal(projectId: string, proposalId: string,
  proposalHash: string): Promise<ExportProposalApproval> {
  return writeJson<ExportProposalApproval>(
    `/api/v1/projects/${projectId}/export-proposals/${proposalId}/approve`, {
      method: "POST", body: JSON.stringify({ proposalHash }),
    });
}

/** Declines an Agent export suggestion without creating a Task. */
export async function rejectExportProposal(projectId: string, proposalId: string): Promise<ExportProposal> {
  return writeJson<ExportProposal>(
    `/api/v1/projects/${projectId}/export-proposals/${proposalId}/reject`, { method: "POST" });
}

/** Starts a silent export with one caller-owned idempotency key and pinned version ranges. */
export async function createMediaExport(projectId: string, key: string,
  input: CreateMediaExportRequest): Promise<Task> {
  return writeJson<Task>(`/api/v1/projects/${projectId}/exports`, {
    method: "POST",
    headers: { "Idempotency-Key": key },
    body: JSON.stringify(input),
  });
}

/** Requests cancellation of local export work. */
export async function cancelMediaExport(projectId: string, taskId: string): Promise<Task> {
  return writeJson<Task>(`/api/v1/projects/${projectId}/exports/${taskId}/cancel`, {
    method: "POST",
  });
}

/** Reads the exact human-selected image version for one shot in this Run. */
export async function getShotKeyframeSelection(projectId: string, runId: string,
  shotId: string): Promise<ShotKeyframeSelection> {
  return readJson<ShotKeyframeSelection>(
    `/api/v1/projects/${projectId}/runs/${runId}/shots/${shotId}/keyframe-selection`,
    "无法读取镜头关键帧选择",
  );
}

/** Persists an explicit keyframe choice with optimistic concurrency. */
export async function selectShotKeyframe(projectId: string, runId: string, shotId: string,
  request: SelectShotKeyframeRequest): Promise<ShotKeyframeSelection> {
  return writeJson<ShotKeyframeSelection>(
    `/api/v1/projects/${projectId}/runs/${runId}/shots/${shotId}/keyframe-selection`,
    { method: "PUT", body: JSON.stringify(request) },
  );
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
