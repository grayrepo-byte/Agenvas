import { useMutation } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { ApiError, previewComfyWorkflow, type ComfyUiGraph, type ComfyUiWorkflowDefinition, type MediaCapability } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { CapabilityConfigurationFields } from "./CapabilityConfigurationFields";
import "./ComfyWorkflowEditor.css";
import { COMFY_WORKFLOW_LIMITS as LIMITS, comfyReferenceCount, comfyWorkflowProblem } from "./comfyWorkflow";

type Settings = MediaCapability["settings"];
type Binding = ComfyUiWorkflowDefinition["bindings"][number];
type Source = Binding["source"];
const NO_MAPPING = "FIXED";
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
  return { schemaVersion: 1, graph, bindings: [], output: { nodeId: "", field: "images" }, width: 1024, height: 1024,
    minimumSeconds: video ? 1 : 0, maximumSeconds: video ? 5 : 0, fps: 24, frameMultiple: 1, frameOffset: 0 };
}

/** Import candidates, inspect actual nodes, then review the exact contract that will be published. */
export function ComfyWorkflowEditor({ connectionId, adapterId, values, onChange, onReadyChange }: {
  connectionId: string; adapterId: string; values: Settings; onChange: (value: Settings) => void; onReadyChange: (ready: boolean) => void;
}) {
  useLocale();
  const video = adapterId === "COMFY_VIDEO_V1";
  const definition = values.comfyWorkflow;
  const [step, setStep] = useState<Step>(definition ? "mapping" : "import");
  const [source, setSource] = useState("");
  const [selectedNode, setSelectedNode] = useState<string>(Object.keys(definition?.graph ?? {})[0] ?? "");
  const [error, setError] = useState("");
  const [reading, setReading] = useState(false);
  const [reviewed, setReviewed] = useState<Settings | null>(null);
  const readEpoch = useRef(0);
  const problem = comfyWorkflowProblem(definition, video);
  const imported = useMutation({
    mutationFn: () => previewComfyWorkflow(connectionId, source),
    onSuccess: (graph) => { onChange({ comfyWorkflow: initialDefinition(graph, video), pricing: values.pricing }); setReviewed(null); setSelectedNode(Object.keys(graph)[0] ?? ""); setStep("mapping"); setError(""); },
    onError: (cause) => setError(cause instanceof ApiError ? cause.message : t("settings.comfy.importFailed")),
  });
  const ready = !!definition && !problem && reviewed === values && !imported.isPending && !reading && !error;
  useEffect(() => { onReadyChange(ready); return () => onReadyChange(false); }, [ready, onReadyChange]);
  useEffect(() => () => { readEpoch.current += 1; }, []);
  const node = definition?.graph[selectedNode];
  function update(patch: Partial<ComfyUiWorkflowDefinition>) {
    if (!definition) return;
    onChange({ ...values, comfyWorkflow: { ...definition, ...patch } });
    setReviewed(null);
  }
  function mapInput(inputName: string, sourceName: string) {
    if (!definition) return;
    const bindings = definition.bindings.filter((binding) => binding.nodeId !== selectedNode || binding.inputName !== inputName);
    if (sourceName !== NO_MAPPING) bindings.push({ nodeId: selectedNode, inputName, source: sourceName as Source,
      ...(sourceName === "REFERENCE_IMAGE" ? { referenceIndex: 0 } : {}) });
    update({ bindings });
  }
  function literal(inputName: string, value: string | number | boolean) {
    if (!definition || !node) return;
    update({ graph: { ...definition.graph, [selectedNode]: { ...node, inputs: { ...node.inputs, [inputName]: value } } } });
  }
  async function readFile(file: File | undefined) {
    if (!file) return;
    const epoch = ++readEpoch.current;
    setReviewed(null); setError("");
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
    {step === "import" ? <div className="ui-stack">
      <p className="ui-muted">{t("settings.comfy.importHint")}</p>
      <Field><FieldLabel>{t("settings.comfy.file")}<Input type="file" accept=".json,application/json" disabled={reading || imported.isPending}
        onChange={(event) => { void readFile(event.target.files?.[0]); event.target.value = ""; }} /></FieldLabel></Field>
      <Field><FieldLabel>{t("settings.comfy.json")}<Textarea rows={9} value={source} maxLength={LIMITS.jsonBytes} disabled={reading || imported.isPending}
        onChange={(event) => { setSource(event.target.value); setReviewed(null); setError(""); }} placeholder={'{"3":{"class_type":"KSampler","inputs":{...}}}'} /></FieldLabel></Field>
      <Button type="button" disabled={!source.trim() || reading || imported.isPending} onClick={() => {
        if (new TextEncoder().encode(source).length > LIMITS.jsonBytes) { setError(t("settings.comfy.tooLarge")); return; }
        setError(""); imported.mutate();
      }}>{reading || imported.isPending ? t("settings.comfy.parsing") : definition ? t("settings.comfy.replace") : t("settings.comfy.parse")}</Button>
      {definition ? <p className="ui-muted">{t("settings.comfy.replaceHint")}</p> : null}
    </div> : null}
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
            const scalar = typeof value === "string" || typeof value === "number" || typeof value === "boolean";
            const choices = typeof value === "string" ? TEXT_SOURCES : typeof value === "number" ? [...NUMBER_SOURCES, ...(video ? VIDEO_SOURCES : [])] : [];
            return <div key={inputName} className="comfy-input-row">
              <code>{inputName}</code>
              {scalar ? <>
                <Select aria-label={t("settings.comfy.mappingFor", { "0": inputName })} value={binding?.source ?? NO_MAPPING} onChange={(event) => mapInput(inputName, event.target.value)}
                  disabled={!binding && definition.bindings.length >= LIMITS.bindings}>
                  <option value={NO_MAPPING}>{t("settings.comfy.fixed")}</option>
                  {choices.map((choice) => <option key={choice} value={choice}>{t(SOURCE_KEYS[choice])}</option>)}
                </Select>
                {binding?.source === "REFERENCE_IMAGE" ? <Input type="number" min={1} max={LIMITS.references} step={1} aria-label={t("settings.comfy.referenceFor", { "0": inputName })} value={(binding.referenceIndex ?? 0) + 1}
                  onChange={(event) => update({ bindings: definition.bindings.map((item) => item === binding ? { ...item, referenceIndex: Number(event.target.value) - 1 } : item) })} />
                  : binding ? <small className="ui-muted">{t(SOURCE_KEYS[binding.source])}</small>
                    : typeof value === "boolean" ? <Select aria-label={t("settings.comfy.valueFor", { "0": inputName })} value={String(value)} onChange={(event) => literal(inputName, event.target.value === "true")}><option value="true">true</option><option value="false">false</option></Select>
                      : <Input aria-label={t("settings.comfy.valueFor", { "0": inputName })} type={typeof value === "number" ? "number" : "text"} step="any" value={value} onChange={(event) => literal(inputName, typeof value === "number" ? Number(event.target.value) : event.target.value)} />}
              </> : <pre className="ui-muted">{JSON.stringify(value)}</pre>}
            </div>;
          }) : null}
        </div>
      </div>
      <div className="ui-form-grid">
        <Field><FieldLabel>{t("settings.comfy.outputNode")}<Select value={definition.output.nodeId} onChange={(event) => update({ output: { ...definition.output, nodeId: event.target.value } })}>
          <option value="">{t("settings.comfy.chooseOutput")}</option>{Object.entries(definition.graph).map(([id, item]) => <option key={id} value={id}>#{id} · {item._meta?.title ?? item.class_type}</option>)}
        </Select></FieldLabel></Field>
        <Field><FieldLabel>{t("settings.comfy.outputField")}<Select value={definition.output.field} onChange={(event) => update({ output: { ...definition.output, field: event.target.value as ComfyUiWorkflowDefinition["output"]["field"] } })}>
          {(video ? ["images", "gifs", "videos"] : ["images"]).map((field) => <option key={field}>{field}</option>)}
        </Select></FieldLabel></Field>
        {(["width", "height", ...(video ? ["minimumSeconds", "maximumSeconds", "fps", "frameMultiple", "frameOffset"] as const : [])] as const).map((key) => <Field key={key}><FieldLabel>{t(`settings.comfy.${key}`)}<Input type="number" required step={1} min={key === "frameOffset" ? 0 : key === "width" || key === "height" ? LIMITS.minimumSide : 1}
          max={key === "width" || key === "height" ? LIMITS.maximumSide : key === "fps" ? LIMITS.fps : key === "frameMultiple" ? LIMITS.frameMultiple : key === "frameOffset" ? definition.frameMultiple - 1 : LIMITS.seconds}
          value={definition[key]} onChange={(event) => update({ [key]: Number(event.target.value) })} /></FieldLabel></Field>)}
      </div>
      {video ? <p className="ui-muted">{t("settings.comfy.framesHint")}</p> : null}
      {problem ? <Notice tone="warning">{problem}</Notice> : null}
      <Button type="button" disabled={!!problem} onClick={() => setStep("review")}>{t("settings.comfy.review")}</Button>
    </div> : null}
    {step === "review" && definition ? <div className="ui-stack">
      <Notice>{t("settings.comfy.summary", { "0": Object.keys(definition.graph).length, "1": definition.bindings.length, "2": definition.output.nodeId, "3": comfyReferenceCount(definition) })}</Notice>
      <ul className="comfy-binding-summary">{definition.bindings.map((binding) => <li key={`${binding.nodeId}:${binding.inputName}`}><code>#{binding.nodeId}.{binding.inputName}</code> ← {t(SOURCE_KEYS[binding.source])}{binding.source === "REFERENCE_IMAGE" ? ` #${(binding.referenceIndex ?? 0) + 1}` : ""}</li>)}</ul>
      <CapabilityConfigurationFields section="defaults" adapterId={adapterId} values={values} onChange={onChange} />
      <CapabilityConfigurationFields section="pricing" adapterId={adapterId} values={values} onChange={onChange} />
      {problem ? <Notice tone="warning">{problem}</Notice> : null}
      <label className="comfy-review-check"><Checkbox aria-label={t("settings.comfy.confirm")} checked={reviewed === values} disabled={!!problem} onCheckedChange={(checked) => setReviewed(checked ? values : null)} />{t("settings.comfy.confirm")}</label>
    </div> : null}
    {error ? <Notice tone="danger">{error}</Notice> : null}
  </div>;
}
