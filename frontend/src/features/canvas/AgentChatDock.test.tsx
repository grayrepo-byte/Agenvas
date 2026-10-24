import { useState } from "react";
import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent,render,screen,waitFor,within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http,HttpResponse } from "msw";
import { beforeEach,describe,expect,it,vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Agent } from "../../shared/api/client";
import { server } from "../../test/server";
import { AgentChatDock } from "./AgentChatDock";
import type { AgentChatPanelData } from "./AgentChatPanel";

const NOW = "2026-10-20T10:00:00Z";
const agent = (id: string): Agent => ({ id,projectId: "project-1",name: id,instruction: `${id} instruction`,profileKey: "creator",
  profileVersion: 1,version: 1,createdAt: NOW,updatedAt: NOW,});
const data = (id = "director"): AgentChatPanelData => ({ agent: agent(id),projectId: "project-1",activeRun: null,
  canvasNodeCount: 6,selectedNodeCount: 2,onUpdateAgent: vi.fn(),updatingAgent: false });
let sessionReads = 0;
beforeEach(() => {
  sessionReads = 0;
  server.use(http.get("/api/v1/projects/:projectId/agents/:agentId/conversations", () => {
    sessionReads++;
    return HttpResponse.json({ items: [],nextCursor: null,currentConversationId: null });
  }));
});
function mount(initial = data(), initialOpen = false) {
  const client = createQueryClient();
  function Host() {
    const [open,setOpen] = useState(initialOpen);
    return <><button type="button">画布操作</button><AgentChatDock data={initial} open={open} onOpenChange={setOpen} /></>;
  }
  render(<QueryClientProvider client={client}><Host /></QueryClientProvider>);
}
async function openDirector() {
  await userEvent.setup().click(screen.getByRole("button", { name: "打开创作导演" }));
  const input = await screen.findByRole("textbox", { name: "本次任务" });
  await waitFor(() => expect(input).toBeEnabled());
  return input;
}

describe("AgentChatDock", () => {
  it("loads conversations only after opening and preserves the composer while minimizing and resizing", async () => {
    mount();
    expect(sessionReads).toBe(0);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    const input = await openDirector();
    await userEvent.setup().type(input, "这是还没发送的创作想法");
    await userEvent.setup().click(screen.getByRole("button", { name: "展开对话" }));
    expect(screen.getByRole("textbox", { name: "本次任务" })).toBe(input);
    expect(screen.getByRole("textbox", { name: "本次任务" })).toHaveValue("这是还没发送的创作想法");
    await userEvent.setup().click(screen.getByRole("button", { name: "收起创作导演" }));
    expect(screen.queryByRole("textbox", { name: "本次任务" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "打开创作导演" })).toHaveFocus();
    await openDirector();
    expect(screen.getByRole("textbox", { name: "本次任务" })).toBe(input);
    expect(input).toHaveValue("这是还没发送的创作想法");
  });

  it("scopes Escape to the focused chat and leaves the canvas interactive", async () => {
    mount();
    const input = await openDirector();
    const canvas = screen.getByRole("button", { name: "画布操作" });
    await userEvent.setup().click(canvas);
    fireEvent.keyDown(canvas, { key: "Escape" });
    expect(screen.getByRole("dialog", { name: "创作导演" })).toBeInTheDocument();
    input.focus();
    fireEvent.keyDown(input, { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("preserves the director's unsaved configuration and task draft when reopening settings", async () => {
    const onUpdateAgent = vi.fn();
    mount({ ...data(),onUpdateAgent });
    await userEvent.setup().type(await openDirector(), "导演的草稿");
    await userEvent.setup().click(screen.getByRole("button", { name: "Agent 设置" }));
    await userEvent.setup().clear(screen.getByRole("textbox", { name: "名称" }));
    await userEvent.setup().type(screen.getByRole("textbox", { name: "名称" }), "导演的未保存名称");
    await userEvent.setup().click(screen.getByRole("button", { name: "收起创作导演" }));
    await userEvent.setup().click(screen.getByRole("button", { name: "打开创作导演" }));
    expect(screen.getByRole("textbox", { name: "名称" })).toHaveValue("导演的未保存名称");
    expect(screen.queryByRole("textbox", { name: "本次任务" })).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "保存配置" }));
    expect(onUpdateAgent).toHaveBeenCalledWith(agent("director"),"导演的未保存名称","director instruction");
    await userEvent.setup().click(screen.getByRole("button", { name: "聊天" }));
    expect(screen.getByRole("textbox", { name: "本次任务" })).toHaveValue("导演的草稿");
  });

  it("returns focus to the launcher when a programmatic opening had no focusable predecessor", async () => {
    mount();
    fireEvent.click(screen.getByRole("button", { name: "打开创作导演" }));
    const input = await screen.findByRole("textbox", { name: "本次任务" });
    await waitFor(() => expect(input).toBeEnabled());
    input.focus();
    fireEvent.keyDown(input, { key: "Escape" });
    expect(screen.getByRole("button", { name: "打开创作导演" })).toHaveFocus();
  });

  it("places a quick suggestion into an editable draft without submitting a run", async () => {
    mount();
    const input = await openDirector();
    await userEvent.setup().click(screen.getByRole("button", { name: /^梳理当前画布/ }));
    expect(input).toHaveValue("先查看当前画布，梳理已有内容和素材，告诉我有哪些可以推进的创作方向。");
    expect(input).toHaveFocus();
    expect(screen.getByRole("button", { name: "发送" })).toBeEnabled();
    expect(screen.getByText("画布 · 6 个节点")).toBeInTheDocument();
    expect(screen.getByText("已选 2")).toBeInTheDocument();
  });

  it("keeps the director available while showing a recoverable load error", async () => {
    const retry = vi.fn();
    render(<QueryClientProvider client={createQueryClient()}><AgentChatDock data={data()} open onOpenChange={vi.fn()} error={new Error("failed")} onRetry={retry} /></QueryClientProvider>);
    const alert = screen.getByRole("alert");
    await userEvent.setup().click(within(alert).getByRole("button", { name: "重试" }));
    expect(retry).toHaveBeenCalledOnce();
    expect(screen.getByRole("textbox", { name: "本次任务" })).toBeInTheDocument();
  });
});
