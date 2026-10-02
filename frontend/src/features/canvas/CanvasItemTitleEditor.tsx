import { useMutation,useQueryClient } from "@tanstack/react-query";
import { useEffect,useRef,useState,type KeyboardEvent } from "react";
import { applyCanvasCommands,type CanvasItem } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";

const MAX_TITLE_LENGTH = 160;
const MIN_INPUT_CHARS = 8;
const MAX_INPUT_CHARS = 32;

/** Double-click editing for one CanvasItem title; failed saves keep the draft in place. */
export function CanvasItemTitleEditor({ projectId, item, kindLabel }: {
  projectId: string;
  item: CanvasItem;
  kindLabel: string;
}) {
  useLocale();
  const queryClient = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const cancelBlur = useRef(false);
  const [editing, setEditing] = useState(false);
  const [displayTitle, setDisplayTitle] = useState(item.title);
  const [draft, setDraft] = useState(item.title);
  const [baseVersion, setBaseVersion] = useState(item.version);
  const [validationError, setValidationError] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: async (title: string) => {
      const canvas = await applyCanvasCommands(projectId, [{
        type: "UPDATE_TITLE",
        itemId: item.id,
        expectedVersion: baseVersion,
        title,
      }]);
      const updated = canvas.items.find((candidate) => candidate.id === item.id);
      if (!updated) throw new Error("Updated canvas item is missing from the response");
      return { canvas, updated };
    },
    onSuccess: ({ canvas, updated }) => {
      setDisplayTitle(updated.title);
      setDraft(updated.title);
      setBaseVersion(updated.version);
      setEditing(false);
      queryClient.setQueryData(["canvas", projectId], canvas);
      void queryClient.invalidateQueries({ queryKey: ["snapshot", projectId] });
    },
  });

  useEffect(() => {
    if (!editing && item.version >= baseVersion) {
      setDisplayTitle(item.title);
      setDraft(item.title);
      setBaseVersion(item.version);
    }
  }, [item.title, item.version, baseVersion, editing]);

  useEffect(() => {
    if (editing) {
      input.current?.focus();
      input.current?.select();
    }
  }, [editing]);

  useEffect(() => {
    if (save.isError) input.current?.focus();
  }, [save.isError]);

  function beginEditing() {
    if (save.isPending) return;
    cancelBlur.current = false;
    setDraft(displayTitle);
    setBaseVersion((current) => Math.max(current, item.version));
    setValidationError(null);
    save.reset();
    setEditing(true);
  }

  function cancelEditing() {
    cancelBlur.current = true;
    setDraft(displayTitle);
    setValidationError(null);
    save.reset();
    setEditing(false);
  }

  function commit() {
    if (cancelBlur.current) {
      cancelBlur.current = false;
      return;
    }
    if (save.isPending) return;
    const title = draft.trim();
    if (!title) {
      setValidationError(t("canvas.title.emptyTitle"));
      queueMicrotask(() => input.current?.focus());
      return;
    }
    if (title === displayTitle) {
      setDraft(displayTitle);
      setEditing(false);
      return;
    }
    setValidationError(null);
    save.mutate(title);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    event.stopPropagation();
    if (event.key === "Enter" && !event.nativeEvent.isComposing) {
      event.preventDefault();
      event.currentTarget.blur();
    } else if (event.key === "Escape") {
      event.preventDefault();
      cancelEditing();
    }
  }

  const error = validationError ?? (save.error ? t("canvas.title.saveFailed") : null);
  const inputWidth = Math.min(Math.max(draft.length + 2, MIN_INPUT_CHARS), MAX_INPUT_CHARS);

  return <span className="artifact-card-title-editor nodrag nowheel nopan">
    {editing ? <>
      <Input ref={input} className={`artifact-card-title-input${error ? " is-error" : ""}`}
        aria-label={t("canvas.title.titleLabel", { "0": kindLabel })} aria-invalid={Boolean(error)}
        disabled={save.isPending} maxLength={MAX_TITLE_LENGTH} size={inputWidth}
        title={error ?? t("canvas.title.keyboardHint")}
        value={draft} onBlur={commit} onChange={(event) => {
          setDraft(event.target.value);
          setValidationError(null);
          if (save.isError) save.reset();
        }} onKeyDown={handleKeyDown} />
      {error ? <span className="sr-only" role="alert">{error}</span> : null}
    </> : <Button variant="ghost" type="button" className="artifact-card-caption nodrag nowheel nopan"
      aria-label={t("canvas.title.renameLabel", { "0": kindLabel, "1": displayTitle })} title={t("canvas.title.editHint")}
      onClick={(event) => { if (event.detail === 2) beginEditing(); }}
      onDoubleClick={(event) => { event.stopPropagation(); beginEditing(); }}
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === "F2") {
          event.preventDefault();
          event.stopPropagation();
          beginEditing();
        }
      }}>{displayTitle}</Button>}
  </span>;
}
