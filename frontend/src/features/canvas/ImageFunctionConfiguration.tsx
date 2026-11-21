import { mediaDisplayName } from "../../shared/mediaDisplayNames";
import { X } from "@/shared/ui/icons";
import { useQuery } from "@tanstack/react-query";
import { useId, useState, type ReactNode } from "react";
import {
  getMediaFunctions,
  getMediaSettings,
  type ImageOperation,
  type MediaCapability,
  type RunImageOperationRequest,
} from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import {
  MEDIA_FUNCTIONS_QUERY_KEY,
  imageFunction,
  imageOperationLabel,
  mediaFunctionChoices,
} from "../../shared/mediaFunctions";
import { estimatedMediaCost } from "../../shared/mediaPricing";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { QUALITY_LABELS, QUALITY_OPTIONS, RESOLUTION_OPTIONS } from "./mediaDraftCapability";
import { RunningHubForm } from "./RunningHubForm";
import { workflowValidationErrors, type WorkflowValue } from "./workflowFields";
import "./ImageFunctionConfiguration.css";

type Submission = Pick<
  RunImageOperationRequest,
  | "expectedFunctionVersion"
  | "expectedCapabilityVersion"
  | "parameters"
  | "instruction"
  | "referenceVersionIds"
  | "maskAssetId"
  | "markupAssetId"
>;
type Configuration = {
  capability: MediaCapability;
  controls: ReactNode;
  parameterControls: ReactNode;
  submitDisabled: boolean;
  submit: (
    parameters: RunImageOperationRequest["parameters"],
    instruction?: string,
    extras?: Pick<RunImageOperationRequest, "referenceVersionIds" | "maskAssetId" | "markupAssetId">,
  ) => void;
};

/** All image workspaces consume the same administrator route and pin its displayed versions. */
export function ImageFunctionConfiguration({
  operation,
  sourceVersionId,
  busy,
  onClose,
  onSubmit,
  children,
}: {
  operation: ImageOperation;
  sourceVersionId: string;
  busy: boolean;
  onClose: () => void;
  onSubmit: (input: Submission) => void;
  children: (configuration: Configuration) => ReactNode;
}) {
  useLocale();
  const functions = useQuery({ queryKey: MEDIA_FUNCTIONS_QUERY_KEY, queryFn: getMediaFunctions, retry: false });
  const settings = useQuery({ queryKey: ["settings", "media"], queryFn: getMediaSettings, retry: false });
  const setting = functions.data?.find(entry => entry.operation === imageFunction(operation));
  const configured =
    settings.data && setting?.capabilityId
      ? mediaFunctionChoices(settings.data, imageFunction(operation)).find(
          ({ capability }) => capability.id === setting.capabilityId,
        )
      : undefined;
  if (configured && setting)
    return (
      <ConfiguredImageFunction
        key={`${operation}:${setting.version}:${configured.capability.capabilityVersion}`}
        configured={configured}
        version={setting.version}
        sourceVersionId={sourceVersionId}
        busy={busy}
        onSubmit={onSubmit}
      >
        {children}
      </ConfiguredImageFunction>
    );
  return (
    <div
      className="media-operation-panel video-operation-panel nodrag nowheel nopan"
      role="dialog"
      aria-label={imageOperationLabel(operation)}
    >
      <header>
        <strong>{imageOperationLabel(operation)}</strong>
        <Button
          variant="ghost"
          size="icon-sm"
          type="button"
          aria-label={t("common.close")}
          disabled={busy}
          onClick={onClose}
        >
          <X size={16} />
        </Button>
      </header>
      {functions.isPending || settings.isPending ? (
        <p role="status">{t("app.pageLoading")}</p>
      ) : functions.error || settings.error ? (
        <p role="alert">
          {(functions.error ?? settings.error)?.message}
          <Button
            variant="ghost"
            type="button"
            onClick={() => {
              void functions.refetch();
              void settings.refetch();
            }}
          >
            {t("common.retry")}
          </Button>
        </p>
      ) : (
        <p>{setting?.capabilityId ? t("media.functions.unavailable") : t("media.functions.configureImageFirst")}</p>
      )}
    </div>
  );
}

function ConfiguredImageFunction({
  configured,
  version,
  sourceVersionId,
  busy,
  onSubmit,
  children,
}: {
  configured: ReturnType<typeof mediaFunctionChoices>[number];
  version: number;
  sourceVersionId: string;
  busy: boolean;
  onSubmit: (input: Submission) => void;
  children: (configuration: Configuration) => ReactNode;
}) {
  const [values, setValues] = useState<Record<string, WorkflowValue>>({});
  const [resolution, setResolution] = useState<Submission["parameters"]["resolution"]>();
  const [quality, setQuality] = useState<Submission["parameters"]["quality"]>();
  const { capability } = configured;
  const definition = capability.settings.runningHub;
  const nativeEdit = capability.adapterId === "OPENAI_GPT_IMAGE_2" || capability.adapterId === "GOOGLE_NANO_BANANA_2";
  const resolutions = RESOLUTION_OPTIONS.filter(value => capability.supportedImageResolutions?.includes(value));
  const qualities = QUALITY_OPTIONS.filter(value => capability.supportedImageQualities?.includes(value));
  const defaults = capability.settings.defaultParameters;
  const sourceField = definition?.fields.find(field => field.type === "IMAGE");
  const fixedValues = sourceField ? { ...values, [sourceField.key]: sourceVersionId } : values;
  // The backend renders the operation's instruction; it owns PROMPT and the pinned source slot.
  const ordinary = definition
    ? { ...definition, fields: definition.fields.filter(field => field.type !== "IMAGE" && field.source !== "PROMPT") }
    : undefined;
  const errors = ordinary ? workflowValidationErrors(ordinary, values, "", null, []) : [];
  const parameterControls = ordinary?.fields.length ? (
    <div className="image-function-parameters ui-stack">
      <RunningHubForm
        definition={ordinary}
        values={values}
        prompt=""
        durationSeconds={null}
        choices={[]}
        disabled={busy}
        onChange={(key, value) =>
          setValues(current => {
            const next = { ...current };
            if (value === undefined) delete next[key];
            else next[key] = value;
            return next;
          })
        }
      />
      {errors.length ? <p role="status">{errors[0]}</p> : null}
    </div>
  ) : nativeEdit && (resolutions.length > 0 || qualities.length > 0) ? (
    <div className="image-function-parameters image-function-output-options">
      <OutputChoice
        label={t("media.editor.resolution")}
        options={resolutions}
        defaultValue={defaults?.resolution ?? "1K"}
        value={resolution}
        onChange={setResolution}
        disabled={busy}
      />
      <OutputChoice
        label={t("media.editor.quality")}
        options={qualities}
        defaultValue={defaults?.quality ?? capability.settings.quality ?? "high"}
        display={value => QUALITY_LABELS[value]}
        value={quality}
        onChange={setQuality}
        disabled={busy}
      />
    </div>
  ) : null;
  const controls = (
    <div className="image-function-controls ui-stack">
      <p className="image-function-method" title={configured.label}>
        {mediaDisplayName(configured.capability)}
      </p>
      {parameterControls}
      <p className="image-function-cost">
        {configured.capability.adapterId === "LOCAL_IMAGE_PROCESSOR"
          ? t("media.video.localCost")
          : estimatedMediaCost(configured.capability, 1, null)}
      </p>
    </div>
  );
  return children({
    capability: configured.capability,
    controls,
    parameterControls,
    submitDisabled: errors.length > 0,
    submit: (parameters, instruction, extras = {}) => {
      if (busy || errors.length) return;
      onSubmit({
        parameters: {
          ...parameters,
          ...(resolution && { resolution }),
          ...(quality && { quality }),
          ...(definition && { dynamicValues: fixedValues }),
        },
        instruction: instruction || null,
        ...extras,
        expectedFunctionVersion: version,
        expectedCapabilityVersion: configured.capability.capabilityVersion,
      });
    },
  });
}

function OutputChoice<T extends string>({
  label,
  options,
  defaultValue,
  display = value => value,
  value,
  onChange,
  disabled,
}: {
  label: string;
  options: readonly T[];
  defaultValue: T;
  display?: (value: T) => string;
  value?: T;
  onChange: (value: T | undefined) => void;
  disabled: boolean;
}) {
  const id = useId();
  if (!options.length) return null;
  return (
    <Field>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Select
        id={id}
        aria-label={label}
        disabled={disabled}
        value={value ?? ""}
        onChange={event => onChange(options.find(option => option === event.target.value))}
      >
        <option value="">{t("media.functions.capabilityDefault", { "0": display(defaultValue) })}</option>
        {options.map(option => (
          <option key={option} value={option}>
            {display(option)}
          </option>
        ))}
      </Select>
    </Field>
  );
}
