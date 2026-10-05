import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import { Navigate } from "react-router";
import { ApiError, HTTP_STATUS, createPrompt, deletePrompt, listPrompts, updatePrompt, type PromptDefinition } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";

const SETTINGS_KEY = ["settings", "prompts"] as const;
const PRESETS_KEY = ["agent-presets"] as const;
const NEW_PROMPT = "new";
const MAX_NAME_LENGTH = 120;
const MAX_DESCRIPTION_LENGTH = 1000;
const MAX_CONTENT_LENGTH = 8000;
const KEY_PATTERN = "[a-z][a-z0-9._-]{0,119}";
type Draft = Pick<PromptDefinition, "key" | "kind" | "name" | "description" | "content" | "version">;
const blankDraft: Draft = { key: "", kind: "AGENT", name: "", description: "", content: "", version: 1 };

export function PromptManagementSection({ enabled }: { enabled: boolean }) {
  useLocale(); const id = useId(); const client = useQueryClient();
  const settings = useQuery({ queryKey: SETTINGS_KEY, queryFn: listPrompts, enabled, retry: false });
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [filter, setFilter] = useState("");
  const [kind, setKind] = useState("");
  const [confirmDelete, setConfirmDelete] = useState(false);
  const selection = selectedId ?? settings.data?.items[0]?.id;
  const record = settings.data?.items.find((value) => value.id === selection);
  const draft = selection ? drafts[selection] : undefined;
  const value = draft ?? record;
  const isNew = selection === NEW_PROMPT;
  const save = useMutation({ mutationFn: ({ selection, draft }: { selection: string; draft: Draft }) => {
    const { key, kind, name, description, content, version } = draft;
    return selection === NEW_PROMPT ? createPrompt({ key, kind, name, description, content })
      : updatePrompt(selection, { name, description, content, expectedVersion: version });
  }, onSuccess: (saved, input) => {
    client.setQueryData(SETTINGS_KEY, { items: input.selection === NEW_PROMPT
      ? [...(settings.data?.items ?? []), saved] : (settings.data?.items ?? []).map((row) => row.id === saved.id ? saved : row) });
    setDrafts((old) => { const next = { ...old }; delete next[input.selection]; return next; });
    setSelectedId(saved.id); void client.invalidateQueries({ queryKey: PRESETS_KEY });
  } });
  const remove = useMutation({ mutationFn: ({ id, version }: { id: string; version: number }) => deletePrompt(id, version),
    onSuccess: (_, input) => {
      client.setQueryData(SETTINGS_KEY, { items: (settings.data?.items ?? []).filter((row) => row.id !== input.id) });
      setDrafts((old) => { const next = { ...old }; delete next[input.id]; return next; });
      setSelectedId(null); setConfirmDelete(false); void client.invalidateQueries({ queryKey: PRESETS_KEY });
    } });
  const error = save.error ?? remove.error ?? settings.error;
  if (error instanceof ApiError && error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  const forbidden = error instanceof ApiError && error.status === HTTP_STATUS.FORBIDDEN;
  const conflict = error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT;
  const pending = save.isPending || remove.isPending || settings.isFetching;
  const disabled = pending || !settings.data || settings.isError || forbidden;
  function select(selection: string) { setSelectedId(selection); setConfirmDelete(false); save.reset(); remove.reset(); }
  function change(fields: Partial<Draft>) {
    if (save.error instanceof ApiError && save.error.code === "PROMPT_KEY_CONFLICT") save.reset();
    if (remove.isError) remove.reset();
    if (value && selection) setDrafts((old) => ({ ...old, [selection]: { ...value, ...fields } }));
  }
  const rows = (settings.data?.items ?? []).filter((row) => (!kind || row.kind === kind)
    && `${row.name} ${row.key} ${row.description}`.toLowerCase().includes(filter.toLowerCase()));
  return <div className="grid items-start gap-6 xl:grid-cols-[minmax(16rem,1fr)_minmax(0,2fr)]">
    <Panel title={t("prompts.title")} description={t("prompts.hint")}>
      <div className="ui-stack">
        <Input aria-label={t("prompts.search")} placeholder={t("prompts.search")} value={filter} onChange={(event) => setFilter(event.target.value)} />
        <Select aria-label={t("prompts.filter")} value={kind} onChange={(event) => setKind(event.target.value)}>
          <option value="">{t("prompts.all")}</option><option value="AGENT">{t("prompts.agent")}</option><option value="FUNCTION">{t("prompts.function")}</option>
        </Select>
        <Button type="button" disabled={disabled} onClick={() => { select(NEW_PROMPT); setDrafts((old) => ({ ...old, [NEW_PROMPT]: old[NEW_PROMPT] ?? { ...blankDraft } })); }}>{t("prompts.create")}</Button>
        {settings.isPending && enabled ? <LoadingState compact label={t("prompts.loading")} /> : null}
        {settings.isSuccess && !rows.length ? <EmptyState title={t("prompts.empty")} /> : null}
        {rows.map((row) => <Button type="button" variant={selection === row.id ? "secondary" : "outline"} className="h-auto justify-start whitespace-normal py-3 text-left"
          disabled={pending} key={row.id} onClick={() => select(row.id)} aria-pressed={selection === row.id}>
          <span className="min-w-0"><span className="block">{row.name}{drafts[row.id] ? ` · ${t("prompts.unsaved")}` : ""}</span><span className="block break-all text-xs text-muted-foreground">{row.key}</span></span>
          <StatusBadge>{row.kind === "AGENT" ? t("prompts.agent") : t("prompts.function")}</StatusBadge>
        </Button>)}
      </div>
    </Panel>
    <Panel title={isNew ? t("prompts.create") : record?.name ?? t("prompts.editor")} description={t("prompts.copyHint")}>
      <form className="ui-stack" onSubmit={(event) => { event.preventDefault(); if (selection && draft && !disabled && !conflict) save.mutate({ selection, draft }); }}>
        {error ? <Notice tone="danger" title={forbidden ? t("prompts.forbidden") : conflict ? t("prompts.conflict") : t("prompts.operationFailed")}>
          {!forbidden ? <Button type="button" variant="outline" disabled={pending} onClick={() => void settings.refetch()}>{t("settings.shared.refresh")}</Button> : null}
        </Notice> : null}
        {value ? <FieldGroup>
          <Field><FieldLabel htmlFor={`${id}-key`}>{t("prompts.key")}</FieldLabel>
            <Input id={`${id}-key`} required maxLength={MAX_NAME_LENGTH} pattern={KEY_PATTERN} disabled={disabled || !isNew} value={value.key} onChange={(event) => change({ key: event.target.value })} />
            <FieldDescription>{t("prompts.keyHint")}</FieldDescription></Field>
          <Field><FieldLabel htmlFor={`${id}-kind`}>{t("prompts.kind")}</FieldLabel>
            <Select id={`${id}-kind`} disabled={disabled || !isNew} value={value.kind} onChange={(event) => { const kind = event.target.value; if (kind === "AGENT" || kind === "FUNCTION") change({ kind }); }}>
              <option value="AGENT">{t("prompts.agent")}</option><option value="FUNCTION">{t("prompts.function")}</option>
            </Select></Field>
          <Field><FieldLabel htmlFor={`${id}-name`}>{t("prompts.name")}</FieldLabel>
            <Input id={`${id}-name`} required maxLength={MAX_NAME_LENGTH} disabled={disabled} value={value.name} onChange={(event) => change({ name: event.target.value })} /></Field>
          <Field><FieldLabel htmlFor={`${id}-description`}>{t("prompts.description")}</FieldLabel>
            <Input id={`${id}-description`} maxLength={MAX_DESCRIPTION_LENGTH} disabled={disabled} value={value.description} onChange={(event) => change({ description: event.target.value })} /></Field>
          <Field><FieldLabel htmlFor={`${id}-content`}>{t("prompts.content")}</FieldLabel>
            <Textarea id={`${id}-content`} required rows={18} maxLength={MAX_CONTENT_LENGTH} disabled={disabled} value={value.content} onChange={(event) => change({ content: event.target.value })} />
            {value.kind === "FUNCTION" ? <FieldDescription>{t("prompts.functionHint")}</FieldDescription> : null}</Field>
        </FieldGroup> : null}
        {save.isSuccess && !draft ? <p role="status">{t("prompts.saved")}</p> : null}
        {record?.builtIn ? <p className="text-sm text-muted-foreground">{t("prompts.builtInHint")}</p> : null}
        <div className="ui-form-actions">
          {draft && selection ? <Button type="button" variant="outline" disabled={pending} onClick={() => {
            setDrafts((old) => { const next = { ...old }; delete next[selection]; return next; }); save.reset(); remove.reset(); if (isNew) setSelectedId(null);
          }}>{t("agent.defaults.discard")}</Button> : null}
          {conflict && draft && record && draft.version !== record.version ? <Button type="button" variant="outline" disabled={pending}
            onClick={() => { change({ version: record.version }); save.reset(); remove.reset(); }}>{t("agent.defaults.keepDraft")}</Button> : null}
          <Button type="submit" disabled={disabled || conflict || !draft || !value?.name.trim() || !value.content.trim() || !value.key.trim()}>{save.isPending ? t("common.savingProgress") : t("common.saveConfig")}</Button>
          {record && !record.builtIn ? <Button type="button" variant="outline" disabled={disabled} onClick={() => setConfirmDelete(true)}>{t("prompts.delete")}</Button> : null}
        </div>
        {confirmDelete && record ? <Notice title={t("prompts.deleteConfirm", { "0": record.name })}>
          <Button type="button" variant="destructive" disabled={pending} onClick={() => remove.mutate({ id: record.id, version: draft?.version ?? record.version })}>{t("prompts.confirmDelete")}</Button>
          <Button type="button" variant="outline" disabled={pending} onClick={() => setConfirmDelete(false)}>{t("common.cancel")}</Button>
        </Notice> : null}
      </form>
    </Panel>
  </div>;
}
