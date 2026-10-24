import { Handle, Position, useUpdateNodeInternals, type Node, type NodeProps } from "@xyflow/react";
import { cn } from "cn";
import { memo, useEffect } from "react";
import { t, useLocale } from "../../shared/i18n";
import { CaretDown, CaretRight, Copy, DotsThree, LinkBreak, PencilSimple, Plus } from "../../shared/ui/icons";
import { Badge } from "../../shared/ui/primitives/badge";
import { Button } from "../../shared/ui/primitives/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuTrigger } from "../../shared/ui/primitives/dropdown-menu";
import { CANVAS_GROUP_INPUT_HANDLE, CANVAS_GROUP_NODE_TYPE, CANVAS_GROUP_OUTPUT_HANDLE } from "./canvasGroups";
import "./CanvasGroupNode.css";

export type CanvasGroupNodeData = {
  groupId: string;
  name: string;
  collapsed: boolean;
  memberCount: number;
  disabled?: boolean;
  dropTarget?: boolean;
  creationDisabled?: boolean;
  onAdd: (groupId: string, anchor: HTMLElement) => void;
  onRename: (groupId: string) => void;
  onToggle: (groupId: string) => void;
  onDuplicate: (groupId: string) => void;
  onUngroup: (groupId: string) => void;
};
export type CanvasGroupFlowNode = Node<CanvasGroupNodeData, typeof CANVAS_GROUP_NODE_TYPE>;

/** A display/drag surface only. Groups do not own artifact content or generation state. */
export const CanvasGroupNode = memo(function CanvasGroupNode({ id, data, selected }: NodeProps<CanvasGroupFlowNode>) {
  useLocale();
  const updateNodeInternals = useUpdateNodeInternals();
  useEffect(() => { updateNodeInternals(id); }, [id, data.collapsed, updateNodeInternals]);
  const toggleLabel = t(data.collapsed ? "canvas.group.expand" : "canvas.group.collapse");
  return <div className={cn("canvas-group", data.collapsed && "canvas-group--collapsed", selected && "canvas-group--selected",
    data.dropTarget && "canvas-group--drop-target")}
    data-group-id={data.groupId}>
    <div className="canvas-group__header canvas-group-drag-handle">
      <Button type="button" variant="ghost" size="icon-sm" className="nodrag nopan" disabled={data.disabled}
        aria-label={toggleLabel} title={toggleLabel} aria-expanded={!data.collapsed} onClick={() => data.onToggle(data.groupId)}>
        {data.collapsed ? <CaretRight /> : <CaretDown />}
      </Button>
      <span className="canvas-group__name truncate" title={data.name}>{data.name}</span>
      <Badge variant="secondary" className="canvas-group__count" aria-label={t("canvas.group.memberCount", { count: data.memberCount })}>{data.memberCount}</Badge>
      <Button type="button" variant="ghost" size="icon-sm" className="nodrag nopan" disabled={data.disabled || data.creationDisabled}
        aria-label={t("canvas.group.addNode")} title={t("canvas.group.addNode")} aria-haspopup="listbox"
        onClick={(event) => data.onAdd(data.groupId, event.currentTarget)}><Plus /></Button>
      <DropdownMenu>
        <DropdownMenuTrigger asChild><Button type="button" variant="ghost" size="icon-sm" className="nodrag nopan" disabled={data.disabled}
          aria-label={t("canvas.group.actions")} title={t("canvas.group.actions")}><DotsThree /></Button></DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="nodrag nopan">
          <DropdownMenuGroup>
            <DropdownMenuItem onSelect={() => data.onRename(data.groupId)}><PencilSimple />{t("canvas.group.rename")}</DropdownMenuItem>
            <DropdownMenuItem onSelect={() => data.onDuplicate(data.groupId)}><Copy />{t("canvas.group.duplicate")}</DropdownMenuItem>
            <DropdownMenuItem onSelect={() => data.onUngroup(data.groupId)}><LinkBreak />{t("canvas.group.ungroup")}</DropdownMenuItem>
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
    {data.dropTarget ? <span className="canvas-group__drop-hint" role="status">{t("canvas.group.dropToJoin")}</span> : null}
    {/* Keep invisible endpoints mounted before the first fold so edges never point at a
        missing handle during the frame in which the controlled group size changes. */}
    <Handle className="canvas-group__relation-handle" id={CANVAS_GROUP_INPUT_HANDLE} type="target" position={Position.Left} isConnectable={false} />
    <Handle className="canvas-group__relation-handle" id={CANVAS_GROUP_OUTPUT_HANDLE} type="source" position={Position.Right} isConnectable={false} />
  </div>;
});
