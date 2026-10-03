import { SlidersHorizontal,Trash } from "@phosphor-icons/react";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { useMutation } from "@tanstack/react-query";
import { Fragment,useEffect,useState } from "react";
import { previewRunningHubImport,type RunningHubDefinition,type RunningHubField } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { Select } from "../../shared/ui/Select";
import { Table,TableBody,TableCell,TableHead,TableHeader,TableRow } from "../../shared/ui/primitives/table";
import "./RunningHubDefinitionEditor.css";
import { RunningHubForm } from "../canvas/RunningHubForm";

const MAX_FIELDS = 64;
const FIELD_TABLE_COLUMNS = 9;
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
  const [expandedField, setExpandedField] = useState<number | null>(null);
  useEffect(() => { if (!value) onChange(emptyDefinition(adapterId)); }, [adapterId, value, onChange]);
  const imported = useMutation({
    mutationFn: () => previewRunningHubImport(connectionId, { targetType: definition.targetType,
      targetId: definition.targetId, kind: adapterId === "RUNNINGHUB_VIDEO" ? "VIDEO_GENERATION" : adapterId === "RUNNINGHUB_AUDIO" ? "AUDIO_GENERATION" : "IMAGE_GENERATION",
      ...(source.trim() ? { source: JSON.parse(source) as unknown } : {}) }),
    onSuccess: (result) => { onChange(result.definition); setWarnings(result.warnings); setReviewed(false); setLocalError(""); setPreviewValues({}); setExpandedField(null); },
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
    <p>{t("settings.runningHub.setupHint")}</p>
    <div className="ui-form-grid">
      <Field><FieldLabel className="ui-field block">{t("settings.runningHub.targetType")}<Select value={definition.targetType} onChange={(event) => update({ ...definition, targetType: event.target.value === "AI_APP" ? "AI_APP" : "WORKFLOW" })}>
        <option value="WORKFLOW">{t("settings.runningHub.comfyWorkflow")}</option><option value="AI_APP">{t("settings.runningHub.aiApp")}</option>
      </Select></FieldLabel></Field>
      <Field><FieldLabel className="ui-field block">{t("settings.runningHub.targetId")}<Input required pattern="[0-9]{1,32}" value={definition.targetId}
        onChange={(event) => update({ ...definition, targetId: event.target.value })} placeholder="workflowId / webappId" /></FieldLabel></Field>
    </div>
    <p className="ui-muted">{t("settings.runningHub.targetIdHint")}</p>
    <details><summary>{t("settings.runningHub.importJson")}</summary>
      <Field><FieldLabel className="ui-field block">{t("settings.runningHub.importPlaceholder")}<Textarea value={source} onChange={(event) => setSource(event.target.value)} rows={5} maxLength={MAX_IMPORT_SOURCE_CHARACTERS} />
      </FieldLabel></Field><p>{t("settings.runningHub.sanitizeImportHint")}</p>
    </details>
    <Button variant="outline"  type="button" disabled={imported.isPending || !/^[0-9]{1,32}$/.test(definition.targetId)}
      onClick={() => { setLocalError(""); imported.mutate(); }}>{imported.isPending ? t("settings.runningHub.importing") : source.trim() ? t("settings.runningHub.importFields") : t("settings.runningHub.discover")}</Button>
    {imported.error ? <p role="alert">{imported.error.message}</p> : null}
    {warnings.map((warning) => <p className="ui-muted" key={warning}>{warning}</p>)}
    <fieldset className="ui-stack"><legend>{t("settings.runningHub.mappingTable")}</legend>
      <p className="ui-muted">{t("settings.runningHub.mappingHint")}</p>
      <div className="runninghub-mapping-scroll" role="region" aria-label={t("settings.runningHub.mappingTable")} tabIndex={0}>
      <Table className="runninghub-mapping-table" aria-label={t("settings.runningHub.mappingTable")}>
        <TableHeader><TableRow><TableHead scope="col">{t("settings.runningHub.nodeId")}</TableHead><TableHead scope="col">{t("settings.runningHub.nodeField")}</TableHead><TableHead scope="col">{t("settings.runningHub.label")}</TableHead><TableHead scope="col">{t("settings.runningHub.fieldKey")}</TableHead><TableHead scope="col">{t("settings.runningHub.type")}</TableHead><TableHead scope="col">{t("settings.runningHub.inputSource")}</TableHead><TableHead scope="col">{t("settings.runningHub.defaultOrFormat")}</TableHead><TableHead scope="col">{t("settings.runningHub.requiredSuffix")}</TableHead><TableHead scope="col">{t("settings.runningHub.mappingActions")}</TableHead></TableRow></TableHeader>
        <TableBody>
        {definition.fields.map((item, index) => <Fragment key={index}>
          <TableRow aria-label={item.label || t("settings.runningHub.fieldLabel", { "0": index + 1 })}>
            <TableCell><Field><FieldLabel className="ui-field block"><span className="sr-only">{t("settings.runningHub.nodeId")}</span><Input required pattern="[0-9]{1,32}" value={item.nodeId} onChange={(event) => field(index, { nodeId: event.target.value })} /></FieldLabel></Field></TableCell>
            <TableCell><Field><FieldLabel className="ui-field block"><span className="sr-only">{t("settings.runningHub.nodeField")}</span><Input required pattern="[A-Za-z_][A-Za-z0-9_]{0,79}" value={item.fieldName} onChange={(event) => field(index, { fieldName: event.target.value })} /></FieldLabel></Field></TableCell>
            <TableCell><Field><FieldLabel className="ui-field block"><span className="sr-only">{t("settings.runningHub.label")}</span><Input required maxLength={160} value={item.label} onChange={(event) => field(index, { label: event.target.value })} /></FieldLabel></Field></TableCell>
            <TableCell><Field><FieldLabel className="ui-field block"><span className="sr-only">{t("settings.runningHub.fieldKey")}</span><Input required pattern="[A-Za-z][A-Za-z0-9_]{0,63}" value={item.key} onChange={(event) => field(index, { key: event.target.value })} /></FieldLabel></Field></TableCell>
            <TableCell><Field><FieldLabel className="ui-field block"><span className="sr-only">{t("settings.runningHub.type")}</span><Select value={item.type} onChange={(event) => field(index, { type: event.target.value as RunningHubField["type"], defaultValue: null, options: [] })}>
            {(["STRING", "NUMBER", "INTEGER", "BOOLEAN", "SELECT", "IMAGE", "AUDIO", "VIDEO"] as const).map((type) => <option value={type} key={type}>{({ STRING: t("common.text"), NUMBER: t("settings.runningHub.numberInput"), INTEGER: t("settings.runningHub.integerInput"), BOOLEAN: t("settings.runningHub.booleanInput"), SELECT: t("settings.runningHub.selectInput"), IMAGE: t("settings.runningHub.imageAsset"), AUDIO: t("settings.runningHub.audioAsset"), VIDEO: t("settings.runningHub.videoAsset") })[type]}</option>)}
          </Select></FieldLabel></Field></TableCell>
            <TableCell><Field><FieldLabel className="ui-field block"><span className="sr-only">{t("settings.runningHub.inputSource")}</span><Select value={item.source ?? "PARAMETER"} onChange={(event) => field(index, { source: event.target.value as RunningHubField["source"] })}>
            <option value="PARAMETER">{t("settings.runningHub.formFields")}</option>{item.type === "STRING" ? <option value="PROMPT">{t("settings.runningHub.canvasPrompt")}</option> : null}{item.type === "INTEGER" && adapterId === "RUNNINGHUB_VIDEO" ? <option value="DURATION_SECONDS">{t("settings.runningHub.videoDuration")}</option> : null}
          </Select></FieldLabel></Field></TableCell>
            <TableCell>{["IMAGE", "AUDIO", "VIDEO"].includes(item.type) ? <Field><FieldLabel className="ui-field block"><span className="sr-only">{t("settings.runningHub.uploadReferenceFormat")}</span><Select value={item.resourceFormat ?? "FILE_NAME"} onChange={(event) => field(index, { resourceFormat: event.target.value as RunningHubField["resourceFormat"] })}>
            <option value="FILE_NAME">{t("settings.runningHub.filenameReference")}</option><option value="URL">{t("settings.runningHub.urlReference")}</option>
          </Select></FieldLabel></Field> : <Field><FieldLabel className="ui-field block"><span className="sr-only">{t("settings.runningHub.defaultValue")}</span>{item.type === "BOOLEAN" ? <Select value={item.defaultValue == null ? "" : String(item.defaultValue)} onChange={(event) => field(index, { defaultValue: event.target.value ? event.target.value === "true" : null })}><option value="">{t("settings.runningHub.noDefault")}</option><option value="true">{t("common.enabled")}</option><option value="false">{t("common.close")}</option></Select>
            : item.type === "STRING" ? <Input value={String(item.defaultValue ?? "")} onChange={(event) => field(index, { defaultValue: event.target.value })} />
            : <Input key={JSON.stringify(item.defaultValue)} defaultValue={item.defaultValue == null ? "" : JSON.stringify(item.defaultValue)} onChange={() => setReviewed(false)} onBlur={(event) => {
              const raw = event.target.value;
              if (!raw) { event.target.setCustomValidity(""); field(index, { defaultValue: null }); setLocalError(""); return; }
              commitScalar(event.target, t("settings.runningHub.invalidDefault"), (defaultValue) => field(index, { defaultValue }));
            }} />}</FieldLabel></Field>}</TableCell>
            <TableCell><Checkbox aria-label={t("settings.runningHub.requiredSuffix")} checked={item.required ?? false} onCheckedChange={(checked) => field(index, { required: checked === true })} /></TableCell>
            <TableCell><div className="runninghub-mapping-actions">
              <Button variant="ghost" size="icon-sm" type="button" aria-label={t("settings.runningHub.moreSettings")} title={t("settings.runningHub.moreSettings")} aria-expanded={expandedField === index}
                onClick={() => setExpandedField(expandedField === index ? null : index)}>
                <SlidersHorizontal /></Button>
              <Button variant="ghost" size="icon-sm" type="button" aria-label={`${t("settings.runningHub.removeCandidate")} · ${item.label}`}
                onClick={() => { setExpandedField(null); update({ ...definition, fields: definition.fields.filter((_, i) => i !== index) }); }}><Trash /></Button>
            </div></TableCell>
          </TableRow>
          <TableRow aria-label={`${t("settings.runningHub.moreSettings")} · ${item.label}`} hidden={expandedField !== index} onInvalidCapture={() => setExpandedField(index)}>
            <TableCell colSpan={FIELD_TABLE_COLUMNS} className="whitespace-normal">
              <fieldset className="runninghub-field-details ui-stack"><legend>{item.label || t("settings.runningHub.fieldLabel", { "0": index + 1 })}</legend>
                <div className="ui-form-grid">
                  <Field><FieldLabel className="ui-field block">{t("settings.runningHub.description")}<Input maxLength={1000} value={item.description ?? ""} onChange={(event) => field(index, { description: event.target.value })} /></FieldLabel></Field>
                  <label><Checkbox  checked={item.advanced ?? false} onCheckedChange={(event) => field(index, { advanced: event === true })} /> {t("settings.runningHub.advancedParameterLabel")}</label>
                  {["NUMBER", "INTEGER"].includes(item.type) ? <>{(["minimum", "maximum"] as const).map((bound) => <Field key={bound}><FieldLabel className="ui-field block">{bound === "minimum" ? t("settings.runningHub.minimum") : t("settings.runningHub.maximum")}<Input type="number" value={item[bound] ?? ""} onChange={(event) => field(index, { [bound]: event.target.value ? Number(event.target.value) : null })} /></FieldLabel></Field>)}</> : null}
                  {item.type === "STRING" ? <Field><FieldLabel className="ui-field block">{t("settings.runningHub.maxLength")}<Input type="number" min={1} max={20000} value={item.maxLength ?? ""} onChange={(event) => field(index, { maxLength: event.target.value ? Number(event.target.value) : null })} /></FieldLabel></Field> : null}
                  <Field><FieldLabel className="ui-field block">{t("settings.runningHub.visibilityCondition")}<Select value={item.enabledWhen?.field ?? ""} onChange={(event) => field(index, { enabledWhen: event.target.value ? { field: event.target.value, value: definition.fields.find((parent) => parent.key === event.target.value)?.defaultValue ?? "" } : null })}>
                    <option value="">{t("settings.runningHub.alwaysVisible")}</option>{definition.fields.filter((parent) => parent.key !== item.key && !parent.enabledWhen && !["IMAGE", "AUDIO", "VIDEO"].includes(parent.type)).map((parent) => <option key={parent.key} value={parent.key}>{parent.label}</option>)}
                  </Select></FieldLabel></Field>
                  {item.enabledWhen ? <Field><FieldLabel className="ui-field block">{t("settings.runningHub.conditionValue")}<Input key={JSON.stringify(item.enabledWhen)} defaultValue={JSON.stringify(item.enabledWhen.value)} onChange={() => setReviewed(false)} onBlur={(event) => {
                    commitScalar(event.target, t("settings.runningHub.invalidCondition"), (value) => field(index, { enabledWhen: { field: item.enabledWhen!.field, value } }));
                  }} /></FieldLabel></Field> : null}
                  <Field><FieldLabel className="ui-field block">{t("settings.runningHub.encoding")}<Select value={item.encoding ?? "NATIVE"} onChange={(event) => field(index, { encoding: event.target.value as RunningHubField["encoding"] })}><option value="NATIVE">{t("settings.runningHub.preserveType")}</option><option value="STRING">{t("settings.runningHub.stringEncoding")}</option></Select></FieldLabel></Field>
                </div>
        {item.type === "SELECT" ? <Field><FieldLabel className="ui-field block">{t("settings.runningHub.choices")}<Textarea key={JSON.stringify(item.options)} defaultValue={(item.options ?? []).map((option) => String(option.value)).join("\n")} onBlur={(event) => field(index, { options: event.target.value.split("\n").filter(Boolean).map((text) => item.options?.find((option) => String(option.value) === text) ?? ({ label: text, value: text })) })} />
        </FieldLabel></Field> : null}
              </fieldset>
            </TableCell>
          </TableRow>
        </Fragment>)}
        </TableBody>
      </Table>
      </div>
      <Button variant="outline"  type="button" disabled={definition.fields.length >= MAX_FIELDS} onClick={() => update({ ...definition,
        fields: [...definition.fields, { key: `field${definition.fields.length + 1}`, label: t("settings.runningHub.newParameter"), type: "STRING", nodeId: "", fieldName: "", required: true, advanced: false, source: "PARAMETER" }] })}>{t("settings.runningHub.addField")}</Button>
    </fieldset>
    <fieldset className="ui-stack"><legend>{t("settings.runningHub.outputMappings")}</legend>
      <Table className="runninghub-secondary-table" aria-label={t("settings.runningHub.outputMappings")}>
      <TableHeader><TableRow><TableHead scope="col">{t("settings.runningHub.outputNode")}</TableHead><TableHead scope="col">{t("media.kind")}</TableHead><TableHead scope="col">{t("settings.runningHub.resultLimit")}</TableHead><TableHead scope="col">{t("settings.runningHub.mappingActions")}</TableHead></TableRow></TableHeader><TableBody>
      {definition.outputs.map((output, index) => <TableRow key={index}>
        <TableCell><Field><FieldLabel className="ui-field block"><span className="sr-only">{t("settings.runningHub.outputNode")}</span><Input pattern="[0-9]{1,32}" value={output.nodeId ?? ""} onChange={(event) => update({ ...definition, outputs: definition.outputs.map((item, i) => i === index ? { ...item, nodeId: event.target.value || null } : item) })} /></FieldLabel></Field></TableCell>
        <TableCell><Field><FieldLabel className="ui-field block"><span className="sr-only">{t("media.kind")}</span><Select value={output.kind} disabled={output.primary} onChange={(event) => update({ ...definition, outputs: definition.outputs.map((item, i) => i === index ? { ...item, kind: event.target.value as typeof output.kind } : item) })}><option value="IMAGE">{t("common.image")}</option><option value="VIDEO">{t("common.video")}</option><option value="AUDIO">{t("common.audio")}</option></Select></FieldLabel></Field></TableCell>
        <TableCell><Field><FieldLabel className="ui-field block"><span className="sr-only">{t("settings.runningHub.resultLimit")}</span><Input type="number" min={1} max={MAX_OUTPUTS} value={output.maxCount} onChange={(event) => update({ ...definition, outputs: definition.outputs.map((item, i) => i === index ? { ...item, maxCount: Number(event.target.value) } : item) })} /></FieldLabel></Field></TableCell>
        <TableCell>{output.primary ? <span>{t("settings.runningHub.primaryOutputHint")}</span> : <Button variant="ghost" type="button" onClick={() => update({ ...definition, outputs: definition.outputs.filter((_, i) => i !== index) })}>{t("settings.runningHub.removeOutput")}</Button>}</TableCell>
      </TableRow>)}
      </TableBody></Table>
      <Button variant="ghost" type="button" disabled={definition.outputs.length >= MAX_OUTPUTS} onClick={() => update({ ...definition, outputs: [...definition.outputs, { kind: "IMAGE", primary: false, maxCount: 1 }] })}>{t("settings.runningHub.addOutputMapping")}</Button>
      <p>{t("settings.runningHub.outputLimitsHint")}</p>
    </fieldset>
    <details><summary>{t("settings.runningHub.executionOptions")}</summary>
      <Field><FieldLabel className="ui-field block">{t("settings.runningHub.instance")}<Select value={definition.instanceType ?? "default"} onChange={(event) => update({ ...definition, instanceType: event.target.value as RunningHubDefinition["instanceType"] })}><option value="default">default</option><option value="plus">plus</option><option value="ultra">ultra</option></Select></FieldLabel></Field>
      <label><Checkbox  checked={definition.usePersonalQueue ?? false} onCheckedChange={(event) => update({ ...definition, usePersonalQueue: event === true })} /> {t("settings.runningHub.personalQueueSuffix")}</label>
      {definition.targetType === "WORKFLOW" ? <label><Checkbox  checked={definition.addMetadata ?? false} onCheckedChange={(event) => update({ ...definition, addMetadata: event === true })} /> {t("settings.runningHub.resultMetadataLabel")}</label> : null}
      <Field><FieldLabel className="ui-field block">{t("settings.runningHub.instanceRetentionSeconds")}<Input type="number" min={10} max={180} value={definition.retainSeconds ?? ""} onChange={(event) => update({ ...definition, retainSeconds: event.target.value ? Number(event.target.value) : null })} /></FieldLabel></Field>
      <p>{t("settings.runningHub.fixedParametersHint")}</p>
      <Table className="runninghub-secondary-table" aria-label={t("settings.runningHub.fixedParametersHint")}>
      <TableHeader><TableRow><TableHead scope="col">{t("settings.runningHub.fixedNodeId")}</TableHead><TableHead scope="col">{t("settings.runningHub.fixedFields")}</TableHead><TableHead scope="col">{t("settings.runningHub.fixedValue")}</TableHead><TableHead scope="col">{t("settings.runningHub.mappingActions")}</TableHead></TableRow></TableHeader><TableBody>
      {(definition.fixedBindings ?? []).map((binding, index) => <TableRow key={index}>
        <TableCell><Field><FieldLabel className="ui-field block"><span className="sr-only">{t("settings.runningHub.fixedNodeId")}</span><Input required pattern="[0-9]{1,32}" value={binding.nodeId} onChange={(event) => update({ ...definition, fixedBindings: definition.fixedBindings?.map((item, i) => i === index ? { ...item, nodeId: event.target.value } : item) })} /></FieldLabel></Field></TableCell>
        <TableCell><Field><FieldLabel className="ui-field block"><span className="sr-only">{t("settings.runningHub.fixedFields")}</span><Input required pattern="[A-Za-z_][A-Za-z0-9_]{0,79}" value={binding.fieldName} onChange={(event) => update({ ...definition, fixedBindings: definition.fixedBindings?.map((item, i) => i === index ? { ...item, fieldName: event.target.value } : item) })} /></FieldLabel></Field></TableCell>
        <TableCell><Field><FieldLabel className="ui-field block"><span className="sr-only">{t("settings.runningHub.fixedValue")}</span><Input key={JSON.stringify(binding.value)} defaultValue={JSON.stringify(binding.value)} onChange={() => setReviewed(false)} onBlur={(event) => {
          commitScalar(event.target, t("settings.runningHub.invalidFixedValue"), (value) => update({ ...definition,
            fixedBindings: definition.fixedBindings?.map((item, i) => i === index ? { ...item, value } : item) }));
        }} /></FieldLabel></Field></TableCell>
        <TableCell><Button variant="ghost" type="button" onClick={() => update({ ...definition, fixedBindings: definition.fixedBindings?.filter((_, i) => i !== index) })}>{t("settings.runningHub.removeFixedMapping")}</Button></TableCell>
      </TableRow>)}
      </TableBody></Table>
      <Button variant="ghost" type="button" onClick={() => update({ ...definition, fixedBindings: [...(definition.fixedBindings ?? []), { nodeId: "", fieldName: "", value: "", encoding: "NATIVE" }] })}>{t("settings.runningHub.addFixedMapping")}</Button>
    </details>
    <fieldset><legend>{t("settings.runningHub.formPreview")}</legend><RunningHubForm definition={definition} values={previewValues} prompt="" durationSeconds={null} choices={[]}
      onChange={(key, next) => setPreviewValues((current) => { const values = { ...current }; if (next === undefined) delete values[key]; else values[key] = next; return values; })} /></fieldset>
    <p className="ui-muted">{t("settings.runningHub.contractVersionHint")}</p>
    {localError ? <p role="alert">{localError}</p> : null}
    <label><Checkbox required  checked={reviewed && !localError} onCheckedChange={(event) => setReviewed(event === true)} /> {t("settings.runningHub.reviewedContractLabel")}</label>
  </div>;
}
