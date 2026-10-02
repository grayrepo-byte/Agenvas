import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Field, FieldLabel, FieldSet, FieldLegend } from "../../shared/ui/primitives/field";
import { getAutoDlWorkflowCatalog, previewAutoDlWorkflow, type MediaCapability } from "../../shared/api/client";
import { AUTODL_DEFAULT_WORKFLOW,autodlWorkflows,resolveAutoDlWorkflow,autoDlResolutionTiers } from "../../shared/autodlWorkflows";
import { t,useLocale } from "../../shared/i18n";
import { Input } from "../../shared/ui/primitives/input";
import { ToggleGroup, ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { Button } from "../../shared/ui/primitives/button";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { AutoDlWorkflowDefinitionEditor } from "./AutoDlWorkflowDefinitionEditor";
import { Select } from "../../shared/ui/Select";

type Settings = MediaCapability["settings"];
const MAX_SEED = 999_999_999_999_999;
export function autodlWorkflow(values: Settings) {
  return resolveAutoDlWorkflow(values);
}
export function AutoDlWorkflowFields({ values, onChange }: {
  values: Settings; onChange: (value: Settings) => void;
}) {
  useLocale();
  const [source, setSource] = useState("");
  const [sourceError, setSourceError] = useState<string | null>(null);
  const catalog = useMutation({ mutationFn: getAutoDlWorkflowCatalog });
  function applyDefinition(definition: NonNullable<Settings["workflowDefinition"]>, reset = false) {
    const tiers = autoDlResolutionTiers(definition);
    const previous = values.videoResolutions ?? (values.videoResolution ? [values.videoResolution] : tiers);
    const retained = reset ? tiers : previous.filter((tier) => tiers.includes(tier));
    const videoResolutions = retained.length ? retained : tiers;
    const videoResolution = videoResolutions.includes(values.videoResolution ?? "") && !reset ? values.videoResolution
      : videoResolutions.includes(definition.defaultResolution) ? definition.defaultResolution : videoResolutions[0];
    onChange({ ...values, workflowId: definition.id, workflowDefinition: { ...definition, defaultResolution: videoResolution ?? definition.defaultResolution },
      videoResolution, videoResolutions,
      ...(values.pricingByResolution ? { pricingByResolution: Object.fromEntries(Object.entries(values.pricingByResolution).filter(([tier]) => videoResolutions.includes(tier))) } : {}),
      ...(!definition.supportsSeed ? { seed: undefined } : {}), ...(reset ? { pricingByResolution: undefined, seed: undefined,
        minimumSeconds: undefined, maximumSeconds: undefined, maxReferenceImages: undefined,
        maxReferenceAudios: undefined, defaultDurationSeconds: undefined } : {}) });
  }
  const preview = useMutation({ mutationFn: ({ id, metadata }: { id: string; metadata?: object }) => previewAutoDlWorkflow(id, metadata),
    onSuccess: (definition) => applyDefinition(definition, definition.id !== workflow?.id) });
  const workflow = autodlWorkflow(values);
  const tiers = workflow ? autoDlResolutionTiers(workflow) : [];
  const selected = values.videoResolutions ?? (values.videoResolution ? [values.videoResolution] : tiers);
  return <>
    <Field><FieldLabel className="ui-field block">{t("AutoDL 工作流")}<Select disabled={preview.isPending} value={values.workflowDefinition ? "custom" : values.workflowId ?? AUTODL_DEFAULT_WORKFLOW} onChange={(event) => {
        if (event.target.value === "custom" && workflow) { applyDefinition({ ...workflow, schemaVersion: 1, imageFields: [...workflow.imageFields], audioFields: [...workflow.audioFields], resolutions: [...workflow.resolutions] }); return; }
        if (catalog.data?.items.some((entry) => entry.id === event.target.value)) { preview.mutate({ id: event.target.value }); return; }
        const next = autodlWorkflows.find((entry) => entry.id === event.target.value);
        if (next) onChange({ ...values, workflowDefinition: undefined, workflowId: next.id, videoResolution: next.defaultResolution,
          videoResolutions: autoDlResolutionTiers(next), pricingByResolution: undefined,
          seed: undefined, minimumSeconds: undefined, maximumSeconds: undefined,
          maxReferenceImages: undefined, maxReferenceAudios: undefined, defaultDurationSeconds: undefined });
      }}>
        {autodlWorkflows.map((entry) => <option key={entry.id} value={entry.id}>{t(entry.label)} · {entry.id}</option>)}
        {(catalog.data?.items ?? []).filter((entry) => !autodlWorkflows.some((preset) => preset.id === entry.id)).map((entry) => <option key={entry.id} value={entry.id}>{entry.label} · {entry.id}</option>)}
        <option value="custom">{t("自定义 / 已导入工作流")}</option>
      </Select>
    </FieldLabel></Field>
    <div className="flex flex-wrap gap-2">
      <Button type="button" variant="outline" disabled={catalog.isPending || preview.isPending} onClick={() => catalog.mutate()}>{catalog.isPending ? t("正在读取目录…") : t("刷新官方工作流目录")}</Button>
      <Button type="button" variant="outline" disabled={preview.isPending || !workflow?.id} onClick={() => preview.mutate({ id: workflow!.id })}>{preview.isPending ? t("正在读取定义…") : t("读取官方输入定义")}</Button>
    </div>
    {catalog.error ? <p role="alert">{catalog.error.message}</p> : null}
    {preview.error ? <p role="alert">{preview.error.message}</p> : null}
    {values.workflowDefinition ? <fieldset disabled={preview.isPending}><AutoDlWorkflowDefinitionEditor value={values.workflowDefinition} onChange={(definition) => applyDefinition(definition)} /></fieldset> : null}
    <details><summary>{t("离线导入官方详情")}</summary>
    <Field><FieldLabel>{t("官方工作流详情 JSON（可选）")}<Textarea rows={3} value={source} onChange={(event) => { setSource(event.target.value); setSourceError(null); }} /></FieldLabel></Field>
    <Button type="button" variant="outline" disabled={!source.trim() || preview.isPending} onClick={() => {
      try {
        const metadata: unknown = JSON.parse(source);
        if (!metadata || typeof metadata !== "object" || Array.isArray(metadata)) throw new Error(t("请输入官方工作流详情 JSON 对象。"));
        const envelope = metadata as Record<string, unknown>;
        const data = envelope.data && typeof envelope.data === "object" && !Array.isArray(envelope.data)
          ? envelope.data as Record<string, unknown> : envelope;
        preview.mutate({ id: typeof data.uuid === "string" ? data.uuid : workflow?.id ?? "", metadata });
      } catch { setSourceError(t("请输入官方工作流详情 JSON 对象。")); }
    }}>{t("导入官方详情 JSON")}</Button>
    {sourceError ? <p role="alert">{sourceError}</p> : null}</details>
    <FieldSet><FieldLegend>{t("可选视频分辨率")}</FieldLegend>
      <ToggleGroup type="multiple" variant="outline" aria-label={t("可选视频分辨率")} value={selected} onValueChange={(choices) => {
        const next = tiers.filter((tier) => choices.includes(tier));
        if (next.length === 0) return;
        const current = values.videoResolution ?? workflow?.defaultResolution;
        const videoResolution = next.find((tier) => tier === current) ?? next[0];
        const pricingByResolution = Object.fromEntries(Object.entries(values.pricingByResolution ?? {}).filter(([tier]) => next.some((choice) => choice === tier)));
        onChange({ ...values, videoResolutions: next, videoResolution, pricingByResolution,
          ...(values.workflowDefinition && videoResolution ? { workflowDefinition: { ...values.workflowDefinition, defaultResolution: videoResolution } } : {}) });
      }}>{tiers.map((tier) => <ToggleGroupItem key={tier} value={tier}>{tier}</ToggleGroupItem>)}</ToggleGroup>
    </FieldSet>
    <Field><FieldLabel className="ui-field block">{t("默认分辨率")}<Select value={values.videoResolution ?? workflow?.defaultResolution} onChange={(event) => onChange({
        ...values, videoResolutions: selected, videoResolution: event.target.value,
        ...(values.workflowDefinition ? { workflowDefinition: { ...values.workflowDefinition, defaultResolution: event.target.value } } : {}),
      })}>{selected.map((tier) => <option key={tier}>{tier}</option>)}</Select>
    </FieldLabel></Field>
    {workflow?.supportsSeed ? <Field><FieldLabel className="ui-field block">{t("随机种子（留空使用工作流默认）")}<Input type="number" min={1} max={MAX_SEED} step={1} value={values.seed ?? ""}
        onChange={(event) => onChange({ ...values, seed: event.target.value ? Number(event.target.value) : undefined })} />
    </FieldLabel></Field> : null}
    {workflow ? <p className="ui-muted media-model-help">{t("输入模式：{0}；图片 {1}–{2} 张，音频 {3}–{4} 条，时长 {5}–{6} 秒。画幅沿用卡片或项目；不支持的画幅与分辨率组合不能运行。导入和编辑后，点击发布或保存才会生效。", { "0": { TEXT: t("纯文本"), START_END: t("首尾帧"), GENERAL_REFERENCE: t("全能参考") }[workflow.mode], "1": workflow.minimumImages, "2": workflow.imageFields.length, "3": workflow.minimumAudios, "4": workflow.audioFields.length, "5": workflow.minimumSeconds, "6": workflow.maximumSeconds })}</p> : null}
  </>;
}
