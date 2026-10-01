import { Check,Cursor,DotsThree,Hand,Plus } from "@phosphor-icons/react";
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
    <Button variant="ghost" aria-label={t("添加卡片")} className="workspace-add-button" onClick={onAdd} type="button"><Plus size={20} /></Button>
    <DropdownMenuTrigger asChild><Button variant="ghost" ref={trigger} type="button" className="workspace-tool-trigger" aria-label={t("画布工具")}
      aria-haspopup="menu" aria-expanded={open}
      title={handActive ? t("手形工具 · 拖动画布") : t("选择工具 · 短按空格切换手形，长按临时拖动")}>
      {handActive ? <Hand size={20} /> : <Cursor size={20} />}<DotsThree size={16} />
    </Button></DropdownMenuTrigger>
    {open ? <DropdownMenuContent aria-labelledby={undefined} onEscapeKeyDown={(event) => event.stopPropagation()} className="workspace-tool-menu" role="menu" aria-label={t("画布工具模式")}><DropdownMenuGroup>
      <DropdownMenuItem ref={firstOption} role="menuitemradio" aria-checked={tool === "select"} onSelect={(event) => { event.preventDefault(); choose("select"); }}><span>{tool === "select" ? <Check size={14} /> : null}</span><Cursor size={18} />{t("选择工具")}<kbd>V</kbd></DropdownMenuItem>
      <DropdownMenuItem role="menuitemradio" aria-checked={tool === "hand"} onSelect={(event) => { event.preventDefault(); choose("hand"); }}><span>{tool === "hand" ? <Check size={14} /> : null}</span><Hand size={18} />{t("手形工具")}<kbd>Space</kbd></DropdownMenuItem>
    </DropdownMenuGroup></DropdownMenuContent> : null}
    {children}
  </div></DropdownMenu>;
}
