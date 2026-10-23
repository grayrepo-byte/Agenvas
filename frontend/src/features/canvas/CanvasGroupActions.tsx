import { useId, useRef, useState } from "react";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";

export const CANVAS_GROUP_NAME_MAX_LENGTH = 80;

/** Parent mounts with a group-specific key. A failed save retains the exact draft name. */
export function CanvasGroupRenameDialog({ initialName = "", mode, busy = false, retrying = false, error, onSave, onClose }: {
  initialName?: string;
  mode: "create" | "rename";
  busy?: boolean;
  /** A failed request retains its exact idempotent intent until retry or explicit discard. */
  retrying?: boolean;
  error?: string | null;
  onSave: (name: string) => Promise<void> | void;
  onClose: () => void;
}) {
  useLocale();
  const id = useId();
  const [name, setName] = useState(initialName);
  const [submitted, setSubmitted] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const savingRef = useRef(false);
  const pending = busy || saving;
  const trimmed = name.trim();
  const valid = trimmed.length > 0 && Array.from(trimmed).length <= CANVAS_GROUP_NAME_MAX_LENGTH;
  const invalid = submitted && !valid;
  async function submit() {
    if (pending || savingRef.current) return;
    setSubmitted(true);
    if (!valid) return;
    savingRef.current = true; setSaving(true); setSaveError(null);
    try { await onSave(trimmed); }
    catch (failure) { setSaveError(failure instanceof Error ? failure.message : t("canvas.group.saveFailed")); }
    finally { savingRef.current = false; setSaving(false); }
  }
  return <Dialog compact title={t(mode === "create" ? "canvas.group.create" : "canvas.group.rename")}
    busy={pending} onClose={onClose} onSubmit={(event) => { event.preventDefault(); void submit(); }}
    footer={<>
      <Button type="button" variant="outline" disabled={pending} onClick={onClose}>{t("common.cancel")}</Button>
      <Button type="submit" disabled={pending}>{t(pending ? "common.saving" : retrying ? "canvas.edit.retry" : "canvas.group.save")}</Button>
    </>}>
    <FieldGroup>
      <Field data-invalid={invalid || undefined} data-disabled={pending || retrying || undefined}>
        <FieldLabel htmlFor={id}>{t("canvas.group.name")}</FieldLabel>
        <Input id={id} value={name} autoFocus disabled={pending || retrying} aria-invalid={invalid || undefined}
          aria-describedby={`${id}-hint${invalid ? ` ${id}-error` : ""}`} onChange={(event) => { setName(event.target.value); setSaveError(null); }} />
        <FieldDescription id={`${id}-hint`}>{t("canvas.group.nameHint")}</FieldDescription>
        {invalid ? <FieldError id={`${id}-error`}>{t("canvas.group.invalidName")}</FieldError> : null}
      </Field>
      {error || saveError ? <Alert variant="destructive"><AlertDescription>{error || saveError}</AlertDescription></Alert> : null}
    </FieldGroup>
  </Dialog>;
}
