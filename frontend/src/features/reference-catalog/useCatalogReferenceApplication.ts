import { useQueryClient } from "@tanstack/react-query";
import { useCallback, useLayoutEffect, useRef } from "react";
import {
  getMediaDraft,
  saveMediaDraft,
  type Artifact,
  type MediaCapability,
  type MediaDraft,
  type SaveMediaDraftRequest,
} from "../../shared/api/client";
import type { ReferenceImport } from "../../shared/api/referenceCatalog";
import { t } from "../../shared/i18n";
import { fieldsFromMediaDraft, type useMediaDraftSession } from "../canvas/useMediaDraftSession";
import { catalogReferenceFields, catalogReferenceUnavailable } from "./catalogReferenceApplication";

type Application = {
  projectId: string;
  canvasItemId: string;
  kind: Artifact["kind"];
  capability?: MediaCapability;
  slotKey: string | null;
  colors: readonly string[];
  session: ReturnType<typeof useMediaDraftSession>;
  onApplied: () => void;
};
/** Import and draft save remain separate: retries retain the imported version and the exact CAS request. */
export function useCatalogReferenceApplication(options: Application) {
  const client = useQueryClient();
  const latest = useRef(options);
  useLayoutEffect(() => {
    latest.current = options;
  });
  const operation = useRef<{
    finish: (error: Error | null) => void;
    failure: Error | null;
    context: Application;
  } | null>(null);
  const pending = useRef<{
    image: ReferenceImport;
    uncertain: boolean;
    capabilityId: string | undefined;
    slotKey: string | null;
    request: SaveMediaDraftRequest;
    fieldsAtStart: NonNullable<Application["session"]["state"]["fields"]>;
    nextFields: NonNullable<Application["session"]["state"]["fields"]>;
  } | null>(null);
  const onBusy = useCallback((busy: boolean) => {
    if (busy) {
      operation.current ??= {
        finish: latest.current.session.beginBusinessWrite(),
        failure: null,
        context: latest.current,
      };
    } else {
      operation.current?.finish(operation.current.failure);
      operation.current = null;
    }
  }, []);
  const onApply = useCallback(
    async (image: ReferenceImport) => {
      const current = operation.current?.context ?? latest.current;
      const { session, projectId, canvasItemId } = current;
      try {
        if (session.isReadOnly()) throw new Error(t("media.editor.editingLocked"));
        await session.awaitPendingSave();
        const state = session.getLatest();
        if (!state.fields || state.expectedVersion === null) throw new Error(t("media.editor.draftRefreshFailed"));
        if (latest.current.capability?.id !== current.capability?.id) throw new Error(t("reference.ordinaryOnly"));
        let attempt = pending.current;
        if (!attempt || attempt.image.versionId !== image.versionId) {
          const color =
            current.colors.find(candidate => !state.fields!.mediaInputs.some(input => input.color === candidate)) ??
            current.colors[0]!;
          const nextFields = catalogReferenceFields(
            { fields: state.fields, kind: current.kind, capability: current.capability, slotKey: current.slotKey },
            image.versionId,
            color,
          );
          if (nextFields === state.fields) {
            current.onApplied();
            return;
          }
          attempt = {
            image,
            uncertain: false,
            capabilityId: current.capability?.id,
            slotKey: current.slotKey,
            fieldsAtStart: state.fields,
            nextFields,
            request: { ...nextFields, expectedVersion: state.expectedVersion },
          };
          pending.current = attempt;
        }
        if (attempt.capabilityId !== current.capability?.id || attempt.slotKey !== current.slotKey)
          throw new Error(t("reference.ordinaryOnly"));
        let saved: MediaDraft | undefined;
        // Recover a lost acknowledgement only when the read proves this exact write succeeded.
        if (attempt.uncertain) {
          const fresh = await getMediaDraft(projectId, canvasItemId);
          if (
            fresh.version === attempt.request.expectedVersion + 1 &&
            JSON.stringify(fieldsFromMediaDraft(fresh)) === JSON.stringify(attempt.nextFields)
          )
            saved = fresh;
        }
        saved ??= await saveMediaDraft(projectId, canvasItemId, attempt.request);
        const after = session.getLatest().fields;
        const changed = after !== null && after !== attempt.fieldsAtStart;
        const merged =
          changed && after
            ? catalogReferenceFields(
                { fields: after, kind: current.kind, capability: current.capability, slotKey: current.slotKey },
                image.versionId,
                attempt.nextFields.mediaInputs.find(input => input.versionId === image.versionId)!.color,
              )
            : attempt.nextFields;
        session.acceptSaved(saved, { fields: merged, dirty: changed, error: null });
        pending.current = null;
        if (operation.current) operation.current.failure = null;
        void client.invalidateQueries({ queryKey: ["artifacts", projectId] });
        current.onApplied();
      } catch (error) {
        const failure = error instanceof Error ? error : new Error(t("reference.importFailed"));
        if (pending.current) pending.current.uncertain = true;
        if (operation.current) operation.current.failure = failure;
        throw failure;
      }
    },
    [client],
  );
  const state = options.session.state;
  const disabledReason = state.fields
    ? catalogReferenceUnavailable({
        fields: state.fields,
        kind: options.kind,
        capability: options.capability,
        slotKey: options.slotKey,
      })
    : t("reference.capacity");
  return { onBusy, onApply, disabledReason };
}
