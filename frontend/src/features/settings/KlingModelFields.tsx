import type { KlingProfile, MediaCapability } from "../../shared/api/client";
import { t, useLocale, type MessageKey } from "../../shared/i18n";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { Select } from "../../shared/ui/Select";

type Settings = MediaCapability["settings"];
const OPERATION_LABELS: Record<KlingProfile["operation"], MessageKey> = {
  GENERATE: "settings.mediaSettings.klingGenerate", MULTI_IMAGE: "settings.mediaSettings.klingMultiImage",
  EXPAND: "settings.mediaSettings.klingExpand", SUBJECT_COMPLETION: "settings.mediaSettings.klingSubjectCompletion",
  MOTION_CONTROL: "settings.mediaSettings.klingMotionControl",
};

/** A compiled profile selects model and operation together; inputs are server-owned metadata. */
export function KlingModelFields({ profiles, selected, values, onChange }: {
  profiles: KlingProfile[]; selected: KlingProfile; values: Settings; onChange: (value: Settings) => void;
}) {
  useLocale();
  function choose(profile: KlingProfile | undefined) {
    if (!profile) return;
    onChange({ ...values, model: profile.model ?? "", operation: profile.operation, klingInputs: profile.inputs,
      defaultParameters: undefined, defaultDurationSeconds: undefined, minimumSeconds: undefined,
      maximumSeconds: undefined, maxReferenceImages: undefined, maxReferenceAudios: undefined,
      maxReferenceVideos: undefined, pricingByResolution: undefined });
  }
  const operations = [...new Set(profiles.map((profile) => profile.operation))];
  const models = profiles.filter((profile) => profile.operation === selected.operation && profile.model !== null);
  return <>
    <Field><FieldLabel className="ui-field block">{t("settings.mediaSettings.klingOperation")}
      <Select value={selected.operation} onChange={(event) => choose(profiles.find((profile) => profile.operation === event.target.value
        && profile.model === selected.model) ?? profiles.find((profile) => profile.operation === event.target.value))}>
        {operations.map((operation) => <option key={operation} value={operation}>{t(OPERATION_LABELS[operation])}</option>)}
      </Select>
    </FieldLabel></Field>
    {models.length ? <Field><FieldLabel className="ui-field block">{t("settings.mediaSettings.klingModel")}
      <Select value={selected.model ?? ""} onChange={(event) => choose(models.find((profile) => profile.model === event.target.value))}>
        {models.map((profile) => <option key={profile.model} value={profile.model ?? ""}>{profile.model}</option>)}
      </Select>
    </FieldLabel></Field> : null}
    <p className="ui-muted media-model-help">{t("settings.mediaSettings.klingNativeModelHint")}</p>
  </>;
}
