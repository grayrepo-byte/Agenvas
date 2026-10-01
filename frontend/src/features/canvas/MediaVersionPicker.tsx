import { CaretDown,Stack } from "@phosphor-icons/react";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { useRef,useState } from "react";
import {
ApiError,listCanvasMediaVersions,selectCanvasMediaVersion,
type CanvasItem
} from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { DropdownMenu,DropdownMenuContent,DropdownMenuGroup,DropdownMenuItem,DropdownMenuTrigger } from "../../shared/ui/primitives/dropdown-menu";

const MIN_SELECTABLE_VERSION_COUNT = 2;
const FIRST_NODE_VERSION_NO = 1;

/** Card-local result selection never restores inputs or changes a sibling's result. */
export function MediaVersionPicker({ projectId, item }: { projectId: string; item: CanvasItem }) {
  useLocale();
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLDivElement>(null);
  const toggle = useRef<HTMLButtonElement>(null);
  const client = useQueryClient();
  const history = useQuery({ queryKey: ["canvas-media-versions", projectId, item.id],
    queryFn: () => listCanvasMediaVersions(projectId, item.id) });
  // Artifact numbers are audit identifiers shared by sibling nodes. Display only this node's sequence.
  const versions = [...(history.data?.items ?? [])]
    .sort((a, b) => a.versionNo - b.versionNo)
    .map((version, index) => ({ version, nodeVersionNo: index + FIRST_NODE_VERSION_NO }))
    .reverse();
  const canSelect = versions.length >= MIN_SELECTABLE_VERSION_COUNT;
  const menuOpen = open && canSelect;
  const selectedNumber = versions.find(({ version }) => version.id === item.selectedVersionId)?.nodeVersionNo;
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

  if (!canSelect) return history.error ? <div className="media-version-picker" role="alert">
    {t("版本历史读取失败")}<Button variant="ghost" type="button" disabled={history.isFetching}
      onClick={() => void history.refetch()}>{t("重试读取")}</Button>
  </div> : null;

  return <DropdownMenu open={menuOpen} onOpenChange={setOpen} modal={false}><div className="media-version-picker" ref={anchor}
    onKeyDown={(event) => {
      if (event.key === "Escape") { event.stopPropagation(); setOpen(false); toggle.current?.focus(); }
    }}>
    <DropdownMenuTrigger asChild><Button variant="ghost" type="button" ref={toggle} aria-expanded={menuOpen} aria-haspopup="menu"
      onPointerDown={() => select.reset()}>
      <Stack size={17} />{t("版本{0}", { "0": selectedNumber !== undefined ? ` v${selectedNumber}` : "" })}<CaretDown size={12} />
    </Button></DropdownMenuTrigger>
    {menuOpen ? <DropdownMenuContent aria-labelledby={undefined} onEscapeKeyDown={(event) => event.stopPropagation()} className="media-version-menu" role="menu" aria-label={t("媒体版本")}><DropdownMenuGroup>
      <p>{history.data ? t("{0} 个版本", { "0": history.data.items.length }) : t("节点版本历史")}</p>
      {history.isPending ? <p role="status">{t("正在读取版本…")}</p> : null}
      {history.error ? <div role="alert">{t("版本历史读取失败")}<Button variant="ghost" type="button" onClick={() => void history.refetch()}>{t("重试读取")}</Button></div> : null}
      {versions.map(({ version, nodeVersionNo }) => <DropdownMenuItem role="menuitem" disabled={select.isPending} aria-label={`v${nodeVersionNo} ${version.id === item.selectedVersionId ? t("当前选用") : t("选用此版本")}`} aria-current={version.id === item.selectedVersionId ? "true" : undefined} key={version.id} onSelect={(event) => { event.preventDefault(); select.mutate(version.id); }}>
        <span>v{nodeVersionNo}</span>
        <small>{version.id === item.selectedVersionId ? t("当前选用") : t("选用此版本")}</small>
      </DropdownMenuItem>)}
      {select.isPending ? <p role="status">{t("正在切换版本…")}</p> : null}
      {select.error ? <div role="alert">{select.error instanceof ApiError
        ? select.error.message : t("版本切换失败，当前结果已保留。")}
        <Button variant="ghost" type="button" onClick={() => {
          void client.invalidateQueries({ queryKey: ["canvas", projectId] });
          void history.refetch();
        }}>{t("刷新版本")}</Button></div> : null}
      <p>{t("切换结果会保留当前草稿和已有引用。")}</p>
    </DropdownMenuGroup></DropdownMenuContent> : null}
  </div></DropdownMenu>;
}
