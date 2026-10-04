import { useMutation } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { ApiError, previewComfyWorkflow, type ComfyUiGraph, type ComfyUiWorkflowDefinition, type MediaCapability, type RunningHubField } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../../shared/ui/primitives/table";
import { CapabilityConfigurationFields } from "./CapabilityConfigurationFields";
import "./ComfyWorkflowEditor.css";
import { COMFY_WORKFLOW_DEFAULT_SIDE, COMFY_WORKFLOW_LIMITS as LIMITS, comfyParameterKey, comfyParameterTypes, comfyReferenceCount, comfyWorkflowProblem } from "./comfyWorkflow";

type Settings = MediaCapability["settings"];
type Binding = ComfyUiWorkflowDefinition["bindings"][number];
type Source = Binding["source"];
const NO_MAPPING = "FIXED";
const PARAMETER_MAPPING = "PARAMETER";
const STEPS = ["import", "mapping", "review"] as const;
type Step = typeof STEPS[number];
const TEXT_SOURCES: Source[] = ["PROMPT", "NEGATIVE_PROMPT", "REFERENCE_IMAGE"];
const NUMBER_SOURCES: Source[] = ["SEED", "WIDTH", "HEIGHT", "BATCH_SIZE"];
const VIDEO_SOURCES: Source[] = ["DURATION_SECONDS", "FRAME_COUNT", "FPS"];
const SOURCE_KEYS = {
  PROMPT: "settings.comfy.sourcePrompt", NEGATIVE_PROMPT: "settings.comfy.sourceNegative", SEED: "settings.comfy.sourceSeed",
  WIDTH: "settings.comfy.sourceWidth", HEIGHT: "settings.comfy.sourceHeight", REFERENCE_IMAGE: "settings.comfy.sourceImage",
  DURATION_SECONDS: "settings.comfy.sourceDuration", FRAME_COUNT: "settings.comfy.sourceFrames", FPS: "settings.comfy.sourceFps", BATCH_SIZE: "settings.comfy.sourceBatch",
} as const;

function initialDefinition(graph: ComfyUiGraph, video: boolean): ComfyUiWorkflowDefinition {
  return { schemaVersion: 1, graph, bindings: [], output: { nodeId: "", field: "images" }, width: COMFY_WORKFLOW_DEFAULT_SIDE, height: COMFY_WORKFLOW_DEFAULT_SIDE,
    minimumSeconds: video ? 1 : 0, maximumSeconds: video ? 5 : 0, fps: 24, frameMultiple: 1, frameOffset: 0 };
}

/** Import candidates, inspect actual nodes, then review the exact contract that will be published. */
export function ComfyWorkflowEditor({ connectionId, adapterId, values, onChange, onReadyChange }: {
  connectionId: string; adapterId: string; values: Settings; onChange: (value: Settings) => void; onReadyChange: (ready: boolean) => void;
}) {
  useLocale();
  const fieldId = useId();
  const video = adapterId === "COMFY_VIDEO_V1";
  const definition = values.comfyWorkflow;
  const [step, setStep] = useState<Step>(definition ? "mapping" : "import");
  const [source, setSource] = useState("");
  const [selectedNode, setSelectedNode] = useState<string>(Object.keys(definition?.graph ?? {})[0] ?? "");
  const [error, setError] = useState("");
  const [parameterErrors, setParameterErrors] = useState<Record<string, boolean>>({});
  const [reading, setReading] = useState(false);
  const readEpoch = useRef(0);
  const problem = comfyWorkflowProblem(definition, video);
  const imported = useMutation({
    mutationFn: () => previewComfyWorkflow(connectionId, source),
    onSuccess: (graph) => { onChange({ comfyWorkflow: initialDefinition(graph, video), pricing: values.pricing }); setSelectedNode(Object.keys(graph)[0] ?? ""); setStep("mapping"); setError(""); setParameterErrors({}); },
    onError: (cause) => setError(cause instanceof ApiError ? cause.message : t("settings.comfy.importFailed")),
  });
  const invalidParameter = Object.values(parameterErrors).some(Boolean);
  const ready = !!definition && !problem && !imported.isPending && !reading && !error && !invalidParameter;
  useEffect(() => { onReadyChange(ready); return () => onReadyChange(false); }, [ready, onReadyChange]);
  useEffect(() => () => { readEpoch.current += 1; }, []);
  const node = definition?.graph[selectedNode];
  function update(patch: Partial<ComfyUiWorkflowDefinition>) {
    if (!definition) return;
    onChange({ ...values, comfyWorkflow: { ...definition, ...patch } });
  }
  function mapInput(inputName: string, sourceName: string) {
    if (!definition) return;
    const previousBinding = definition.bindings.find((binding) => binding.nodeId === selectedNode && binding.inputName === inputName);
    const bindings = definition.bindings.filter((binding) => binding.nodeId !== selectedNode || binding.inputName !== inputName);
    const parameters = (definition.parameters ?? []).filter((field) => field.nodeId !== selectedNode || field.fieldName !== inputName);
    const previousParameter = definition.parameters?.find((field) => field.nodeId === selectedNode && field.fieldName === inputName);
    if (previousParameter) setParameterErrors((before) => Object.fromEntries(Object.entries(before).filter(([key]) => !key.startsWith(`${previousParameter.key}:`))));
    if (sourceName === PARAMETER_MAPPING) {
      const value = node?.inputs[inputName];
      const type = comfyParameterTypes(value)[0];
      if (!type || typeof value !== "string" && typeof value !== "number" && typeof value !== "boolean") return;
      parameters.push({ key: comfyParameterKey(selectedNode, inputName, parameters), label: inputName,
        type, nodeId: selectedNode, fieldName: inputName, source: "PARAMETER", defaultValue: value, required: false, advanced: false });
    } else if (sourceName !== NO_MAPPING) bindings.push({ nodeId: selectedNode, inputName, source: sourceName as Source,
      ...(sourceName === "REFERENCE_IMAGE" ? { referenceIndex: 0 } : {}) });
    // The first mapped dimension supplies the common pixel basis; additional targets share it.
    const dimension = sourceName === "WIDTH" ? "width" : sourceName === "HEIGHT" ? "height" : null;
    const value = node?.inputs[inputName];
    const dimensionPatch: Partial<ComfyUiWorkflowDefinition> = {};
    // Removing the last dimension target also clears an invalid, now unused technical basis.
    if (previousBinding?.source === "WIDTH" && !bindings.some((binding) => binding.source === "WIDTH")) dimensionPatch.width = COMFY_WORKFLOW_DEFAULT_SIDE;
    if (previousBinding?.source === "HEIGHT" && !bindings.some((binding) => binding.source === "HEIGHT")) dimensionPatch.height = COMFY_WORKFLOW_DEFAULT_SIDE;
    if (dimension && typeof value === "number"
      && !definition.bindings.some((binding) => binding.source === sourceName
        && (binding.nodeId !== selectedNode || binding.inputName !== inputName))) dimensionPatch[dimension] = value;
    update({ bindings, ...dimensionPatch, ...(parameters.length || definition.parameters ? { parameters } : {}) });
  }
  function parameter(index: number, patch: Partial<RunningHubField>) {
    if (!definition) return;
    update({ parameters: definition.parameters?.map((field, position) => position === index ? { ...field, ...patch } : field) });
  }
  function commitParameterJson(input: HTMLInputElement | HTMLTextAreaElement, key: string, commit: (value: unknown) => boolean) {
    try {
      const value: unknown = input.value ? JSON.parse(input.value) : null;
      if (!commit(value)) throw new Error();
      input.setCustomValidity(""); setParameterErrors((before) => ({ ...before, [key]: false }));
    } catch {
      input.setCustomValidity(t("settings.comfy.parameterInvalid")); setParameterErrors((before) => ({ ...before, [key]: true }));
    }
  }
  function literal(inputName: string, value: string | number | boolean) {
    if (!definition || !node) return;
    update({ graph: { ...definition.graph, [selectedNode]: { ...node, inputs: { ...node.inputs, [inputName]: value } } } });
  }
  async function readFile(file: File | undefined) {
    if (!file) return;
    const epoch = ++readEpoch.current;
    setError("");
    if (file.size > LIMITS.jsonBytes) { setError(t("settings.comfy.tooLarge")); return; }
    setReading(true);
    try { const text = await file.text(); if (epoch === readEpoch.current) setSource(text); }
    catch { if (epoch === readEpoch.current) setError(t("settings.comfy.importFailed")); }
    finally { if (epoch === readEpoch.current) setReading(false); }
  }
  return <div className="comfy-workflow-editor ui-stack">
    <nav className="comfy-steps" aria-label={t("settings.comfy.steps")}>
      {STEPS.map((item, index) => <Button key={item} type="button" variant={step === item ? "secondary" : "ghost"} aria-current={step === item ? "step" : undefined}
        disabled={imported.isPending || reading || item !== "import" && !definition} onClick={() => setStep(item)}>{index + 1}. {t(`settings.comfy.step.${item}`)}</Button>)}
    </nav>
    {step === "import" ? <FieldGroup className="comfy-workflow-import">
      <p className="ui-muted">{t("settings.comfy.importHint")}</p>
      <Field><FieldLabel htmlFor={`${fieldId}-file`}>{t("settings.comfy.file")}</FieldLabel><Input id={`${fieldId}-file`} type="file" accept=".json,application/json" disabled={reading || imported.isPending}
        onChange={(event) => { void readFile(event.target.files?.[0]); event.target.value = ""; }} /></Field>
      <Field><FieldLabel htmlFor={`${fieldId}-json`}>{t("settings.comfy.json")}</FieldLabel><Textarea id={`${fieldId}-json`} className="comfy-workflow-json" rows={7} value={source} maxLength={LIMITS.jsonBytes} disabled={reading || imported.isPending}
        onChange={(event) => { setSource(event.target.value); setError(""); }} placeholder={'{"3":{"class_type":"KSampler","inputs":{...}}}'} /></Field>
      <Button type="button" disabled={!source.trim() || reading || imported.isPending} onClick={() => {
        if (new TextEncoder().encode(source).length > LIMITS.jsonBytes) { setError(t("settings.comfy.tooLarge")); return; }
        setError(""); imported.mutate();
      }}>{reading || imported.isPending ? t("settings.comfy.parsing") : definition ? t("settings.comfy.replace") : t("settings.comfy.parse")}</Button>
      {definition ? <p className="ui-muted">{t("settings.comfy.replaceHint")}</p> : null}
    </FieldGroup> : null}
    {step === "mapping" && definition ? <div className="ui-stack">
      <p className="ui-muted">{t("settings.comfy.mappingHint")}</p>
      <div className="comfy-node-layout">
        <div className="comfy-node-list" role="group" aria-label={t("settings.comfy.nodes")}>
          {Object.entries(definition.graph).map(([id, item]) => <Button key={id} type="button" variant={id === selectedNode ? "secondary" : "ghost"} onClick={() => setSelectedNode(id)} aria-pressed={id === selectedNode}>
            <span><strong>#{id} · {item._meta?.title ?? item.class_type}</strong><small>{item.class_type}</small></span>
          </Button>)}
        </div>
        <div className="comfy-node-inputs">
          <h3>{selectedNode ? `#${selectedNode} · ${node?._meta?.title ?? node?.class_type}` : t("settings.comfy.nodes")}</h3>
          {node ? Object.entries(node.inputs).map(([inputName, value]) => {
            const binding = definition.bindings.find((item) => item.nodeId === selectedNode && item.inputName === inputName);
            const extended = definition.parameters?.find((item) => item.nodeId === selectedNode && item.fieldName === inputName);
            const scalar = typeof value === "string" || typeof value === "number" || typeof value === "boolean";
            const choices = typeof value === "string" ? TEXT_SOURCES : typeof value === "number" ? [...NUMBER_SOURCES, ...(video ? VIDEO_SOURCES : [])] : [];
            return <div key={inputName} className="comfy-input-row">
              <code>{inputName}</code>
              {scalar ? <>
                <Select aria-label={t("settings.comfy.mappingFor", { "0": inputName })} value={extended ? PARAMETER_MAPPING : binding?.source ?? NO_MAPPING} onChange={(event) => mapInput(inputName, event.target.value)}>
                  <option value={NO_MAPPING}>{t("settings.comfy.fixed")}</option>
                  <option value={PARAMETER_MAPPING} disabled={!extended && (definition.parameters?.length ?? 0) >= LIMITS.parameters}>{t("settings.comfy.extendedParameters")}</option>
                  {choices.map((choice) => <option key={choice} value={choice} disabled={!binding && definition.bindings.length >= LIMITS.bindings}>{t(SOURCE_KEYS[choice])}</option>)}
                </Select>
                {binding?.source === "REFERENCE_IMAGE" ? <Input type="number" min={1} max={LIMITS.references} step={1} aria-label={t("settings.comfy.referenceFor", { "0": inputName })} value={(binding.referenceIndex ?? 0) + 1}
                  onChange={(event) => update({ bindings: definition.bindings.map((item) => item === binding ? { ...item, referenceIndex: Number(event.target.value) - 1 } : item) })} />
                  : extended ? <small className="ui-muted">{extended.label}</small>
                    : binding ? <small className="ui-muted">{t(SOURCE_KEYS[binding.source])}</small>
                    : typeof value === "boolean" ? <Select aria-label={t("settings.comfy.valueFor", { "0": inputName })} value={String(value)} onChange={(event) => literal(inputName, event.target.value === "true")}><option value="true">true</option><option value="false">false</option></Select>
                      : <Input aria-label={t("settings.comfy.valueFor", { "0": inputName })} type={typeof value === "number" ? "number" : "text"} step="any" value={value} onChange={(event) => literal(inputName, typeof value === "number" ? Number(event.target.value) : event.target.value)} />}
              </> : <pre className="ui-muted">{JSON.stringify(value)}</pre>}
            </div>;
          }) : null}
        </div>
      </div>
      {definition.parameters?.length ? <FieldGroup>
        <h3>{t("settings.comfy.extendedParameters")}</h3>
        <p className="ui-muted">{t("settings.comfy.parameterHint")}</p>
        <Table className="comfy-parameter-table" aria-label={t("settings.comfy.extendedParameters")}>
          <TableHeader><TableRow>
            <TableHead>{t("settings.comfy.parameterTarget")}</TableHead><TableHead>{t("settings.runningHub.label")}</TableHead>
            <TableHead>{t("settings.runningHub.type")}</TableHead><TableHead>{t("settings.runningHub.defaultValue")}</TableHead>
            <TableHead>{t("settings.comfy.parameterLimits")}</TableHead><TableHead>{t("settings.runningHub.requiredSuffix")}</TableHead>
          </TableRow></TableHeader>
          <TableBody>{definition.parameters.map((field, index) => {
            const literalValue = definition.graph[field.nodeId]?.inputs[field.fieldName];
            return <TableRow key={field.key} aria-label={`#${field.nodeId}.${field.fieldName}`}>
              <TableCell><code>#{field.nodeId}.{field.fieldName}</code><small className="ui-muted">{field.key}</small></TableCell>
              <TableCell><Field><FieldLabel className="sr-only">{t("settings.runningHub.label")}</FieldLabel><Input required maxLength={LIMITS.fieldLabelLength}
                value={field.label} onChange={(event) => parameter(index, { label: event.target.value })} aria-label={t("settings.runningHub.label")} /></Field></TableCell>
              <TableCell><Field><FieldLabel className="sr-only">{t("settings.runningHub.type")}</FieldLabel><Select value={field.type} aria-label={t("settings.runningHub.type")}
                onChange={(event) => {
                  const type = event.target.value as RunningHubField["type"];
                  parameter(index, { type, minimum: null, maximum: null, maxLength: null,
                    options: type === "SELECT" && (typeof literalValue === "string" || typeof literalValue === "number" || typeof literalValue === "boolean")
                      ? [{ label: String(literalValue), value: literalValue }] : null });
                  setParameterErrors((before) => Object.fromEntries(Object.entries(before).filter(([key]) => !key.startsWith(`${field.key}:`))));
                }}>
                {comfyParameterTypes(literalValue).map((type) => <option key={type} value={type}>{type === "STRING" ? t("common.text")
                  : type === "INTEGER" ? t("settings.runningHub.integerInput") : type === "NUMBER" ? t("settings.runningHub.numberInput")
                    : type === "BOOLEAN" ? t("settings.runningHub.booleanInput") : t("settings.runningHub.selectInput")}</option>)}
              </Select></Field></TableCell>
              <TableCell><Field data-invalid={parameterErrors[`${field.key}:default`] || undefined}><FieldLabel className="sr-only">{t("settings.runningHub.defaultValue")}</FieldLabel>
                {typeof literalValue === "boolean" ? <Select aria-label={t("settings.runningHub.defaultValue")} value={String(field.defaultValue ?? literalValue)}
                  onChange={(event) => parameter(index, { defaultValue: event.target.value === "true" })}><option value="true">true</option><option value="false">false</option></Select>
                  : typeof literalValue === "string" ? <Input aria-label={t("settings.runningHub.defaultValue")} value={String(field.defaultValue ?? literalValue)}
                    onChange={(event) => parameter(index, { defaultValue: event.target.value })} />
                    : <Input key={`${field.type}:${JSON.stringify(field.defaultValue)}`} aria-label={t("settings.runningHub.defaultValue")} defaultValue={JSON.stringify(field.defaultValue ?? literalValue)}
                      aria-invalid={parameterErrors[`${field.key}:default`] || undefined} onBlur={(event) => commitParameterJson(event.target, `${field.key}:default`, (value) => {
                        if (value !== null && (typeof value !== "number" || !Number.isFinite(value))) return false;
                        parameter(index, { defaultValue: value }); return true;
                      })} />}
              </Field></TableCell>
              <TableCell><FieldGroup className="comfy-parameter-limits">
                {field.type === "NUMBER" || field.type === "INTEGER" ? (["minimum", "maximum"] as const).map((bound) => <Field key={bound}><FieldLabel className="sr-only">{t(`settings.runningHub.${bound}`)}</FieldLabel>
                  <Input type="number" step={field.type === "INTEGER" ? 1 : "any"} aria-label={t(`settings.runningHub.${bound}`)} placeholder={t(`settings.runningHub.${bound}`)} value={field[bound] ?? ""}
                    onChange={(event) => parameter(index, { [bound]: event.target.value ? Number(event.target.value) : null })} /></Field>)
                  : field.type === "STRING" ? <Field><FieldLabel className="sr-only">{t("settings.runningHub.maxLength")}</FieldLabel><Input type="number" min={1} max={LIMITS.fieldMaxLength}
                    aria-label={t("settings.runningHub.maxLength")} placeholder={t("settings.runningHub.maxLength")} value={field.maxLength ?? ""}
                    onChange={(event) => parameter(index, { maxLength: event.target.value ? Number(event.target.value) : null })} /></Field>
                    : field.type === "SELECT" ? <Field data-invalid={parameterErrors[`${field.key}:options`] || undefined}><FieldLabel className="sr-only">{t("settings.comfy.parameterOptions")}</FieldLabel>
                      <Textarea key={JSON.stringify(field.options)} rows={2} aria-label={t("settings.comfy.parameterOptions")} aria-invalid={parameterErrors[`${field.key}:options`] || undefined}
                        defaultValue={JSON.stringify(field.options?.map((option) => option.value) ?? [])} onBlur={(event) => commitParameterJson(event.target, `${field.key}:options`, (value) => {
                          if (!Array.isArray(value) || !value.length || value.length > LIMITS.options
                              || value.some((option: unknown) => typeof option !== typeof literalValue || typeof option === "number" && !Number.isFinite(option))) return false;
                          const options: NonNullable<RunningHubField["options"]> = [];
                          for (const option of value) {
                            if (typeof option !== "string" && typeof option !== "number" && typeof option !== "boolean") return false;
                            options.push({ label: String(option), value: option });
                          }
                          parameter(index, { options }); return true;
                        })} /></Field> : null}
              </FieldGroup></TableCell>
              <TableCell><Checkbox aria-label={t("settings.runningHub.requiredSuffix")} checked={field.required ?? false} onCheckedChange={(checked) => parameter(index, { required: checked === true })} /></TableCell>
            </TableRow>;
          })}</TableBody>
        </Table>
        {invalidParameter ? <Notice tone="warning">{t("settings.comfy.parameterInvalid")}</Notice> : null}
      </FieldGroup> : null}
      <FieldGroup className="ui-form-grid">
        <Field><FieldLabel htmlFor={`${fieldId}-output-node`}>{t("settings.comfy.outputNode")}</FieldLabel><Select id={`${fieldId}-output-node`} value={definition.output.nodeId} onChange={(event) => update({ output: { ...definition.output, nodeId: event.target.value } })}>
          <option value="">{t("settings.comfy.chooseOutput")}</option>{Object.entries(definition.graph).map(([id, item]) => <option key={id} value={id}>#{id} · {item._meta?.title ?? item.class_type}</option>)}
        </Select></Field>
        <Field><FieldLabel htmlFor={`${fieldId}-output-field`}>{t("settings.comfy.outputField")}</FieldLabel><Select id={`${fieldId}-output-field`} value={definition.output.field} onChange={(event) => update({ output: { ...definition.output, field: event.target.value as ComfyUiWorkflowDefinition["output"]["field"] } })}>
          {(video ? ["images", "gifs", "videos"] : ["images"]).map((field) => <option key={field}>{field}</option>)}
        </Select></Field>
        {video ? (["minimumSeconds", "maximumSeconds", "fps", "frameMultiple", "frameOffset"] as const).map((key) => <Field key={key}><FieldLabel htmlFor={`${fieldId}-${key}`}>{t(`settings.comfy.${key}`)}</FieldLabel><Input id={`${fieldId}-${key}`} type="number" required step={1} min={key === "frameOffset" ? 0 : 1}
          max={key === "fps" ? LIMITS.fps : key === "frameMultiple" ? LIMITS.frameMultiple : key === "frameOffset" ? definition.frameMultiple - 1 : LIMITS.seconds}
          value={definition[key]} onChange={(event) => update({ [key]: Number(event.target.value) })} /></Field>) : null}
      </FieldGroup>
      {video ? <p className="ui-muted">{t("settings.comfy.framesHint")}</p> : null}
      {problem ? <Notice tone="warning">{problem}</Notice> : null}
      <Button type="button" disabled={!!problem || invalidParameter} onClick={() => setStep("review")}>{t("settings.comfy.review")}</Button>
    </div> : null}
    {step === "review" && definition ? <div className="ui-stack">
      <Notice>{t("settings.comfy.summary", { "0": Object.keys(definition.graph).length, "1": definition.bindings.length, "2": definition.output.nodeId, "3": comfyReferenceCount(definition) })}</Notice>
      <ul className="comfy-binding-summary">{definition.bindings.map((binding) => <li key={`${binding.nodeId}:${binding.inputName}`}><code>#{binding.nodeId}.{binding.inputName}</code> ← {t(SOURCE_KEYS[binding.source])}{binding.source === "REFERENCE_IMAGE" ? ` #${(binding.referenceIndex ?? 0) + 1}` : ""}</li>)}</ul>
      {definition.parameters?.length ? <ul className="comfy-binding-summary">{definition.parameters.map((field) => <li key={field.key}><code>#{field.nodeId}.{field.fieldName}</code> ← {t("settings.comfy.extendedParameters")} · {field.label}</li>)}</ul> : null}
      <CapabilityConfigurationFields section="defaults" adapterId={adapterId} values={values} onChange={onChange} />
      <CapabilityConfigurationFields section="pricing" adapterId={adapterId} values={values} onChange={onChange} />
      {problem ? <Notice tone="warning">{problem}</Notice> : null}
    </div> : null}
    {error ? <Notice tone="danger">{error}</Notice> : null}
  </div>;
}
