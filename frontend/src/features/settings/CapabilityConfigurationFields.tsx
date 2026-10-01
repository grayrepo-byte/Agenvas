import type { MediaCapability } from "../../shared/api/client";
import { Select } from "../../shared/ui/Select";
import { adapterMetadata } from "./mediaAdapterCatalog";

import { autodlWorkflow } from "./AutoDlWorkflowFields";
import { AUTODL_ADAPTER } from "../../shared/autodlWorkflows";
type Settings = MediaCapability["settings"];
type Parameters = NonNullable<Settings["defaultParameters"]>;
const IMAGE_RATIOS = ["AUTO", "1:1", "2:3", "3:2", "9:16", "16:9", "3:4", "4:3", "21:9"] as const;
const VIDEO_RATIOS = ["AUTO", "16:9", "9:16", "1:1"] as const;
const RESOLUTIONS = ["1K", "2K", "4K"] as const;
const COUNTS = [1, 2, 4] as const;
const PRICE_STEP = "0.000001";

/** Shared by publishing and editing; no settings are silently discarded on save. */
export function CapabilityConfigurationFields({ adapterId, values, onChange, section }: {
  adapterId: string; values: Settings; onChange: (value: Settings) => void;
  section: "defaults" | "limits" | "pricing";
}) {
  const metadata = adapterMetadata(adapterId);
  const workflow = adapterId === AUTODL_ADAPTER ? autodlWorkflow(values) : undefined;
  const adapter = metadata && workflow ? { ...metadata, minimum: workflow.minimumSeconds,
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
      <h3>默认生成参数</h3>
      {!audio ? <label className="ui-field">默认画幅
        <Select value={parameters.aspectRatio ?? "AUTO"}
          onChange={(event) => changeParameters({ aspectRatio: event.target.value as Parameters["aspectRatio"] })}>
          {(image ? comfyImage ? ["AUTO", "1:1", "9:16", "16:9"] : IMAGE_RATIOS : VIDEO_RATIOS)
            .map((ratio) => <option key={ratio} value={ratio}>{ratio === "AUTO" ? "自动" : ratio}</option>)}
        </Select>
      </label> : null}
      {audio ? <>{(["speechRate", "loudnessRate", "pitchRate"] as const).map((key) => <label className="ui-field" key={key}>{{ speechRate: "默认语速", loudnessRate: "默认音量", pitchRate: "默认音调" }[key]}<input type="number" min={key === "pitchRate" ? -12 : -50} max={key === "pitchRate" ? 12 : 100} step={1} value={parameters[key] ?? 0} onChange={(event) => changeParameters({ [key]: Number(event.target.value) })} /></label>)}</> : image ? <>
        <label className="ui-field">默认分辨率
          <Select value={parameters.resolution ?? "1K"}
            onChange={(event) => changeParameters({ resolution: event.target.value as Parameters["resolution"] })}>
            {(comfyImage ? ["1K"] : RESOLUTIONS).map((resolution) => <option key={resolution}>{resolution}</option>)}
          </Select>
        </label>
        <label className="ui-field">默认生成数量
          <Select value={parameters.generationCount ?? 1}
            onChange={(event) => changeParameters({ generationCount: Number(event.target.value) as Parameters["generationCount"] })}>
            {COUNTS.map((count) => <option key={count} value={count}>{count} 张</option>)}
          </Select>
        </label>
        {transparent ? <label className="ui-field">默认背景
          <Select value={parameters.transparentBackground ? "transparent" : "opaque"}
            onChange={(event) => changeParameters({ transparentBackground: event.target.value === "transparent" })}>
            <option value="opaque">不透明</option><option value="transparent">透明</option>
          </Select>
        </label> : null}
      </> : <label className="ui-field">默认视频时长（秒）
        <input type="number" min={values.minimumSeconds ?? adapter.minimum}
          max={values.maximumSeconds ?? adapter.maximum} step={1}
          value={values.defaultDurationSeconds ?? ""} placeholder="未设置"
          onChange={(event) => onChange({ ...values, defaultDurationSeconds: event.target.value ? Number(event.target.value) : undefined })} />
      </label>}
      <p className="ui-muted">仅补充草稿中未填写的参数；已有草稿和已受理任务保留原值。</p>
    </div> : null}
    {section === "limits" ? <div className="media-config-section ui-form-grid">
      <h3>输入限制</h3>
      {!image && !audio ? <>
        <label className="ui-field">最短视频时长（秒）
          <input type="number" min={adapter.minimum} max={values.maximumSeconds ?? adapter.maximum} step={1}
            value={values.minimumSeconds ?? ""} placeholder={String(adapter.minimum)}
            onChange={(event) => onChange({ ...values, minimumSeconds: event.target.value ? Number(event.target.value) : undefined })} />
        </label>
        <label className="ui-field">最长视频时长（秒）
          <input type="number" min={values.minimumSeconds ?? adapter.minimum} max={adapter.maximum} step={1}
            value={values.maximumSeconds ?? ""} placeholder={String(adapter.maximum)}
            onChange={(event) => onChange({ ...values, maximumSeconds: event.target.value ? Number(event.target.value) : undefined })} />
        </label>
      </> : null}
      <label className="ui-field">最多参考图数量
        <input type="number" min={workflow?.minimumImages ?? 0} max={adapter.references} step={1}
          value={values.maxReferenceImages ?? ""} placeholder={String(adapter.references)}
          onChange={(event) => onChange({ ...values, maxReferenceImages: event.target.value ? Number(event.target.value) : undefined })} />
      </label>
      {audio || adapterId === "MOCK_VIDEO" || adapterId === "ARK_SEEDANCE_2_I2V" || (workflow && workflow.audioFields.length > 0) ? <label className="ui-field">最多参考音频数量<input type="number" min={workflow?.minimumAudios ?? 0} max={workflow?.audioFields.length ?? 3} step={1} value={values.maxReferenceAudios ?? ""} placeholder={String(workflow?.audioFields.length ?? 3)} onChange={(event) => onChange({ ...values, maxReferenceAudios: event.target.value ? Number(event.target.value) : undefined })} /></label> : null}
      <p className="ui-muted">{image || audio ? "" : `固定协议支持 ${adapter.minimum}–${adapter.maximum} 秒；`}参考图最多 {adapter.references} 张。留空使用协议范围；可收紧限制，超限草稿需手动调整。</p>
    </div> : null}
    {section === "pricing" ? <div className="media-config-section ui-form-grid">
      <h3>估算价格</h3>
      <label className="ui-field">单位价格
        <input type="number" min={0} max="9999999999.999999" step={PRICE_STEP}
          value={values.pricing?.amount ?? ""} placeholder="留空表示费用未知"
          onChange={(event) => onChange({ ...values, pricing: event.target.value ? {
            amount: event.target.value, currency: values.pricing?.currency ?? "CNY",
            unit: values.pricing?.unit ?? (image ? "IMAGE" : "SECOND"),
          } : undefined })} />
      </label>
      <label className="ui-field">币种
        <Select value={values.pricing?.currency ?? "CNY"} onChange={(event) => onChange({ ...values,
          pricing: { amount: values.pricing?.amount ?? "", unit: values.pricing?.unit ?? (image ? "IMAGE" : "SECOND"),
            currency: event.target.value as "CNY" | "USD" } })}>
          <option value="CNY">CNY · 人民币</option><option value="USD">USD · 美元</option>
        </Select>
      </label>
      <label className="ui-field">计价单位
        <Select value={values.pricing?.unit ?? (image ? "IMAGE" : "SECOND")}
          onChange={(event) => onChange({ ...values, pricing: { amount: values.pricing?.amount ?? "",
            currency: values.pricing?.currency ?? "CNY", unit: event.target.value as "IMAGE" | "VIDEO" | "AUDIO" | "SECOND" } })}>
          {image ? <option value="IMAGE">每张图片</option> : audio ? <><option value="SECOND">每秒音频（按 120 秒上限预估）</option><option value="AUDIO">每条音频</option></> : <><option value="SECOND">每秒视频</option><option value="VIDEO">每个视频</option></>}
        </Select>
      </label>
      <p className="ui-muted">用于运行前估算和用量账本，实际扣费以平台账单为准。留空保留“费用未知”。</p>
    </div> : null}
  </>;
}
