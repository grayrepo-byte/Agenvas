import type { MediaCapability } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Field, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { Select } from "../../shared/ui/Select";

type Definition = NonNullable<MediaCapability["settings"]["workflowDefinition"]>;
const MAX_REFERENCES = 14;
const MAX_AUDIOS = 3;
const MAX_SECONDS = 30;
const MAX_PROMPT = 10000;
const MAX_LABEL_LENGTH = 160;
const RESOLUTION_ROWS = 6;

export function AutoDlWorkflowDefinitionEditor({ value, onChange }: {
  value: Definition; onChange: (value: Definition) => void;
}) {
  useLocale();
  return <FieldSet><FieldLegend>{t("工作流输入定义")}</FieldLegend><FieldGroup className="ui-form-grid">
    <Field><FieldLabel>{t("工作流 ID")}<Input required pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,119}" value={value.id} onChange={(event) => onChange({ ...value, id: event.target.value })} /></FieldLabel></Field>
    <Field><FieldLabel>{t("工作流名称")}<Input required maxLength={MAX_LABEL_LENGTH} value={value.label} onChange={(event) => onChange({ ...value, label: event.target.value })} /></FieldLabel></Field>
    <Field><FieldLabel>{t("输入模式")}<Select value={value.mode} onChange={(event) => {
      const mode = event.target.value as Definition["mode"];
      onChange({ ...value, mode, imageFields: mode === "START_END" ? ["first_frame", "last_frame"] : mode === "TEXT" ? [] : ["ref_image_0"],
        audioFields: [], minimumImages: mode === "START_END" ? 2 : mode === "TEXT" ? 0 : 1, minimumAudios: 0 });
    }}><option value="TEXT">{t("纯文本")}</option><option value="START_END">{t("首尾帧")}</option><option value="GENERAL_REFERENCE">{t("全能参考")}</option></Select></FieldLabel></Field>
    {(["minimumSeconds", "maximumSeconds", "promptLimit", "minimumImages", "minimumAudios"] as const).map((key) => <Field key={key}><FieldLabel>{{
      minimumSeconds: t("协议最短时长（秒）"), maximumSeconds: t("协议最长时长（秒）"), promptLimit: t("提示词长度上限"),
      minimumImages: t("必填图片数量"), minimumAudios: t("必填音频数量"),
    }[key]}<Input type="number" required min={key === "minimumImages" || key === "minimumAudios" ? 0 : 1}
      max={key === "promptLimit" ? MAX_PROMPT : key === "minimumImages" ? value.imageFields.length : key === "minimumAudios" ? value.audioFields.length : MAX_SECONDS}
      step={1} value={value[key]} onChange={(event) => onChange({ ...value, [key]: Number(event.target.value) })} /></FieldLabel></Field>)}
    {value.mode === "GENERAL_REFERENCE" ? <>
      <Field><FieldLabel>{t("协议图片数量上限")}<Input type="number" min={0} max={MAX_REFERENCES - value.audioFields.length} step={1} value={value.imageFields.length}
        onChange={(event) => onChange({ ...value, imageFields: Array.from({ length: Math.max(0, Math.min(MAX_REFERENCES, Number(event.target.value))) }, (_, i) => `ref_image_${i}`) })} /></FieldLabel></Field>
      <Field><FieldLabel>{t("协议音频数量上限")}<Input type="number" min={0} max={Math.min(MAX_AUDIOS, MAX_REFERENCES - value.imageFields.length)} step={1} value={value.audioFields.length}
        onChange={(event) => onChange({ ...value, audioFields: Array.from({ length: Math.max(0, Math.min(MAX_AUDIOS, Number(event.target.value))) }, (_, i) => `ref_audio_${i}`) })} /></FieldLabel></Field>
    </> : null}
    <Field><FieldLabel>{t("支持随机种子")}<Select value={value.supportsSeed ? "yes" : "no"} onChange={(event) => onChange({ ...value, supportsSeed: event.target.value === "yes" })}>
      <option value="yes">{t("支持")}</option><option value="no">{t("不支持")}</option></Select></FieldLabel></Field>
    <Field><FieldLabel>{t("供应商精确分辨率枚举（每行一个）")}<Textarea required rows={RESOLUTION_ROWS} value={value.resolutions.join("\n")}
      onChange={(event) => onChange({ ...value, resolutions: event.target.value.split("\n") })}
      onBlur={() => onChange({ ...value, resolutions: value.resolutions.map((line) => line.trim()).filter(Boolean) })} /></FieldLabel></Field>
    <p className="ui-muted">{t("从官方定义导入，或填写供应商的精确枚举，例如 720p横(1280*720)。新增同协议视频工作流无需更新代码；其他输入或输出协议需要单独适配。")}</p>
  </FieldGroup></FieldSet>;
}
