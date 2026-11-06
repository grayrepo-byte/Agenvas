import { useMutation } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState } from "react";
import { previewLlmModels, type LlmModelPreview, type LlmModelPreviewRequest, type LlmModelProvider, type LlmSettings } from "../../shared/api/client";
import { formatDate, t, useLocale, type MessageKey } from "../../shared/i18n";
import { Select } from "../../shared/ui/Select";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldDescription, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { automaticModelOptions, DEFAULT_CONTEXT_WINDOW_TOKENS, fallbackModelOptions, modelOptionsFromPolicy, type ModelOptionsDraft } from "./llmModelConfiguration";
import { validLlmApiKey } from "./llmApiKey";

const PROVIDERS: Record<LlmModelProvider, MessageKey> = {
  COMPATIBLE: "modelConnection.compatible", OPENAI: "modelConnection.openai", OPENROUTER: "modelConnection.openrouter",
  GEMINI: "modelConnection.gemini", ANTHROPIC: "modelConnection.anthropic", OLLAMA: "modelConnection.ollama",
};
const PARAMETERS = ["MAX_TOKENS", "MAX_COMPLETION_TOKENS"] as const;
type Props = { value: ModelOptionsDraft; onChange: (value: ModelOptionsDraft) => void;
  endpoint: string; apiKey: string; modelId: string; onSelectModel: (id: string) => void; disabled: boolean; base?: LlmSettings };

/** Metadata only fills the existing connection draft; user overrides and CAS remain authoritative. */
export function LlmModelOptions(props: Props) {
  useLocale();
  const { value, endpoint, apiKey, modelId, disabled, base, onChange, onSelectModel } = props;
  const latest = useRef(props); latest.current = props;
  const sequence = useRef(0);
  const active = useRef<{ sequence: number; controller: AbortController; request: LlmModelPreviewRequest;
    endpoint: string; key: string; modelId: string; provider: LlmModelProvider; version?: number } | null>(null);
  const [search, setSearch] = useState("");
  const [error, setError] = useState("");
  const { mutate, reset, data, isPending, error: failed } = useMutation({
    mutationFn: async (id: number) => {
      const pending = active.current;
      if (!pending || pending.sequence !== id) throw new DOMException("Canceled", "AbortError");
      try {
        const result = await previewLlmModels(pending.request, pending.controller.signal);
        const current = latest.current;
        if (pending.controller.signal.aborted || id !== sequence.current || current.endpoint !== pending.endpoint
          || current.apiKey !== pending.key || current.modelId !== pending.modelId || current.value.provider !== pending.provider
          || current.base?.version !== pending.version) throw new DOMException("Canceled", "AbortError");
        return result;
      } finally { if (active.current?.sequence === id) active.current = null; }
    },
    onSuccess: (preview: LlmModelPreview) => {
      const current = latest.current;
      const model = preview.models.find(item => item.id === current.modelId);
      if (!current.value.manualOverride) {
        const next = model ? automaticModelOptions(current.value, model, preview.id) : null;
        current.onChange(next ?? fallbackModelOptions(current.value));
      }
    },
    onError: (cause, id) => {
      if (id !== sequence.current || cause instanceof DOMException && cause.name === "AbortError") return;
      const current = latest.current;
      if (!current.value.manualOverride) current.onChange(fallbackModelOptions(current.value));
    },
    retry: false,
  });
  const cancel = useCallback(() => {
    sequence.current++; active.current?.controller.abort(); active.current = null; reset();
  }, [reset]);
  useEffect(() => {
    cancel(); setError("");
    const current = latest.current;
    if (current.value.previewId && !current.value.manualOverride)
      current.onChange(fallbackModelOptions(current.value));
    return () => { sequence.current++; active.current?.controller.abort(); active.current = null; };
  }, [endpoint, apiKey, value.provider, base?.version, cancel]);
  useEffect(() => { if (disabled) cancel(); }, [disabled, cancel]);

  function load() {
    if (disabled || isPending) return;
    let request: LlmModelPreviewRequest;
    if (validLlmApiKey(apiKey)) {
      request = { provider: value.provider, credentialMode: "DRAFT", expectedVersion: base?.version ?? 0, endpoint: endpoint.trim(), apiKey, modelId: modelId.trim() || null };
    } else if (apiKey === "" && base?.configured && endpoint === base.endpoint
      && value.provider === (base.modelPolicy?.provider ?? "COMPATIBLE")) {
      request = { provider: value.provider, credentialMode: "SAVED", expectedVersion: base.version, modelId: modelId.trim() || null };
    } else { setError(t("modelConnection.needsKey")); return; }
    setError("");
    const id = ++sequence.current;
    active.current = { sequence: id, controller: new AbortController(), request, endpoint, key: apiKey,
      modelId, provider: value.provider, version: base?.version };
    mutate(id);
  }
  const selected = data?.models.find(item => item.id === modelId);
  const automatic = selected && data ? automaticModelOptions(value, selected, data.id) : null;
  const visible = (data?.models ?? []).filter(model => model.id.toLowerCase().includes(search.toLowerCase())).slice(0, 100);
  function edit(field: "context" | "input" | "output", content: string) {
    onChange({ ...value, [field]: content, ...(field === "output" ? {} : { mode: "MANUAL" as const, manualOverride: true }) });
  }
  return <div className="ui-stack" aria-busy={isPending}>
    <Field><FieldLabel htmlFor="llm-provider">{t("modelConnection.provider")}</FieldLabel>
      <Select id="llm-provider" value={value.provider} disabled={disabled} onChange={event => {
        const provider = event.target.value as LlmModelProvider;
        onChange({ ...modelOptionsFromPolicy(null), provider, outputParameter: provider === "OPENAI" ? "MAX_COMPLETION_TOKENS" : "MAX_TOKENS" });
      }}>{Object.entries(PROVIDERS).map(([provider, label]) => <option key={provider} value={provider}>{t(label)}</option>)}</Select>
      <FieldDescription>{t("modelConnection.scope")}</FieldDescription>
    </Field>
    <div className="ui-form-actions"><Button type="button" variant="outline" disabled={disabled || isPending} onClick={load}>{t("modelConnection.read")}</Button>
      {isPending ? <Button type="button" variant="ghost" onClick={cancel}>{t("common.cancel")}</Button> : null}</div>
    {isPending ? <LoadingState compact label={t("modelConnection.reading")} /> : null}
    {error || failed ? <Notice tone="warning">{error || t("modelConnection.failed")}</Notice> : null}
    {data ? <>
      {data.partial ? <Notice tone="info">{t("modelConnection.partial")}</Notice> : null}
      <Field><FieldLabel htmlFor="llm-model-search">{t("modelConnection.filter")}</FieldLabel><Input id="llm-model-search" value={search} onChange={event => setSearch(event.target.value)} disabled={disabled} /></Field>
      <Field><FieldLabel htmlFor="llm-discovered-model">{t("modelConnection.select")}</FieldLabel><Select id="llm-discovered-model" value={modelId} disabled={disabled} onChange={event => {
        const id = event.target.value; onSelectModel(id);
        const model = data.models.find(item => item.id === id);
        if (model && !value.manualOverride) {
          const next = automaticModelOptions(value, model, data.id);
          onChange(next ?? fallbackModelOptions(value));
        }
      }}>
        {!visible.some(model => model.id === modelId) ? <option value={modelId}>{modelId || t("modelConnection.select")}</option> : null}
        {visible.map(model => <option key={model.id} value={model.id}>{model.id}</option>)}
      </Select><FieldDescription>{t("modelConnection.listHint", { "0": data.models.length, "1": visible.length })}</FieldDescription></Field>
      {selected ? <div className="ui-stack ui-muted">
        <p>{t("modelConnection.theory")}: {selected.contextWindowTokens ?? t("common.unknown")} · {t("modelConnection.runtime")}: {selected.runtimeContextTokens ?? t("common.unknown")}</p>
        <p>{t("modelConnection.input")}: {selected.inputLimitTokens ?? t("common.unknown")} · {t("modelConnection.reportedOutput")}: {selected.outputLimitTokens ?? t("common.unknown")}</p>
        {selected.deploymentContextTokens ? <p>{t("modelConnection.deployment")}: {selected.deploymentContextTokens}</p> : null}
        <p>{t("modelConnection.readAt")}: {formatDate(data.checkedAt)}</p>
      </div> : null}
    </> : null}
    {value.provider === "OLLAMA" ? <Notice tone="info">{t("modelConnection.ollamaHint")}</Notice> : null}
    {value.provider === "GEMINI" || value.provider === "ANTHROPIC" ? <FieldDescription>{t("modelConnection.compatibilityHint")}</FieldDescription> : null}
    {!automatic && !value.context && !value.input ? <Notice tone="warning">{t("modelConnection.unknown")}</Notice> : null}
    {value.mode === "MANUAL" && !value.manualOverride && value.context === String(DEFAULT_CONTEXT_WINDOW_TOKENS)
      ? <Notice tone="info">{t("modelConnection.defaultContext")}</Notice> : null}
    <div className="ui-form-actions">
      <Button type="button" variant={value.mode === "AUTO" ? "secondary" : "outline"} disabled={disabled || !automatic} onClick={() => { if (automatic) onChange({ ...automatic, manualOverride: false }); }}>{t("modelConnection.auto")}</Button>
      <Button type="button" variant={value.mode === "MANUAL" ? "secondary" : "outline"} disabled={disabled} onClick={() => onChange({ ...value, mode: "MANUAL", manualOverride: true })}>{t("modelConnection.manual")}</Button>
    </div>
    <div className="llm-connection-fields">
      <Field><FieldLabel htmlFor="llm-context">{t("modelConnection.context")}</FieldLabel><Input id="llm-context" type="number" min={1} max={16000000} value={value.context} readOnly={value.mode === "AUTO"} disabled={disabled} onChange={event => edit("context", event.target.value)} /></Field>
      <Field><FieldLabel htmlFor="llm-input-limit">{t("modelConnection.input")}</FieldLabel><Input id="llm-input-limit" type="number" min={1} max={16000000} value={value.input} readOnly={value.mode === "AUTO"} disabled={disabled} onChange={event => edit("input", event.target.value)} /></Field>
      <Field><FieldLabel htmlFor="llm-output-limit">{t("modelConnection.output")}</FieldLabel><Input id="llm-output-limit" type="number" min={1} max={2000000} value={value.output} disabled={disabled} onChange={event => edit("output", event.target.value)} /></Field>
      <Field><FieldLabel htmlFor="llm-output-parameter">{t("modelConnection.parameter")}</FieldLabel><Select id="llm-output-parameter" value={value.outputParameter} disabled={disabled} onChange={event => onChange({ ...value, outputParameter: event.target.value as ModelOptionsDraft["outputParameter"] })}>
        {PARAMETERS.map(parameter => <option key={parameter} value={parameter}>{parameter.toLowerCase()}</option>)}
      </Select></Field>
    </div>
    <FieldDescription>{t("modelConnection.limitHint")}</FieldDescription>
  </div>;
}
