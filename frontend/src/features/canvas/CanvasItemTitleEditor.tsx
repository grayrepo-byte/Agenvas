import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { applyCanvasCommands, type CanvasItem } from "../../shared/api/client";

const MAX_TITLE_LENGTH = 160;
const MIN_INPUT_CHARS = 8;
const MAX_INPUT_CHARS = 32;

/** Double-click editing for one CanvasItem title; failed saves keep the draft in place. */
export function CanvasItemTitleEditor({ projectId, item, kindLabel }: {
  projectId: string;
  item: CanvasItem;
  kindLabel: string;
}) {
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
      setValidationError("标题不能为空");
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

  const error = validationError ?? (save.error ? "标题保存失败，请重试" : null);
  const inputWidth = Math.min(Math.max(draft.length + 2, MIN_INPUT_CHARS), MAX_INPUT_CHARS);

  return <span className="artifact-card-title-editor nodrag nowheel nopan">
    {editing ? <>
      <input ref={input} className={`artifact-card-title-input${error ? " is-error" : ""}`}
        aria-label={`${kindLabel}标题`} aria-invalid={Boolean(error)}
        disabled={save.isPending} maxLength={MAX_TITLE_LENGTH} size={inputWidth}
        title={error ?? "按 Enter 或移开焦点保存，按 Esc 取消"}
        value={draft} onBlur={commit} onChange={(event) => {
          setDraft(event.target.value);
          setValidationError(null);
          if (save.isError) save.reset();
        }} onKeyDown={handleKeyDown} />
      {error ? <span className="sr-only" role="alert">{error}</span> : null}
    </> : <button type="button" className="artifact-card-caption nodrag nowheel nopan"
      aria-label={`重命名${kindLabel}：${displayTitle}`} title="双击编辑标题"
      onClick={(event) => { if (event.detail === 2) beginEditing(); }}
      onDoubleClick={(event) => { event.stopPropagation(); beginEditing(); }}
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === "F2") {
          event.preventDefault();
          event.stopPropagation();
          beginEditing();
        }
      }}>{displayTitle}</button>}
  </span>;
}
