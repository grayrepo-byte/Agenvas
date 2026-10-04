import { X } from "@phosphor-icons/react";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { getMediaFunctions, getMediaSettings, type RunVideoOperationRequest, type VideoOperation } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { MEDIA_FUNCTIONS_QUERY_KEY, videoOperationLabel, videoFunctionChoices, videoFunction } from "../../shared/mediaFunctions";
import { estimatedMediaCost } from "../../shared/mediaPricing";
import { Button } from "../../shared/ui/primitives/button";
import { RunningHubForm, runningHubErrors, type RunningHubValue } from "./RunningHubForm";

type SubmissionInput = Pick<RunVideoOperationRequest, "expectedFunctionVersion" | "expectedCapabilityVersion" | "prompt" | "parameters">;

/** Reads the configured method and pins the visible video in the workflow's source slot. */
export function VideoOperationPanel({ operation, sourceVersionId, sourceTitle, busy, error, onClose, onSubmit }: {
  operation: VideoOperation; sourceVersionId: string; sourceTitle: string; busy: boolean; error: Error | null;
  onClose: () => void; onSubmit: (input: SubmissionInput) => void;
}) {
  useLocale();
  const functions = useQuery({ queryKey: MEDIA_FUNCTIONS_QUERY_KEY, queryFn: getMediaFunctions, retry: false });
  const settings = useQuery({ queryKey: ["settings", "media"], queryFn: getMediaSettings, retry: false });
  const setting = functions.data?.find((entry) => entry.operation === videoFunction(operation));
  const configured = settings.data && setting?.capabilityId
    ? videoFunctionChoices(settings.data, operation).find(({ capability }) => capability.id === setting.capabilityId) : undefined;
  return <div className="media-operation-panel video-operation-panel nodrag nowheel nopan" role="dialog" aria-label={videoOperationLabel(operation)}>
    <header><strong>{videoOperationLabel(operation)}</strong><Button variant="ghost" type="button" aria-label={t("common.close")} disabled={busy} onClick={onClose}><X size={16} /></Button></header>
    <p className="ui-muted">{t("media.video.source", { "0": sourceTitle })}</p>
    {functions.isPending || settings.isPending ? <p role="status">{t("app.pageLoading")}</p>
      : functions.error || settings.error ? <p role="alert">{(functions.error ?? settings.error)?.message}
        <Button variant="ghost" type="button" onClick={() => { void functions.refetch(); void settings.refetch(); }}>{t("common.retry")}</Button></p>
      : configured && setting ? <ConfiguredOperation key={`${configured.capability.id}:${configured.capability.capabilityVersion}:${setting.version}`}
        operation={operation} sourceVersionId={sourceVersionId} sourceTitle={sourceTitle} settingVersion={setting.version}
        configured={configured} busy={busy} error={error} onSubmit={onSubmit} />
      : <p>{setting?.capabilityId ? t("media.functions.unavailable") : t("media.video.configureFirst")}</p>}
  </div>;
}

function ConfiguredOperation({ operation, sourceVersionId, sourceTitle, settingVersion, configured, busy, error, onSubmit }: {
  operation: VideoOperation; sourceVersionId: string; sourceTitle: string; settingVersion: number;
  configured: ReturnType<typeof videoFunctionChoices>[number]; busy: boolean; error: Error | null;
  onSubmit: (input: SubmissionInput) => void;
}) {
  const [values, setValues] = useState<Record<string, RunningHubValue>>({});
  const definition = configured.capability.settings.runningHub;
  const videoField = definition?.fields.find((field) => field.type === "VIDEO");
  const fixedValues = videoField ? { ...values, [videoField.key]: sourceVersionId } : values;
  const choices = [{ id: sourceVersionId, label: sourceTitle, kind: "VIDEO", available: true }];
  const formDefinition = definition ? { ...definition, fields: definition.fields.filter((field) => field.type !== "VIDEO")
    .map((field) => ({ ...field, source: "PARAMETER" as const })) } : undefined;
  const durationField = definition?.fields.find((field) => field.source === "DURATION_SECONDS");
  const durationValue = durationField ? values[durationField.key] ?? durationField.defaultValue : undefined;
  const promptField = definition?.fields.find((field) => field.source === "PROMPT");
  const prompt = promptField ? values[promptField.key] ?? promptField.defaultValue : "";
  const errors = definition ? runningHubErrors(definition, fixedValues, typeof prompt === "string" ? prompt : "",
    typeof durationValue === "number" ? durationValue : null, choices) : [];
  return <>
    <p className="ui-muted">{configured.label}</p>
    {!definition ? <p>{operation === "DEPTH_MAP" ? t("media.video.depthHint") : t("media.video.audioHint")}</p> : null}
    {formDefinition ? <div className="video-operation-fields"><RunningHubForm definition={formDefinition} values={values} prompt="" durationSeconds={null}
      choices={choices} disabled={busy} onChange={(key, value) => setValues((current) => {
        const next = { ...current }; if (value === undefined) delete next[key]; else next[key] = value; return next;
      })} /></div> : null}
    <p className="ui-muted">{t("media.video.resultHint")}</p>
    <p>{definition ? estimatedMediaCost(configured.capability, 1, typeof durationValue === "number" ? durationValue : null) : t("media.video.localCost")}</p>
    {error ? <p role="alert">{error.message}</p> : null}
    {errors.length ? <p role="status">{errors[0]}</p> : null}
    <footer><Button variant="ghost" type="button" className="is-primary" disabled={busy || errors.length > 0}
      onClick={() => {
        onSubmit({ expectedFunctionVersion: settingVersion, expectedCapabilityVersion: configured.capability.capabilityVersion, prompt: typeof prompt === "string" ? prompt : "",
          parameters: definition ? { dynamicValues: fixedValues } : {} });
      }}>{busy ? t("media.video.submitting") : t("media.video.start")}</Button></footer>
  </>;
}
