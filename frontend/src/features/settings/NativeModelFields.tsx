import type { MediaCapability, NativeMediaProfile } from "../../shared/api/client";
import { t, useLocale, type MessageKey } from "../../shared/i18n";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { Select } from "../../shared/ui/Select";

const OPERATION_LABELS: Record<NativeMediaProfile["operation"], MessageKey> = {
  GENERATE: "settings.mediaSettings.nativeGenerate", LAYER_DECOMPOSITION: "settings.mediaSettings.nativeLayers",
  INTERACTIVE_EDIT: "settings.mediaSettings.nativeInteractive", EDIT: "settings.mediaSettings.nativeEdit",
  EXTEND: "settings.mediaSettings.nativeExtend",
};

/** Server-owned profiles select an operation and its exact supported model without editable protocol URLs. */
export function NativeModelFields({ profiles, selected, values, onChange }: {
  profiles: NativeMediaProfile[]; selected: NativeMediaProfile; values: MediaCapability["settings"];
  onChange: (value: MediaCapability["settings"]) => void;
}) {
  useLocale();
  function choose(profile: NativeMediaProfile | undefined) {
    if (!profile) return;
    onChange({ ...values, model: profile.model, operation: profile.operation, nativeInputs: profile.inputs,
      defaultParameters: undefined, defaultDurationSeconds: undefined, minimumSeconds: undefined, maximumSeconds: undefined,
      maxReferenceImages: undefined, maxReferenceAudios: undefined, maxReferenceVideos: undefined, pricingByResolution: undefined });
  }
  const operations = [...new Set(profiles.map((profile) => profile.operation))];
  const models = profiles.filter((profile) => profile.operation === selected.operation);
  return <>
    <Field><FieldLabel className="ui-field block">{t("settings.mediaSettings.nativeOperation")}
      <Select value={selected.operation} onChange={(event) => choose(profiles.find((profile) => profile.operation === event.target.value
        && profile.model === selected.model) ?? profiles.find((profile) => profile.operation === event.target.value))}>
        {operations.map((operation) => <option key={operation} value={operation}>{t(OPERATION_LABELS[operation])}</option>)}
      </Select>
    </FieldLabel></Field>
    <Field><FieldLabel className="ui-field block">{t("settings.mediaSettings.nativeModel")}
      <Select value={selected.model} onChange={(event) => choose(models.find((profile) => profile.model === event.target.value))}>
        {models.map((profile) => <option key={profile.model} value={profile.model}>{profile.model}</option>)}
      </Select>
    </FieldLabel></Field>
    <p className="ui-muted media-model-help">{t("settings.mediaSettings.nativeModelHint")}</p>
  </>;
}
