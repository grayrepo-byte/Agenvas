import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import type { MediaCapability } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Input } from "../../shared/ui/primitives/input";
import { Select } from "../../shared/ui/Select";
import { comfyReferenceCount } from "./comfyWorkflow";
import { adapterMetadata } from "./mediaAdapterCatalog";

import { AUTODL_ADAPTER } from "../../shared/autodlWorkflows";
import { VideoResolutionPricingFields } from "./VideoResolutionPricingFields";
import { autodlWorkflow } from "./AutoDlWorkflowFields";
type Settings = MediaCapability["settings"];
type Parameters = NonNullable<Settings["defaultParameters"]>;
const IMAGE_RATIOS = ["AUTO", "1:1", "2:3", "3:2", "9:16", "16:9", "3:4", "4:3", "21:9"] as const;
const VIDEO_RATIOS = ["AUTO", "16:9", "9:16", "1:1"] as const;
const RESOLUTIONS = ["1K", "2K", "4K"] as const;
const COUNTS = [1, 2, 4] as const;
const SEEDANCE_MAX_REFERENCE_VIDEOS = 3;
const PRICE_STEP = "0.000001";

/** Shared by publishing and editing; no settings are silently discarded on save. */
export function CapabilityConfigurationFields({ adapterId, values, onChange, section }: {
  adapterId: string; values: Settings; onChange: (value: Settings) => void;
  section: "defaults" | "limits" | "pricing";
}) {
  useLocale();
  const metadata = adapterMetadata(adapterId);
  const workflow = adapterId === AUTODL_ADAPTER ? autodlWorkflow(values) : undefined;
  const adapter = metadata && values.comfyWorkflow ? { ...metadata, minimum: values.comfyWorkflow.minimumSeconds, maximum: values.comfyWorkflow.maximumSeconds, references: comfyReferenceCount(values.comfyWorkflow) } : metadata && workflow ? { ...metadata, minimum: workflow.minimumSeconds,
    maximum: workflow.maximumSeconds, references: workflow.imageFields.length } : metadata;
  if (!adapter) return null;
  const image = adapter.kind === "IMAGE_GENERATION";
  const audio = adapter.kind === "AUDIO_GENERATION";
  const comfyImage = adapterId === "COMFY_IMAGE_V1";
  const transparent = adapterId === "OPENAI_GPT_IMAGE_2" || adapterId === "MOCK_IMAGE";
  const parameters = values.defaultParameters ?? {};
  function changeParameters(patch: Parameters) {
    onChange({ ...values, defaultParameters: { ...(image ? {
      aspectRatio: "AUTO" as const, resolution: "1K" as const,
      quality: values.quality ?? "medium", transparentBackground: false, generationCount: 1 as const,
    } : audio ? { speaker: "", speechRate: 0, loudnessRate: 0, pitchRate: 0 } : { aspectRatio: "AUTO" as const }), ...parameters, ...patch } });
  }
  return <>
    {section === "defaults" ? <div className="media-config-section ui-form-grid">
      <h3>{t("settings.capabilities.defaultParameters")}</h3>
      {!audio ? <Field><FieldLabel className="ui-field block">{t("settings.capabilities.defaultAspectRatio")}<Select value={parameters.aspectRatio ?? "AUTO"}
          onChange={(event) => changeParameters({ aspectRatio: event.target.value as Parameters["aspectRatio"] })}>
          {(image ? comfyImage ? values.comfyWorkflow && !values.comfyWorkflow.bindings.some((binding) => binding.source === "WIDTH") ? ["AUTO"] : ["AUTO", "1:1", "9:16", "16:9"] : IMAGE_RATIOS : values.comfyWorkflow && !values.comfyWorkflow.bindings.some((binding) => binding.source === "WIDTH") ? ["AUTO"] : VIDEO_RATIOS)
            .map((ratio) => <option key={ratio} value={ratio}>{ratio === "AUTO" ? t("common.automatic") : ratio}</option>)}
        </Select>
      </FieldLabel></Field> : null}
      {audio ? <>{(["speechRate", "loudnessRate", "pitchRate"] as const).map((key) => <Field><FieldLabel className="ui-field block" key={key}>{{ speechRate: t("settings.capabilities.defaultSpeechRate"), loudnessRate: t("settings.capabilities.defaultVolume"), pitchRate: t("settings.capabilities.defaultPitch") }[key]}<Input type="number" min={key === "pitchRate" ? -12 : -50} max={key === "pitchRate" ? 12 : 100} step={1} value={parameters[key] ?? 0} onChange={(event) => changeParameters({ [key]: Number(event.target.value) })} /></FieldLabel></Field>)}</> : image ? <>
        <Field><FieldLabel className="ui-field block">{t("media.defaultResolution")}<Select value={parameters.resolution ?? "1K"}
            onChange={(event) => changeParameters({ resolution: event.target.value as Parameters["resolution"] })}>
            {(comfyImage ? ["1K"] : RESOLUTIONS).map((resolution) => <option key={resolution}>{resolution}</option>)}
          </Select>
        </FieldLabel></Field>
        <Field><FieldLabel className="ui-field block">{t("settings.capabilities.defaultBatchSize")}<Select value={parameters.generationCount ?? 1}
            onChange={(event) => changeParameters({ generationCount: Number(event.target.value) as Parameters["generationCount"] })}>
            {COUNTS.map((count) => <option key={count} value={count}>{t("settings.capabilities.imageCount", { "0": count })}</option>)}
          </Select>
        </FieldLabel></Field>
        {transparent ? <Field><FieldLabel className="ui-field block">{t("settings.capabilities.defaultBackground")}<Select value={parameters.transparentBackground ? "transparent" : "opaque"}
            onChange={(event) => changeParameters({ transparentBackground: event.target.value === "transparent" })}>
            <option value="opaque">{t("settings.capabilities.opaque")}</option><option value="transparent">{t("settings.capabilities.transparent")}</option>
          </Select>
        </FieldLabel></Field> : null}
      </> : <Field><FieldLabel className="ui-field block">{t("settings.capabilities.defaultVideoSeconds")}<Input type="number" min={values.minimumSeconds ?? adapter.minimum}
          max={values.maximumSeconds ?? adapter.maximum} step={1}
          value={values.defaultDurationSeconds ?? ""} placeholder={t("common.unset")}
          onChange={(event) => onChange({ ...values, defaultDurationSeconds: event.target.value ? Number(event.target.value) : undefined })} />
      </FieldLabel></Field>}
      <p className="ui-muted">{t("settings.capabilities.defaultsHint")}</p>
    </div> : null}
    {section === "limits" ? <div className="media-config-section ui-form-grid">
      <h3>{t("media.inputLimits")}</h3>
      {!image && !audio ? <>
        <Field><FieldLabel className="ui-field block">{t("settings.capabilities.minimumVideoSeconds")}<Input type="number" min={adapter.minimum} max={values.maximumSeconds ?? adapter.maximum} step={1}
            value={values.minimumSeconds ?? ""} placeholder={String(adapter.minimum)}
            onChange={(event) => onChange({ ...values, minimumSeconds: event.target.value ? Number(event.target.value) : undefined })} />
        </FieldLabel></Field>
        <Field><FieldLabel className="ui-field block">{t("settings.capabilities.maximumVideoSeconds")}<Input type="number" min={values.minimumSeconds ?? adapter.minimum} max={adapter.maximum} step={1}
            value={values.maximumSeconds ?? ""} placeholder={String(adapter.maximum)}
            onChange={(event) => onChange({ ...values, maximumSeconds: event.target.value ? Number(event.target.value) : undefined })} />
        </FieldLabel></Field>
      </> : null}
      <Field><FieldLabel className="ui-field block">{t("settings.capabilities.maxImages")}<Input type="number" min={workflow?.minimumImages ?? 0} max={adapter.references} step={1}
          value={values.maxReferenceImages ?? ""} placeholder={String(adapter.references)}
          onChange={(event) => onChange({ ...values, maxReferenceImages: event.target.value ? Number(event.target.value) : undefined })} />
      </FieldLabel></Field>
      {adapterId === "ARK_SEEDANCE_2_I2V" ? <Field><FieldLabel className="ui-field block">{t("settings.capabilities.maxVideos")}<Input type="number" min={0} max={SEEDANCE_MAX_REFERENCE_VIDEOS} step={1} value={values.maxReferenceVideos ?? ""} placeholder={String(SEEDANCE_MAX_REFERENCE_VIDEOS)} onChange={(event) => onChange({ ...values, maxReferenceVideos: event.target.value ? Number(event.target.value) : undefined })} /></FieldLabel></Field> : null}
      {audio || adapterId === "MOCK_VIDEO" || adapterId === "ARK_SEEDANCE_2_I2V" || (workflow && workflow.audioFields.length > 0) ? <Field><FieldLabel className="ui-field block">{t("settings.capabilities.maxAudios")}<Input type="number" min={workflow?.minimumAudios ?? 0} max={workflow?.audioFields.length ?? 3} step={1} value={values.maxReferenceAudios ?? ""} placeholder={String(workflow?.audioFields.length ?? 3)} onChange={(event) => onChange({ ...values, maxReferenceAudios: event.target.value ? Number(event.target.value) : undefined })} /></FieldLabel></Field> : null}
      <p className="ui-muted">{t("settings.capabilities.referenceLimitHint", { "0": image || audio ? "" : t("settings.capabilities.protocolDurationHint", { "0": adapter.minimum, "1": adapter.maximum }), "1": adapter.references })}</p>
    </div> : null}
    {section === "pricing" ? <div className="media-config-section ui-form-grid">
      <h3>{t("media.pricing.estimated")}</h3>
      <Field><FieldLabel className="ui-field block">{t("settings.capabilities.unitPrice")}<Input type="number" min={0} max="9999999999.999999" step={PRICE_STEP}
          value={values.pricing?.amount ?? ""} placeholder={t("settings.capabilities.unknownPricePlaceholder")}
          onChange={(event) => onChange({ ...values, pricing: event.target.value ? {
            amount: event.target.value, currency: values.pricing?.currency ?? "CNY",
            unit: values.pricing?.unit ?? (image ? "IMAGE" : "SECOND"),
          } : undefined })} />
      </FieldLabel></Field>
      <Field><FieldLabel className="ui-field block">{t("settings.capabilities.currency")}<Select value={values.pricing?.currency ?? "CNY"} onChange={(event) => onChange({ ...values,
          pricing: { amount: values.pricing?.amount ?? "", unit: values.pricing?.unit ?? (image ? "IMAGE" : "SECOND"),
            currency: event.target.value as "CNY" | "USD" } })}>
          <option value="CNY">{t("media.pricing.cny")}</option><option value="USD">{t("media.pricing.usd")}</option>
        </Select>
      </FieldLabel></Field>
      <Field><FieldLabel className="ui-field block">{t("settings.capabilities.priceUnit")}<Select value={values.pricing?.unit ?? (image ? "IMAGE" : "SECOND")}
          onChange={(event) => onChange({ ...values, pricing: { amount: values.pricing?.amount ?? "",
            currency: values.pricing?.currency ?? "CNY", unit: event.target.value as "IMAGE" | "VIDEO" | "AUDIO" | "SECOND" } })}>
          {image ? <option value="IMAGE">{t("settings.capabilities.perImage")}</option> : audio ? <><option value="SECOND">{t("settings.capabilities.perAudioSecond")}</option><option value="AUDIO">{t("settings.capabilities.perAudio")}</option></> : <><option value="SECOND">{t("media.pricing.perVideoSecond")}</option><option value="VIDEO">{t("media.pricing.perVideo")}</option></>}
        </Select>
      </FieldLabel></Field>
      <p className="ui-muted">{t("settings.capabilities.pricingHint")}</p>
      {workflow ? <VideoResolutionPricingFields values={values} onChange={onChange} /> : null}
    </div> : null}
  </>;
}
