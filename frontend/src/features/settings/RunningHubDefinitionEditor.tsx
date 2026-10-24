import { CaretDown,SlidersHorizontal,Trash } from "@/shared/ui/icons";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { useMutation } from "@tanstack/react-query";
import { Fragment,useEffect,useId,useMemo,useRef,useState,type FormEvent,type ReactNode } from "react";
import { previewRunningHubImport,type RunningHubDefinition,type RunningHubField,type RunningHubImportPreview } from "../../shared/api/client";
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
import { adoptRunningHubMetadata, compareRunningHubImports, importedRunningHubSelection, restoreRunningHubCandidates, runningHubFieldIdentity, runningHubMetadataAdoptionConflict, type RunningHubImportDiff } from "./runningHubImport";
import { selectRunningHubNodes } from "./runningHubSelection";
import { invalidRunningHubEditorField, invalidRunningHubEditorFixed } from "./runningHubEditorValidation";

const MAX_FIELDS = 1024;
const PARAMETER_PAGE_SIZE = 20;
type FieldInsight = NonNullable<RunningHubImportPreview["fieldInsights"]>[number];
type CandidateFilter = "ALL" | "RECOMMENDED" | "EXPOSED" | "UNCERTAIN";
const CONFIDENCE_KEYS = { DECLARED: "settings.runningHub.confidence.declared", INFERRED: "settings.runningHub.confidence.inferred", UNCERTAIN: "settings.runningHub.confidence.uncertain", UNVERIFIED: "settings.runningHub.confidence.unverified" } as const;
const REASON_KEYS = {
  TYPE_DECLARATION: "settings.runningHub.reason.typeDeclaration", WIDGET_TYPE: "settings.runningHub.reason.widgetType",
  JSON_VALUE: "settings.runningHub.reason.jsonValue", UPLOAD_WIDGET: "settings.runningHub.reason.uploadWidget",
  NAME_HINT: "settings.runningHub.reason.nameHint", DIRECT_CREATIVE_CONNECTION: "settings.runningHub.reason.directCreativeConnection",
  PRIMARY_PROMPT: "settings.runningHub.reason.primaryPrompt", NEGATIVE_PROMPT: "settings.runningHub.reason.negativePrompt",
  AMBIGUOUS_PROMPT: "settings.runningHub.reason.ambiguousPrompt", CONFLICTING_MEDIA_METADATA: "settings.runningHub.reason.conflictingMediaMetadata",
  MISSING_ENUM_OPTIONS: "settings.runningHub.reason.missingEnumOptions", RESOURCE_URL_NODE: "settings.runningHub.reason.resourceUrlNode",
  RESOURCE_FILE_NODE: "settings.runningHub.reason.resourceFileNode", RESOURCE_FORMAT_UNCERTAIN: "settings.runningHub.reason.resourceFormatUncertain",
  EXPLICIT_GROUP: "settings.runningHub.reason.explicitGroup", MISSING_TYPE_METADATA: "settings.runningHub.reason.missingTypeMetadata",
} as const satisfies Record<FieldInsight["reasons"][number], Parameters<typeof t>[0]>;
const CHANGE_KEYS = { ADDED: "settings.runningHub.change.added", REMOVED: "settings.runningHub.change.removed", TYPE: "settings.runningHub.change.type", OPTIONS: "settings.runningHub.change.options", CONSTRAINTS: "settings.runningHub.change.constraints", RESOURCE_FORMAT: "settings.runningHub.change.resourceFormat" } as const;
const FILTER_KEYS = { ALL: "settings.runningHub.filter.all", RECOMMENDED: "settings.runningHub.filter.recommended", EXPOSED: "settings.runningHub.filter.exposed", UNCERTAIN: "settings.runningHub.filter.uncertain" } as const;
const MAX_OUTPUTS = 16;
const MAX_IMPORT_SOURCE_CHARACTERS = 1024 * 1024;
const NODE_LABEL_SEPARATOR = " · ";
const WORKBENCH_SECTION_IDS = ["target", "inputs", "outputs", "preview"] as const;
type OutputKind = RunningHubDefinition["outputs"][number]["kind"];
function nodeLabel(nodeId: string, definition: RunningHubDefinition, fields: readonly RunningHubField[], importedLabels: ReadonlyMap<string, string>, currentLocale: Parameters<typeof translate>[0]) {
  if (!nodeId) return translate(currentLocale, "settings.runningHub.unassignedNode");
  const idLabel = translate(currentLocale, "settings.runningHub.nodeOption", { "0": nodeId });
  for (const field of fields) {
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
  const importedName = importedLabels.get(nodeId)?.trim();
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
  const locale = useLocale();
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
  const [insights, setInsights] = useState<FieldInsight[]>([]);
  const [recommended, setRecommended] = useState<string[]>([]);
  const [remoteDiff, setRemoteDiff] = useState<RunningHubImportDiff | null>(null);
  const [diffPage, setDiffPage] = useState(0);
  const [search, setSearch] = useState("");
  const [nodeSearch, setNodeSearch] = useState("");
  const [candidateFilter, setCandidateFilter] = useState<CandidateFilter>("ALL");
  const [parameterPage, setParameterPage] = useState(0);
  const [rawDrafts, setRawDrafts] = useState<Record<string, string>>({});
  const draftKey = (item: RunningHubField, name: string) => `${item.key}:${name}`;
  function retainRaw(item: RunningHubField, name: string, raw: string) {
    setRawDrafts((current) => ({ ...current, [draftKey(item, name)]: raw }));
  }
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
  const fieldIndex = useMemo(() => {
    const nodes = new Map<string, { item: RunningHubField; index: number }[]>();
    definition.fields.forEach((item, index) => { const items = nodes.get(item.nodeId) ?? []; items.push({ item, index }); nodes.set(item.nodeId, items); });
    return nodes;
  }, [definition.fields]);
  const fixedIndex = useMemo(() => {
    const nodes = new Map<string, { binding: NonNullable<RunningHubDefinition["fixedBindings"]>[number]; index: number }[]>();
    (definition.fixedBindings ?? []).forEach((binding, index) => { const items = nodes.get(binding.nodeId) ?? []; items.push({ binding, index }); nodes.set(binding.nodeId, items); });
    return nodes;
  }, [definition.fixedBindings]);
  const insightIndex = useMemo(() => new Map(insights.map((item) => [runningHubFieldIdentity(item), item])), [insights]);
  const recommendedIdentities = useMemo(() => new Set(recommended), [recommended]);
  // Output-only nodes have no editable input fields. Retain the imported catalog and
  // saved IDs regardless of the parameter view's selection or removed bindings.
  const outputNodeIds = [...new Set([
    ...(definition.nodeOptions ?? []).map((node) => node.nodeId),
    ...nodeIds, ...savedOutputNodeIds, ...definition.outputs.map((output) => output.nodeId),
  ].filter((nodeId): nodeId is string => !!nodeId))]
    .sort((first, second) => first.localeCompare(second, undefined, { numeric: true }));
  const selectedNodes = nodeIds.filter((nodeId) => nodeSelection.includes(nodeId));
  const importedNodeLabels = useMemo(() => new Map(definition.nodeOptions?.map((node) => [node.nodeId, node.label]) ?? []), [definition.nodeOptions]);
  const nodeLabels = useMemo(() => new Map([...new Set([...fieldIndex.keys(), ...importedNodeLabels.keys()])].map((nodeId) => [nodeId,
    nodeLabel(nodeId, definition, (fieldIndex.get(nodeId) ?? []).map(({ item }) => item), importedNodeLabels, locale)])), [definition, fieldIndex, importedNodeLabels, locale]);
  const getNodeLabel = (nodeId: string) => nodeLabels.get(nodeId) ?? nodeLabel(nodeId, definition, [], importedNodeLabels, locale);
  const outputNodeOptions = outputNodeIds.map((nodeId) => ({ nodeId, label: getNodeLabel(nodeId) }));
  const nodeSummary = selectedNodes.map(getNodeLabel).join(NODE_LABEL_SEPARATOR);
  useEffect(() => { if (!value) onChange(emptyDefinition(adapterId)); }, [adapterId, value, onChange]);
  const imported = useMutation({
    mutationFn: async (useSource: boolean) => {
      const request = { targetType: definition.targetType, targetId: definition.targetId,
        kind: adapterId === "RUNNINGHUB_VIDEO" ? "VIDEO_GENERATION" as const : adapterId === "RUNNINGHUB_AUDIO" ? "AUDIO_GENERATION" as const : "IMAGE_GENERATION" as const };
      const sameTarget = configuredTarget.current.targetType === definition.targetType && configuredTarget.current.targetId === definition.targetId;
      const localSource: unknown = useSource && source.trim() ? JSON.parse(source) as unknown : undefined;
      const unchangedSource = sameTarget && definition.importSource && localSource !== undefined && JSON.stringify(localSource) === JSON.stringify(definition.importSource);
      // Re-use the server parser for both snapshots so browser heuristics cannot diverge from discovery.
      const [next, baseline] = await Promise.allSettled([
        previewRunningHubImport(connectionId, { ...request, ...(localSource !== undefined ? { source: localSource } : {}) }),
        sameTarget && definition.importSource && !unchangedSource ? previewRunningHubImport(connectionId, { ...request, source: definition.importSource }) : Promise.resolve(null),
      ]);
      if (next.status === "rejected") throw next.reason;
      return { result: next.value, baseline: unchangedSource ? next.value.definition : baseline.status === "fulfilled" ? baseline.value?.definition : undefined };
    },
    onSuccess: ({ result, baseline }) => {
      if (!sessionActive.current) return;
      const sameTarget = configuredTarget.current.targetType === result.definition.targetType && configuredTarget.current.targetId === result.definition.targetId;
      const next = sameTarget ? restoreRunningHubCandidates(definition, result.definition) : result.definition;
      if (next.fields.length > MAX_FIELDS) { setLocalError(t("settings.runningHub.restoreTooManyFields")); return; }
      setDiffPage(0);
      setRemoteDiff(sameTarget && definition.fields.length ? compareRunningHubImports(baseline ?? definition, result.definition, !!baseline) : null);
      setInsights(result.fieldInsights ?? []);
      const suggestions = new Set(result.recommendedFieldKeys);
      setRecommended(result.definition.fields.filter((item) => suggestions.has(item.key)).map(runningHubFieldIdentity));
      const selection = importedRunningHubSelection(sameTarget ? definition : undefined, result.definition, next,
        nodeSelection, fieldSelection, result.recommendedFieldKeys);
      update(next, undefined, selection.nodeIds, selection.fieldKeys); configuredTarget.current = { targetType: next.targetType, targetId: next.targetId };
      if (result.targetName) onDiscoveredName?.(result.targetName);
      setSource(next.importSource ? JSON.stringify(next.importSource, null, 2) : "");
      setWarnings(result.warnings); setLocalError(""); setPreviewValues({}); setExpandedField(null);
      setSearch(""); setNodeSearch(""); setCandidateFilter("ALL"); setParameterPage(0); if (!sameTarget) setRawDrafts({});
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
  const query = search.trim().toLocaleLowerCase();
  const matchingFields = selectedNodes.flatMap((nodeId) => (fieldIndex.get(nodeId) ?? []).filter(({ item }) => {
    const identity = runningHubFieldIdentity(item);
    if (candidateFilter === "RECOMMENDED" && !recommendedIdentities.has(identity)
      || candidateFilter === "EXPOSED" && !fieldSelected(item)
      || candidateFilter === "UNCERTAIN" && insightIndex.get(identity)?.confidence !== "UNCERTAIN" && insightIndex.has(identity)) return false;
    return !query || `${item.nodeId} ${getNodeLabel(item.nodeId)} ${item.fieldName} ${item.key} ${item.label} ${item.group ?? ""}`.toLocaleLowerCase().includes(query);
  }));
  const matchingFieldIndexes = new Set(matchingFields.map(({ index }) => index));
  const matchingEntries = selectedNodes.flatMap((nodeId) => [
    ...(fieldIndex.get(nodeId) ?? []).filter(({ index }) => matchingFieldIndexes.has(index)).map(({ index }) => ({ kind: "FIELD" as const, index, nodeId })),
    ...(candidateFilter === "ALL" || candidateFilter === "EXPOSED" ? fixedIndex.get(nodeId) ?? [] : [])
      .filter(({ binding }) => !query || `${nodeId} ${getNodeLabel(nodeId)} ${binding.fieldName}`.toLocaleLowerCase().includes(query))
      .map(({ index }) => ({ kind: "FIXED" as const, index, nodeId })),
  ]);
  const pageCount = Math.max(1, Math.ceil(matchingEntries.length / PARAMETER_PAGE_SIZE));
  const currentPage = Math.min(parameterPage, pageCount - 1);
  const pageEntries = matchingEntries.slice(currentPage * PARAMETER_PAGE_SIZE, (currentPage + 1) * PARAMETER_PAGE_SIZE);
  const pageFieldIndexes = new Set(pageEntries.filter((entry) => entry.kind === "FIELD").map(({ index }) => index));
  const pageFixedIndexes = new Set(pageEntries.filter((entry) => entry.kind === "FIXED").map(({ index }) => index));
  const selectedFixed = (definition.fixedBindings ?? []).filter((binding) => selectedNodes.includes(binding.nodeId));
  useEffect(() => {
    const form = workbench.current?.closest("form");
    if (!form) return;
    const validate = (event: Event) => {
      if (imported.isPending) { event.preventDefault(); event.stopImmediatePropagation(); return; }
      const fieldIndexToReveal = invalidRunningHubEditorField(definition, selectedNodes, fieldSelection, rawDrafts);
      const fixedIndexToReveal = fieldIndexToReveal < 0 ? invalidRunningHubEditorFixed(definition, selectedNodes, rawDrafts) : -1;
      const kind = fieldIndexToReveal >= 0 ? "FIELD" : "FIXED";
      const index = fieldIndexToReveal >= 0 ? fieldIndexToReveal : fixedIndexToReveal;
      if (index < 0) return;
      event.preventDefault(); event.stopImmediatePropagation(); event.stopPropagation();
      const invalid = kind === "FIELD" ? definition.fields[index]! : definition.fixedBindings![index]!;
      const unselectedCondition = "enabledWhen" in invalid && invalid.enabledWhen && !exposedFields.some((candidate) => candidate.key === invalid.enabledWhen?.field);
      setSearch(""); setCandidateFilter("ALL"); setExpandedField(kind === "FIELD" && !unselectedCondition ? index : null);
      const ordinal = selectedNodes.flatMap((nodeId) => [
        ...(fieldIndex.get(nodeId) ?? []).map((entry) => ({ kind: "FIELD", index: entry.index })),
        ...(fixedIndex.get(nodeId) ?? []).map((entry) => ({ kind: "FIXED", index: entry.index })),
      ]).findIndex((entry) => entry.kind === kind && entry.index === index);
      setParameterPage(Math.max(0, Math.floor(ordinal / PARAMETER_PAGE_SIZE)));
      setLocalError(t(unselectedCondition ? "settings.runningHub.unselectedCondition" : "settings.runningHub.invalidSelectedField", { "0": "label" in invalid ? invalid.label || invalid.key : invalid.fieldName }));
      requestAnimationFrame(() => {
        const rows = workbench.current?.querySelectorAll<HTMLElement>(kind === "FIELD" ? "[data-parameter-index]" : "[data-fixed-index]");
        const row = [...rows ?? []].find((entry) => (kind === "FIELD" ? entry.dataset.parameterIndex : entry.dataset.fixedIndex) === String(index));
        const input = row?.querySelector<HTMLElement>("input:invalid, textarea:invalid, button[role=combobox]:invalid") ?? row?.querySelector<HTMLElement>("input, textarea, button");
        input?.focus(); row?.scrollIntoView?.({ block: "nearest" });
      });
    };
    form.addEventListener("submit", validate, true);
    return () => form.removeEventListener("submit", validate, true);
  });
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
    const current = definition.fields[index];
    if (current) setRawDrafts((before) => {
      const next = { ...before };
      if (patch.key !== undefined && patch.key !== current.key) for (const [key, value] of Object.entries(before)) {
        if (key.startsWith(`${current.key}:`)) { next[`${patch.key}:${key.slice(current.key.length + 1)}`] = value; delete next[key]; }
      }
      const key = patch.key ?? current.key;
      if ("defaultValue" in patch) delete next[`${key}:default`];
      if ("enabledWhen" in patch) delete next[`${key}:condition`];
      if ("options" in patch) delete next[`${key}:options`];
      return next;
    });
    const selection = patch.nodeId !== undefined ? revealNode(patch.nodeId) : nodeSelection;
    const previousKey = definition.fields[index]?.key;
    const keys = patch.key !== undefined ? fieldSelection.map((key) => key === previousKey ? patch.key! : key) : fieldSelection;
    update({ ...definition, fields: definition.fields.map((item, i) => i === index ? { ...item, ...patch } : item) }, undefined, selection, keys);
  }
  function addField(nodeId: string) {
    let ordinal = definition.fields.length + 1;
    while (definition.fields.some((item) => item.key === `field${ordinal}`)) ordinal += 1;
    const selection = revealNode(nodeId);
    const next: RunningHubDefinition = { ...definition, fields: [...definition.fields, { key: `field${ordinal}`, label: t("settings.runningHub.newParameter"),
      type: "STRING", nodeId, fieldName: "", required: true, advanced: false, source: "PARAMETER" }] };
    revealNewParameterPage(next, selection, nodeId, "FIELD");
    update(next, undefined, selection, [...fieldSelection, `field${ordinal}`]);
  }
  function revealNewParameterPage(next: RunningHubDefinition, selection: string[], nodeId: string, kind: "FIELD" | "FIXED") {
    const precedingNodes = inputNodeIds(next).filter((id) => selection.includes(id)).slice(0, inputNodeIds(next).filter((id) => selection.includes(id)).indexOf(nodeId));
    const preceding = next.fields.filter((item) => precedingNodes.includes(item.nodeId)).length + (next.fixedBindings ?? []).filter((item) => precedingNodes.includes(item.nodeId)).length;
    const withinNode = next.fields.filter((item) => item.nodeId === nodeId).length + (kind === "FIXED" ? (next.fixedBindings ?? []).filter((item) => item.nodeId === nodeId).length : 0) - 1;
    setSearch(""); setCandidateFilter("ALL"); setParameterPage(Math.floor((preceding + withinNode) / PARAMETER_PAGE_SIZE));
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
  function restoreScalarValidity(input: HTMLInputElement | null, raw: string | undefined, message: string, allowEmpty = true) {
    if (!input || raw === undefined) return;
    if (allowEmpty && !raw) { input.setCustomValidity(""); return; }
    try {
      const value: unknown = JSON.parse(raw);
      input.setCustomValidity(["string", "number", "boolean"].includes(typeof value) ? "" : message);
    } catch { input.setCustomValidity(message); }
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
    const insight = insightIndex.get(runningHubFieldIdentity(item));
    return <Fragment key={`field-${item.key}`}>
      <TableRow data-parameter-index={index} className="runninghub-parameter-card" data-exposed={fieldSelected(item)} data-parameter-node={item.nodeId} data-parameter-key={item.key} aria-label={item.label || t("settings.runningHub.fieldLabel", { "0": index + 1 })}>
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
                onClick={() => { setExpandedField(null); setRawDrafts((before) => Object.fromEntries(Object.entries(before).filter(([key]) => !key.startsWith(`${item.key}:`)))); update({ ...definition, fields: definition.fields.filter((_, i) => i !== index) }); }}><Trash /></Button>
            </div>
            </div>
          </div>
          <div className="runninghub-field-evidence">
            <Badge variant="outline">{t(CONFIDENCE_KEYS[insight?.confidence ?? "UNVERIFIED"])}</Badge>
            {recommendedIdentities.has(runningHubFieldIdentity(item)) ? <Badge variant="secondary">{t("settings.runningHub.recommended")}</Badge> : null}
            {insight?.reasons.map((reason) => <span key={reason}>{t(REASON_KEYS[reason])}</span>)}
          </div>
          <FieldGroup className="runninghub-parameter-grid">
            <Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.label")}</span><Input disabled={!fieldSelected(item)} required maxLength={160} value={item.label} onChange={(event) => field(index, { label: event.target.value })} /></FieldLabel></Field>
            <Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.type")}</span><Select disabled={!fieldSelected(item)} value={item.type} onChange={(event) => field(index, { type: event.target.value as RunningHubField["type"], defaultValue: null, options: [] })}>
            {(["STRING", "NUMBER", "INTEGER", "BOOLEAN", "SELECT", "IMAGE", "AUDIO", "VIDEO"] as const).map((type) => <option value={type} key={type}>{({ STRING: t("common.text"), NUMBER: t("settings.runningHub.numberInput"), INTEGER: t("settings.runningHub.integerInput"), BOOLEAN: t("settings.runningHub.booleanInput"), SELECT: t("settings.runningHub.selectInput"), IMAGE: t("settings.runningHub.imageAsset"), AUDIO: t("settings.runningHub.audioAsset"), VIDEO: t("settings.runningHub.videoAsset") })[type]}</option>)}
          </Select></FieldLabel></Field>
            <Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.inputSource")}</span><Select disabled={!fieldSelected(item)} value={item.source ?? "PARAMETER"} onChange={(event) => field(index, { source: event.target.value as RunningHubField["source"] })}>
            <option value="PARAMETER">{t("settings.runningHub.formFields")}</option>{item.type === "STRING" ? <option value="PROMPT">{t("settings.runningHub.canvasPrompt")}</option> : null}{item.type === "INTEGER" && adapterId === "RUNNINGHUB_VIDEO" ? <option value="DURATION_SECONDS">{t("settings.runningHub.videoDuration")}</option> : null}
          </Select></FieldLabel></Field>
            {["IMAGE", "AUDIO", "VIDEO"].includes(item.type) ? <Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.uploadReferenceFormat")}</span><Select disabled={!fieldSelected(item)} value={item.resourceFormat ?? ""} onChange={(event) => field(index, { resourceFormat: event.target.value ? event.target.value as RunningHubField["resourceFormat"] : null })}>
            <option value="">{t("settings.runningHub.resourceFormatUncertain")}</option><option value="FILE_NAME">{t("settings.runningHub.filenameReference")}</option><option value="URL">{t("settings.runningHub.urlReference")}</option>
          </Select></FieldLabel></Field> : <Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.defaultValue")}</span>{item.type === "BOOLEAN" ? <Select disabled={!fieldSelected(item)} value={item.defaultValue == null ? "" : String(item.defaultValue)} onChange={(event) => field(index, { defaultValue: event.target.value ? event.target.value === "true" : null })}><option value="">{t("settings.runningHub.noDefault")}</option><option value="true">{t("common.enabled")}</option><option value="false">{t("common.close")}</option></Select>
            : item.type === "STRING" ? <Input disabled={!fieldSelected(item)} value={String(item.defaultValue ?? "")} onChange={(event) => field(index, { defaultValue: event.target.value })} />
            : <Input disabled={!fieldSelected(item)} key={JSON.stringify(item.defaultValue)} ref={(input) => restoreScalarValidity(input, rawDrafts[draftKey(item, "default")], t("settings.runningHub.invalidDefault"))} value={rawDrafts[draftKey(item, "default")] ?? (item.defaultValue == null ? "" : JSON.stringify(item.defaultValue))} onChange={(event) => retainRaw(item, "default", event.target.value)} onBlur={(event) => {
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
          {definition.fields.length <= PARAMETER_PAGE_SIZE || expandedField === index ? <TableRow data-parameter-node={item.nodeId} data-parameter-key={item.key} data-parameter-index={index} aria-label={`${t("settings.runningHub.moreSettings")} · ${item.label}`} hidden={!selectedNodes.includes(item.nodeId) || expandedField !== index}>
            <TableCell className="runninghub-parameter-details">
              <fieldset className="runninghub-field-details ui-stack"><legend>{item.label || t("settings.runningHub.fieldLabel", { "0": index + 1 })}</legend>
                <FieldGroup className="ui-form-grid">
                  <Field><FieldLabel className="ui-field block">{t("settings.runningHub.description")}<Input disabled={!fieldSelected(item)} maxLength={1000} value={item.description ?? ""} onChange={(event) => field(index, { description: event.target.value })} /></FieldLabel></Field>
                  <Field><FieldLabel className="ui-field block">{t("settings.runningHub.group")}<Input disabled={!fieldSelected(item)} maxLength={80} value={item.group ?? ""} onChange={(event) => field(index, { group: event.target.value || null })} /></FieldLabel></Field>
                  <Field orientation="horizontal"><FieldLabel><Checkbox disabled={!fieldSelected(item)} checked={item.advanced ?? false} onCheckedChange={(event) => field(index, { advanced: event === true })} /> {t("settings.runningHub.advancedParameterLabel")}</FieldLabel></Field>
                  {["NUMBER", "INTEGER"].includes(item.type) ? <>{(["minimum", "maximum"] as const).map((bound) => <Field key={bound}><FieldLabel className="ui-field block">{bound === "minimum" ? t("settings.runningHub.minimum") : t("settings.runningHub.maximum")}<Input disabled={!fieldSelected(item)} type="number" value={item[bound] ?? ""} onChange={(event) => field(index, { [bound]: event.target.value ? Number(event.target.value) : null })} /></FieldLabel></Field>)}</> : null}
                  {item.type === "STRING" ? <Field><FieldLabel className="ui-field block">{t("settings.runningHub.maxLength")}<Input disabled={!fieldSelected(item)} type="number" min={1} max={20000} value={item.maxLength ?? ""} onChange={(event) => field(index, { maxLength: event.target.value ? Number(event.target.value) : null })} /></FieldLabel></Field> : null}
                  <Field><FieldLabel className="ui-field block">{t("settings.runningHub.visibilityCondition")}<Select disabled={!fieldSelected(item)} value={item.enabledWhen?.field ?? ""} onChange={(event) => field(index, { enabledWhen: event.target.value ? { field: event.target.value, value: definition.fields.find((parent) => parent.key === event.target.value)?.defaultValue ?? "" } : null })}>
                    <option value="">{t("settings.runningHub.alwaysVisible")}</option>{definition.fields.filter((parent) => parent.key !== item.key && !parent.enabledWhen && !["IMAGE", "AUDIO", "VIDEO"].includes(parent.type)).map((parent) => <option key={parent.key} value={parent.key}>{parent.label}</option>)}
                  </Select></FieldLabel></Field>
                  {item.enabledWhen ? <Field><FieldLabel className="ui-field block">{t("settings.runningHub.conditionValue")}<Input disabled={!fieldSelected(item)} key={JSON.stringify(item.enabledWhen)} ref={(input) => restoreScalarValidity(input, rawDrafts[draftKey(item, "condition")], t("settings.runningHub.invalidCondition"), false)} value={rawDrafts[draftKey(item, "condition")] ?? JSON.stringify(item.enabledWhen.value)} onChange={(event) => retainRaw(item, "condition", event.target.value)} onBlur={(event) => {
                    commitScalar(event.target, t("settings.runningHub.invalidCondition"), (value) => field(index, { enabledWhen: { field: item.enabledWhen!.field, value } }));
                  }} /></FieldLabel></Field> : null}
                  <Field><FieldLabel className="ui-field block">{t("settings.runningHub.encoding")}<Select disabled={!fieldSelected(item)} value={item.encoding ?? "NATIVE"} onChange={(event) => field(index, { encoding: event.target.value as RunningHubField["encoding"] })}><option value="NATIVE">{t("settings.runningHub.preserveType")}</option><option value="STRING">{t("settings.runningHub.stringEncoding")}</option></Select></FieldLabel></Field>
                </FieldGroup>
        {item.type === "SELECT" ? <Field><FieldLabel className="ui-field block">{t("settings.runningHub.choices")}<Textarea disabled={!fieldSelected(item)} key={JSON.stringify(item.options)} value={rawDrafts[draftKey(item, "options")] ?? (item.options ?? []).map((option) => String(option.value)).join("\n")} onChange={(event) => retainRaw(item, "options", event.target.value)} onBlur={(event) => field(index, { options: event.target.value.split("\n").filter(Boolean).map((text) => item.options?.find((option) => String(option.value) === text) ?? ({ label: text, value: text })) })} />
        </FieldLabel></Field> : null}
              </fieldset>
            </TableCell>
          </TableRow> : null}
    </Fragment>;
  }
  function editFixed(index: number, patch: Partial<NonNullable<RunningHubDefinition["fixedBindings"]>[number]>) {
    const current = definition.fixedBindings?.[index];
    if (!current) return;
    const next = { ...current, ...patch };
    const oldKey = `fixed:${index}:${current.nodeId}:${current.fieldName}`;
    const nextKey = `fixed:${index}:${next.nodeId}:${next.fieldName}`;
    if (oldKey !== nextKey) setRawDrafts((before) => {
      if (!(oldKey in before)) return before;
      const drafts = { ...before, [nextKey]: before[oldKey]! }; delete drafts[oldKey]; return drafts;
    });
    update({ ...definition, fixedBindings: definition.fixedBindings?.map((item, i) => i === index ? next : item) }, undefined,
      patch.nodeId !== undefined ? revealNode(patch.nodeId) : nodeSelection);
  }
  function removeFixed(index: number) {
    setRawDrafts((before) => Object.fromEntries(Object.entries(before).flatMap(([key, value]) => {
      const match = /^fixed:([0-9]+):(.*)$/.exec(key);
      if (!match) return [[key, value]];
      const ordinal = Number(match[1]);
      return ordinal === index ? [] : [[`fixed:${ordinal > index ? ordinal - 1 : ordinal}:${match[2]}`, value]];
    })));
    update({ ...definition, fixedBindings: definition.fixedBindings?.filter((_, i) => i !== index) });
  }
  function renderFixed(binding: NonNullable<RunningHubDefinition["fixedBindings"]>[number], index: number) {
    const fixedDraftKey = `fixed:${index}:${binding.nodeId}:${binding.fieldName}`;
    return <TableRow key={`fixed-${index}`} data-fixed-index={index} hidden={!selectedNodes.includes(binding.nodeId)} className="runninghub-fixed-card" data-parameter-node={binding.nodeId}>
      <TableCell>
        <div className="runninghub-parameter-header"><strong>{t("settings.runningHub.fixedFields")} · {binding.fieldName || "—"}</strong><Button variant="ghost" type="button" onClick={() => removeFixed(index)}>{t("settings.runningHub.removeFixedMapping")}</Button></div>
        <FieldGroup className="runninghub-technical-grid"><Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.fixedNodeId")}</span><Input disabled={!selectedNodes.includes(binding.nodeId)} required pattern="[0-9]{1,32}" value={binding.nodeId} onChange={(event) => editFixed(index, { nodeId: event.target.value })} /></FieldLabel></Field><Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.fixedFields")}</span><Input disabled={!selectedNodes.includes(binding.nodeId)} required pattern="[A-Za-z_][A-Za-z0-9_.]{0,79}" value={binding.fieldName} onChange={(event) => editFixed(index, { fieldName: event.target.value })} /></FieldLabel></Field><Field><FieldLabel className="ui-field block"><span>{t("settings.runningHub.fixedValue")}</span><Input disabled={!selectedNodes.includes(binding.nodeId)} key={JSON.stringify(binding.value)} ref={(input) => restoreScalarValidity(input, rawDrafts[fixedDraftKey], t("settings.runningHub.invalidFixedValue"), false)} value={rawDrafts[fixedDraftKey] ?? JSON.stringify(binding.value)} onChange={(event) => setRawDrafts((current) => ({ ...current, [fixedDraftKey]: event.target.value }))} onBlur={(event) => {
          commitScalar(event.target, t("settings.runningHub.invalidFixedValue"), (value) => editFixed(index, { value }));
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
    {remoteDiff ? <details className="runninghub-editor-section" open><summary>{t("settings.runningHub.remoteChanges")}</summary>
      <p className="ui-muted">{t(remoteDiff.baselineAvailable ? "settings.runningHub.diffBaselineSource" : "settings.runningHub.diffBaselineMissing")}</p>
      {!remoteDiff.fields.length && !remoteDiff.addedNodes.length && !remoteDiff.removedNodes.length ? <p>{t("settings.runningHub.noRemoteChanges")}</p> : null}
      <ul className="runninghub-diff-list">{remoteDiff.fields.slice(diffPage * PARAMETER_PAGE_SIZE, (diffPage + 1) * PARAMETER_PAGE_SIZE).map((change) => {
        const candidate = change.after ?? change.before!;
        const configuredIndex = definition.fields.findIndex((item) => runningHubFieldIdentity(item) === change.identity);
        const configured = definition.fields[configuredIndex];
        const adoptionConflict = configured && change.after ? runningHubMetadataAdoptionConflict(configured, change.after, definition.fields)
          ?? (rawDrafts[draftKey(configured, "default")] !== undefined ? "DEFAULT" : null) : null;
        return <li key={change.identity}><code>{candidate.nodeId} / {candidate.fieldName}</code><span>{change.changes.map((kind) => t(CHANGE_KEYS[kind])).join(" · ")}</span>
          {change.before && change.after && change.before.type !== change.after.type ? <span>{change.before.type} → {change.after.type}</span> : null}
          {change.before && change.after && change.changes.includes("CONSTRAINTS") ? <span>{t("settings.runningHub.constraintChange", {
            "0": [change.before.minimum ?? "—", change.before.maximum ?? "—", change.before.maxLength ?? "—", change.before.required ? t("common.enabled") : t("common.close")].join(" / "),
            "1": [change.after.minimum ?? "—", change.after.maximum ?? "—", change.after.maxLength ?? "—", change.after.required ? t("common.enabled") : t("common.close")].join(" / "),
          })}</span> : null}
          {change.before && change.after && change.changes.includes("OPTIONS") ? <span>{t("settings.runningHub.optionChange", {
            "0": (change.before.options ?? []).map((option) => `${option.label} (${JSON.stringify(option.value)})`).join(", ") || "—",
            "1": (change.after.options ?? []).map((option) => `${option.label} (${JSON.stringify(option.value)})`).join(", ") || "—",
          })}</span> : null}
          {change.after && change.before && configuredIndex >= 0 ? <Button type="button" variant="outline" size="sm" disabled={!!adoptionConflict} onClick={() => {
            const current = definition.fields[configuredIndex];
            if (current && change.after) update({ ...definition, fields: definition.fields.map((item, index) => index === configuredIndex ? adoptRunningHubMetadata(current, change.after!, definition.fields) : item) });
          }}>{t("settings.runningHub.adoptRemoteMetadata")}</Button> : null}
          {adoptionConflict ? <span>{t(adoptionConflict === "SOURCE" ? "settings.runningHub.adoptSourceConflict" : adoptionConflict === "CONDITION" ? "settings.runningHub.adoptConditionConflict" : "settings.runningHub.adoptDefaultConflict")}</span> : null}
          {change.changes.includes("REMOVED") ? <span>{t("settings.runningHub.removedBindingRetained")}</span> : null}
        </li>;
      })}</ul>
      {remoteDiff.fields.length > PARAMETER_PAGE_SIZE ? <div className="runninghub-pagination">
        <Button type="button" variant="outline" disabled={diffPage === 0} onClick={() => setDiffPage(diffPage - 1)}>{t("settings.runningHub.previousChanges")}</Button>
        <span>{t("settings.runningHub.pageSummary", { "0": diffPage + 1, "1": Math.ceil(remoteDiff.fields.length / PARAMETER_PAGE_SIZE), "2": remoteDiff.fields.length })}</span>
        <Button type="button" variant="outline" disabled={(diffPage + 1) * PARAMETER_PAGE_SIZE >= remoteDiff.fields.length} onClick={() => setDiffPage(diffPage + 1)}>{t("settings.runningHub.nextChanges")}</Button>
      </div> : null}
      {remoteDiff.addedNodes.length ? <p>{t("settings.runningHub.addedOutputNodes", { "0": remoteDiff.addedNodes.join(", ") })}</p> : null}
      {remoteDiff.removedNodes.length ? <p>{t("settings.runningHub.removedOutputNodes", { "0": remoteDiff.removedNodes.join(", ") })}</p> : null}
    </details> : null}
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
            <Field className="runninghub-node-search"><FieldLabel className="sr-only">{t("settings.runningHub.searchNodes")}</FieldLabel><Input value={nodeSearch} onChange={(event) => setNodeSearch(event.target.value)} onKeyDown={(event) => event.stopPropagation()} placeholder={t("settings.runningHub.searchNodes")} /></Field>
            <DropdownMenuGroup>{nodeIds.filter((nodeId) => !nodeSearch.trim() || getNodeLabel(nodeId).toLocaleLowerCase().includes(nodeSearch.trim().toLocaleLowerCase())).map((nodeId) => <DropdownMenuCheckboxItem key={nodeId} title={getNodeLabel(nodeId)} checked={selectedNodes.includes(nodeId)}
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
      <FieldGroup className="runninghub-candidate-tools">
        <Field><FieldLabel>{t("settings.runningHub.searchFields")}<Input value={search} onChange={(event) => { setSearch(event.target.value); setParameterPage(0); }} /></FieldLabel></Field>
        <Field><FieldLabel>{t("settings.runningHub.filterFields")}<Select value={candidateFilter} onChange={(event) => { setCandidateFilter(event.target.value as CandidateFilter); setParameterPage(0); }}>
          {(["ALL", "RECOMMENDED", "EXPOSED", "UNCERTAIN"] as const).map((filter) => <option key={filter} value={filter}>{t(FILTER_KEYS[filter])}</option>)}
        </Select></FieldLabel></Field>
      </FieldGroup>
      {selectedNodes.length && !matchingEntries.length ? <p className="ui-muted">{t("settings.runningHub.noMatchingFields")}</p> : null}
      <div hidden={!selectedNodes.length} className="runninghub-parameters" role="region" aria-label={t("settings.runningHub.mappingTable")}>
        <Table className="runninghub-parameter-table" aria-label={t("settings.runningHub.mappingTable")}>
          <TableBody>{selectedNodes.filter((nodeId) => pageEntries.some((entry) => entry.nodeId === nodeId)).flatMap((nodeId) => [
            <TableRow key={`node-${nodeId}`} className="runninghub-node-heading">
              <TableCell><div><h4>{getNodeLabel(nodeId)}</h4><Badge variant="secondary">{t("settings.runningHub.exposedCount", { "0": (fieldIndex.get(nodeId) ?? []).filter(({ item }) => fieldSelected(item)).length })}</Badge></div></TableCell>
            </TableRow>,
            ...(fieldIndex.get(nodeId) ?? []).flatMap(({ item, index }) => pageFieldIndexes.has(index) ? [renderParameter(item, index)] : []),
            ...(fixedIndex.get(nodeId) ?? []).flatMap(({ binding, index }) => pageFixedIndexes.has(index) ? [renderFixed(binding, index)] : []),
          ])}</TableBody>
        </Table>
      </div>
      {pageCount > 1 ? <div className="runninghub-pagination" aria-label={t("settings.runningHub.parameterPages")}>
        <Button type="button" variant="outline" disabled={currentPage === 0} onClick={() => setParameterPage(currentPage - 1)}>{t("settings.runningHub.previousPage")}</Button>
        <span role="status">{t("settings.runningHub.pageSummary", { "0": currentPage + 1, "1": pageCount, "2": matchingEntries.length })}</span>
        <Button type="button" variant="outline" disabled={currentPage + 1 >= pageCount} onClick={() => setParameterPage(currentPage + 1)}>{t("settings.runningHub.nextPage")}</Button>
      </div> : null}
      <NodeParameterAction nodeIds={selectedNodes} getNodeLabel={getNodeLabel} label={t("settings.runningHub.addField")} disabled={definition.fields.length >= MAX_FIELDS} onAdd={addField} />
      <p hidden={!selectedNodes.length}>{t("settings.runningHub.fixedParametersHint")}</p>
      <NodeParameterAction nodeIds={selectedNodes} getNodeLabel={getNodeLabel} label={t("settings.runningHub.addFixedMapping")} variant="ghost" onAdd={(nodeId) => {
        const selection = revealNode(nodeId);
        const next: RunningHubDefinition = { ...definition, fixedBindings: [...(definition.fixedBindings ?? []), { nodeId, fieldName: "", value: "", encoding: "NATIVE" }] };
        revealNewParameterPage(next, selection, nodeId, "FIXED");
        update(next, undefined, selection);
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
