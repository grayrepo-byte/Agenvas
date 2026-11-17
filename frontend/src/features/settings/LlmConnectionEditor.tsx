import { CaretDown, Question, SlidersHorizontal } from "@/shared/ui/icons";
import { useState, type FormEvent } from "react";
import {
  ApiError,
  HTTP_STATUS,
  listLlmConnections,
  saveLlmConnection,
  type LlmConnection,
  type LlmConnections,
  type LlmSettings,
} from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "../../shared/ui/primitives/popover";
import { LocalServiceDiscovery } from "./LocalServiceDiscovery";
import { LlmModelOptions } from "./LlmModelOptions";
import { LLM_API_KEY_MAX_LENGTH, LLM_API_KEY_MIN_LENGTH, LLM_API_KEY_PATTERN, validLlmApiKey } from "./llmApiKey";
import {
  fallbackModelOptions,
  modelOptionsFromPolicy,
  modelSelection,
  type ModelOptionsDraft,
} from "./llmModelConfiguration";

type Props = {
  id: string;
  connection: LlmConnection | null;
  onClose: () => void;
  onSaved: (connection: LlmConnection) => void | Promise<void>;
  onCatalog: (catalog: LlmConnections) => void | Promise<void>;
};

/** Collapse optional settings without unmounting metadata state or replacing a pinned edit base. */
export function LlmConnectionEditor({ id, connection, onClose, onSaved, onCatalog }: Props) {
  useLocale();
  const [base, setBase] = useState(connection);
  const [name, setName] = useState(connection?.name ?? "");
  const [endpoint, setEndpoint] = useState(connection?.endpoint ?? "");
  const [modelId, setModelId] = useState(connection?.modelId ?? "");
  const [apiKey, setApiKey] = useState("");
  const [keyInvalid, setKeyInvalid] = useState(false);
  const [modelOptions, setModelOptions] = useState<ModelOptionsDraft | null>(null);
  const [validationAttempt, setValidationAttempt] = useState(0);
  const [advanced, setAdvanced] = useState(false);
  const [connectionHelp, setConnectionHelp] = useState(false);
  const [busy, setBusy] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [discard, setDiscard] = useState(false);
  const [error, setError] = useState("");
  const [submittedKeyCleared, setSubmittedKeyCleared] = useState(false);
  const options = modelOptions ?? modelOptionsFromPolicy(base?.modelPolicy);
  const changed =
    name !== (base?.name ?? "") ||
    endpoint !== (base?.endpoint ?? "") ||
    modelId !== (base?.modelId ?? "") ||
    apiKey.trim() !== "" ||
    modelOptions !== null;
  const metadataBase: LlmSettings = {
    configured: !!base,
    version: base?.version ?? 0,
    endpoint: base?.endpoint ?? null,
    modelId: base?.modelId ?? null,
    keyMask: base?.keyMask ?? null,
    toolCallingVerified: base?.toolCallingVerified ?? false,
    updatedAt: base?.updatedAt ?? null,
    modelPolicy: base?.modelPolicy ?? null,
  };
  function invalidateAutomatic() {
    setModelOptions(options.mode === "AUTO" && !options.manualOverride ? fallbackModelOptions(options) : options);
  }
  function editEndpoint(value: string) {
    if (value !== endpoint) invalidateAutomatic();
    setEndpoint(value);
  }
  function editModel(value: string) {
    if (value !== modelId) invalidateAutomatic();
    setModelId(value);
  }
  function close() {
    if (changed) setDiscard(true);
    else onClose();
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    const suppliedKey = apiKey.trim() !== "";
    if ((!base || suppliedKey) && !validLlmApiKey(apiKey)) {
      setKeyInvalid(true);
      return;
    }
    // A name-only edit can retain the exact server policy without a fresh AUTO preview token.
    const selection = base && modelOptions === null ? undefined : modelSelection(options, modelId.trim());
    if (selection === null) {
      setValidationAttempt(value => value + 1);
      setAdvanced(true);
      setError(t("modelConnection.capacityInvalid"));
      return;
    }
    setBusy(true);
    setError("");
    setKeyInvalid(false);
    setSubmittedKeyCleared(false);
    try {
      const result = await saveLlmConnection(id, {
        name: name.trim(),
        endpoint: endpoint.trim(),
        modelId: modelId.trim(),
        expectedVersion: base?.version ?? 0,
        ...(suppliedKey ? { apiKey } : {}),
        ...(selection ? { modelSelection: selection } : {}),
      });
      await onSaved(result);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("settings.shared.saveFailed"));
      if (cause instanceof ApiError && cause.status === HTTP_STATUS.CONFLICT) setConflict(true);
    } finally {
      setApiKey("");
      setSubmittedKeyCleared(suppliedKey);
      setBusy(false);
    }
  }
  async function refresh() {
    if (busy) return;
    setBusy(true);
    try {
      const catalog = await listLlmConnections();
      const latest = catalog.items.find(item => item.id === id);
      if (!latest) {
        setError(t("settings.llm.connections.missing"));
        return;
      }
      setBase(latest);
      setName(latest.name);
      setEndpoint(latest.endpoint);
      setModelId(latest.modelId);
      setModelOptions(null);
      setApiKey("");
      setKeyInvalid(false);
      setError("");
      setConflict(false);
      setSubmittedKeyCleared(false);
      await onCatalog(catalog);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("settings.llm.loadFailed"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Dialog
        className="llm-connection-dialog"
        title={t(base ? "settings.llm.connections.edit" : "settings.llm.connections.add")}
        busy={busy}
        onClose={close}
        onSubmit={submit}
        footer={
          <>
            <Button variant="outline" type="button" disabled={busy} onClick={close}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={busy || !changed}>
              {busy ? t("common.savingProgress") : t("common.saveConfig")}
            </Button>
          </>
        }
      >
        <div className="llm-connection-content">
          <FieldGroup className="llm-essential-fields">
            <Field>
              <FieldLabel htmlFor="llm-name">{t("settings.llm.connections.name")}</FieldLabel>
              <Input
                id="llm-name"
                value={name}
                maxLength={120}
                required
                disabled={busy}
                placeholder={t("settings.llm.connections.namePlaceholder")}
                onChange={event => setName(event.target.value)}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="llm-model">{t("settings.llm.modelId")}</FieldLabel>
              <Input
                id="llm-model"
                autoComplete="off"
                value={modelId}
                maxLength={160}
                required
                disabled={busy}
                placeholder="model-name"
                onChange={event => editModel(event.target.value)}
              />
            </Field>
            <Field>
              <div className="llm-field-heading">
                <FieldLabel htmlFor="llm-endpoint">{t("settings.llm.endpoint")}</FieldLabel>
                <Popover open={connectionHelp} onOpenChange={setConnectionHelp}>
                  <PopoverTrigger asChild>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon-sm"
                      disabled={busy}
                      aria-label={t("settings.llm.connections.connectionHelp")}
                    >
                      <Question />
                    </Button>
                  </PopoverTrigger>
                  <PopoverContent
                    className="llm-connection-help"
                    align="start"
                    aria-label={t("settings.llm.connections.connectionHelp")}
                  >
                    <PopoverHeader>
                      <PopoverTitle>{t("settings.llm.openAiEndpoint")}</PopoverTitle>
                      <PopoverDescription>{t("settings.llm.endpointHint")}</PopoverDescription>
                    </PopoverHeader>
                    <LocalServiceDiscovery
                      service="OLLAMA"
                      disabled={busy}
                      onSelect={value => {
                        editEndpoint(value);
                        setConnectionHelp(false);
                      }}
                    />
                  </PopoverContent>
                </Popover>
              </div>
              <Input
                id="llm-endpoint"
                autoComplete="off"
                type="url"
                value={endpoint}
                maxLength={500}
                required
                disabled={busy}
                placeholder="https://api.example.com/v1"
                onChange={event => editEndpoint(event.target.value)}
              />
              {endpoint.trim().toLowerCase().startsWith("http:") ? (
                <FieldDescription>{t("settings.llm.connections.httpHint")}</FieldDescription>
              ) : null}
            </Field>
            <Field data-invalid={keyInvalid || undefined}>
              <div className="llm-field-heading">
                <FieldLabel htmlFor="llm-api-key">{t("settings.llm.connections.key")}</FieldLabel>
                <Popover>
                  <PopoverTrigger asChild>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon-sm"
                      disabled={busy}
                      aria-label={t("settings.llm.connections.keyHelp")}
                    >
                      <Question />
                    </Button>
                  </PopoverTrigger>
                  <PopoverContent className="llm-connection-help" align="end">
                    <PopoverHeader>
                      <PopoverTitle>{t("settings.llm.connections.keyHelp")}</PopoverTitle>
                      <PopoverDescription>{t("settings.llm.keyEntryHint")}</PopoverDescription>
                    </PopoverHeader>
                  </PopoverContent>
                </Popover>
              </div>
              <Input
                id="llm-api-key"
                autoComplete="new-password"
                type="password"
                value={apiKey}
                required={!base}
                disabled={busy}
                placeholder={base ? t("settings.llm.connections.keepKey", { "0": base.keyMask }) : undefined}
                minLength={LLM_API_KEY_MIN_LENGTH}
                maxLength={LLM_API_KEY_MAX_LENGTH}
                pattern={LLM_API_KEY_PATTERN}
                aria-describedby={`llm-key-hint${keyInvalid ? " llm-key-error" : ""}`}
                aria-invalid={keyInvalid || undefined}
                onInvalid={() => setKeyInvalid(true)}
                onChange={event => {
                  if (event.target.value !== apiKey) invalidateAutomatic();
                  setApiKey(event.target.value);
                  setKeyInvalid(false);
                }}
              />
              <FieldDescription id="llm-key-hint">
                {t(base ? "settings.llm.connections.keepKeyHint" : "settings.llm.connections.newKeyHint")}
              </FieldDescription>
              {keyInvalid ? <FieldError id="llm-key-error">{t("settings.llm.keyValidationError")}</FieldError> : null}
            </Field>
          </FieldGroup>
          <Button
            className="llm-advanced-toggle"
            type="button"
            variant="ghost"
            disabled={busy}
            aria-expanded={advanced}
            aria-controls="llm-advanced-settings"
            onClick={() => setAdvanced(value => !value)}
          >
            <SlidersHorizontal data-icon="inline-start" />
            {t("settings.llm.connections.advanced")}
            <CaretDown data-icon="inline-end" />
          </Button>
          <div
            id="llm-advanced-settings"
            className="llm-advanced-settings"
            hidden={!advanced}
            onInvalidCapture={() => setAdvanced(true)}
          >
            <LlmModelOptions
              validationAttempt={validationAttempt}
              connectionId={base?.id}
              value={options}
              endpoint={endpoint}
              modelId={modelId}
              apiKey={apiKey}
              base={metadataBase}
              disabled={busy}
              onSelectModel={editModel}
              onChange={value => setModelOptions(value)}
            />
          </div>
          {conflict ? (
            <Notice tone="warning">
              <p>{t("settings.llm.conflictHint")}</p>
              <Button
                variant="outline"
                type="button"
                disabled={busy}
                onClick={() => {
                  void refresh();
                }}
              >
                {t("settings.shared.refreshConfig")}
              </Button>
            </Notice>
          ) : null}
          {error ? (
            <Notice tone="danger">
              <p>{error}</p>
              {submittedKeyCleared ? <p>{t("settings.llm.connections.keyCleared")}</p> : null}
            </Notice>
          ) : null}
        </div>
      </Dialog>
      {discard ? (
        <Dialog
          compact
          title={t("settings.llm.connections.discardTitle")}
          busy={busy}
          onClose={() => setDiscard(false)}
          onSubmit={event => {
            event.preventDefault();
            setApiKey("");
            onClose();
          }}
          footer={
            <>
              <Button type="button" variant="outline" onClick={() => setDiscard(false)}>
                {t("settings.llm.connections.keepEditing")}
              </Button>
              <Button type="submit" variant="destructive">
                {t("settings.llm.connections.discard")}
              </Button>
            </>
          }
        >
          <p>{t("settings.llm.connections.discardHint")}</p>
        </Dialog>
      ) : null}
    </>
  );
}
