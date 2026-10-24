import { QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { createRoot } from "react-dom/client";
import { AgentChatDock } from "../src/features/canvas/AgentChatDock";
import { AgentMediaApprovalCard } from "../src/features/canvas/AgentMediaApprovalCard";
import type { Agent, AgentMediaApproval, MediaDraft, MediaSettings } from "../src/shared/api/client";
import { setLocale } from "../src/shared/i18n";
import { Button } from "../src/shared/ui/primitives/button";
import "../src/styles.css";

// Synthetic component/layout evidence only: no backend, credentials or Provider requests.
setLocale("zh");
const now = "2026-10-20T00:00:00Z";
const agent: Agent = { id: "synthetic-director", projectId: "synthetic-project", name: "导演 Agent",
  instruction: "依据用户需求协作创作。", profileKey: "creator", profileVersion: 1,
  version: 0, createdAt: now, updatedAt: now };
const history = new URLSearchParams(location.search).has("history");
const runs = history ? Array.from({ length: 12 }, (_, index) => ({ id: `synthetic-run-${index}`,
  projectId: agent.projectId, agentInstanceId: agent.id, conversationId: "synthetic-conversation",
  conversationTurn: index + 1, status: "SUCCEEDED", instruction: `第 ${index + 1} 轮：帮我整理画布中的创意。`,
  contextSnapshot: {}, policySnapshot: { schemaVersion: 7, toolPolicyVersion: 6, systemPromptVersion: 14, allowedTools: ["read_canvas"],
    modelConfigSource: "MOCK", modelConfigVersion: 0, maxModelTurns: null, maxToolExecutions: null }, createdAt: now, updatedAt: now })) : [];
const approvalMode = new URLSearchParams(location.search).has("approval");
const videoDraft: MediaDraft = { projectId: agent.projectId, canvasItemId: "synthetic-video", prompt: "清晨海岸，海浪缓缓拍打礁石。",
  parameters: { aspectRatio: "16:9", videoResolution: "768p" }, durationSeconds: 5, capabilityId: "synthetic-h3", styleId: null,
  videoInputMode: "TEXT", mediaInputs: [], mentions: [], displayMode: "DRAFT", version: 1, createdAt: now, updatedAt: now };
let mediaApproval: AgentMediaApproval = { id: "synthetic-approval", projectId: agent.projectId, runId: "synthetic-run", operationId: "synthetic-operation",
  status: "PENDING", version: 0, taskIds: [], result: null, createdAt: now, expiresAt: "2030-01-01T00:00:00Z", executionDeadline: null,
  outputs: [{ kind: "VIDEO", title: "SC01 · SHOT01 · 海岸晨光", artifactId: "synthetic-artifact", canvasItemId: videoDraft.canvasItemId, draftVersion: 1,
    preview: { prompt: videoDraft.prompt, capabilityId: videoDraft.capabilityId, adapterId: "MINIMAX_H3", durationSeconds: 5,
      parameters: videoDraft.parameters, videoInputMode: "TEXT", mediaInputs: [], mediaPricing: { amount: "1", currency: "CNY", unit: "SECOND" } } }] };
const mediaSettings: MediaSettings = { connections: [{ id: "synthetic-connection", name: "MiniMax 官方", platform: "MINIMAX", enabled: true, version: 0,
  connectionVersion: 1, origin: null, keyMask: null, connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
  capabilities: [{ id: "synthetic-h3", name: "H3 · 合成配置", adapterId: "MINIMAX_H3", enabled: true, version: 0, capabilityVersion: 1,
    kind: "VIDEO_GENERATION", minimumSeconds: 4, maximumSeconds: 15, maxReferenceImages: 9, maxReferenceAudios: 3, maxReferenceVideos: 3,
    supportedVideoInputModes: ["TEXT", "START_END", "GENERAL_REFERENCE"], defaultVideoInputMode: "TEXT", supportsEndFrame: true,
    supportedImageAspectRatios: [], supportedImageResolutions: [], supportedImageQualities: [], supportsTransparentBackground: false,
    supportsImageMask: false, mappingSha256: "synthetic", settings: { defaultParameters: { videoResolution: "768p" } } }] }], defaults: [] };
window.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.href);
  let value: unknown;
  if (url.pathname.endsWith("/auth/csrf")) value = { headerName: "X-XSRF-TOKEN", token: "synthetic-token" };
  else if (url.pathname.endsWith("/media-connections")) value = mediaSettings;
  else if (url.pathname.endsWith("/media-draft")) value = videoDraft;
  else if (url.pathname.endsWith("/synthetic-approval") && init?.method === "PUT") {
    const revision = JSON.parse(String(init.body)) as { parameters: MediaDraft["parameters"]; durationSeconds: number; capabilityId: string };
    videoDraft.parameters = revision.parameters; videoDraft.durationSeconds = revision.durationSeconds; videoDraft.version++;
    mediaApproval = { ...mediaApproval, version: mediaApproval.version + 1, outputs: mediaApproval.outputs.map(output => ({ ...output,
      draftVersion: videoDraft.version, preview: { ...output.preview, parameters: revision.parameters, durationSeconds: revision.durationSeconds,
        capabilityId: revision.capabilityId, mediaPricing: { amount: "2", currency: "CNY", unit: "SECOND" } } })) };
    value = mediaApproval;
  }
  else if (url.pathname.endsWith("/conversations")) value = { items: [{ id: "synthetic-conversation",
    projectId: agent.projectId, agentInstanceId: agent.id, title: "午后城市 · 创作讨论", version: 0,
    turnCount: runs.length, createdAt: now, updatedAt: now }], currentConversationId: "synthetic-conversation", nextCursor: null };
  else if (url.pathname.endsWith("/skill-binding")) value = { agentId: agent.id, agentVersion: 0, skills: [] };
  else if (url.pathname.endsWith("/runs")) value = { items: history && !url.searchParams.has("cursor") ? runs.slice(6) : runs.slice(0, 6),
    nextCursor: history && !url.searchParams.has("cursor") ? "synthetic-older" : null };
  else if (url.pathname.endsWith("/tasks")) {
    const runId = url.pathname.split("/").at(-2);
    value = [{ id: `task-${runId}`, kind: "AGENT_TURN", status: "SUCCEEDED", runId, attemptNo: 1,
      input: { stepIndex: 0 }, output: { assistantText: "### 可以从这里继续\n\n画布中已有故事梗概、角色参考和镜头方向。\n\n1. 先明确主角的目标与冲突。\n2. 让画面沿着情绪变化推进。\n3. 确认方案后，再提出需要生成的媒体批次。\n\n| 内容 | 下一步 |\n| --- | --- |\n| 故事梗概 | 精炼人物动机 |\n| 视觉参考 | 保持色彩与光线一致 |" },
      createdAt: now, updatedAt: now }];
  } else if (url.pathname.endsWith("/actions") || url.pathname.endsWith("/media-approvals")) value = approvalMode ? [mediaApproval] : [];
  else throw new Error(`Unexpected synthetic request: ${url.pathname}`);
  return new Response(JSON.stringify(value), { headers: { "Content-Type": "application/json" } });
};
function ApprovalFixture() {
  const approval = useQuery({ queryKey: ["run-media-approvals", agent.projectId, "synthetic-run"],
    queryFn: async () => [mediaApproval], initialData: [mediaApproval] });
  return <AgentMediaApprovalCard projectId={agent.projectId} runId="synthetic-run" approval={approval.data[0]!} />;
}
function Fixture() {
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState(false);
  if (approvalMode) return <main style={{ maxWidth: 640, margin: "auto", padding: 16 }}>
    <ApprovalFixture />
  </main>;
  return <main style={{ height: "100dvh", position: "relative", overflow: "hidden", background: "var(--ui-bg)", color: "var(--ui-text)" }}>
    <header style={{ height: 64, padding: "18px 28px", fontSize: 14 }}>Agenvas <span style={{ color: "var(--ui-text-muted)", marginLeft: 24 }}>午后城市 · 合成画布</span></header>
    <div style={{ position: "absolute", left: 38, top: 130, width: 310, padding: 24, border: "1px solid var(--ui-border)", borderRadius: 14, background: "var(--ui-surface)" }}>
      <p style={{ color: "var(--ui-text-muted)", fontSize: 11, marginBottom: 12 }}>故事梗概 · TEXT</p>
      <h2 style={{ fontSize: 19, marginBottom: 12 }}>一场不期而遇</h2>
      <p style={{ fontSize: 13, lineHeight: 1.8, color: "var(--ui-text-secondary)" }}>城市午后，陌生人的一次偶遇，让两个平行的日常交汇。</p>
      <Button id="synthetic-canvas-node" variant="outline" size="sm" aria-pressed={selected} style={{ marginTop: 20 }} onClick={() => setSelected(value => !value)}>选择画布节点</Button>
    </div>
    <AgentChatDock open={open} onOpenChange={setOpen} data={{ agent, projectId: agent.projectId,
      activeRun: null, canvasNodeCount: 3, selectedNodeCount: selected ? 1 : 0,
      onUpdateAgent: () => undefined, updatingAgent: false }} />
  </main>;
}
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Fixture /></QueryClientProvider>);
