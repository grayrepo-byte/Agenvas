import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import type { MediaCapability } from "../../shared/api/client";
import { AUTODL_DEFAULT_WORKFLOW,autodlWorkflows,getAutoDlWorkflow } from "../../shared/autodlWorkflows";
import { t,useLocale } from "../../shared/i18n";
import { Input } from "../../shared/ui/primitives/input";
import { Select } from "../../shared/ui/Select";

type Settings = MediaCapability["settings"];
const MAX_SEED = 999_999_999_999_999;
export function autodlWorkflow(values: Settings) {
  return getAutoDlWorkflow(values.workflowId);
}
export function AutoDlWorkflowFields({ values, onChange }: {
  values: Settings; onChange: (value: Settings) => void;
}) {
  useLocale();
  const workflow = autodlWorkflow(values);
  const tiers = [...new Set(workflow?.resolutions.map((label) => `${label.split("p")[0]}p`) ?? [])];
  return <>
    <Field><FieldLabel className="ui-field block">{t("AutoDL 工作流")}<Select value={values.workflowId ?? AUTODL_DEFAULT_WORKFLOW} onChange={(event) => {
        const next = autodlWorkflows.find((entry) => entry.id === event.target.value);
        if (next) onChange({ ...values, workflowId: next.id, videoResolution: next.defaultResolution as Settings["videoResolution"],
          seed: undefined, minimumSeconds: undefined, maximumSeconds: undefined,
          maxReferenceImages: undefined, maxReferenceAudios: undefined, defaultDurationSeconds: undefined });
      }}>
        {autodlWorkflows.map((entry) => <option key={entry.id} value={entry.id}>{t(entry.label)} · {entry.id}</option>)}
      </Select>
    </FieldLabel></Field>
    <Field><FieldLabel className="ui-field block">{t("AutoDL 输出分辨率")}<Select value={values.videoResolution ?? workflow?.defaultResolution} onChange={(event) => onChange({
        ...values, videoResolution: event.target.value as Settings["videoResolution"],
      })}>{tiers.map((tier) => <option key={tier}>{tier}</option>)}</Select>
    </FieldLabel></Field>
    {workflow?.supportsSeed ? <Field><FieldLabel className="ui-field block">{t("随机种子（留空使用工作流默认）")}<Input type="number" min={1} max={MAX_SEED} step={1} value={values.seed ?? ""}
        onChange={(event) => onChange({ ...values, seed: event.target.value ? Number(event.target.value) : undefined })} />
    </FieldLabel></Field> : null}
    {workflow ? <p className="ui-muted media-model-help">{t("输入模式：{0}； 图片 {1}–{2} 张，音频 {3}–{4} 条， 时长 {5}–{6} 秒。生成使用运行时固定的素材版本。 画幅沿用卡片或项目；工作流不支持的画幅与分辨率组合不能运行。支持当前已声明的 H3 工作流。", { "0": { TEXT: t("纯文本"), START_END: t("首尾帧"), GENERAL_REFERENCE: t("全能参考") }[workflow.mode], "1": workflow.minimumImages, "2": workflow.imageFields.length, "3": workflow.minimumAudios, "4": workflow.audioFields.length, "5": workflow.minimumSeconds, "6": workflow.maximumSeconds })}</p> : null}
  </>;
}
