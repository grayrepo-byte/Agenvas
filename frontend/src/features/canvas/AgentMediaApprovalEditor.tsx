import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import { ApiError, getMediaDraft, getMediaSettings, reviseRunMediaApproval,
  type AgentMediaApproval, type MediaDraft, type MediaSettings } from "../../shared/api/client";
import { AUTODL_ADAPTER, publishedAutoDlResolutions } from "../../shared/autodlWorkflows";
import { MINIMAX_H3_ADAPTER, MINIMAX_H3_RESOLUTIONS, minimaxResolutionLabel } from "../../shared/minimaxH3";
import { KLING_VIDEO_ADAPTER, KLING_VIDEO_RESOLUTIONS, klingVideoResolutionLabel } from "../../shared/kling";
import { t } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { planMediaCapabilityChange, normalizedVideoParameters, VIDEO_ASPECT_RATIO_OPTIONS, type MediaDraftFields } from "./mediaDraftCapability";
import { WorkflowParameterFields } from "./WorkflowDraftControls";
import { workflowDefinition, workflowDraftValues } from "./workflowDraft";
import { type WorkflowValue } from "./workflowFields";

export type ApprovalEditorProps = {
  projectId: string; runId: string; approvalId: string; version: number; outputIndex: number;
  output: AgentMediaApproval["outputs"][number];
  onClose: () => void; onSaved: (approval: AgentMediaApproval) => void;
};

/** Load the exact draft separately: the approval prompt may already include a style suffix. */
export function AgentMediaApprovalEditor(props: ApprovalEditorProps) {
  const settings = useQuery({ queryKey: ["media-settings"], queryFn: getMediaSettings });
  const draft = useQuery({ queryKey: ["approval-edit-draft", props.projectId, props.output.canvasItemId, props.version],
    queryFn: () => getMediaDraft(props.projectId, props.output.canvasItemId) });
  if (settings.data && draft.data) return <ApprovalVideoForm {...props} settings={settings.data} draft={draft.data} />;
  return <Dialog title={t("agent.approval.editTitle")} onClose={props.onClose} onSubmit={(event) => event.preventDefault()}>
    {settings.error || draft.error ? <p role="alert">{(settings.error ?? draft.error)?.message}</p> : <p role="status">{t("common.loading")}</p>}
    {settings.error || draft.error ? <Button type="button" variant="outline" onClick={() => { void settings.refetch(); void draft.refetch(); }}>{t("common.retry")}</Button> : null}
  </Dialog>;
}

function ApprovalVideoForm({ settings, draft, ...props }: ApprovalEditorProps & { settings: MediaSettings; draft: MediaDraft }) {
  const client = useQueryClient();
  const id = useId();
  const [fields, setFields] = useState<MediaDraftFields>(() => ({ ...draft }));
  const [switchError, setSwitchError] = useState("");
  const options = settings.connections.filter((connection) => connection.enabled)
    .flatMap((connection) => connection.capabilities.filter((capability) => capability.enabled && capability.kind === "VIDEO_GENERATION")
      .map((capability) => ({ capability, label: `${connection.name} · ${capability.name}` })));
  const capability = options.find((option) => option.capability.id === fields.capabilityId)?.capability;
  const definition = workflowDefinition(capability);
  const values = workflowDraftValues(capability, fields);
  const parameters = normalizedVideoParameters(fields.parameters, capability);
  const resolutions: readonly string[] = capability?.adapterId === MINIMAX_H3_ADAPTER ? MINIMAX_H3_RESOLUTIONS
    : capability?.adapterId === KLING_VIDEO_ADAPTER ? KLING_VIDEO_RESOLUTIONS
    : capability?.adapterId === AUTODL_ADAPTER ? publishedAutoDlResolutions(capability.settings) : [];
  const stale = draft.version !== props.output.draftVersion;
  const save = useMutation({
    mutationFn: () => reviseRunMediaApproval(props.projectId, props.runId, props.approvalId, {
      expectedVersion: props.version, outputIndex: props.outputIndex, capabilityId: fields.capabilityId ?? "",
      parameters: fields.parameters, durationSeconds: fields.durationSeconds, videoInputMode: fields.videoInputMode ?? "TEXT",
    }),
    onSuccess: props.onSaved,
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) {
        void client.invalidateQueries({ queryKey: ["run-media-approvals", props.projectId, props.runId] });
        void client.invalidateQueries({ queryKey: ["storyboard-media-approvals", props.projectId] });
        void client.invalidateQueries({ queryKey: ["approval-edit-draft", props.projectId, props.output.canvasItemId] });
      }
    },
  });
  const locked = save.isPending;
  function changeCapability(nextId: string) {
    const next = options.find((option) => option.capability.id === nextId)?.capability;
    const planned = planMediaCapabilityChange({ kind: "VIDEO", fields, capabilityId: nextId,
      resolvedCapabilityId: fields.capabilityId ?? undefined, previous: capability, next }).fields;
    const retained = planned.mediaInputs ?? fields.mediaInputs;
    // Approval changes cannot silently discard the Director's reference material.
    if (retained.length !== fields.mediaInputs.length || fields.mediaInputs.some((input) => !retained.some((other) => other.versionId === input.versionId))) {
      setSwitchError(t("agent.approval.incompatibleInputs")); return;
    }
    setSwitchError(""); save.reset(); setFields({ ...fields, ...planned, prompt: fields.prompt });
  }
  function changeDynamic(key: string, value: WorkflowValue | undefined) {
    const field = definition?.fields.find((candidate) => candidate.key === key);
    if (field?.source === "DURATION_SECONDS") {
      setFields({ ...fields, durationSeconds: typeof value === "number" ? value : null }); return;
    }
    const dynamicValues = { ...values };
    if (value === undefined) delete dynamicValues[key]; else dynamicValues[key] = value;
    setFields({ ...fields, parameters: { ...fields.parameters, dynamicValues } });
  }
  return <Dialog title={t("agent.approval.editTitle")} description={t("agent.approval.editHint")}
    className="workflow-parameters-dialog" busy={locked} onClose={props.onClose}
    onSubmit={(event) => { event.preventDefault(); if (!locked && !stale && capability) save.mutate(); }}
    footer={<><Button type="button" variant="outline" disabled={locked} onClick={props.onClose}>{t("common.cancel")}</Button>
      <Button type="submit" disabled={locked || stale || !capability}>{locked ? t("common.saving") : t("agent.approval.saveSettings")}</Button></>}>
    <FieldGroup>
      <Field><FieldLabel htmlFor={`${id}-capability`}>{t("agent.approval.capability")}</FieldLabel>
        <Select id={`${id}-capability`} value={fields.capabilityId ?? ""} disabled={locked} onChange={(event) => changeCapability(event.target.value)}>
          {!capability ? <option value={fields.capabilityId ?? ""}>{t("media.editor.modelUnavailable")}</option> : null}
          {options.map((option) => <option key={option.capability.id} value={option.capability.id}>{option.label}</option>)}
        </Select>
      </Field>
      {!definition ? <>
        <Field><FieldLabel htmlFor={`${id}-duration`}>{t("agent.approval.duration")}</FieldLabel>
          <Input id={`${id}-duration`} type="number" min={capability?.minimumSeconds ?? 1} max={capability?.maximumSeconds ?? 60}
            step={1} value={fields.durationSeconds ?? ""} disabled={locked} onChange={(event) => setFields({ ...fields,
              durationSeconds: event.target.value === "" ? null : Number(event.target.value) })} /></Field>
        <Field><FieldLabel htmlFor={`${id}-ratio`}>{t("media.editor.aspectRatio")}</FieldLabel>
          <Select id={`${id}-ratio`} value={parameters.aspectRatio} disabled={locked} onChange={(event) => {
            const aspectRatio = VIDEO_ASPECT_RATIO_OPTIONS.find((value) => value === event.target.value);
            if (aspectRatio) setFields({ ...fields, parameters: { ...fields.parameters, aspectRatio } });
          }}>{VIDEO_ASPECT_RATIO_OPTIONS.map((value) => <option key={value} value={value}>{value}</option>)}</Select></Field>
        {resolutions.length ? <Field><FieldLabel htmlFor={`${id}-resolution`}>{t("media.editor.resolution")}</FieldLabel>
          <Select id={`${id}-resolution`} value={parameters.videoResolution ?? ""} disabled={locked} onChange={(event) => setFields({ ...fields,
            parameters: { ...fields.parameters, videoResolution: event.target.value } })}>
            {parameters.videoResolution && !resolutions.includes(parameters.videoResolution) ? <option value={parameters.videoResolution}>{parameters.videoResolution}</option> : null}
            {resolutions.map((value) => <option key={value} value={value}>{capability?.adapterId === MINIMAX_H3_ADAPTER ? minimaxResolutionLabel(value)
              : capability?.adapterId === KLING_VIDEO_ADAPTER ? klingVideoResolutionLabel(value) : value}</option>)}
          </Select></Field> : <p className="ui-muted">{t("media.editor.fixedVideoQuality")}</p>}
      </> : null}
    </FieldGroup>
    {definition ? <WorkflowParameterFields definition={definition} values={values} prompt={fields.prompt}
      durationSeconds={fields.durationSeconds} disabled={locked} onChange={changeDynamic} /> : null}
    {stale ? <p role="alert">{t("agent.approval.conflict")}</p> : null}
    {switchError ? <p role="alert">{switchError}</p> : null}
    {save.error ? <p role="alert">{save.error instanceof ApiError && save.error.status === 409
      ? t("agent.approval.conflict") : save.error.message}</p> : null}
  </Dialog>;
}
