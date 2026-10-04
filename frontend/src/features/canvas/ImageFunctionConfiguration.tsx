import { X } from "@phosphor-icons/react";
import { useQuery } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { getMediaFunctions, getMediaSettings, type ImageOperation, type MediaCapability, type RunImageOperationRequest } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { MEDIA_FUNCTIONS_QUERY_KEY, imageFunction, imageOperationLabel, mediaFunctionChoices } from "../../shared/mediaFunctions";
import { estimatedMediaCost } from "../../shared/mediaPricing";
import { Button } from "../../shared/ui/primitives/button";
import { RunningHubForm, runningHubErrors, type RunningHubValue } from "./RunningHubForm";
import "./ImageFunctionConfiguration.css";

type Submission = Pick<RunImageOperationRequest, "expectedFunctionVersion" | "expectedCapabilityVersion" | "parameters" | "instruction" | "referenceVersionIds" | "maskAssetId">;
type Configuration = {
  capability: MediaCapability;
  controls: ReactNode;
  parameterControls: ReactNode;
  submitDisabled: boolean;
  submit: (parameters: RunImageOperationRequest["parameters"], instruction?: string,
    extras?: Pick<RunImageOperationRequest, "referenceVersionIds" | "maskAssetId">) => void;
};

/** All image workspaces consume the same administrator route and pin its displayed versions. */
export function ImageFunctionConfiguration({ operation, sourceVersionId, busy, onClose, onSubmit, children }: {
  operation: ImageOperation; sourceVersionId: string; busy: boolean; onClose: () => void;
  onSubmit: (input: Submission) => void; children: (configuration: Configuration) => ReactNode;
}) {
  useLocale();
  const functions = useQuery({ queryKey: MEDIA_FUNCTIONS_QUERY_KEY, queryFn: getMediaFunctions, retry: false });
  const settings = useQuery({ queryKey: ["settings", "media"], queryFn: getMediaSettings, retry: false });
  const setting = functions.data?.find((entry) => entry.operation === imageFunction(operation));
  const configured = settings.data && setting?.capabilityId
    ? mediaFunctionChoices(settings.data, imageFunction(operation)).find(({ capability }) => capability.id === setting.capabilityId) : undefined;
  if (configured && setting) return <ConfiguredImageFunction key={`${operation}:${setting.version}:${configured.capability.capabilityVersion}`}
    configured={configured} version={setting.version} sourceVersionId={sourceVersionId} busy={busy} onSubmit={onSubmit}>{children}</ConfiguredImageFunction>;
  return <div className="media-operation-panel video-operation-panel nodrag nowheel nopan" role="dialog" aria-label={imageOperationLabel(operation)}>
    <header><strong>{imageOperationLabel(operation)}</strong><Button variant="ghost" type="button" aria-label={t("common.close")} disabled={busy} onClick={onClose}><X size={16} /></Button></header>
    {functions.isPending || settings.isPending ? <p role="status">{t("app.pageLoading")}</p>
      : functions.error || settings.error ? <p role="alert">{(functions.error ?? settings.error)?.message}
        <Button variant="ghost" type="button" onClick={() => { void functions.refetch(); void settings.refetch(); }}>{t("common.retry")}</Button></p>
      : <p>{setting?.capabilityId ? t("media.functions.unavailable") : t("media.functions.configureImageFirst")}</p>}
  </div>;
}

function ConfiguredImageFunction({ configured, version, sourceVersionId, busy, onSubmit, children }: {
  configured: ReturnType<typeof mediaFunctionChoices>[number]; version: number; sourceVersionId: string; busy: boolean;
  onSubmit: (input: Submission) => void; children: (configuration: Configuration) => ReactNode;
}) {
  const [values, setValues] = useState<Record<string, RunningHubValue>>({});
  const definition = configured.capability.settings.runningHub;
  const sourceField = definition?.fields.find((field) => field.type === "IMAGE");
  const fixedValues = sourceField ? { ...values, [sourceField.key]: sourceVersionId } : values;
  // The backend renders the operation's instruction; it owns PROMPT and the pinned source slot.
  const ordinary = definition ? { ...definition, fields: definition.fields.filter((field) => field.type !== "IMAGE" && field.source !== "PROMPT") } : undefined;
  const errors = ordinary ? runningHubErrors(ordinary, values, "", null, []) : [];
  const parameterControls = ordinary?.fields.length ? <div className="image-function-parameters ui-stack">
    <RunningHubForm definition={ordinary} values={values} prompt="" durationSeconds={null} choices={[]}
      disabled={busy} onChange={(key, value) => setValues((current) => {
        const next = { ...current }; if (value === undefined) delete next[key]; else next[key] = value; return next;
      })} />
    {errors.length ? <p role="status">{errors[0]}</p> : null}
  </div> : null;
  const controls = <div className="image-function-controls ui-stack">
    <p className="image-function-method" title={configured.label}>{configured.capability.name}</p>
    {parameterControls}
    <p className="image-function-cost">{configured.capability.adapterId === "LOCAL_IMAGE_PROCESSOR"
      ? t("media.video.localCost") : estimatedMediaCost(configured.capability, 1, null)}</p>
  </div>;
  return children({ capability: configured.capability, controls, parameterControls, submitDisabled: errors.length > 0,
    submit: (parameters, instruction, extras = {}) => {
      if (busy || errors.length) return;
      onSubmit({ parameters: definition ? { ...parameters, dynamicValues: fixedValues } : parameters,
        instruction: instruction || null, ...extras, expectedFunctionVersion: version,
        expectedCapabilityVersion: configured.capability.capabilityVersion });
    } });
}
