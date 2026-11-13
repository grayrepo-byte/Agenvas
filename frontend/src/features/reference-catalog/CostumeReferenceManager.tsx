import { useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { ApiError } from "../../shared/api/client";
import {
  deleteCostumeReference,
  updateCostumeReference,
  type ReferenceEntry,
  type UpdateCostumeReferenceRequest,
} from "../../shared/api/referenceCatalog";
import { t } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import {
  CostumeReferenceFields,
  costumeMetadataRequest,
  costumeMetadataValid,
  type CostumeMetadata,
} from "./CostumeReferenceFields";

export function CostumeReferenceManager({
  entry,
  disabled,
  onBusy,
  onUpdated,
  onDeleted,
  onRefresh,
}: {
  entry: ReferenceEntry;
  disabled: boolean;
  onBusy: (value: boolean) => void;
  onUpdated: (entry: ReferenceEntry) => void;
  onDeleted: () => void;
  onRefresh: () => Promise<ReferenceEntry | null>;
}) {
  const client = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [fields, setFields] = useState<CostumeMetadata>({
    name: entry.title,
    category: entry.category ?? "",
    tags: entry.tags.join(", "),
  });
  const [error, setError] = useState<{ action: "update" | "delete"; failure: Error } | null>(null);
  const running = useRef(false);
  const pendingUpdate = useRef<UpdateCostumeReferenceRequest | null>(null);
  const [uncertain, setUncertain] = useState(false);
  async function refresh() {
    if (disabled || running.current) return;
    running.current = true;
    onBusy(true);
    try {
      const fresh = await onRefresh();
      if (!fresh) return;
      const pending = pendingUpdate.current;
      // A read proves a lost acknowledgement only for the exact next CAS version
      // and metadata submitted by this editor. Local input is otherwise retained.
      if (
        pending &&
        fresh.version === pending.expectedVersion + 1 &&
        fresh.title === pending.name &&
        (fresh.category ?? "") === (pending.category ?? "") &&
        JSON.stringify(fresh.tags) === JSON.stringify(pending.tags)
      ) {
        setEditing(false);
      }
      pendingUpdate.current = null;
      setUncertain(false);
      setError(null);
    } catch (failure) {
      setError({
        action: editing ? "update" : "delete",
        failure: failure instanceof Error ? failure : new Error(t("reference.readFailed")),
      });
    } finally {
      running.current = false;
      onBusy(false);
    }
  }
  async function mutate(action: "update" | "delete") {
    if (
      disabled ||
      running.current ||
      entry.version === undefined ||
      (action === "update" && (!costumeMetadataValid(fields) || uncertain))
    )
      return;
    running.current = true;
    onBusy(true);
    setError(null);
    try {
      if (action === "update") {
        pendingUpdate.current = { expectedVersion: entry.version, ...costumeMetadataRequest(fields) };
        const saved = await updateCostumeReference(entry.id, pendingUpdate.current);
        pendingUpdate.current = null;
        setUncertain(false);
        onUpdated(saved);
        setEditing(false);
      } else {
        await deleteCostumeReference(entry.id, entry.version);
        onDeleted();
      }
      void client.invalidateQueries({ queryKey: ["reference-catalog"] });
    } catch (failure) {
      if (action === "update") {
        const unknown = !(failure instanceof ApiError && failure.status < 500 && failure.status !== 408);
        setUncertain(unknown);
        if (!unknown) pendingUpdate.current = null;
      }
      setError({
        action,
        failure:
          failure instanceof Error
            ? failure
            : new Error(t(action === "update" ? "costume.updateFailed" : "costume.deleteFailed")),
      });
    } finally {
      running.current = false;
      onBusy(false);
    }
  }
  return (
    <section className="costume-reference-manager">
      <div className="ui-form-actions">
        <Button
          type="button"
          variant="outline"
          disabled={disabled || entry.version === undefined}
          onClick={() => {
            setEditing(true);
            setConfirm(false);
            setError(null);
          }}
        >
          {t("costume.edit")}
        </Button>
        <Button
          type="button"
          variant="destructive"
          disabled={disabled || entry.version === undefined}
          onClick={() => {
            setConfirm(true);
            setError(null);
          }}
        >
          {t("costume.delete")}
        </Button>
      </div>
      {editing ? (
        <Dialog
          className="costume-reference-dialog"
          title={t("costume.edit")}
          busy={disabled}
          onClose={() => {
            setEditing(false);
            setError(null);
          }}
          onSubmit={event => {
            event.preventDefault();
            void mutate("update");
          }}
          footer={
            <>
              <Button
                type="button"
                variant="outline"
                disabled={disabled}
                onClick={() => {
                  setEditing(false);
                  setError(null);
                }}
              >
                {t("common.cancel")}
              </Button>
              <Button type="button" variant="outline" disabled={disabled} onClick={() => void refresh()}>
                {t("costume.refresh")}
              </Button>
              <Button type="submit" disabled={disabled || uncertain || !costumeMetadataValid(fields)}>
                {t("costume.saveDetails")}
              </Button>
            </>
          }
        >
          <CostumeReferenceFields fields={fields} onChange={setFields} disabled={disabled || uncertain} />
          {error?.action === "update" ? (
            <Notice tone="danger">
              <p>{t("costume.updateFailed")}</p>
              <p>{error.failure.message}</p>
            </Notice>
          ) : null}
        </Dialog>
      ) : null}
      {confirm ? (
        <Notice tone="warning">
          <p>{t("costume.deleteConfirm")}</p>
          <div className="ui-form-actions">
            <Button type="button" variant="destructive" disabled={disabled} onClick={() => void mutate("delete")}>
              {t("costume.confirmDelete")}
            </Button>
            <Button type="button" variant="outline" disabled={disabled} onClick={() => setConfirm(false)}>
              {t("common.cancel")}
            </Button>
          </div>
        </Notice>
      ) : null}
      {error?.action === "delete" ? (
        <Notice tone="danger">
          <p>{t("costume.deleteFailed")}</p>
          <p>{error.failure.message}</p>
          <Button type="button" variant="outline" disabled={disabled} onClick={() => void refresh()}>
            {t("costume.refresh")}
          </Button>
        </Notice>
      ) : null}
    </section>
  );
}
