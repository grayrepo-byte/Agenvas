import { BoundingBox, ImagesSquare, PaintBrush, Plus, UploadSimple } from "@/shared/ui/icons";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { t, useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuTrigger } from "../../shared/ui/primitives/dropdown-menu";

const SOURCE_CLOSE_DELAY_MS = 120;
export type MediaReferenceSource = "upload" | "resources" | "canvas" | "library";

/** Ordinary references and named workflow slots share the same source interaction. */
export function MediaReferenceSourceMenu({ label, title, disabled = false, suspended = false, libraryDisabled = false, uploadDisabled = false,
  className = "media-draft-reference-add", invalid, children, open: controlledOpen, onOpenChange,
  onTrigger, onChoose }: {
  label: string; title?: string; disabled?: boolean; suspended?: boolean; libraryDisabled?: boolean; uploadDisabled?: boolean;
  className?: string; invalid?: boolean; children?: ReactNode; open?: boolean;
  onOpenChange?: (open: boolean) => void; onTrigger?: (trigger: HTMLButtonElement) => void;
  onChoose: (source: MediaReferenceSource, trigger: HTMLButtonElement) => void;
}) {
  useLocale();
  const [localOpen, setLocalOpen] = useState(false);
  const open = controlledOpen ?? localOpen;
  const triggerRef = useRef<HTMLButtonElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const hoverOpened = useRef(false);
  const closeTimer = useRef<number | null>(null);
  const id = useId();
  function setOpen(next: boolean) { if (next && suspended) return; setLocalOpen(next); onOpenChange?.(next); }
  function clearTimer() {
    if (closeTimer.current !== null) window.clearTimeout(closeTimer.current);
    closeTimer.current = null;
  }
  function scheduleClose() {
    clearTimer();
    closeTimer.current = window.setTimeout(() => { setOpen(false); closeTimer.current = null; }, SOURCE_CLOSE_DELAY_MS);
  }
  useEffect(() => () => { if (closeTimer.current !== null) window.clearTimeout(closeTimer.current); }, []);
  useEffect(() => {
    if (!open) return;
    function pointerDown(event: PointerEvent) {
      if (event.target instanceof Node && !contentRef.current?.contains(event.target)
        && !triggerRef.current?.contains(event.target)) setOpen(false);
    }
    function keyDown(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      event.preventDefault(); event.stopPropagation(); setOpen(false); triggerRef.current?.focus();
    }
    document.addEventListener("pointerdown", pointerDown);
    document.addEventListener("keydown", keyDown, true);
    return () => { document.removeEventListener("pointerdown", pointerDown); document.removeEventListener("keydown", keyDown, true); };
  });
  function choose(source: MediaReferenceSource) {
    clearTimer(); setOpen(false);
    if (!disabled && triggerRef.current) onChoose(source, triggerRef.current);
  }
  return <DropdownMenu open={open && !disabled && !suspended} onOpenChange={setOpen} modal={false}>
    <div className="media-draft-popover-anchor" onPointerEnter={() => {
      clearTimer();
      if (disabled || suspended) return;
      if (!open) hoverOpened.current = true;
      if (triggerRef.current) onTrigger?.(triggerRef.current);
      setOpen(true);
    }} onPointerLeave={scheduleClose}>
      <DropdownMenuTrigger asChild><Button ref={triggerRef} variant="ghost" type="button" className={className}
        disabled={disabled} aria-label={label} title={title} aria-invalid={invalid} aria-haspopup="menu"
        aria-expanded={open && !disabled && !suspended} aria-controls={id}
        onFocus={(event) => onTrigger?.(event.currentTarget)} onPointerDown={(event) => event.preventDefault()}
        onClick={(event) => { onTrigger?.(event.currentTarget); setOpen(!open || hoverOpened.current); hoverOpened.current = false; }}>
        {children ?? <Plus size={20} />}
      </Button></DropdownMenuTrigger>
      {open && !disabled && !suspended ? <DropdownMenuContent ref={contentRef} id={id} role="menu" aria-labelledby={undefined}
        aria-label={t("media.editor.imageSource")} className="media-draft-popover media-draft-reference-sources"
        onCloseAutoFocus={(event) => event.preventDefault()} onEscapeKeyDown={(event) => event.stopPropagation()}
        onPointerDownOutside={(event) => {
          if (event.detail.originalEvent.target instanceof Node && triggerRef.current?.contains(event.detail.originalEvent.target)) event.preventDefault();
        }} onPointerEnter={clearTimer} onPointerLeave={scheduleClose}>
        <DropdownMenuGroup>
          <DropdownMenuItem disabled={uploadDisabled} onSelect={(event) => { event.preventDefault(); choose("upload"); }}>
            <UploadSimple /><span>{t("media.editor.upload")}</span>
          </DropdownMenuItem>
          <DropdownMenuItem onSelect={(event) => { event.preventDefault(); choose("resources"); }}>
            <ImagesSquare /><span>{t("media.editor.chooseResources")}</span>
          </DropdownMenuItem>
          <DropdownMenuItem onSelect={(event) => { event.preventDefault(); choose("canvas"); }}>
            <BoundingBox /><span>{t("media.editor.chooseCanvas")}</span>
          </DropdownMenuItem>
          <DropdownMenuItem disabled={libraryDisabled} onSelect={(event) => { event.preventDefault(); choose("library"); }}>
            <ImagesSquare /><span>{t("media.editor.chooseLibrary")}</span>
          </DropdownMenuItem>
          <DropdownMenuItem disabled aria-label={t("media.editor.sketchUnavailableLabel")} title={t("media.editor.sketchUnavailable")}>
            <PaintBrush /><span>{t("media.editor.sketchReference")}</span><small>{t("media.editor.notAvailable")}</small>
          </DropdownMenuItem>
        </DropdownMenuGroup>
      </DropdownMenuContent> : null}
    </div>
  </DropdownMenu>;
}
