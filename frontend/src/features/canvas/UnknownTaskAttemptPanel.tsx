import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { WarningCircle } from "@phosphor-icons/react";
import "./AgentChatPanels.css";
import { useId, useRef, useState } from "react";
import { createManualUnknownAttempt, listProviderAttempts, reconcileUnknownTask } from "../../shared/api/client";

/** Fetches only on demand; a request key is an audit clue, never proof of acceptance. */
export function UnknownTaskAttemptPanel({ projectId, taskId, taskVersion, planned,
  direct = false, cancelRequested, onChanged }: {
  projectId: string; taskId: string; taskVersion: number; planned: boolean;
  direct?: boolean; cancelRequested: boolean;
  onChanged?: () => void | Promise<void>;
}) {
  const ledgerId = useId();
  const [open, setOpen] = useState(false);
  const [riskAccepted, setRiskAccepted] = useState(false);
  const retryKey = useRef<string | null>(null);
  const queryClient = useQueryClient();
  const attempts = useQuery({
    queryKey: ["provider-attempts", projectId, taskId],
    queryFn: () => listProviderAttempts(projectId, taskId),
    enabled: open,
  });
  const reconcile = useMutation({
    mutationFn: () => reconcileUnknownTask(projectId, taskId),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["snapshot", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["provider-attempts", projectId, taskId] }),
        queryClient.invalidateQueries({ queryKey: ["run-tasks", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["run-history-tasks", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["direct-media-tasks", projectId] }),
        onChanged?.(),
      ]);
    },
  });
  const newAttempt = useMutation({
    mutationFn: () => {
      retryKey.current ??= crypto.randomUUID();
      return createManualUnknownAttempt(projectId, taskId, retryKey.current, {
        expectedTaskVersion: taskVersion,
        riskAcknowledgement: "ACCEPT_POSSIBLE_DUPLICATE_COST",
      });
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["snapshot", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["provider-attempts", projectId, taskId] }),
        queryClient.invalidateQueries({ queryKey: ["run-tasks", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["run-history-tasks", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["project-usage", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["direct-media-tasks", projectId] }),
        onChanged?.(),
      ]);
    },
  });
  const replaced = attempts.data?.some((attempt) => attempt.replacementTaskId);

  return <div className="agent-chat-panel agent-chat-unknown">
    <p className="agent-chat-panel-notice-title"><WarningCircle aria-hidden="true" />请求结果待核实</p>
    <p>可能已在外部开始执行；系统不会自动重复提交。</p>
    <button aria-expanded={open} aria-controls={ledgerId} className="agent-chat-panel-secondary" onClick={() => setOpen(!open)} type="button">
      {open ? "收起提交账本" : "查看提交账本并处理重试"}
    </button>
    {open ? <div className="agent-chat-unknown-ledger" id={ledgerId}>
      <p>关联键用于在原 Provider 实例人工核对；它不能证明请求已受理，也不能作为安全重试许可。自动核对只查询原 ID，不会新建生成。</p>
      {attempts.isPending ? <p>正在读取提交账本…</p> : null}
      {attempts.error ? <p className="text-red-700" role="alert">提交账本读取失败。</p> : null}
      {attempts.error ? <button className="agent-chat-panel-text-button" onClick={() => void attempts.refetch()} type="button">重试读取</button> : null}
      {attempts.data?.length === 0 ? <p>没有已保存的提交尝试。</p> : null}
      <ul className="space-y-1">
        {attempts.data?.map((attempt) => <li className="agent-chat-attempt" key={attempt.id}>
          <span className="font-medium">{attempt.status}</span>
          <span className="block">提交关联键：{attempt.requestKey}</span>
          {attempt.candidateRequestId ? <span className="block">可核对的候选 Provider ID：{attempt.candidateRequestId}（尚未证明已受理）</span> : null}
          {attempt.providerRequestId ? <span className="block">原 Provider 请求 ID：{attempt.providerRequestId}</span> :
            <span className="block">未保存 Provider 请求 ID；外部受理状态仍未知。</span>}
          {attempt.replacementTaskId ? <span className="block">已人工创建新尝试：{attempt.replacementTaskId}；原任务仍保留 UNKNOWN。</span> : null}
          <span className="block">记录时间：<time dateTime={attempt.createdAt}>{new Date(attempt.createdAt).toLocaleString()}</time></span>
        </li>)}
      </ul>
      {!cancelRequested && attempts.data?.some((attempt) => attempt.reconcilable) ?
        <button className="agent-chat-panel-secondary" disabled={reconcile.isPending} onClick={() => reconcile.mutate()} type="button">
          {reconcile.isPending ? "正在核对原请求…" : "查询原 Provider 请求"}
        </button> : null}
      {reconcile.data?.outcome === "NO_EVIDENCE" ? <p role="status">原实例暂未找到该 ID；任务仍为 UNKNOWN，不能据此重新生成。</p> : null}
      {reconcile.data?.outcome === "RESUMED" ? <p role="status">已找到原请求，恢复对原 ID 的轮询；没有重新提交生成。</p> : null}
      {reconcile.error ? <p className="text-red-700" role="alert">原请求核对失败；任务仍未确认，请检查 Provider 配置或稍后重试核对。</p> : null}
      {(planned || direct) && !cancelRequested && attempts.data && !replaced && !newAttempt.data ? <div className="agent-chat-unknown-risk">
        <label className="flex items-start gap-2"><input checked={riskAccepted} onChange={(event) => setRiskAccepted(event.target.checked)} type="checkbox" />
          <span>我理解原请求可能已执行；创建新尝试可能重复产生费用。原任务、提交账本和用量预留仍保留，取消不代表外部停止或退款。</span>
        </label>
        <button className="agent-chat-panel-danger" disabled={!riskAccepted || newAttempt.isPending}
          onClick={() => newAttempt.mutate()} type="button">
          {newAttempt.isPending ? "正在创建新尝试…" : "明确风险后创建新尝试"}
        </button>
      </div> : null}
      {newAttempt.data ? <p role="status">新尝试已创建：{newAttempt.data.id}。原任务仍为 UNKNOWN。</p> : null}
      {newAttempt.error ? <p className="text-red-700" role="alert">新尝试未创建。请刷新任务状态、计划和预算后重试。</p> : null}
    </div> : null}
  </div>;
}
