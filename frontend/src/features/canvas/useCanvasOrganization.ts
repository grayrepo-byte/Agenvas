import { useCallback, useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getCanvasEditor, getMediaDraft, type CanvasEditRequest, type CanvasGroup,
  type CanvasItem } from "../../shared/api/client";
import { t } from "../../shared/i18n";
import { useCanvasStore } from "./canvasStore";
import { canvasGroupIdFromNodeId, canvasGroupNodeId, projectCanvasGroups } from "./canvasGroups";
import { canvasEditorKey, canvasItemPresentation, useCanvasEditing } from "./useCanvasEditing";
import { flushCanvasEditors } from "./canvasEditingGuards";

export const CANVAS_EDIT_ITEM_LIMIT = 100;
const COPY_OFFSET = 32;
type Copy = NonNullable<CanvasEditRequest["copies"]>[number];
type Clipboard = { items: (Omit<Copy, "targetItemId" | "groupId"> & { sourceGroupId: string | null })[];
  groups: CanvasGroup[] };
type GroupDialog = { mode: "create"; items: CanvasItem[] } | { mode: "rename"; group: CanvasGroup };

/** Groups are server state; selection, clipboard and history remain local to this project session. */
export function useCanvasOrganization({ projectId, items, enabled, selectedIds, select, effectiveNodeSize }: {
  projectId: string; items: readonly CanvasItem[]; enabled: boolean; selectedIds: readonly string[];
  select: (ids: string[]) => void;
  effectiveNodeSize: (item: CanvasItem) => { width: number; height: number };
}) {
  const editing = useCanvasEditing(projectId);
  const editor = useQuery({ queryKey: canvasEditorKey(projectId), queryFn: () => getCanvasEditor(projectId),
    enabled, retry: false });
  const groups = useMemo(() => editor.data?.groups ?? [], [editor.data?.groups]);
  const drafts = useCanvasStore((state) => state.drafts);
  const projection = useMemo(() => projectCanvasGroups(groups, items.map((item) => ({
    id: item.id, groupId: item.groupId, x: drafts[item.id]?.x ?? item.x,
    y: drafts[item.id]?.y ?? item.y, ...effectiveNodeSize(item),
  }))), [groups, items, drafts, effectiveNodeSize]);
  const selectedItems = useMemo(() => {
    const ids = new Set(selectedIds);
    const groupIds = new Set(selectedIds.map(canvasGroupIdFromNodeId).filter((id): id is string => id !== null));
    return items.filter((item) => ids.has(item.id) || Boolean(item.groupId && groupIds.has(item.groupId)));
  }, [items, selectedIds]);
  const [dialog, setDialog] = useState<GroupDialog | null>(null);
  const clipboard = useRef<Clipboard | null>(null);
  const pasteCount = useRef(0);
  const [copiedCount, setCopiedCount] = useState(0);
  const [actionError, setActionError] = useState<Error | null>(null);
  const [preparing, setPreparing] = useState(false);
  const preparingRef = useRef(false);
  const checkItems = useCallback((targets: readonly CanvasItem[]) => {
    if (!targets.length) throw new Error(t("canvas.edit.noSelection"));
    if (targets.length > CANVAS_EDIT_ITEM_LIMIT) throw new Error(t("canvas.edit.limit"));
    if (targets.some((item) => useCanvasStore.getState().drafts[item.id])) {
      throw new Error(t("canvas.edit.draftsPending"));
    }
  }, []);
  const safely = useCallback(async <T,>(action: () => Promise<T>) => {
    setActionError(null);
    try { return await action(); }
    catch (failure) {
      const reason = failure instanceof Error ? failure : new Error(t("canvas.edit.failed"));
      setActionError(reason);
      throw reason;
    }
  }, []);
  const requestGroup = useCallback(() => {
    try {
      if (editing.error) throw editing.error;
      checkItems(selectedItems); setActionError(null); setDialog({ mode: "create", items: [...selectedItems] });
    }
    catch (failure) { setActionError(failure instanceof Error ? failure : new Error(t("canvas.edit.failed"))); }
  }, [checkItems, selectedItems, editing.error]);
  const saveGroup = useCallback(async (name: string) => {
    if (!dialog) return;
    if (editing.error) { await editing.retry(); setDialog(null); return; }
    await safely(async () => {
      const groupId = dialog.mode === "rename" ? dialog.group.id : crypto.randomUUID();
      const result = await editing.execute({ operation: "GROUP",
        groupChanges: [{ type: "UPSERT", groupId,
          expectedVersion: dialog.mode === "rename" ? dialog.group.version : 0,
          name, collapsed: dialog.mode === "rename" ? dialog.group.collapsed : false }],
        itemChanges: dialog.mode === "create" ? dialog.items.map((item) => ({
          ...canvasItemPresentation(item), groupId,
        })) : [] }, () => { setDialog(null); select([canvasGroupNodeId(groupId)]); });
      if (result.groups.some((group) => group.id === groupId)) select([canvasGroupNodeId(groupId)]);
      setDialog(null);
    });
  }, [dialog, editing, safely, select]);
  const toggleGroup = useCallback((groupId: string) => safely(async () => {
    const group = groups.find((candidate) => candidate.id === groupId);
    if (!group) return;
    await editing.execute({ operation: "GROUP", groupChanges: [{
      type: "UPSERT", groupId, expectedVersion: group.version, name: group.name, collapsed: !group.collapsed,
    }] });
    select([canvasGroupNodeId(groupId)]);
  }), [groups, editing, safely, select]);
  const ungroup = useCallback((groupId?: string) => safely(async () => {
    const targets = groupId ? items.filter((item) => item.groupId === groupId)
      : selectedItems.filter((item) => item.groupId);
    checkItems(targets);
    const group = groupId ? groups.find((candidate) => candidate.id === groupId) : undefined;
    await editing.execute({ operation: "GROUP",
      itemChanges: targets.map((item) => ({ ...canvasItemPresentation(item), groupId: null })),
      groupChanges: group ? [{ type: "DELETE", groupId: group.id, expectedVersion: group.version }] : [] });
    select(targets.map((item) => item.id));
  }), [items, selectedItems, groups, checkItems, editing, safely, select]);
  const moveToGroup = useCallback((groupId: string) => safely(async () => {
    checkItems(selectedItems);
    await editing.execute({ operation: "GROUP",
      itemChanges: selectedItems.map((item) => ({ ...canvasItemPresentation(item), groupId })) });
    select([canvasGroupNodeId(groupId)]);
  }), [checkItems, selectedItems, editing, safely, select]);
  const capture = useCallback(async (ids: readonly string[]): Promise<Clipboard> => {
    const requested = new Set(ids);
    const targets = items.filter((item) => requested.has(item.id));
    checkItems(targets);
    await flushCanvasEditors(projectId, targets.map((item) => item.id));
    // A flush can create a text revision or advance a media draft. Pin acknowledged versions.
    const fresh = await getCanvasEditor(projectId);
    const source = targets.map((item) => fresh.items.find((candidate) => candidate.id === item.id));
    if (source.some((item) => !item)) throw new Error(t("canvas.edit.draftsPending"));
    const sourceItems = source.filter((item): item is CanvasItem => item !== undefined);
    const copiedGroups = fresh.groups.filter((group) => {
      const members = fresh.items.filter((item) => item.groupId === group.id);
      return members.length > 0 && members.every((item) => requested.has(item.id));
    });
    return {
      groups: copiedGroups,
      items: await Promise.all(sourceItems.map(async (item) => ({
        sourceItemId: item.id, expectedSourceVersion: item.version,
        expectedSourceContentVersion: item.artifact.kind === "TEXT" ? item.artifact.resourceDefaultVersion?.id ?? null : null,
        expectedSourceDraftVersion: item.artifact.kind === "TEXT" ? null : (await getMediaDraft(projectId, item.id)).version,
        x: item.x, y: item.y, ...effectiveNodeSize(item), zIndex: item.zIndex,
        sourceGroupId: item.groupId ?? null,
      }))),
    };
  }, [items, projectId, checkItems, effectiveNodeSize]);
  const paste = useCallback((buffer: Clipboard, point?: { x: number; y: number }) => safely(async () => {
    const left = Math.min(...buffer.items.map((item) => item.x));
    const top = Math.min(...buffer.items.map((item) => item.y));
    const offset = COPY_OFFSET * (pasteCount.current + 1);
    const dx = point ? point.x - left : offset;
    const dy = point ? point.y - top : offset;
    const groupIds = new Map(buffer.groups.map((group) => [group.id, crypto.randomUUID()]));
    const copies = buffer.items.map(({ sourceGroupId, ...item }) => ({
      ...item, targetItemId: crypto.randomUUID(), x: item.x + dx, y: item.y + dy,
      zIndex: Math.min(1000, item.zIndex + 1),
      groupId: sourceGroupId ? groupIds.get(sourceGroupId) ?? null : null,
    }));
    const result = await editing.execute({ operation: "DUPLICATE", copies,
      groupChanges: buffer.groups.map((group) => ({
        type: "UPSERT", groupId: groupIds.get(group.id)!, expectedVersion: 0,
        name: group.name, collapsed: group.collapsed,
      })) }, (saved) => {
        const ids = copies.map((copy) => copy.targetItemId).filter((id) => saved.items.some((item) => item.id === id));
        select(groupIds.size ? [...groupIds.values()].map(canvasGroupNodeId) : ids);
      });
    pasteCount.current += 1;
    return result;
  }), [editing, safely, select]);
  const prepareCopy = useCallback(async (ids: readonly string[], duplicate: boolean) => {
    if (preparingRef.current) return;
    preparingRef.current = true; setPreparing(true);
    try {
      const buffer = await safely(() => capture(ids));
      if (duplicate) return await paste(buffer);
      clipboard.current = buffer; pasteCount.current = 0; setCopiedCount(buffer.items.length);
    } finally { preparingRef.current = false; setPreparing(false); }
  }, [capture, paste, safely]);
  const duplicateItems = useCallback((ids?: readonly string[]) => prepareCopy(ids ?? selectedItems.map((item) => item.id), true), [prepareCopy, selectedItems]);
  const copyItems = useCallback(() => prepareCopy(selectedItems.map((item) => item.id), false), [prepareCopy, selectedItems]);
  const pasteItems = useCallback((point?: { x: number; y: number }) => clipboard.current
    ? paste(clipboard.current, point) : Promise.resolve(undefined), [paste]);
  return { editor, editing, groups, projection, selectedItems, dialog, saveGroup, requestGroup,
    closeDialog: () => setDialog(null),
    renameGroup: (groupId: string) => {
      if (editing.error) { setActionError(editing.error); return; }
      const group = groups.find((item) => item.id === groupId);
      if (group) { setActionError(null); setDialog({ mode: "rename", group }); }
    },
    toggleGroup, ungroup, moveToGroup, duplicateItems, copyItems, pasteItems,
    duplicateGroup: (groupId: string) => duplicateItems(items.filter((item) => item.groupId === groupId).map((item) => item.id)),
    copiedCount, canPaste: Boolean(clipboard.current), actionError,
    clearActionError: () => setActionError(null), busy: editing.busy || preparing };
}
