import { CaretDown,SlidersHorizontal,Trash } from "@/shared/ui/icons";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { useMutation } from "@tanstack/react-query";
import { Fragment,useEffect,useId,useRef,useState,type FormEvent,type ReactNode } from "react";
import { previewRunningHubImport,type RunningHubDefinition,type RunningHubField } from "../../shared/api/client";
import { SUPPORTED_LOCALES,t,translate,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { DropdownMenu,DropdownMenuCheckboxItem,DropdownMenuContent,DropdownMenuGroup,DropdownMenuItem,DropdownMenuSeparator,DropdownMenuTrigger } from "../../shared/ui/primitives/dropdown-menu";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { Badge } from "../../shared/ui/primitives/badge";
import { EmptyState,Notice } from "../../shared/ui/PagePrimitives";
import { Select } from "../../shared/ui/Select";
import { Table,TableBody,TableCell,TableRow } from "../../shared/ui/primitives/table";
import "./RunningHubDefinitionEditor.css";
import { RunningHubForm } from "../canvas/RunningHubForm";
import { importedRunningHubSelection, restoreRunningHubCandidates } from "./runningHubImport";
import { selectRunningHubNodes } from "./runningHubSelection";

const MAX_FIELDS = 64;
const MAX_OUTPUTS = 16;
const MAX_IMPORT_SOURCE_CHARACTERS = 1024 * 1024;
const NODE_LABEL_SEPARATOR = " · ";
const WORKBENCH_SECTION_IDS = ["target", "inputs", "outputs", "preview"] as const;
type OutputKind = RunningHubDefinition["outputs"][number]["kind"];
function nodeLabel(nodeId: string, definition: RunningHubDefinition) {
  if (!nodeId) return t("settings.runningHub.unassignedNode");
  const idLabel = t("settings.runningHub.nodeOption", { "0": nodeId });
  for (const field of definition.fields) {
    if (field.nodeId !== nodeId) continue;
    let name = field.label.trimEnd();
    // Workflow imports store the node title and input name together in the editable field label.
    const fieldSuffix = `${NODE_LABEL_SEPARATOR}${field.fieldName}`;
    if (definition.targetType === "WORKFLOW" && field.fieldName && name.endsWith(fieldSuffix)) name = name.slice(0, -fieldSuffix.length);
    name = name.trim();
    if (name && !SUPPORTED_LOCALES.some((locale) => name === translate(locale, "settings.runningHub.nodeOption", { "0": nodeId }))) {
      return `${idLabel}${NODE_LABEL_SEPARATOR}${name}`;
    }
  }
  const importedName = definition.nodeOptions?.find((node) => node.nodeId === nodeId)?.label.trim();
  return importedName && !SUPPORTED_LOCALES.some((locale) => importedName === translate(locale, "settings.runningHub.nodeOption", { "0": nodeId }))
    ? `${idLabel}${NODE_LABEL_SEPARATOR}${importedName}` : idLabel;
}

/** With several visible nodes, the administrator explicitly chooses where a new binding belongs. */
function NodeParameterAction({ nodeIds, label, getNodeLabel, onAdd, disabled = false, variant = "outline" }: {
  nodeIds: string[]; label: string; getNodeLabel: (nodeId: string) => string; onAdd: (nodeId: string) => void; disabled?: boolean; variant?: "outline" | "ghost";
}) {
  const trigger = useRef<HTMLButtonElement>(null);
  const firstNode = nodeIds[0];
  if (firstNode === undefined) return null;
  if (nodeIds.length === 1) return <Button variant={variant} type="button" disabled={disabled} onClick={() => onAdd(firstNode)}>{label}</Button>;
  return <DropdownMenu modal={false}>
    <DropdownMenuTrigger asChild><Button ref={trigger} variant={variant} type="button" disabled={disabled}>{label}<CaretDown data-icon="inline-end" /></Button></DropdownMenuTrigger>
    <DropdownMenuContent aria-label={label} aria-labelledby={undefined} align="start" className="max-w-[var(--radix-dropdown-menu-content-available-width)]"><DropdownMenuGroup>
      {nodeIds.map((nodeId) => <DropdownMenuItem key={nodeId} title={getNodeLabel(nodeId)} onSelect={() => { if (!trigger.current?.matches(":disabled")) onAdd(nodeId); }}><span className="min-w-0 whitespace-normal break-words">{getNodeLabel(nodeId)}</span></DropdownMenuItem>)}
    </DropdownMenuGroup></DropdownMenuContent>
  </DropdownMenu>;
}
function emptyDefinition(adapterId: string): RunningHubDefinition {
  const kind = adapterId === "RUNNINGHUB_VIDEO" ? "VIDEO" : adapterId === "RUNNINGHUB_AUDIO" ? "AUDIO" : "IMAGE";
  return { schemaVersion: 1, protocolVersion: "V2", targetType: "WORKFLOW", targetId: "", fields: [],
    fixedBindings: [], outputs: [{ kind, primary: true, maxCount: 1 }], instanceType: "default", usePersonalQueue: false, addMetadata: false };
}

function inputNodeIds(definition: RunningHubDefinition) {
  return [...new Set([...definition.fields, ...(definition.fixedBindings ?? [])].map((item) => item.nodeId))]
    .sort((first, second) => first.localeCompare(second, undefined, { numeric: true }));
}

type RunningHubDefinitionEditorProps = {
  connectionId: string; adapterId: string; value?: RunningHubDefinition; selectedNodeIds?: string[]; selectedFieldKeys?: string[];
  onChange: (value: RunningHubDefinition, primaryKind?: OutputKind, selectedNodeIds?: string[], selectedFieldKeys?: string[]) => void;
  onDiscoveredName?: (name: string) => void;
  onBusyChange?: (busy: boolean) => void;
  resetKey?: number;
  identityFields?: ReactNode;
  pricingFields?: ReactNode;
  /** Parent-owned raw text survives closing the modal without changing the published definition. */
  sourceDraft?: string;
  /** User edits only; import normalization updates the local view without rewriting the parent contract. */
  onSourceDraftChange?: (source: string) => void;
};

/** Explicit server reloads replace the complete editing session, including unparsed JSON and validation state. */
export function RunningHubDefinitionEditor(props: RunningHubDefinitionEditorProps) {
  return <RunningHubWorkbench key={props.resetKey} {...props} />;
}

/** Candidate import and contract editing never submit a generation request. */
function RunningHubWorkbench({ connectionId, adapterId, value, selectedNodeIds, selectedFieldKeys, onChange, onDiscoveredName,
  onBusyChange, identityFields, pricingFields, sourceDraft, onSourceDraftChange }: RunningHubDefinitionEditorProps) {
  useLocale();
  const definition = value ?? emptyDefinition(adapterId);
  const [source, setSource] = useState(() => sourceDraft ?? (definition.importSource ? JSON.stringify(definition.importSource, null, 2) : ""));
  const configuredTarget = useRef({ targetType: definition.targetType, targetId: definition.targetId });
  const sessionActive = useRef(true);
  useEffect(() => {
    sessionActive.current = true;
    return () => { sessionActive.current = false; };
  }, []);
  const [localError, setLocalError] = useState("");
  const [warnings, setWarnings] = useState<string[]>([]);
  const [previewValues, setPreviewValues] = useState<Record<string, string | number | boolean>>({});
  const [expandedField, setExpandedField] = useState<number | null>(null);
  // Saved mappings must be visible without rediscovering and replacing the published contract.
  const [localNodeSelection, setNodeSelection] = useState<string[]>(() => inputNodeIds(definition));
  const nodeSelection = selectedNodeIds ?? localNodeSelection;
  const [localFieldSelection, setFieldSelection] = useState(() => definition.fields.map((field) => field.key));
  const fieldSelection = selectedFieldKeys ?? localFieldSelection;
  const fieldSelected = (item: RunningHubField) => selectedNodes.includes(item.nodeId) && fieldSelection.includes(item.key);
  // Legacy definitions may know output IDs that are absent from every input mapping.
  const [savedOutputNodeIds, setSavedOutputNodeIds] = useState(() => definition.outputs.flatMap((output) => output.nodeId ? [output.nodeId] : []));
  const nodePickerId = useId();
  const nodePickerHintId = useId();
  const executionOptionsId = useId();
  const nodePickerTrigger = useRef<HTMLButtonElement>(null);
  const invalidParameter = useRef<HTMLElement | null>(null);
  const nodeIds = inputNodeIds(definition);
  // Output-only nodes have no editable input fields. Retain the imported catalog and
  // saved IDs regardless of the parameter view's selection or removed bindings.
  const outputNodeIds = [...new Set([
    ...(definition.nodeOptions ?? []).map((node) => node.nodeId),
    ...nodeIds, ...savedOutputNodeIds, ...definition.outputs.map((output) => output.nodeId),
  ].filter((nodeId): nodeId is string => !!nodeId))]
    .sort((first, second) => first.localeCompare(second, undefined, { numeric: true }));
  const selectedNodes = nodeIds.filter((nodeId) => nodeSelection.includes(nodeId));
  const getNodeLabel = (nodeId: string) => nodeLabel(nodeId, definition);
  const outputNodeOptions = outputNodeIds.map((nodeId) => ({ nodeId, label: getNodeLabel(nodeId) }));
  const nodeSummary = selectedNodes.map(getNodeLabel).join(NODE_LABEL_SEPARATOR);
  useEffect(() => { if (!value) onChange(emptyDefinition(adapterId)); }, [adapterId, value, onChange]);
  const imported = useMutation({
    mutationFn: (useSource: boolean) => previewRunningHubImport(connectionId, { targetType: definition.targetType,
      targetId: definition.targetId, kind: adapterId === "RUNNINGHUB_VIDEO" ? "VIDEO_GENERATION" : adapterId === "RUNNINGHUB_AUDIO" ? "AUDIO_GENERATION" : "IMAGE_GENERATION",
      ...(useSource && source.trim() ? { source: JSON.parse(source) as unknown } : {}) }),
    onSuccess: (result) => {
      if (!sessionActive.current) return;
      const sameTarget = configuredTarget.current.targetType === result.definition.targetType && configuredTarget.current.targetId === result.definition.targetId;
      const next = sameTarget ? restoreRunningHubCandidates(definition, result.definition) : result.definition;
      if (next.fields.length > MAX_FIELDS) { setLocalError(t("settings.runningHub.restoreTooManyFields")); return; }
      const selection = importedRunningHubSelection(sameTarget ? definition : undefined, result.definition, next,
        nodeSelection, fieldSelection, result.recommendedFieldKeys);
      update(next, undefined, selection.nodeIds, selection.fieldKeys); configuredTarget.current = { targetType: next.targetType, targetId: next.targetId };
      if (result.targetName) onDiscoveredName?.(result.targetName);
      setSource(next.importSource ? JSON.stringify(next.importSource, null, 2) : "");
      setWarnings(result.warnings); setLocalError(""); setPreviewValues({}); setExpandedField(null);
      setNodeSelection(selection.nodeIds); setSavedOutputNodeIds(next.outputs.flatMap((output) => output.nodeId ? [output.nodeId] : []));
    },
  });
  const reportBusy = useRef(onBusyChange);
  reportBusy.current = onBusyChange;
  useEffect(() => {
    reportBusy.current?.(imported.isPending);
    return () => reportBusy.current?.(false);
  }, [imported.isPending]);
  const sectionId = useId();
  const workbench = useRef<HTMLDivElement>(null);
  const [activeSection, setActiveSection] = useState<typeof WORKBENCH_SECTION_IDS[number]>("target");
  useEffect(() => {
    if (typeof IntersectionObserver === "undefined") return;
    const root = workbench.current?.closest(".ui-dialog-body") ?? null;
    // The dialog body owns scrolling; observing the window misses transitions in a modal.
    const observer = new IntersectionObserver((entries) => {
      const visible = entries.filter((entry) => entry.isIntersecting)
        .sort((first, second) => first.boundingClientRect.top - second.boundingClientRect.top)[0];
      const id = visible?.target.getAttribute("data-workbench-section");
      const section = WORKBENCH_SECTION_IDS.find((item) => item === id);
      if (section) setActiveSection(section);
    }, { root, rootMargin: "-8% 0px -65% 0px", threshold: 0 });
    for (const id of WORKBENCH_SECTION_IDS) {
      const section = document.getElementById(`${sectionId}-${id}`);
      if (section) observer.observe(section);
    }
    return () => observer.disconnect();
  }, [sectionId]);
  const exposedFields = definition.fields.filter(fieldSelected);
  const selectedFixed = (definition.fixedBindings ?? []).filter((binding) => selectedNodes.includes(binding.nodeId));
  const sections: { id: typeof WORKBENCH_SECTION_IDS[number]; label: string }[] = [
    { id: "target", label: t("settings.runningHub.targetSection") },
    { id: "inputs", label: t("settings.runningHub.inputsSection") },
    { id: "outputs", label: t("settings.runningHub.outputsSection") },
    { id: "preview", label: t("settings.runningHub.previewSection") },
  ];
  function navigateSection(id: typeof WORKBENCH_SECTION_IDS[number]) {
    setActiveSection(id);
    const target = document.getElementById(`${sectionId}-${id}`);
    if (!target) return;
    const behavior = window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth";
    const scrollArea = workbench.current?.closest<HTMLElement>(".ui-dialog-body");
    if (scrollArea) {
      // scrollIntoView also moves overflow-hidden dialog ancestors, hiding the fixed title.
      const navigation = workbench.current?.querySelector<HTMLElement>(".runninghub-workbench-nav");
      const navigationHeight = navigation && workbench.current && getComputedStyle(workbench.current).display === "block"
        ? navigation.offsetHeight : 0;
      const top = target.getBoundingClientRect().top - scrollArea.getBoundingClientRect().top + scrollArea.scrollTop - navigationHeight - 16;
      scrollArea.scrollTo({ top, behavior });
    } else target.scrollIntoView({ behavior, block: "start" });
  }
  function sectionHeading(index: number) {
    return <div className="runninghub-section-heading"><span aria-hidden>{String(index + 1).padStart(2, "0")}</span><h3>{sections[index]!.label}</h3></div>;
  }
  function update(next: RunningHubDefinition, primaryKind?: OutputKind, selection = nodeSelection, fields = fieldSelection) {
    const keys = new Set(next.fields.map((field) => field.key));
    const retained = fields.filter((key) => keys.has(key));
    setFieldSelection(retained); onChange(next, primaryKind, selection, retained);
  }
  function selectField(key: string, checked: boolean) {
    setLocalError(""); update(definition, undefined, nodeSelection, checked ? [...new Set([...fieldSelection, key])] : fieldSelection.filter((item) => item !== key));
  }
  function selectNodes(selection: string[]) {
    setNodeSelection(selection); setLocalError(""); update(definition, undefined, selection);
  }
  function revealNode(nodeId: string) {
    const selection = nodeSelection.includes(nodeId) ? nodeSelection : [...nodeSelection, nodeId];
    setNodeSelection(selection);
    return selection;
  }
  function field(index: number, patch: Partial<RunningHubField>) {
    const selection = patch.nodeId !== undefined ? revealNode(patch.nodeId) : nodeSelection;
    const previousKey = definition.fields[index]?.key;
    const keys = patch.key !== undefined ? fieldSelection.map((key) => key === previousKey ? patch.key! : key) : fieldSelection;
    update({ ...definition, fields: definition.fields.map((item, i) => i === index ? { ...item, ...patch } : item) }, undefined, selection, keys);
  }
  function addField(nodeId: string) {
    let ordinal = definition.fields.length + 1;
    while (definition.fields.some((item) => item.key === `field${ordinal}`)) ordinal += 1;
    const selection = revealNode(nodeId);
    update({ ...definition, fields: [...definition.fields, { key: `field${ordinal}`, label: t("settings.runningHub.newParameter"),
      type: "STRING", nodeId, fieldName: "", required: true, advanced: false, source: "PARAMETER" }] }, undefined, selection, [...fieldSelection, `field${ordinal}`]);
  }
  function revealInvalidParameter(event: FormEvent<HTMLFieldSetElement>) {
    const input = event.target;
    if (!(input instanceof HTMLElement)) return;
    const row = input.closest<HTMLElement>("[data-parameter-node]");
    if (!row || !selectedNodes.includes(row.dataset.parameterNode ?? "")) return;
    if (row.dataset.parameterKey && !fieldSelection.includes(row.dataset.parameterKey)) return;
    event.preventDefault();
    // Native validation visits every invalid control; reveal and focus the first one only.
    if (invalidParameter.current) return;
    invalidParameter.current = input;
    revealNode(row.dataset.parameterNode ?? "");
    if (row.dataset.parameterIndex !== undefined) setExpandedField(Number(row.dataset.parameterIndex));
    requestAnimationFrame(() => { input.focus(); invalidParameter.current = null; });
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
  function renderParameter(item: RunningHubField, index: number) {
    return <Fragment key={`field-${index}`}>
      <TableRow hidden={!selectedNodes.includes(item.nodeId)} className="runninghub-parameter-card" data-exposed={fieldSelected(item)} data-parameter-node={item.nodeId} data-parameter-key={item.key} aria-label={item.label || t("settings.runningHub.fieldLabel", { "0": index + 1 })}>
        <TableCell>
          <div className="runninghub-parameter-header">
            <div><strong>{item.label || t("settings.runningHub.fieldLabel", { "0": index + 1 })}</strong><code>{item.nodeId || "—"} / {item.fieldName || "—"}</code></div>
            <div className="runninghub-parameter-toggles">
              <Field orientation="horizontal" className="runninghub-expose-control"><FieldLabel><Checkbox disabled={!selectedNodes.includes(item.nodeId)} aria-label={t("settings.runningHub.exposeFieldLabel", { "0": item.label })} checked={fieldSelection.includes(item.key)} onCheckedChange={(checked) => selectField(item.key, checked === true)} /><span>{t("settings.runningHub.exposeField")}</span></FieldLabel></Field>
              <Field orientation="horizontal" className="runninghub-expose-control"><FieldLabel><Checkbox disabled={!fieldSelected(item)} aria-label={t("settings.runningHub.requiredSuffix")} checked={item.required ?? false} onCheckedChange={(checked) => field(index, { required: checked === true })} /><span>{t("settings.runningHub.requiredSuffix")}</span></FieldLabel></Field>
              <div className="runninghub-mapping-actions">
              <Button variant="ghost" size="icon-sm" type="button" aria-label={t("settings.runningHub.moreSettings")} title={t("settings.runningHub.moreSettings")} aria-expanded={expandedField === index}
                onClick={() => setExpandedField(expandedField === index ? null : index)}>
                <SlidersHorizontal /></Button>
              <Button variant="ghost" size="icon-sm" type="button" aria-label={`${t("settings.runningHub.removeCandidate")} · ${item.label}`}
                onClick={() => { setExpandedField(null); update({ ...definition, fields: definition.fields.filter((_, i) => i !== index) }); }}><Trash /></Button>
            </div>
            </div>
          </div>
          <FieldGroup className="runninghub-parameter-grid">
            <Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.label")}</span><Input disabled={!fieldSelected(item)} required maxLength={160} value={item.label} onChange={(event) => field(index, { label: event.target.value })} /></FieldLabel></Field>
            <Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.type")}</span><Select disabled={!fieldSelected(item)} value={item.type} onChange={(event) => field(index, { type: event.target.value as RunningHubField["type"], defaultValue: null, options: [] })}>
            {(["STRING", "NUMBER", "INTEGER", "BOOLEAN", "SELECT", "IMAGE", "AUDIO", "VIDEO"] as const).map((type) => <option value={type} key={type}>{({ STRING: t("common.text"), NUMBER: t("settings.runningHub.numberInput"), INTEGER: t("settings.runningHub.integerInput"), BOOLEAN: t("settings.runningHub.booleanInput"), SELECT: t("settings.runningHub.selectInput"), IMAGE: t("settings.runningHub.imageAsset"), AUDIO: t("settings.runningHub.audioAsset"), VIDEO: t("settings.runningHub.videoAsset") })[type]}</option>)}
          </Select></FieldLabel></Field>
            <Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.inputSource")}</span><Select disabled={!fieldSelected(item)} value={item.source ?? "PARAMETER"} onChange={(event) => field(index, { source: event.target.value as RunningHubField["source"] })}>
            <option value="PARAMETER">{t("settings.runningHub.formFields")}</option>{item.type === "STRING" ? <option value="PROMPT">{t("settings.runningHub.canvasPrompt")}</option> : null}{item.type === "INTEGER" && adapterId === "RUNNINGHUB_VIDEO" ? <option value="DURATION_SECONDS">{t("settings.runningHub.videoDuration")}</option> : null}
          </Select></FieldLabel></Field>
            {["IMAGE", "AUDIO", "VIDEO"].includes(item.type) ? <Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.uploadReferenceFormat")}</span><Select disabled={!fieldSelected(item)} value={item.resourceFormat ?? "FILE_NAME"} onChange={(event) => field(index, { resourceFormat: event.target.value as RunningHubField["resourceFormat"] })}>
            <option value="FILE_NAME">{t("settings.runningHub.filenameReference")}</option><option value="URL">{t("settings.runningHub.urlReference")}</option>
          </Select></FieldLabel></Field> : <Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.defaultValue")}</span>{item.type === "BOOLEAN" ? <Select disabled={!fieldSelected(item)} value={item.defaultValue == null ? "" : String(item.defaultValue)} onChange={(event) => field(index, { defaultValue: event.target.value ? event.target.value === "true" : null })}><option value="">{t("settings.runningHub.noDefault")}</option><option value="true">{t("common.enabled")}</option><option value="false">{t("common.close")}</option></Select>
            : item.type === "STRING" ? <Input disabled={!fieldSelected(item)} value={String(item.defaultValue ?? "")} onChange={(event) => field(index, { defaultValue: event.target.value })} />
            : <Input disabled={!fieldSelected(item)} key={JSON.stringify(item.defaultValue)} defaultValue={item.defaultValue == null ? "" : JSON.stringify(item.defaultValue)} onBlur={(event) => {
              const raw = event.target.value;
              if (!raw) { event.target.setCustomValidity(""); field(index, { defaultValue: null }); setLocalError(""); return; }
              commitScalar(event.target, t("settings.runningHub.invalidDefault"), (defaultValue) => field(index, { defaultValue }));
            }} />}</FieldLabel></Field>}
          </FieldGroup>
          <div className="runninghub-technical-label">{t("settings.runningHub.technicalMapping")}</div>
          <FieldGroup className="runninghub-technical-grid">
            <Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.nodeId")}</span><Input disabled={!fieldSelected(item)} required pattern="[0-9]{1,32}" value={item.nodeId} onChange={(event) => field(index, { nodeId: event.target.value })} /></FieldLabel></Field>
            <Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.nodeField")}</span><Input disabled={!fieldSelected(item)} required pattern="[A-Za-z_][A-Za-z0-9_.]{0,79}" value={item.fieldName} onChange={(event) => field(index, { fieldName: event.target.value })} /></FieldLabel></Field>
            <Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.fieldKey")}</span><Input disabled={!fieldSelected(item)} required pattern="[A-Za-z][A-Za-z0-9_]{0,63}" value={item.key} onChange={(event) => field(index, { key: event.target.value })} /></FieldLabel></Field>
          </FieldGroup>
        </TableCell>
      </TableRow>
          <TableRow data-parameter-node={item.nodeId} data-parameter-key={item.key} data-parameter-index={index} aria-label={`${t("settings.runningHub.moreSettings")} · ${item.label}`} hidden={!selectedNodes.includes(item.nodeId) || expandedField !== index}>
            <TableCell className="runninghub-parameter-details">
              <fieldset className="runninghub-field-details ui-stack"><legend>{item.label || t("settings.runningHub.fieldLabel", { "0": index + 1 })}</legend>
                <FieldGroup className="ui-form-grid">
                  <Field><FieldLabel className="ui-field block">{t("settings.runningHub.description")}<Input disabled={!fieldSelected(item)} maxLength={1000} value={item.description ?? ""} onChange={(event) => field(index, { description: event.target.value })} /></FieldLabel></Field>
                  <Field orientation="horizontal"><FieldLabel><Checkbox disabled={!fieldSelected(item)} checked={item.advanced ?? false} onCheckedChange={(event) => field(index, { advanced: event === true })} /> {t("settings.runningHub.advancedParameterLabel")}</FieldLabel></Field>
                  {["NUMBER", "INTEGER"].includes(item.type) ? <>{(["minimum", "maximum"] as const).map((bound) => <Field key={bound}><FieldLabel className="ui-field block">{bound === "minimum" ? t("settings.runningHub.minimum") : t("settings.runningHub.maximum")}<Input disabled={!fieldSelected(item)} type="number" value={item[bound] ?? ""} onChange={(event) => field(index, { [bound]: event.target.value ? Number(event.target.value) : null })} /></FieldLabel></Field>)}</> : null}
                  {item.type === "STRING" ? <Field><FieldLabel className="ui-field block">{t("settings.runningHub.maxLength")}<Input disabled={!fieldSelected(item)} type="number" min={1} max={20000} value={item.maxLength ?? ""} onChange={(event) => field(index, { maxLength: event.target.value ? Number(event.target.value) : null })} /></FieldLabel></Field> : null}
                  <Field><FieldLabel className="ui-field block">{t("settings.runningHub.visibilityCondition")}<Select disabled={!fieldSelected(item)} value={item.enabledWhen?.field ?? ""} onChange={(event) => field(index, { enabledWhen: event.target.value ? { field: event.target.value, value: definition.fields.find((parent) => parent.key === event.target.value)?.defaultValue ?? "" } : null })}>
                    <option value="">{t("settings.runningHub.alwaysVisible")}</option>{definition.fields.filter((parent) => parent.key !== item.key && !parent.enabledWhen && !["IMAGE", "AUDIO", "VIDEO"].includes(parent.type)).map((parent) => <option key={parent.key} value={parent.key}>{parent.label}</option>)}
                  </Select></FieldLabel></Field>
                  {item.enabledWhen ? <Field><FieldLabel className="ui-field block">{t("settings.runningHub.conditionValue")}<Input disabled={!fieldSelected(item)} key={JSON.stringify(item.enabledWhen)} defaultValue={JSON.stringify(item.enabledWhen.value)} onBlur={(event) => {
                    commitScalar(event.target, t("settings.runningHub.invalidCondition"), (value) => field(index, { enabledWhen: { field: item.enabledWhen!.field, value } }));
                  }} /></FieldLabel></Field> : null}
                  <Field><FieldLabel className="ui-field block">{t("settings.runningHub.encoding")}<Select disabled={!fieldSelected(item)} value={item.encoding ?? "NATIVE"} onChange={(event) => field(index, { encoding: event.target.value as RunningHubField["encoding"] })}><option value="NATIVE">{t("settings.runningHub.preserveType")}</option><option value="STRING">{t("settings.runningHub.stringEncoding")}</option></Select></FieldLabel></Field>
                </FieldGroup>
        {item.type === "SELECT" ? <Field><FieldLabel className="ui-field block">{t("settings.runningHub.choices")}<Textarea disabled={!fieldSelected(item)} key={JSON.stringify(item.options)} defaultValue={(item.options ?? []).map((option) => String(option.value)).join("\n")} onBlur={(event) => field(index, { options: event.target.value.split("\n").filter(Boolean).map((text) => item.options?.find((option) => String(option.value) === text) ?? ({ label: text, value: text })) })} />
        </FieldLabel></Field> : null}
              </fieldset>
            </TableCell>
          </TableRow>
    </Fragment>;
  }
  function renderFixed(binding: NonNullable<RunningHubDefinition["fixedBindings"]>[number], index: number) {
    return <TableRow key={`fixed-${index}`} hidden={!selectedNodes.includes(binding.nodeId)} className="runninghub-fixed-card" data-parameter-node={binding.nodeId}>
      <TableCell>
        <div className="runninghub-parameter-header"><strong>{t("settings.runningHub.fixedFields")} · {binding.fieldName || "—"}</strong><Button variant="ghost" type="button" onClick={() => update({ ...definition, fixedBindings: definition.fixedBindings?.filter((_, i) => i !== index) })}>{t("settings.runningHub.removeFixedMapping")}</Button></div>
        <FieldGroup className="runninghub-technical-grid"><Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.fixedNodeId")}</span><Input disabled={!selectedNodes.includes(binding.nodeId)} required pattern="[0-9]{1,32}" value={binding.nodeId} onChange={(event) => { const selection = revealNode(event.target.value); update({ ...definition, fixedBindings: definition.fixedBindings?.map((item, i) => i === index ? { ...item, nodeId: event.target.value } : item) }, undefined, selection); }} /></FieldLabel></Field><Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.fixedFields")}</span><Input disabled={!selectedNodes.includes(binding.nodeId)} required pattern="[A-Za-z_][A-Za-z0-9_.]{0,79}" value={binding.fieldName} onChange={(event) => update({ ...definition, fixedBindings: definition.fixedBindings?.map((item, i) => i === index ? { ...item, fieldName: event.target.value } : item) })} /></FieldLabel></Field><Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.fixedValue")}</span><Input disabled={!selectedNodes.includes(binding.nodeId)} key={JSON.stringify(binding.value)} defaultValue={JSON.stringify(binding.value)} onBlur={(event) => {
          commitScalar(event.target, t("settings.runningHub.invalidFixedValue"), (value) => update({ ...definition,
            fixedBindings: definition.fixedBindings?.map((item, i) => i === index ? { ...item, value } : item) }));
        }} /></FieldLabel></Field></FieldGroup>
      </TableCell>
    </TableRow>;
  }
  return <div ref={workbench} className="runninghub-definition-editor">
    <nav className="runninghub-workbench-nav" aria-label={t("settings.runningHub.workbenchNavigation")}>
      {sections.map((section, index) => <a key={section.id} href={`#${sectionId}-${section.id}`} aria-current={activeSection === section.id ? "step" : undefined} onClick={(event) => {
        event.preventDefault(); navigateSection(section.id);
      }}><span aria-hidden>{String(index + 1).padStart(2, "0")}</span>{section.label}</a>)}
      <div className="runninghub-nav-scope"><small>{t("settings.runningHub.scopeSummary")}</small>
        <strong>{t("settings.runningHub.nodeCount", { "0": selectedNodes.length })}</strong>
        <span>{t("settings.runningHub.exposedCount", { "0": exposedFields.length })}</span>
        <span>{t("settings.runningHub.fixedCount", { "0": selectedFixed.length })}</span>
      </div>
    </nav>
    <fieldset className="runninghub-workbench-fields">
    <section data-workbench-section="target" id={`${sectionId}-target`} className="runninghub-workbench-section" aria-label={sections[0]!.label}>
    {sectionHeading(0)}
    <p className="ui-muted">{t("settings.runningHub.setupHint")}</p>
    {identityFields}
    <FieldGroup className="ui-form-grid">
      <Field><FieldLabel className="ui-field block">{t("settings.runningHub.targetType")}<Select disabled={imported.isPending} value={definition.targetType} onChange={(event) => update({ ...definition, targetType: event.target.value === "AI_APP" ? "AI_APP" : "WORKFLOW" })}>
        <option value="WORKFLOW">{t("settings.runningHub.comfyWorkflow")}</option><option value="AI_APP">{t("settings.runningHub.aiApp")}</option>
      </Select></FieldLabel></Field>
      <Field><FieldLabel className="ui-field block">{t("settings.runningHub.targetId")}<Input disabled={imported.isPending} required pattern="[0-9]{1,32}" value={definition.targetId}
        onChange={(event) => update({ ...definition, targetId: event.target.value })} placeholder="workflowId / webappId" /></FieldLabel></Field>
    </FieldGroup>
    <p className="ui-muted">{t("settings.runningHub.targetIdHint")}</p>
    <details className="runninghub-editor-section runninghub-import-source"><summary>{t("settings.runningHub.importJson")}</summary>
      <Field><FieldLabel className="ui-field block">{t("settings.runningHub.importPlaceholder")}<Textarea disabled={imported.isPending} value={source} onChange={(event) => {
        setSource(event.target.value); onSourceDraftChange?.(event.target.value);
      }} rows={5} maxLength={MAX_IMPORT_SOURCE_CHARACTERS} />
      </FieldLabel></Field><p>{t("settings.runningHub.sanitizeImportHint")}</p>
      <p className="ui-muted">{t("settings.runningHub.savedSourceHint")}</p>
    </details>
    <Button variant="outline"  type="button" disabled={imported.isPending || !/^[0-9]{1,32}$/.test(definition.targetId)}
      onClick={() => { setLocalError(""); imported.mutate(true); }}>{imported.isPending ? t("settings.runningHub.importing") : source.trim() ? t("settings.runningHub.importFields") : t("settings.runningHub.discover")}</Button>
    {source.trim() ? <Button variant="ghost" type="button" disabled={imported.isPending || !/^[0-9]{1,32}$/.test(definition.targetId)}
      onClick={() => { setLocalError(""); imported.mutate(false); }}>{t("settings.runningHub.discover")}</Button> : null}
    {imported.error ? <Notice tone="danger">{imported.error.message}</Notice> : null}
    {warnings.map((warning) => <Notice tone="warning" key={warning}>{warning}</Notice>)}
    </section>
    <section data-workbench-section="inputs" id={`${sectionId}-inputs`} className="runninghub-workbench-section" aria-label={sections[1]!.label}>
    {sectionHeading(1)}
    <fieldset className="ui-stack runninghub-input-fields" disabled={imported.isPending} onInvalidCapture={revealInvalidParameter}><legend className="sr-only">{t("settings.runningHub.mappingTable")}</legend>
      <p className="ui-muted">{t("settings.runningHub.mappingHint")}</p>
      <Field className="runninghub-node-picker"><FieldLabel htmlFor={nodePickerId}>{t("settings.runningHub.selectNode")}</FieldLabel>
        {/* A modal menu grants its portal a scroll boundary inside the parent dialog's scroll lock. */}
        <DropdownMenu>
          <DropdownMenuTrigger asChild><Button ref={nodePickerTrigger} id={nodePickerId} variant="outline" type="button" className="w-full justify-between"
            aria-label={t("settings.runningHub.selectNode")} aria-describedby={nodePickerHintId} disabled={!nodeIds.length} title={nodeSummary || undefined}>
            <span className="truncate">{nodeSummary || t("settings.runningHub.selectNodePlaceholder")}</span><CaretDown data-icon="inline-end" />
          </Button></DropdownMenuTrigger>
          <DropdownMenuContent aria-label={t("settings.runningHub.selectNode")} aria-labelledby={undefined} align="start" className="w-[var(--radix-dropdown-menu-trigger-width)] max-w-[var(--radix-dropdown-menu-content-available-width)]">
            <DropdownMenuGroup>
              <DropdownMenuItem onSelect={(event) => { event.preventDefault(); if (!nodePickerTrigger.current?.matches(":disabled")) selectNodes(nodeIds); }}>{t("settings.runningHub.selectAllNodes")}</DropdownMenuItem>
              <DropdownMenuItem disabled={!selectedNodes.length} onSelect={(event) => { event.preventDefault(); if (!nodePickerTrigger.current?.matches(":disabled")) selectNodes([]); }}>{t("settings.runningHub.clearNodeSelection")}</DropdownMenuItem>
            </DropdownMenuGroup>
            <DropdownMenuSeparator />
            <DropdownMenuGroup>{nodeIds.map((nodeId) => <DropdownMenuCheckboxItem key={nodeId} title={getNodeLabel(nodeId)} checked={selectedNodes.includes(nodeId)}
              onSelect={(event) => event.preventDefault()} onCheckedChange={(checked) => {
                if (nodePickerTrigger.current?.matches(":disabled")) return;
                selectNodes(checked ? [...new Set([...nodeSelection, nodeId])] : nodeSelection.filter((id) => id !== nodeId));
              }}><span className="min-w-0 whitespace-normal break-words">{getNodeLabel(nodeId)}</span></DropdownMenuCheckboxItem>)}
            </DropdownMenuGroup>
          </DropdownMenuContent>
        </DropdownMenu>
        <FieldDescription id={nodePickerHintId}>{t("settings.runningHub.selectNodeHint")}</FieldDescription></Field>
      <Button variant="outline" type="button" disabled={definition.fields.length >= MAX_FIELDS} onClick={() => addField("")}>{t("settings.runningHub.addNode")}</Button>
      {!selectedNodes.length ? <EmptyState title={t("settings.runningHub.emptySelection")} description={t("settings.runningHub.emptySelectionHint")} /> : null}
      <div hidden={!selectedNodes.length} className="runninghub-parameters" role="region" aria-label={t("settings.runningHub.mappingTable")}>
        <Table className="runninghub-parameter-table" aria-label={t("settings.runningHub.mappingTable")}>
          <TableBody>{nodeIds.flatMap((nodeId) => [
            <TableRow key={`node-${nodeId}`} hidden={!selectedNodes.includes(nodeId)} className="runninghub-node-heading">
              <TableCell><div><h4>{getNodeLabel(nodeId)}</h4><Badge variant="secondary">{t("settings.runningHub.exposedCount", { "0": definition.fields.filter((item) => item.nodeId === nodeId && fieldSelected(item)).length })}</Badge></div></TableCell>
            </TableRow>,
            ...definition.fields.flatMap((item, index) => item.nodeId === nodeId ? [renderParameter(item, index)] : []),
            ...(definition.fixedBindings ?? []).flatMap((binding, index) => binding.nodeId === nodeId ? [renderFixed(binding, index)] : []),
          ])}</TableBody>
        </Table>
      </div>
      <NodeParameterAction nodeIds={selectedNodes} getNodeLabel={getNodeLabel} label={t("settings.runningHub.addField")} disabled={definition.fields.length >= MAX_FIELDS} onAdd={addField} />
      <p hidden={!selectedNodes.length}>{t("settings.runningHub.fixedParametersHint")}</p>
      <NodeParameterAction nodeIds={selectedNodes} getNodeLabel={getNodeLabel} label={t("settings.runningHub.addFixedMapping")} variant="ghost" onAdd={(nodeId) => {
        update({ ...definition, fixedBindings: [...(definition.fixedBindings ?? []), { nodeId, fieldName: "", value: "", encoding: "NATIVE" }] }, undefined, revealNode(nodeId));
      }} />
    </fieldset>
    </section>
    <section data-workbench-section="outputs" id={`${sectionId}-outputs`} className="runninghub-workbench-section" aria-label={sections[2]!.label}>
    {sectionHeading(2)}
    <fieldset className="ui-stack" disabled={imported.isPending}><legend className="sr-only">{t("settings.runningHub.outputMappings")}</legend>
      <Table className="runninghub-output-table" aria-label={t("settings.runningHub.outputMappings")}>
      <TableBody>
      {definition.outputs.map((output, index) => <TableRow key={index} className="runninghub-output-card"><TableCell>
        <div className="runninghub-output-header">{output.primary ? <span>{t("settings.runningHub.primaryOutputHint")}</span> : <Button variant="ghost" type="button" onClick={() => update({ ...definition, outputs: definition.outputs.filter((_, i) => i !== index) })}>{t("settings.runningHub.removeOutput")}</Button>}</div>
        <FieldGroup className="runninghub-output-grid"><Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.outputNode")}</span><Select value={output.nodeId ?? ""} onChange={(event) => update({ ...definition, outputs: definition.outputs.map((item, i) => i === index ? { ...item, nodeId: event.target.value || null } : item) })}>
          <option value="">{t("settings.runningHub.matchOutputKind")}</option>
          {outputNodeOptions.map(({ nodeId, label }) => <option key={nodeId} value={nodeId}><span className="block max-w-[min(480px,calc(100vw-4rem))] whitespace-normal break-words" title={label}>{label}</span></option>)}
        </Select></FieldLabel></Field><Field><FieldLabel className="ui-field block"><span>{t("media.kind")}</span><Select value={output.kind} onChange={(event) => {
          const kind = event.target.value as OutputKind;
          update({ ...definition, outputs: definition.outputs.map((item, i) => i === index ? { ...item, kind } : item) }, output.primary ? kind : undefined);
        }}><option value="IMAGE">{t("common.image")}</option><option value="VIDEO">{t("common.video")}</option><option value="AUDIO">{t("common.audio")}</option></Select></FieldLabel></Field><Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.resultLimit")}</span><Input type="number" min={1} max={MAX_OUTPUTS} value={output.maxCount} onChange={(event) => update({ ...definition, outputs: definition.outputs.map((item, i) => i === index ? { ...item, maxCount: Number(event.target.value) } : item) })} /></FieldLabel></Field></FieldGroup>
      </TableCell></TableRow>)}
      </TableBody></Table>
      <Button variant="ghost" type="button" disabled={definition.outputs.length >= MAX_OUTPUTS} onClick={() => update({ ...definition, outputs: [...definition.outputs, { kind: "IMAGE", primary: false, maxCount: 1 }] })}>{t("settings.runningHub.addOutputMapping")}</Button>
      <p className="ui-muted">{t("settings.runningHub.outputNodeHint")}</p>
      <p>{t("settings.runningHub.outputLimitsHint")}</p>
      <p className="ui-muted">{t("settings.runningHub.outputKindChangeHint")}</p>
    </fieldset>
    <details className="runninghub-editor-section"><summary>{t("settings.runningHub.executionOptions")}</summary>
      <fieldset className="runninghub-section-content ui-stack" disabled={imported.isPending}>
        <FieldGroup className="ui-form-grid">
          <Field><FieldLabel htmlFor={`${executionOptionsId}-instance`}>{t("settings.runningHub.instance")}</FieldLabel><Select id={`${executionOptionsId}-instance`} value={definition.instanceType ?? "default"} onChange={(event) => update({ ...definition, instanceType: event.target.value as RunningHubDefinition["instanceType"] })}><option value="default">default</option><option value="plus">plus</option><option value="ultra">ultra</option></Select></Field>
          <Field><FieldLabel htmlFor={`${executionOptionsId}-retention`}>{t("settings.runningHub.instanceRetentionSeconds")}</FieldLabel><Input id={`${executionOptionsId}-retention`} type="number" min={10} max={180} value={definition.retainSeconds ?? ""} onChange={(event) => update({ ...definition, retainSeconds: event.target.value ? Number(event.target.value) : null })} /></Field>
        </FieldGroup>
        <FieldGroup>
          <Field orientation="horizontal"><Checkbox id={`${executionOptionsId}-queue`} checked={definition.usePersonalQueue ?? false} onCheckedChange={(event) => update({ ...definition, usePersonalQueue: event === true })} /><FieldLabel htmlFor={`${executionOptionsId}-queue`}>{t("settings.runningHub.personalQueueSuffix")}</FieldLabel></Field>
          {definition.targetType === "WORKFLOW" ? <Field orientation="horizontal"><Checkbox id={`${executionOptionsId}-metadata`} checked={definition.addMetadata ?? false} onCheckedChange={(event) => update({ ...definition, addMetadata: event === true })} /><FieldLabel htmlFor={`${executionOptionsId}-metadata`}>{t("settings.runningHub.resultMetadataLabel")}</FieldLabel></Field> : null}
        </FieldGroup>
      </fieldset>
    </details>
    </section>
    <section data-workbench-section="preview" id={`${sectionId}-preview`} className="runninghub-workbench-section" aria-label={sections[3]!.label}>
    {sectionHeading(3)}
    <div className="runninghub-scope-summary" aria-label={t("settings.runningHub.scopeSummary")}>
      <Badge variant="outline">{t("settings.runningHub.nodeCount", { "0": selectedNodes.length })}</Badge>
      <Badge variant="outline">{t("settings.runningHub.exposedCount", { "0": exposedFields.length })}</Badge>
      <Badge variant="outline">{t("settings.runningHub.fixedCount", { "0": selectedFixed.length })}</Badge>
    </div>
    {pricingFields}
    <details className="runninghub-editor-section"><summary>{t("settings.runningHub.formPreview")}</summary><FieldGroup className="runninghub-section-content"><RunningHubForm definition={selectRunningHubNodes(definition, selectedNodes, fieldSelection)} values={previewValues} prompt="" durationSeconds={null} choices={[]}
      onChange={(key, next) => setPreviewValues((current) => { const values = { ...current }; if (next === undefined) delete values[key]; else values[key] = next; return values; })} /></FieldGroup></details>
    <p className="ui-muted">{t("settings.runningHub.contractVersionHint")}</p>
    </section>
    </fieldset>
    {localError ? <Notice tone="danger">{localError}</Notice> : null}
  </div>;
}
