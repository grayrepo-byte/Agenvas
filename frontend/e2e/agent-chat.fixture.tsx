import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { createRoot } from "react-dom/client";
import { AgentChatDock } from "../src/features/canvas/AgentChatDock";
import type { Agent } from "../src/shared/api/client";
import { setLocale } from "../src/shared/i18n";
import { Button } from "../src/shared/ui/primitives/button";
import { Select } from "../src/shared/ui/Select";
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
  contextSnapshot: {}, policySnapshot: {}, createdAt: now, updatedAt: now })) : [];
window.fetch = async input => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.href);
  let value: unknown;
  if (url.pathname.endsWith("/conversations")) value = { items: [{ id: "synthetic-conversation",
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
  } else if (url.pathname.endsWith("/actions") || url.pathname.endsWith("/media-approvals")) value = [];
  else throw new Error(`Unexpected synthetic request: ${url.pathname}`);
  return new Response(JSON.stringify(value), { headers: { "Content-Type": "application/json" } });
};
function Fixture() {
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState(false);
  return <main style={{ height: "100dvh", position: "relative", overflow: "hidden", background: "var(--ui-bg)", color: "var(--ui-text)" }}>
    <header style={{ height: 64, padding: "18px 28px", fontSize: 14 }}>Agenvas <span style={{ color: "var(--ui-text-muted)", marginLeft: 24 }}>午后城市 · 合成画布</span></header>
    <div style={{ position: "absolute", left: 38, top: 130, width: 310, padding: 24, border: "1px solid var(--ui-border)", borderRadius: 14, background: "var(--ui-surface)" }}>
      <p style={{ color: "var(--ui-text-muted)", fontSize: 11, marginBottom: 12 }}>故事梗概 · TEXT</p>
      <h2 style={{ fontSize: 19, marginBottom: 12 }}>一场不期而遇</h2>
      <p style={{ fontSize: 13, lineHeight: 1.8, color: "var(--ui-text-secondary)" }}>城市午后，陌生人的一次偶遇，让两个平行的日常交汇。</p>
      <Button id="synthetic-canvas-node" variant="outline" size="sm" aria-pressed={selected} style={{ marginTop: 20 }} onClick={() => setSelected(value => !value)}>选择画布节点</Button>
    </div>
    <AgentChatDock open={open} onOpenChange={setOpen} controls={<div style={{ display: "flex", gap: 6 }}>
      <Select aria-label="切换 Agent" value={agent.id} onChange={() => undefined} variant="ghost" density="compact">
        <option value={agent.id}>{agent.name}</option>
      </Select>
      <Button variant="ghost" size="icon-sm" aria-label="从预设新建 Agent">+</Button>
    </div>} data={{ agent, projectId: agent.projectId,
      activeRun: null, outputCount: 0, canvasNodeCount: 3, selectedNodeCount: selected ? 1 : 0,
      onShowOutputs: () => undefined, onUpdateAgent: () => undefined, updatingAgent: false }} />
  </main>;
}
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Fixture /></QueryClientProvider>);
