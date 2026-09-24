import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  approveExecutionPlan,
  cancelRun,
  listExecutionPlans,
  rejectExecutionPlan,
  type ExecutionPlan,
} from "../../shared/api/client";

/** Shows the frozen, server-owned approval scope; the buttons never infer approval from model text. */
export function PlanApprovalPanel({ projectId, runId }: { projectId: string; runId: string }) {
  const queryClient = useQueryClient();
  const plans = useQuery({
    queryKey: ["plans", projectId, runId],
    queryFn: () => listExecutionPlans(projectId, runId),
  });
  const pending = plans.data?.find((plan) => plan.status === "PENDING");
  // Approval creates exactly one media Task per step; never advertise a separate estimate as the count.
  const imageCount = pending?.steps.filter((step) => step.kind === "IMAGE_GENERATION").length ?? 0;
  const videoCount = pending?.steps.filter((step) => step.kind === "VIDEO_GENERATION").length ?? 0;
  const countMatchesPlan = pending !== undefined && pending.steps.length > 0
    && imageCount + videoCount === pending.steps.length
    && (pending.stage === "IMAGE" ? imageCount === pending.steps.length : videoCount === pending.steps.length)
    && pending.estimate.imageCount === imageCount
    && pending.estimate.videoCount === videoCount;
  const approve = useMutation({
    mutationFn: (plan: ExecutionPlan) => approveExecutionPlan(projectId, plan.id, plan.planHash),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["plans", projectId, runId] });
      void queryClient.invalidateQueries({ queryKey: ["snapshot", projectId] });
    },
  });
  const reject = useMutation({
    mutationFn: (plan: ExecutionPlan) => rejectExecutionPlan(projectId, plan.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["plans", projectId, runId] });
      void queryClient.invalidateQueries({ queryKey: ["snapshot", projectId] });
    },
  });
  const cancel = useMutation({
    mutationFn: () => cancelRun(projectId, runId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["plans", projectId, runId] });
      void queryClient.invalidateQueries({ queryKey: ["snapshot", projectId] });
    },
  });
  const busy = approve.isPending || reject.isPending || cancel.isPending;

  return (
    <section aria-label="待审批执行计划" className="col-span-full border-b border-amber-300 bg-amber-50 px-6 py-4 text-sm text-amber-950">
      {plans.isPending ? <p role="status">正在读取待审批计划…</p> : null}
      {plans.error ? <p role="alert">计划读取失败：{plans.error.message}</p> : null}
      {plans.isSuccess && !pending ? <p>暂无可审批计划，请刷新项目或检查 Run 状态。</p> : null}
      {pending ? (
        <div className="max-w-4xl space-y-3">
          <h2 className="text-base font-semibold">{pending.stage === "IMAGE" ? "关键帧图片计划" : "视频计划"}待确认</h2>
          <p>{pending.objective}</p>
          <p>计划修订 {pending.revision} · {pending.steps.length} 个生成步骤 · 工作流 {pending.workflowVersion} · Provider 配置版本 {pending.providerConfigVersion}</p>
          <p>本次将创建图片任务 {imageCount} 个、视频任务 {videoCount} 个；成本来源：{String(pending.estimate.costSource ?? "未知")}。Mock 模式不产生真实媒体或费用。</p>
          {!countMatchesPlan ? <p role="alert">计划步骤与预计生成数量不一致，无法确认。请重新生成计划。</p> : null}
          <ol className="list-inside list-decimal space-y-1">
            {pending.steps.map((step) => (
              <li key={step.stepKey}>镜头 {step.stepKey}：{String(step.input.prompt ?? "")}；输入版本 {step.shotVersionId}{step.imageVersionId ? `；关键帧版本 ${step.imageVersionId}` : ""}</li>
            ))}
          </ol>
          <p className="break-all text-xs">审批哈希：{pending.planHash}</p>
          <div className="flex gap-3">
            <button className="primary-button" disabled={busy || !countMatchesPlan} onClick={() => approve.mutate(pending)} type="button">{approve.isPending ? "正在确认…" : "确认执行此计划"}</button>
            <button className="secondary-button" disabled={busy} onClick={() => reject.mutate(pending)} type="button">{reject.isPending ? "正在拒绝…" : "返回修改"}</button>
            <button className="secondary-button" disabled={busy} onClick={() => cancel.mutate()} type="button">{cancel.isPending ? "正在取消…" : "取消本次 Run"}</button>
          </div>
          {approve.error ? <p role="alert">批准失败：{approve.error.message}。请重新读取计划后再试。</p> : null}
          {reject.error ? <p role="alert">拒绝失败：{reject.error.message}</p> : null}
          {cancel.error ? <p role="alert">取消失败：{cancel.error.message}。请检查 Run 状态后再试。</p> : null}
        </div>
      ) : null}
    </section>
  );
}
