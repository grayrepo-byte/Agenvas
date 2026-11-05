import { CaretDown,Check,Cursor,Hand,Plus } from "@/shared/ui/icons";
import { useRef,useState,type ReactNode } from "react";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { DropdownMenu,DropdownMenuContent,DropdownMenuGroup,DropdownMenuItem,DropdownMenuTrigger } from "../../shared/ui/primitives/dropdown-menu";
import type { CanvasTool } from "./canvasInteraction";

export function CanvasToolMenu({ tool, spaceHeld, onToolChange, onAdd, children }: {
  tool: CanvasTool; spaceHeld: boolean; onToolChange: (tool: CanvasTool) => void; onAdd: () => void;
  children?: ReactNode;
}) {
  useLocale();
  const [open, setOpen] = useState(false);
  const container = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const firstOption = useRef<HTMLDivElement>(null);
  const handActive = tool === "hand" || spaceHeld;
  const choose = (value: CanvasTool) => {
    onToolChange(value);
    setOpen(false);
    trigger.current?.focus();
  };
  return <DropdownMenu open={open} onOpenChange={setOpen} modal={false}><div className="workspace-tool-rail" ref={container} onKeyDown={(event) => {
    if (event.key === "Escape" && open) {
      event.stopPropagation(); setOpen(false); trigger.current?.focus();
    }
  }}>
    <Button variant="ghost" size="sm" aria-label={t("canvas.tools.addCard")} className="workspace-add-button" onClick={onAdd} type="button"><Plus size={18} aria-hidden /><span>{t("canvas.tools.addCard")}</span></Button>
    <DropdownMenuTrigger asChild><Button variant="ghost" size="icon-sm" ref={trigger} type="button" className="workspace-tool-trigger" aria-label={t("canvas.tools.title")}
      aria-haspopup="menu" aria-expanded={open}
      title={handActive ? t("canvas.tools.handToolHint") : t("canvas.tools.selectionToolHint")}>
      {handActive ? <Hand size={18} aria-hidden /> : <Cursor size={18} aria-hidden />}<span>{handActive ? t("canvas.tools.handTool") : t("canvas.tools.selectionTool")}</span><CaretDown size={12} aria-hidden />
    </Button></DropdownMenuTrigger>
    {open ? <DropdownMenuContent aria-labelledby={undefined} onEscapeKeyDown={(event) => event.stopPropagation()} className="workspace-tool-menu" role="menu" aria-label={t("canvas.tools.mode")}><DropdownMenuGroup>
      <DropdownMenuItem ref={firstOption} role="menuitemradio" aria-checked={tool === "select"} onSelect={(event) => { event.preventDefault(); choose("select"); }}><span>{tool === "select" ? <Check size={14} /> : null}</span><Cursor size={18} />{t("canvas.tools.selectionTool")}<kbd>V</kbd></DropdownMenuItem>
      <DropdownMenuItem role="menuitemradio" aria-checked={tool === "hand"} onSelect={(event) => { event.preventDefault(); choose("hand"); }}><span>{tool === "hand" ? <Check size={14} /> : null}</span><Hand size={18} />{t("canvas.tools.handTool")}<kbd>Space</kbd></DropdownMenuItem>
    </DropdownMenuGroup></DropdownMenuContent> : null}
    {children}
  </div></DropdownMenu>;
}
