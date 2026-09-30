import { CaretDown, Stack } from "@phosphor-icons/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { ApiError, listCanvasMediaVersions, selectCanvasMediaVersion,
  type CanvasItem } from "../../shared/api/client";

/** Card-local result selection never restores inputs or changes a sibling's result. */
export function MediaVersionPicker({ projectId, item }: { projectId: string; item: CanvasItem }) {
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLDivElement>(null);
  const toggle = useRef<HTMLButtonElement>(null);
  const client = useQueryClient();
  const history = useQuery({ queryKey: ["canvas-media-versions", projectId, item.id],
    queryFn: () => listCanvasMediaVersions(projectId, item.id), enabled: open });
  const select = useMutation({
    mutationFn: (versionId: string) => selectCanvasMediaVersion(projectId, item.id,
      { versionId, expectedVersion: item.version }),
    onSuccess: async () => {
      setOpen(false);
      await Promise.all([
        client.invalidateQueries({ queryKey: ["canvas", projectId] }),
        client.invalidateQueries({ queryKey: ["media-draft", projectId, item.id] }),
        client.invalidateQueries({ queryKey: ["snapshot", projectId] }),
      ]);
    },
  });
  useEffect(() => {
    if (!open) return;
    function dismiss(event: PointerEvent) {
      if (!anchor.current?.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener("pointerdown", dismiss);
    return () => document.removeEventListener("pointerdown", dismiss);
  }, [open]);

  return <div className="media-version-picker" ref={anchor}
    onKeyDown={(event) => {
      if (event.key === "Escape") { event.stopPropagation(); setOpen(false); toggle.current?.focus(); }
    }}>
    <button type="button" ref={toggle} aria-expanded={open} aria-haspopup="menu"
      onClick={() => { select.reset(); setOpen((value) => !value); }}>
      <Stack size={17} />版本{item.selectedVersion ? ` v${item.selectedVersion.versionNo}` : ""}
      <CaretDown size={12} />
    </button>
    {open ? <div className="media-version-menu nodrag nowheel nopan" role="menu" aria-label="媒体版本">
      <p>{history.data ? `${history.data.items.length} 个版本` : "节点版本历史"}</p>
      {history.isPending ? <p role="status">正在读取版本…</p> : null}
      {history.error ? <div role="alert">版本历史读取失败
        <button type="button" onClick={() => void history.refetch()}>重试读取</button></div> : null}
      {history.data?.items.length === 0 ? <p>还没有已归档的结果。</p> : null}
      {history.data?.items.map((version) => <button type="button" role="menuitem"
        key={version.id} disabled={select.isPending}
        aria-label={`v${version.versionNo} ${version.id === item.selectedVersionId ? "当前选用" : "选用此版本"}`}
        aria-current={version.id === item.selectedVersionId ? "true" : undefined}
        onClick={() => select.mutate(version.id)}>
        <span>v{version.versionNo}</span>
        <small>{version.id === item.selectedVersionId ? "当前选用" : "选用此版本"}</small>
      </button>)}
      {select.isPending ? <p role="status">正在切换版本…</p> : null}
      {select.error ? <div role="alert">{select.error instanceof ApiError
        ? select.error.message : "版本切换失败，当前结果已保留。"}
        <button type="button" onClick={() => {
          void client.invalidateQueries({ queryKey: ["canvas", projectId] });
          void history.refetch();
        }}>刷新版本</button></div> : null}
      <p>切换结果会保留当前草稿和已有引用。</p>
    </div> : null}
  </div>;
}
