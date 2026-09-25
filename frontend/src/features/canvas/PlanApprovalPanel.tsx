import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import {
  approveExecutionPlan,
  cancelRun,
  getShotKeyframeSelection,
  listExecutionPlans,
  listMediaCapabilityCandidates,
  rejectExecutionPlan,
  reviseExecutionPlanStep,
  type ExecutionPlan,
  type ReviseExecutionPlanStepRequest,
} from "../../shared/api/client";

/** Shows the frozen, server-owned approval scope; the buttons never infer approval from model text. */
export function PlanApprovalPanel({ projectId, runId }: { projectId: string; runId: string }) {
  const queryClient = useQueryClient();
  const plans = useQuery({
    queryKey: ["plans", projectId, runId],
    queryFn: () => listExecutionPlans(projectId, runId),
  });
  const pending = plans.data?.find((plan) => plan.status === "PENDING" || plan.status === "NEEDS_INPUT");
  const [confirmed, setConfirmed] = useState<string[]>([]);
  useEffect(() => { setConfirmed([]); }, [pending?.id, pending?.planHash]);
  // Approval creates exactly one media Task per step; never advertise a separate estimate as the count.
  const imageCount = pending?.steps.filter((step) => step.kind === "IMAGE_GENERATION").length ?? 0;
  const videoCount = pending?.steps.filter((step) => step.kind === "VIDEO_GENERATION").length ?? 0;
  const countMatchesPlan = pending !== undefined && pending.steps.length > 0
    && imageCount + videoCount === pending.steps.length
    && (pending.stage === "IMAGE" ? imageCount === pending.steps.length : videoCount === pending.steps.length)
    && pending.estimate.imageCount === imageCount
    && pending.estimate.videoCount === videoCount;
  const approve = useMutation({
    mutationFn: (plan: ExecutionPlan) => approveExecutionPlan(projectId, plan.id,
      plan.planHash, confirmed),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["plans", projectId, runId] });
      void queryClient.invalidateQueries({ queryKey: ["snapshot", projectId] });
    },
  });
  const revise = useMutation({
    mutationFn: ({ stepKey, capabilityId, inputPatch }: {
      stepKey: string; capabilityId?: string; inputPatch: ReviseExecutionPlanStepRequest["inputPatch"];
    }) => {
      if (!pending) throw new Error("待审计划已变化");
      return reviseExecutionPlanStep(projectId, pending.id, stepKey, {
        expectedPlanHash: pending.planHash, capabilityId: capabilityId ?? null, inputPatch,
      });
    },
    onSuccess: (revised) => {
      setConfirmed([]);
      queryClient.setQueryData<ExecutionPlan[]>(["plans", projectId, runId], (previous) =>
        [revised, ...(previous ?? []).map((plan) => plan.id === pending?.id
          ? { ...plan, status: "STALE" as const } : plan)]);
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
  const busy = approve.isPending || reject.isPending || cancel.isPending || revise.isPending;
  const allConfirmed = pending?.status === "PENDING" && pending.steps.length === confirmed.length
    && pending.steps.every((step) => confirmed.includes(step.stepKey));

  return (
    <section aria-label="待审批执行计划" className="col-span-full border-b border-amber-300 bg-amber-50 px-6 py-4 text-sm text-amber-950">
      {plans.isPending ? <p role="status">正在读取待审批计划…</p> : null}
      {plans.error ? <p role="alert">计划读取失败：{plans.error.message}</p> : null}
      {plans.isSuccess && !pending ? <p>暂无可审批计划，请刷新项目或检查 Run 状态。</p> : null}
      {pending ? (
        <div className="max-w-4xl space-y-3">
          <h2 className="text-base font-semibold">{pending.stage === "IMAGE" ? "关键帧图片计划" : "视频计划"}待确认</h2>
          <p>{pending.objective}</p>
          <p>计划修订 {pending.revision} · {pending.steps.length} 个生成步骤</p>
          <p>本次将创建图片任务 {imageCount} 个、视频任务 {videoCount} 个；成本来源：{String(pending.estimate.costSource ?? "未知")}。{pending.estimate.costSource === "MOCK_UNPRICED" ? "Mock 只产生演示素材。" : "实际渠道费用未实测。"}</p>
          {pending.status === "NEEDS_INPUT" ? <p role="alert">计划缺少必要关键帧，请先选择并补齐输入。</p> : null}
          {!countMatchesPlan ? <p role="alert">计划步骤与预计生成数量不一致，无法确认。请重新生成计划。</p> : null}
          <ol className="list-inside list-decimal space-y-1">
            {pending.steps.map((step) => <PlanStepReview key={step.stepKey}
              projectId={projectId} runId={runId} plan={pending} step={step}
              confirmed={confirmed.includes(step.stepKey)} busy={busy}
              onConfirm={(checked) => setConfirmed((previous) => checked
                ? [...previous, step.stepKey] : previous.filter((key) => key !== step.stepKey))}
              onRevise={(capabilityId, inputPatch) => revise.mutate({
                stepKey: step.stepKey, capabilityId, inputPatch,
              })} />)}
          </ol>
          <p className="break-all text-xs">审批哈希：{pending.planHash}</p>
          <div className="flex gap-3">
            <button className="primary-button" disabled={busy || !countMatchesPlan || !allConfirmed} onClick={() => approve.mutate(pending)} type="button">{approve.isPending ? "正在确认…" : "确认执行此计划"}</button>
            <button className="secondary-button" disabled={busy} onClick={() => reject.mutate(pending)} type="button">{reject.isPending ? "正在拒绝…" : "返回修改"}</button>
            <button className="secondary-button" disabled={busy} onClick={() => cancel.mutate()} type="button">{cancel.isPending ? "正在取消…" : "取消本次 Run"}</button>
          </div>
          {approve.error ? <p role="alert">批准失败：{approve.error.message}。请重新读取计划后再试。</p> : null}
          {revise.error ? <p role="alert">修改失败：{revise.error.message}。请重新读取计划后再试。</p> : null}
          {reject.error ? <p role="alert">拒绝失败：{reject.error.message}</p> : null}
          {cancel.error ? <p role="alert">取消失败：{cancel.error.message}。请检查 Run 状态后再试。</p> : null}
        </div>
      ) : null}
    </section>
  );
}

function PlanStepReview({ projectId, runId, plan, step, confirmed, busy, onConfirm, onRevise }: {
  projectId: string;
  runId: string;
  plan: ExecutionPlan;
  step: ExecutionPlan["steps"][number];
  confirmed: boolean;
  busy: boolean;
  onConfirm: (checked: boolean) => void;
  onRevise: (capabilityId: string | undefined,
    inputPatch: ReviseExecutionPlanStepRequest["inputPatch"]) => void;
}) {
  const candidates = useQuery({
    queryKey: ["media-candidates", projectId, plan.id, step.stepKey],
    queryFn: () => listMediaCapabilityCandidates(projectId, plan.id, step.stepKey),
  });
  const missingKeyframe = step.kind === "VIDEO_GENERATION" && !step.imageVersionId;
  const selectedKeyframe = useQuery({
    queryKey: ["keyframe-selection", projectId, runId, step.shotArtifactId],
    queryFn: () => getShotKeyframeSelection(projectId, runId, step.shotArtifactId),
    enabled: missingKeyframe,
    retry: false,
  });
  const current = candidates.data?.find((candidate) =>
    candidate.binding.capabilityId === step.binding?.capabilityId);
  const modelName = step.binding?.adapterId === "OPENAI_GPT_IMAGE_2" ? "gpt-image-2"
    : step.binding?.adapterId === "GOOGLE_NANO_BANANA_2" ? "gemini-3.1-flash-image"
    : step.binding?.adapterId === "ARK_SEEDANCE_2_I2V" ? "doubao-seedance-2-0-260128" : null;
  return <li className="rounded-lg border border-amber-300 bg-white p-3">
    <p className="font-medium">镜头 {step.stepKey}：{String(step.input.prompt ?? "")}</p>
    <p className="mt-1 text-xs">输入版本 {step.shotVersionId}{step.imageVersionId ? `；关键帧版本 ${step.imageVersionId}` : ""}</p>
    <p className="mt-1 text-xs">{current ? `${current.connectionName} / ${current.capabilityName}` : step.binding?.adapterId ?? "历史能力"} · {step.binding?.adapterId ?? "未绑定"} · {step.binding ? `连接 v${step.binding.connectionVersion} / 能力 v${step.binding.capabilityVersion}` : ""} · 已配置、未实测</p>
    {modelName ? <p className="mt-1 text-xs">固定模型 {modelName}{current?.settings.quality
      ? ` · 质量 ${current.settings.quality}` : ""}{step.kind === "VIDEO_GENERATION"
      ? ` · ${String(step.input.durationSeconds)} 秒` : ""} · 费用来源未确认</p> : null}
    <label className="mt-2 block text-xs">生成能力
      <select value={step.binding?.capabilityId ?? ""} disabled={busy || candidates.isPending || !!candidates.error}
        onChange={(event) => onRevise(event.target.value, {})}>
        {!current ? <option value={step.binding?.capabilityId ?? ""} disabled>
          {step.binding ? "原能力已停用或不兼容，请改选" : "未绑定能力"}
        </option> : null}
        {(candidates.data ?? []).map((candidate) => <option key={candidate.binding.capabilityId}
          value={candidate.binding.capabilityId}>{candidate.connectionName} / {candidate.capabilityName}（{candidate.binding.adapterId}）</option>)}
      </select>
    </label>
    {candidates.error ? <p role="alert">可用能力读取失败：{candidates.error.message}</p> : null}
    {missingKeyframe ? <div className="mt-2 text-xs">
      {selectedKeyframe.data ? <button className="secondary-button" type="button" disabled={busy}
        onClick={() => onRevise(undefined, {
          imageArtifactId: selectedKeyframe.data.imageArtifactId,
          imageVersionId: selectedKeyframe.data.imageVersionId,
        })}>使用已选关键帧补齐此步骤</button>
        : <p>请先在关键帧区域为此镜头选图，再补齐本步骤。</p>}
      {selectedKeyframe.error && !(selectedKeyframe.error instanceof Error && "status" in selectedKeyframe.error
        && selectedKeyframe.error.status === 404) ? <p role="alert">关键帧读取失败：{selectedKeyframe.error.message}</p> : null}
    </div> : null}
    <label className="mt-3 flex items-center gap-2 text-xs"><input type="checkbox"
      checked={confirmed} disabled={busy || missingKeyframe || plan.status !== "PENDING"}
      onChange={(event) => onConfirm(event.target.checked)} />确认镜头 {step.stepKey} 的能力、输入和费用来源</label>
  </li>;
}
