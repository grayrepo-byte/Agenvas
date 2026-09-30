import type { MediaCapability } from "../../shared/api/client";
import { Select } from "../../shared/ui/Select";
import { autodlWorkflows, AUTODL_DEFAULT_WORKFLOW, getAutoDlWorkflow } from "../../shared/autodlWorkflows";

type Settings = MediaCapability["settings"];
const MAX_SEED = 999_999_999_999_999;
export function autodlWorkflow(values: Settings) {
  return getAutoDlWorkflow(values.workflowId);
}
export function AutoDlWorkflowFields({ values, onChange }: {
  values: Settings; onChange: (value: Settings) => void;
}) {
  const workflow = autodlWorkflow(values);
  const tiers = [...new Set(workflow?.resolutions.map((label) => `${label.split("p")[0]}p`) ?? [])];
  return <>
    <label className="ui-field">AutoDL 工作流
      <Select value={values.workflowId ?? AUTODL_DEFAULT_WORKFLOW} onChange={(event) => {
        const next = autodlWorkflows.find((entry) => entry.id === event.target.value);
        if (next) onChange({ ...values, workflowId: next.id, videoResolution: next.defaultResolution as Settings["videoResolution"],
          seed: undefined, minimumSeconds: undefined, maximumSeconds: undefined,
          maxReferenceImages: undefined, maxReferenceAudios: undefined, defaultDurationSeconds: undefined });
      }}>
        {autodlWorkflows.map((entry) => <option key={entry.id} value={entry.id}>{entry.label} · {entry.id}</option>)}
      </Select>
    </label>
    <label className="ui-field">AutoDL 输出分辨率
      <Select value={values.videoResolution ?? workflow?.defaultResolution} onChange={(event) => onChange({
        ...values, videoResolution: event.target.value as Settings["videoResolution"],
      })}>{tiers.map((tier) => <option key={tier}>{tier}</option>)}</Select>
    </label>
    {workflow?.supportsSeed ? <label className="ui-field">随机种子（留空使用工作流默认）
      <input type="number" min={1} max={MAX_SEED} step={1} value={values.seed ?? ""}
        onChange={(event) => onChange({ ...values, seed: event.target.value ? Number(event.target.value) : undefined })} />
    </label> : null}
    {workflow ? <p className="ui-muted media-model-help">输入模式：{{ TEXT: "纯文本", START_END: "首尾帧", GENERAL_REFERENCE: "全能参考" }[workflow.mode]}；
      图片 {workflow.minimumImages}–{workflow.imageFields.length} 张，音频 {workflow.minimumAudios}–{workflow.audioFields.length} 条，
      时长 {workflow.minimumSeconds}–{workflow.maximumSeconds} 秒。生成使用运行时固定的素材版本。
      画幅沿用卡片或项目；工作流不支持的画幅与分辨率组合不能运行。支持当前已声明的 H3 工作流。</p> : null}
  </>;
}
