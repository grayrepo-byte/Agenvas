import { t, useLocale } from "../../shared/i18n";
import { DropdownMenu } from "../../shared/ui/DropdownMenu";
import { Check, Cursor, DotsThree, Hand, Plus } from "@phosphor-icons/react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import type { CanvasTool } from "./canvasInteraction";

export function CanvasToolMenu({ tool, spaceHeld, onToolChange, onAdd, children }: {
  tool: CanvasTool; spaceHeld: boolean; onToolChange: (tool: CanvasTool) => void; onAdd: () => void;
  children?: ReactNode;
}) {
  useLocale();
  const [open, setOpen] = useState(false);
  const container = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const firstOption = useRef<HTMLButtonElement>(null);
  const handActive = tool === "hand" || spaceHeld;
  useEffect(() => {
    if (!open) return;
    firstOption.current?.focus();
    const close = (event: PointerEvent) => {
      if (event.target instanceof Node && !container.current?.contains(event.target)) setOpen(false);
    };
    document.addEventListener("pointerdown", close);
    return () => document.removeEventListener("pointerdown", close);
  }, [open]);
  const choose = (value: CanvasTool) => {
    onToolChange(value);
    setOpen(false);
    trigger.current?.focus();
  };
  return <div className="workspace-tool-rail" ref={container} onKeyDown={(event) => {
    if (event.key === "Escape" && open) {
      event.stopPropagation(); setOpen(false); trigger.current?.focus();
    }
  }}>
    <button aria-label={t("添加卡片")} className="workspace-add-button" onClick={onAdd} type="button"><Plus size={20} /></button>
    <button ref={trigger} type="button" className="workspace-tool-trigger" aria-label={t("画布工具")}
      aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(!open)}
      title={handActive ? t("手形工具 · 拖动画布") : t("选择工具 · 短按空格切换手形，长按临时拖动")}>
      {handActive ? <Hand size={20} /> : <Cursor size={20} />}<DotsThree size={16} />
    </button>
    {open ? <DropdownMenu className="workspace-tool-menu" role="menu" aria-label={t("画布工具模式")}>
      <button ref={firstOption} type="button" role="menuitemradio" aria-checked={tool === "select"}
        onClick={() => choose("select")}><span>{tool === "select" ? <Check size={14} /> : null}</span><Cursor size={18} />{t("选择工具")}<kbd>V</kbd></button>
      <button type="button" role="menuitemradio" aria-checked={tool === "hand"}
        onClick={() => choose("hand")}><span>{tool === "hand" ? <Check size={14} /> : null}</span><Hand size={18} />{t("手形工具")}<kbd>Space</kbd></button>
    </DropdownMenu> : null}
    {children}
  </div>;
}
