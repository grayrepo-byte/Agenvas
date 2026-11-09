import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { ApiError, getCurrentUser } from "../../shared/api/client";
import { saveCostumeReference, type SaveCostumeReferenceRequest } from "../../shared/api/referenceCatalog";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { CostumeReferenceFields, costumeMetadataRequest, costumeMetadataValid, type CostumeMetadata } from "./CostumeReferenceFields";
import "./ReferenceCatalog.css";

export type SaveCostumeReferenceButtonProps = { projectId: string; canvasItemId: string; sourceVersionId: string; expectedCanvasItemVersion: number; sourceTitle: string; disabled?: boolean };
/** Optional catalog save pins the displayed result; retry never requests image generation. */
export function SaveCostumeReferenceButton(props: SaveCostumeReferenceButtonProps) {
  useLocale(); const client = useQueryClient();
  const user = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const [open, setOpen] = useState(false); const [source, setSource] = useState(props);
  const [fields, setFields] = useState<CostumeMetadata>({ name: props.sourceTitle, category: "", tags: "" });
  const [busy, setBusy] = useState(false); const [error, setError] = useState<Error | null>(null);
  const [uncertain, setUncertain] = useState(false); const [saved, setSaved] = useState(false);
  const intent = useRef<{ projectId: string; request: SaveCostumeReferenceRequest } | null>(null);
  const submitting = useRef(false);
  if (user.data?.role !== "ADMIN") return null;
  function show() {
    if (!intent.current) {
      setSource(props); setFields({ name: props.sourceTitle, category: "", tags: "" }); setError(null); setSaved(false); setUncertain(false);
    }
    setOpen(true);
  }
  async function save() {
    if (submitting.current || saved || !costumeMetadataValid(fields)) return;
    intent.current ??= { projectId: source.projectId, request: { canvasItemId: source.canvasItemId, sourceVersionId: source.sourceVersionId,
      expectedCanvasItemVersion: source.expectedCanvasItemVersion, ...costumeMetadataRequest(fields), commandKey: crypto.randomUUID() } };
    submitting.current = true; setBusy(true); setError(null);
    try {
      await saveCostumeReference(intent.current.projectId, intent.current.request);
      setSaved(true); setUncertain(false); intent.current = null;
      void client.invalidateQueries({ queryKey: ["reference-catalog"] });
    } catch (failure) {
      const frozen = !(failure instanceof ApiError && failure.status < 500 && failure.status !== 408);
      setError(failure instanceof Error ? failure : new Error(t("costume.saveFailed"))); setUncertain(frozen);
      if (!frozen) intent.current = null;
    } finally { submitting.current = false; setBusy(false); }
  }
  return <><Button type="button" variant="ghost" disabled={props.disabled || busy} onClick={show}>{t("costume.saveAction")}</Button>
    {open ? <Dialog className="costume-reference-dialog" title={t("costume.saveAction")} description={t("costume.sourcePinned")} busy={busy} onClose={() => setOpen(false)}
      onSubmit={(event) => { event.preventDefault(); void save(); }} footer={<>
        <Button type="button" variant="outline" disabled={busy} onClick={() => setOpen(false)}>{saved ? t("common.done") : t("common.cancel")}</Button>
        {!saved ? <Button type="submit" disabled={busy || !costumeMetadataValid(fields)}>{busy ? t("common.saving") : error ? t("common.retry") : t("costume.save")}</Button> : null}
      </>}>
      <CostumeReferenceFields fields={fields} onChange={setFields} disabled={busy || uncertain || saved} />
      {error ? <Notice tone="danger"><p>{t("costume.saveFailed")}</p><p>{error.message}</p>{uncertain ? <p>{t("costume.uncertain")}</p> : null}</Notice> : null}
      {saved ? <p role="status">{t("costume.saved")}</p> : null}
    </Dialog> : null}
  </>;
}
