import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { listAgentRuns, listExecutionPlans, listRunTasks } from "../../shared/api/client";

/** Read-only, persisted Run history; intentionally renders no model messages or tool arguments. */
export function RunHistoryPanel({ projectId, agentId }: { projectId: string; agentId: string }) {
  const [cursor, setCursor] = useState<string>();
  const [previous, setPrevious] = useState<(string | undefined)[]>([]);
  const [selectedRunId, setSelectedRunId] = useState<string>();
  const history = useQuery({
    queryKey: ["run-history", projectId, agentId, cursor],
    queryFn: () => listAgentRuns(projectId, agentId, cursor),
  });
  const plans = useQuery({
    queryKey: ["run-history-plans", projectId, selectedRunId],
    queryFn: () => listExecutionPlans(projectId, selectedRunId!),
    enabled: Boolean(selectedRunId),
  });
  const tasks = useQuery({
    queryKey: ["run-history-tasks", projectId, selectedRunId],
    queryFn: () => listRunTasks(projectId, selectedRunId!),
    enabled: Boolean(selectedRunId),
  });

  return <section aria-label="运行记录" className="nodrag nowheel mt-3 rounded-lg border border-[var(--line)] p-3 text-xs">
    <h4 className="font-semibold">运行记录</h4>
    {history.isPending ? <p className="mt-2">正在读取记录…</p> : null}
    {history.error ? <p className="mt-2 text-red-700" role="alert">读取记录失败，请重试。</p> : null}
    {history.error ? <button className="node-action mt-2" onClick={() => void history.refetch()} type="button">重试</button> : null}
    {history.data?.items.length === 0 ? <p className="mt-2 text-[var(--muted)]">尚无运行记录。</p> : null}
    <ul className="mt-2 space-y-2">
      {history.data?.items.map((run) => <li className="rounded border border-[var(--line)] p-2" key={run.id}>
        <button aria-expanded={selectedRunId === run.id} className="w-full text-left"
          onClick={() => setSelectedRunId(selectedRunId === run.id ? undefined : run.id)} type="button">
          <span className="font-medium">{run.status}</span> · <time dateTime={run.createdAt}>{new Date(run.createdAt).toLocaleString()}</time>
          <span className="mt-1 block break-words">{run.instruction}</span>
        </button>
        {selectedRunId === run.id ? <div className="mt-2 border-t border-[var(--line)] pt-2">
          <p className="break-all text-[var(--muted)]">Run {run.id}</p>
          {run.completedAt ? <p>完成：<time dateTime={run.completedAt}>{new Date(run.completedAt).toLocaleString()}</time></p> : null}
          {plans.isPending || tasks.isPending ? <p>正在读取计划与任务…</p> : null}
          {plans.error || tasks.error ? <p className="text-red-700" role="alert">计划或任务读取失败。</p> : null}
          {plans.error ? <button className="node-action mt-1" onClick={() => void plans.refetch()} type="button">重试计划</button> : null}
          {tasks.error ? <button className="node-action mt-1" onClick={() => void tasks.refetch()} type="button">重试任务</button> : null}
          {plans.data ? <div className="mt-2">
            <p className="font-medium">审批计划（{plans.data.length}）</p>
            <ul>{plans.data.map((plan) => <li className="break-words" key={plan.id}>
              {plan.stage} · 第 {plan.revision} 版 · {plan.status} · {plan.steps.length} 个步骤
            </li>)}</ul>
          </div> : null}
          {tasks.data ? <div className="mt-2">
            <p className="font-medium">执行任务（{tasks.data.length}）</p>
            <ul>{tasks.data.map((task) => <li className="break-words" key={task.id}>
              {task.kind} · {task.stepKey} · {task.status}{task.errorCode ? ` · ${task.errorCode}` : ""}
            </li>)}</ul>
            {tasks.data.some((task) => task.kind === "IMAGE_GENERATION" ||
              task.kind === "VIDEO_GENERATION") ? <p className="mt-2 text-[var(--muted)]">
              已受理任务的查询或归档技术重试只针对原 Provider 请求，不会因下载失败重新生成。若内容不满意，需要基于当前版本发起新 Run 并重新审批媒体计划；这属于可能产生额外成本的新生成尝试。UNKNOWN 需要在卡片上显式重试，系统不会自动重做。
            </p> : null}
          </div> : null}
        </div> : null}
      </li>)}
    </ul>
    <div className="mt-2 flex gap-2">
      {previous.length ? <button className="node-action" onClick={() => {
        const prior = previous.at(-1);
        setPrevious(previous.slice(0, -1));
        setCursor(prior);
        setSelectedRunId(undefined);
      }} type="button">上一页</button> : null}
      {history.data?.nextCursor ? <button className="node-action" onClick={() => {
        setPrevious([...previous, cursor]);
        setCursor(history.data.nextCursor ?? undefined);
        setSelectedRunId(undefined);
      }} type="button">下一页</button> : null}
    </div>
  </section>;
}
