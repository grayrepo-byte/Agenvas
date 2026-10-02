import { CheckCircle, Clock, WarningCircle } from "@phosphor-icons/react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";
import { ApiError, decideRunMediaApproval, type AgentMediaApproval, type AgentMediaApprovalDecision } from "../../shared/api/client";
import { getFormatLocale, t, useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { AgentChatApproval } from "./AgentChatPrimitives";
import { CreativeSkillSource } from "../skills/CreativeSkillSource";
import { taskErrorDetail } from "./taskErrorMessages";

const STATUS_LABELS: Record<AgentMediaApproval["status"], () => string> = {
  PENDING: () => t("agent.approval.pending"), APPROVED: () => t("agent.approval.approved"),
  SUCCEEDED: () => t("agent.approval.succeeded"), FAILED: () => t("agent.approval.failed"),
  REJECTED: () => t("agent.approval.rejected"), EXPIRED: () => t("agent.approval.expired"),
  CANCELED: () => t("agent.approval.canceled"),
};
const KIND_LABELS: Record<string, () => string> = {
  IMAGE: () => t("agent.run.generateImage"), IMAGE_GENERATION: () => t("agent.run.generateImage"),
  AUDIO: () => t("agent.run.generateAudio"), AUDIO_GENERATION: () => t("agent.run.generateAudio"),
  VIDEO: () => t("media.generateVideo"), VIDEO_GENERATION: () => t("media.generateVideo"),
};
const RESULT_LABELS: Record<string, () => string> = {
  SUCCEEDED: () => t("common.succeeded"), FAILED: () => t("common.failed"),
  UNKNOWN: () => t("tasks.status.unknown"), CANCELED: () => t("common.canceled"),
  BLOCKED: () => t("tasks.status.blocked"), READY: () => t("agent.status.waiting"),
  RUNNING: () => t("tasks.status.running"), SUBMITTING: () => t("tasks.status.running"),
  WAITING_PROVIDER: () => t("tasks.status.running"), PENDING: () => t("agent.status.waiting"),
};
const STATUS_ICON_SIZE = 15;

function record(value: unknown): Readonly<Record<string, unknown>> {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? value as Readonly<Record<string, unknown>> : {};
}
function text(value: unknown): string { return typeof value === "string" ? value : ""; }
function publicValue(value: unknown): string {
  if (typeof value === "string" || typeof value === "number") return String(value);
  if (typeof value === "boolean") return value ? t("agent.approval.enabled") : t("agent.approval.disabled");
  if (Array.isArray(value)) return value.map(publicValue).filter(Boolean).join(", ");
  return Object.entries(record(value)).filter(([key]) => !/credential|apiKey|endpoint|authorization|secret/i.test(key))
    .map(([key, item]) => `${key}: ${publicValue(item)}`).join(" · ");
}
function priceLabel(preview: Readonly<Record<string, unknown>>): string {
  const price = record(preview.mediaPricing);
  const amount = text(price.amount);
  const currency = text(price.currency);
  if (preview.priceUnknown === true || !amount || !currency) return t("media.pricing.unknown");
  return t("agent.approval.price", { "0": currency, "1": amount, "2": text(price.unit) });
}

/** Review only the frozen safe summary; neither arbitrary Task JSON nor Provider receipts reach this view. */
export function AgentMediaApprovalCard({ projectId, runId, approval, disabled = false }: {
  projectId: string;
  runId: string;
  approval: AgentMediaApproval;
  disabled?: boolean;
}) {
  useLocale();
  const client = useQueryClient();
  const pending = useRef(false);
  const decisionKeys = useRef(new Map<string, string>());
  const queryKey = ["run-media-approvals", projectId, runId];
  const decision = useMutation({
    mutationFn: ({ body, key }: { body: AgentMediaApprovalDecision; key: string }) =>
      decideRunMediaApproval(projectId, runId, approval.id, body, key),
    onSuccess: (updated) => {
      client.setQueryData<AgentMediaApproval[]>(queryKey, (previous) =>
        previous?.map((item) => item.id === updated.id ? updated : item));
    },
    onSettled: async () => {
      pending.current = false;
      await Promise.all([
        client.invalidateQueries({ queryKey }),
        client.invalidateQueries({ queryKey: ["run-history-tasks", projectId, runId] }),
        client.invalidateQueries({ queryKey: ["run-actions", projectId, runId] }),
        client.invalidateQueries({ queryKey: ["conversation-runs", projectId] }),
        client.invalidateQueries({ queryKey: ["snapshot", projectId] }),
        client.invalidateQueries({ queryKey: ["project-usage", projectId] }),
      ]);
    },
  });
  const submit = (choice: AgentMediaApprovalDecision["decision"]) => {
    if (pending.current || disabled || approval.status !== "PENDING") return;
    const operation = `${approval.version}:${choice}`;
    let key = decisionKeys.current.get(operation);
    if (!key) { key = crypto.randomUUID(); decisionKeys.current.set(operation, key); }
    pending.current = true;
    decision.mutate({ body: { expectedVersion: approval.version, decision: choice }, key });
  };
  const taskResults = Array.isArray(approval.result?.tasks) ? approval.result.tasks.map(record) : [];
  const unknown = taskResults.some((task) => task.status === "UNKNOWN");
  const reviewing = approval.status === "PENDING";
  const Icon = reviewing || approval.status === "APPROVED" ? Clock
    : approval.status === "SUCCEEDED" ? CheckCircle : WarningCircle;
  const status = STATUS_LABELS[approval.status]();

  return <AgentChatApproval title={t("agent.approval.title", { "0": approval.outputs.length })}
    description={reviewing ? t("agent.approval.reviewHint") : undefined}
    className={`agent-media-approval agent-media-approval--${approval.status.toLowerCase()}`}
    footer={reviewing ? <div className="agent-media-approval__decision">
      <p>{t("agent.approval.expiresAt", { "0": new Date(approval.expiresAt).toLocaleString(getFormatLocale()) })}</p>
      <div className="agent-media-approval__buttons">
        <Button variant="ghost" size="sm" className="agent-chat-panel-secondary" type="button"
          disabled={disabled || decision.isPending} onClick={() => submit("REJECT")}>{t("agent.approval.reject")}</Button>
        <Button variant="ghost" size="sm" className="agent-chat-panel-primary" type="button"
          disabled={disabled || decision.isPending} onClick={() => submit("APPROVE")}>{decision.isPending ? t("agent.approval.submitting") : t("agent.approval.approve")}</Button>
      </div>
    </div> : undefined}>
    <p className="agent-media-approval__status" role="status"><Icon size={STATUS_ICON_SIZE} aria-hidden="true" />{status}</p>
    {approval.outputs.map((output, index) => {
      const preview = record(output.preview);
      const parameters = Object.entries(record(preview.parameters))
        .filter(([key]) => !/credential|apiKey|endpoint|authorization|secret/i.test(key));
      const inputs = Array.isArray(preview.mediaInputs) ? preview.mediaInputs.map(record) : [];
      return <details key={output.canvasItemId} className="agent-media-approval__output" open={reviewing && approval.outputs.length === 1}>
        <summary><span>{index + 1}. {output.title}</span><small>{KIND_LABELS[output.kind]?.()}</small></summary>
        <CreativeSkillSource source={preview.creativeSkill} />
        <p className="agent-media-approval__prompt">{text(preview.prompt) || t("agent.approval.promptEmpty")}</p>
        <dl className="agent-media-approval__fields">
          <div><dt>{t("agent.approval.capability")}</dt><dd><span>{text(preview.adapterId)}</span>
            {text(preview.capabilityId) ? <code className="agent-media-approval__capability-id">{text(preview.capabilityId)}</code> : null}</dd></div>
          <div><dt>{t("agent.approval.priceLabel")}</dt><dd>{priceLabel(preview)}</dd></div>
          {typeof preview.durationSeconds === "number" ? <div><dt>{t("agent.approval.duration")}</dt><dd>{t("agent.approval.seconds", { "0": preview.durationSeconds })}</dd></div> : null}
          {text(preview.videoInputMode) ? <div><dt>{t("agent.approval.inputMode")}</dt><dd>{text(preview.videoInputMode)}</dd></div> : null}
          {parameters.map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{publicValue(value)}</dd></div>)}
        </dl>
        <div className="agent-media-approval__inputs"><strong>{t("agent.approval.inputs", { "0": inputs.length })}</strong>
          {inputs.length ? <ul>{inputs.map((input, inputIndex) => <li key={`${text(input.versionId)}:${inputIndex}`}>
            <span>{text(input.role)}</span><code>{text(input.versionId)}</code>
          </li>)}</ul> : <p>{t("agent.approval.inputsEmpty")}</p>}
        </div>
      </details>;
    })}
    {reviewing ? <p className="agent-media-approval__cost-hint">{t("agent.approval.costHint")}</p> : null}
    {unknown ? <p className="agent-media-approval__warning">{t("agent.approval.unknownHint")}</p> : null}
    {approval.status === "EXPIRED" || approval.status === "CANCELED" ? <p className="agent-media-approval__cost-hint">{t("agent.run.cancelHint")}</p> : null}
    {taskResults.length ? <ul className="agent-media-approval__results">{taskResults.map((task, index) =>
      <li key={text(task.taskId) || index}><span>{KIND_LABELS[text(task.kind)]?.() ?? text(task.kind)}</span>
        <span>{RESULT_LABELS[text(task.status)]?.() ?? text(task.status)}{taskErrorDetail(text(task.errorCode))}</span></li>)}</ul> : null}
    {decision.error ? <p className="agent-media-approval__error" role="alert">{decision.error instanceof ApiError && decision.error.status === 409
      ? t("agent.approval.conflict") : decision.error.message || t("agent.approval.decisionFailed")}</p> : null}
  </AgentChatApproval>;
}
