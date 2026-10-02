import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { useMutation } from "@tanstack/react-query";
import { useEffect,useState } from "react";
import { previewRunningHubImport,type RunningHubDefinition,type RunningHubField } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { Select } from "../../shared/ui/Select";
import { RunningHubForm } from "../canvas/RunningHubForm";

const MAX_FIELDS = 64;
const MAX_OUTPUTS = 16;
const MAX_IMPORT_SOURCE_CHARACTERS = 256 * 1024;
function emptyDefinition(adapterId: string): RunningHubDefinition {
  const kind = adapterId === "RUNNINGHUB_VIDEO" ? "VIDEO" : adapterId === "RUNNINGHUB_AUDIO" ? "AUDIO" : "IMAGE";
  return { schemaVersion: 1, protocolVersion: "V2", targetType: "WORKFLOW", targetId: "", fields: [],
    fixedBindings: [], outputs: [{ kind, primary: true, maxCount: 1 }], instanceType: "default", usePersonalQueue: false, addMetadata: false };
}

/** Candidate import, human field review and the same form used on the canvas. No generation occurs here. */
export function RunningHubDefinitionEditor({ connectionId, adapterId, value, onChange }: {
  connectionId: string; adapterId: string; value?: RunningHubDefinition; onChange: (value: RunningHubDefinition) => void;
}) {
  useLocale();
  const definition = value ?? emptyDefinition(adapterId);
  const [source, setSource] = useState("");
  const [localError, setLocalError] = useState("");
  const [warnings, setWarnings] = useState<string[]>([]);
  const [previewValues, setPreviewValues] = useState<Record<string, string | number | boolean>>({});
  const [reviewed, setReviewed] = useState(false);
  useEffect(() => { if (!value) onChange(emptyDefinition(adapterId)); }, [adapterId, value, onChange]);
  const imported = useMutation({
    mutationFn: () => previewRunningHubImport(connectionId, { targetType: definition.targetType,
      targetId: definition.targetId, kind: adapterId === "RUNNINGHUB_VIDEO" ? "VIDEO_GENERATION" : adapterId === "RUNNINGHUB_AUDIO" ? "AUDIO_GENERATION" : "IMAGE_GENERATION",
      ...(source.trim() ? { source: JSON.parse(source) as unknown } : {}) }),
    onSuccess: (result) => { onChange(result.definition); setWarnings(result.warnings); setReviewed(false); setLocalError(""); setPreviewValues({}); },
  });
  function update(next: RunningHubDefinition) { setReviewed(false); onChange(next); }
  function field(index: number, patch: Partial<RunningHubField>) {
    update({ ...definition, fields: definition.fields.map((item, i) => i === index ? { ...item, ...patch } : item) });
  }
  function commitScalar(input: HTMLInputElement, errorMessage: string, commit: (value: string | number | boolean) => void) {
    try {
      const parsed: unknown = JSON.parse(input.value);
      if (typeof parsed !== "string" && typeof parsed !== "number" && typeof parsed !== "boolean") throw new Error(errorMessage);
      input.setCustomValidity("");
      commit(parsed);
      setLocalError("");
    } catch {
      input.setCustomValidity(errorMessage);
      setLocalError(errorMessage);
    }
  }
  return <div className="ui-stack runninghub-definition-editor">
    <p>{t("填写真实工作流 / 应用 ID，导入候选后整理用户可见字段。保存与发布不提交生成。")}</p>
    <div className="ui-form-grid">
      <Field><FieldLabel className="ui-field block">{t("目标类型")}<Select value={definition.targetType} onChange={(event) => update({ ...definition, targetType: event.target.value === "AI_APP" ? "AI_APP" : "WORKFLOW" })}>
        <option value="WORKFLOW">{t("ComfyUI 工作流")}</option><option value="AI_APP">{t("AI 应用")}</option>
      </Select></FieldLabel></Field>
      <Field><FieldLabel className="ui-field block">{t("真实目标 ID")}<Input required pattern="[0-9]{1,32}" value={definition.targetId}
        onChange={(event) => update({ ...definition, targetId: event.target.value })} placeholder="workflowId / webappId" /></FieldLabel></Field>
    </div>
    <p className="ui-muted">{t("从 RunningHub 请求路径 /run/workflow/ 或 /run/ai-app/ 读取 ID；文档目录中的 SKU ID 不能直接使用。")}</p>
    <details><summary>{t("导入脱敏 JSON（自动发现不可用时）")}</summary>
      <Field><FieldLabel className="ui-field block">{t("nodeInfoList 或 ComfyUI API-format JSON")}<Textarea value={source} onChange={(event) => setSource(event.target.value)} rows={5} maxLength={MAX_IMPORT_SOURCE_CHARACTERS} />
      </FieldLabel></Field><p>{t("请先移除 Key、密码和请求示例。这里不会执行 cURL。")}</p>
    </details>
    <Button variant="outline"  type="button" disabled={imported.isPending || !/^[0-9]{1,32}$/.test(definition.targetId)}
      onClick={() => { setLocalError(""); imported.mutate(); }}>{imported.isPending ? t("正在导入…") : source.trim() ? t("导入 JSON 字段") : t("自动发现参数")}</Button>
    {imported.error ? <p role="alert">{imported.error.message}</p> : null}
    {warnings.map((warning) => <p className="ui-muted" key={warning}>{warning}</p>)}
    <fieldset className="ui-stack"><legend>{t("整理字段")}</legend>
      {definition.fields.map((item, index) => <fieldset className="ui-stack" key={index}>
        <legend>{item.label || t("字段 {0}", { "0": index + 1 })}</legend>
        <div className="ui-form-grid">
          <Field><FieldLabel className="ui-field block">{t("用户看到的名称")}<Input required maxLength={160} value={item.label} onChange={(event) => field(index, { label: event.target.value })} /></FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">{t("稳定字段键")}<Input required pattern="[A-Za-z][A-Za-z0-9_]{0,63}" value={item.key} onChange={(event) => field(index, { key: event.target.value })} /></FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">{t("类型")}<Select value={item.type} onChange={(event) => field(index, { type: event.target.value as RunningHubField["type"], defaultValue: null, options: [] })}>
            {(["STRING", "NUMBER", "INTEGER", "BOOLEAN", "SELECT", "IMAGE", "AUDIO", "VIDEO"] as const).map((type) => <option value={type} key={type}>{({ STRING: t("文字"), NUMBER: t("数值"), INTEGER: t("整数"), BOOLEAN: t("开关"), SELECT: t("下拉选择"), IMAGE: t("图片素材"), AUDIO: t("音频素材"), VIDEO: t("视频素材") })[type]}</option>)}
          </Select></FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">{t("输入来源")}<Select value={item.source ?? "PARAMETER"} onChange={(event) => field(index, { source: event.target.value as RunningHubField["source"] })}>
            <option value="PARAMETER">{t("专属表单字段")}</option>{item.type === "STRING" ? <option value="PROMPT">{t("画布提示词")}</option> : null}{item.type === "INTEGER" && adapterId === "RUNNINGHUB_VIDEO" ? <option value="DURATION_SECONDS">{t("视频时长")}</option> : null}
          </Select></FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">{t("节点 ID")}<Input required pattern="[0-9]{1,32}" value={item.nodeId} onChange={(event) => field(index, { nodeId: event.target.value })} /></FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">{t("节点字段")}<Input required pattern="[A-Za-z_][A-Za-z0-9_]{0,79}" value={item.fieldName} onChange={(event) => field(index, { fieldName: event.target.value })} /></FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">{t("说明")}<Input maxLength={1000} value={item.description ?? ""} onChange={(event) => field(index, { description: event.target.value })} /></FieldLabel></Field>
          <label><Checkbox  checked={item.required ?? false} onCheckedChange={(event) => field(index, { required: event === true })} /> {t(" 必填")}</label>
          <label><Checkbox  checked={item.advanced ?? false} onCheckedChange={(event) => field(index, { advanced: event === true })} /> {t(" 放入高级参数")}</label>
          {["IMAGE", "AUDIO", "VIDEO"].includes(item.type) ? <Field><FieldLabel className="ui-field block">{t("上传后的引用格式")}<Select value={item.resourceFormat ?? "FILE_NAME"} onChange={(event) => field(index, { resourceFormat: event.target.value as RunningHubField["resourceFormat"] })}>
            <option value="FILE_NAME">{t("内部文件名（Load 节点）")}</option><option value="URL">{t("下载 URL（URL 输入节点）")}</option>
          </Select></FieldLabel></Field> : <Field><FieldLabel className="ui-field block">{t("默认值")}{item.type === "BOOLEAN" ? <Select value={item.defaultValue == null ? "" : String(item.defaultValue)} onChange={(event) => field(index, { defaultValue: event.target.value ? event.target.value === "true" : null })}><option value="">{t("不设默认")}</option><option value="true">{t("开启")}</option><option value="false">{t("关闭")}</option></Select>
            : item.type === "STRING" ? <Input value={String(item.defaultValue ?? "")} onChange={(event) => field(index, { defaultValue: event.target.value })} />
            : <Input key={JSON.stringify(item.defaultValue)} defaultValue={item.defaultValue == null ? "" : JSON.stringify(item.defaultValue)} onChange={() => setReviewed(false)} onBlur={(event) => {
              const raw = event.target.value;
              if (!raw) { event.target.setCustomValidity(""); field(index, { defaultValue: null }); setLocalError(""); return; }
              commitScalar(event.target, t("默认值需要有效数字或 JSON 标量。"), (defaultValue) => field(index, { defaultValue }));
            }} />}</FieldLabel></Field>}

          {["NUMBER", "INTEGER"].includes(item.type) ? <>{(["minimum", "maximum"] as const).map((bound) => <Field><FieldLabel className="ui-field block" key={bound}>{bound === "minimum" ? t("最小值") : t("最大值")}<Input type="number" value={item[bound] ?? ""} onChange={(event) => field(index, { [bound]: event.target.value ? Number(event.target.value) : null })} /></FieldLabel></Field>)}</> : null}
          {item.type === "STRING" ? <Field><FieldLabel className="ui-field block">{t("最长字符数")}<Input type="number" min={1} max={20000} value={item.maxLength ?? ""} onChange={(event) => field(index, { maxLength: event.target.value ? Number(event.target.value) : null })} /></FieldLabel></Field> : null}
          <Field><FieldLabel className="ui-field block">{t("显示条件")}<Select value={item.enabledWhen?.field ?? ""} onChange={(event) => field(index, { enabledWhen: event.target.value ? { field: event.target.value, value: definition.fields.find((parent) => parent.key === event.target.value)?.defaultValue ?? "" } : null })}>
            <option value="">{t("始终显示")}</option>{definition.fields.filter((parent) => parent.key !== item.key && !parent.enabledWhen && !["IMAGE", "AUDIO", "VIDEO"].includes(parent.type)).map((parent) => <option key={parent.key} value={parent.key}>{parent.label}</option>)}
          </Select></FieldLabel></Field>
          {item.enabledWhen ? <Field><FieldLabel className="ui-field block">{t("条件值（JSON 标量）")}<Input key={JSON.stringify(item.enabledWhen)} defaultValue={JSON.stringify(item.enabledWhen.value)} onChange={() => setReviewed(false)} onBlur={(event) => {
            commitScalar(event.target, t("显示条件需要有效的 JSON 标量。"), (value) => field(index, { enabledWhen: { field: item.enabledWhen!.field, value } }));
          }} /></FieldLabel></Field> : null}
          <Field><FieldLabel className="ui-field block">{t("提交编码")}<Select value={item.encoding ?? "NATIVE"} onChange={(event) => field(index, { encoding: event.target.value as RunningHubField["encoding"] })}><option value="NATIVE">{t("保持原类型")}</option><option value="STRING">{t("转成字符串")}</option></Select></FieldLabel></Field>
        </div>
        {item.type === "SELECT" ? <Field><FieldLabel className="ui-field block">{t("下拉选项（每行一个值；导入时保留显示名）")}<Textarea key={JSON.stringify(item.options)} defaultValue={(item.options ?? []).map((option) => String(option.value)).join("\n")} onBlur={(event) => field(index, { options: event.target.value.split("\n").filter(Boolean).map((text) => item.options?.find((option) => String(option.value) === text) ?? ({ label: text, value: text })) })} />
        </FieldLabel></Field> : null}
        <Button variant="ghost"  type="button" onClick={() => update({ ...definition, fields: definition.fields.filter((_, i) => i !== index) })}>{t("移除此候选字段")}</Button>
      </fieldset>)}
      <Button variant="outline"  type="button" disabled={definition.fields.length >= MAX_FIELDS} onClick={() => update({ ...definition,
        fields: [...definition.fields, { key: `field${definition.fields.length + 1}`, label: t("新参数"), type: "STRING", nodeId: "", fieldName: "", required: true, advanced: false, source: "PARAMETER" }] })}>{t("手动添加字段")}</Button>
    </fieldset>
    <fieldset className="ui-stack"><legend>{t("输出映射")}</legend>
      {definition.outputs.map((output, index) => <div className="ui-form-grid" key={index}>
        <Field><FieldLabel className="ui-field block">{t("输出节点（留空匹配此类型）")}<Input pattern="[0-9]{1,32}" value={output.nodeId ?? ""} onChange={(event) => update({ ...definition, outputs: definition.outputs.map((item, i) => i === index ? { ...item, nodeId: event.target.value || null } : item) })} /></FieldLabel></Field>
        <Field><FieldLabel className="ui-field block">{t("媒体类型")}<Select value={output.kind} disabled={output.primary} onChange={(event) => update({ ...definition, outputs: definition.outputs.map((item, i) => i === index ? { ...item, kind: event.target.value as typeof output.kind } : item) })}><option value="IMAGE">{t("图片")}</option><option value="VIDEO">{t("视频")}</option><option value="AUDIO">{t("音频")}</option></Select></FieldLabel></Field>
        <Field><FieldLabel className="ui-field block">{t("最多结果数")}<Input type="number" min={1} max={MAX_OUTPUTS} value={output.maxCount} onChange={(event) => update({ ...definition, outputs: definition.outputs.map((item, i) => i === index ? { ...item, maxCount: Number(event.target.value) } : item) })} /></FieldLabel></Field>
        {output.primary ? <span>{t("主输出 · 首个结果留在当前节点")}</span> : <Button variant="ghost" type="button" onClick={() => update({ ...definition, outputs: definition.outputs.filter((_, i) => i !== index) })}>{t("移除额外输出")}</Button>}
      </div>)}
      <Button variant="ghost" type="button" disabled={definition.outputs.length >= MAX_OUTPUTS} onClick={() => update({ ...definition, outputs: [...definition.outputs, { kind: "IMAGE", primary: false, maxCount: 1 }] })}>{t("添加额外输出映射")}</Button>
      <p>{t("所有映射的结果上限合计最多 16。同一类型的通配映射不能再添加该类型的节点映射。额外结果创建独立节点；未匹配的媒体结果会阻断归档。")}</p>
    </fieldset>
    <details><summary>{t("执行选项与固定映射")}</summary>
      <Field><FieldLabel className="ui-field block">{t("实例")}<Select value={definition.instanceType ?? "default"} onChange={(event) => update({ ...definition, instanceType: event.target.value as RunningHubDefinition["instanceType"] })}><option value="default">default</option><option value="plus">plus</option><option value="ultra">ultra</option></Select></FieldLabel></Field>
      <label><Checkbox  checked={definition.usePersonalQueue ?? false} onCheckedChange={(event) => update({ ...definition, usePersonalQueue: event === true })} /> {t(" 使用个人队列")}</label>
      {definition.targetType === "WORKFLOW" ? <label><Checkbox  checked={definition.addMetadata ?? false} onCheckedChange={(event) => update({ ...definition, addMetadata: event === true })} /> {t(" 结果包含元数据")}</label> : null}
      <Field><FieldLabel className="ui-field block">{t("保留实例秒数（额外计费；留空关闭）")}<Input type="number" min={10} max={180} value={definition.retainSeconds ?? ""} onChange={(event) => update({ ...definition, retainSeconds: event.target.value ? Number(event.target.value) : null })} /></FieldLabel></Field>
      <p>{t("固定参数映射可以保持远程工作流的默认值；下方可填写受控的固定值，不支持脚本或表达式。")}</p>
      {(definition.fixedBindings ?? []).map((binding, index) => <div className="ui-form-grid" key={index}>
        <Field><FieldLabel className="ui-field block">{t("固定节点 ID")}<Input required pattern="[0-9]{1,32}" value={binding.nodeId} onChange={(event) => update({ ...definition, fixedBindings: definition.fixedBindings?.map((item, i) => i === index ? { ...item, nodeId: event.target.value } : item) })} /></FieldLabel></Field>
        <Field><FieldLabel className="ui-field block">{t("固定字段")}<Input required pattern="[A-Za-z_][A-Za-z0-9_]{0,79}" value={binding.fieldName} onChange={(event) => update({ ...definition, fixedBindings: definition.fixedBindings?.map((item, i) => i === index ? { ...item, fieldName: event.target.value } : item) })} /></FieldLabel></Field>
        <Field><FieldLabel className="ui-field block">{t("固定值（JSON 标量）")}<Input key={JSON.stringify(binding.value)} defaultValue={JSON.stringify(binding.value)} onChange={() => setReviewed(false)} onBlur={(event) => {
          commitScalar(event.target, t("固定值需要有效的 JSON 文字、数字或布尔值。"), (value) => update({ ...definition,
            fixedBindings: definition.fixedBindings?.map((item, i) => i === index ? { ...item, value } : item) }));
        }} /></FieldLabel></Field>
        <Button variant="ghost" type="button" onClick={() => update({ ...definition, fixedBindings: definition.fixedBindings?.filter((_, i) => i !== index) })}>{t("移除固定映射")}</Button>
      </div>)}
      <Button variant="ghost" type="button" onClick={() => update({ ...definition, fixedBindings: [...(definition.fixedBindings ?? []), { nodeId: "", fieldName: "", value: "", encoding: "NATIVE" }] })}>{t("添加固定映射")}</Button>
    </details>
    <fieldset><legend>{t("创作者表单预览 · 离线")}</legend><RunningHubForm definition={definition} values={previewValues} prompt="" durationSeconds={null} choices={[]}
      onChange={(key, next) => setPreviewValues((current) => { const values = { ...current }; if (next === undefined) delete values[key]; else values[key] = next; return values; })} /></fieldset>
    <p className="ui-muted">{t("只冻结本地字段契约；云端工作流可能变化。估算费用在价格设置中填写，未知费用不会当成零。")}</p>
    {localError ? <p role="alert">{localError}</p> : null}
    <label><Checkbox required  checked={reviewed && !localError} onCheckedChange={(event) => setReviewed(event === true)} /> {t(" 已核对开放字段、素材格式与输出映射")}</label>
  </div>;
}
