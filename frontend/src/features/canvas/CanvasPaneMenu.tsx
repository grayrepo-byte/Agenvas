import { DotsNine,ImageSquare,MusicNotes,Plus,Sparkle,TextT,UploadSimple,VideoCamera } from "@phosphor-icons/react";
import type { AgentPreset } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import {
  DropdownMenu,DropdownMenuContent,DropdownMenuGroup,DropdownMenuItem,DropdownMenuPortal,
  DropdownMenuSeparator,DropdownMenuSub,DropdownMenuSubContent,DropdownMenuSubTrigger,DropdownMenuTrigger,
} from "../../shared/ui/primitives/dropdown-menu";

export type PaneCreationKind = "IMAGE" | "VIDEO" | "TEXT" | "AUDIO" | "AGENT";
const MENU_COLLISION_PADDING = 12;
const CREATION_OPTIONS = [
  { kind: "IMAGE", label: "common.image", icon: ImageSquare },
  { kind: "VIDEO", label: "common.video", icon: VideoCamera },
  { kind: "TEXT", label: "common.text", icon: TextT },
  { kind: "AUDIO", label: "common.audio", icon: MusicNotes },
] as const;

/** A controlled menu anchored to the pane pointer; Radix owns focus, nesting and edge collision. */
export function CanvasPaneMenu({ position, onClose, onAdd, agentPresets, onUpload, onArrange, uploading, creatingText, creatingAgent = false, arranging, canArrange }: {
  position: { x: number; y: number } | null;
  onClose: () => void; onAdd: (kind: PaneCreationKind, promptKey?: string) => void; agentPresets?: AgentPreset[]; onUpload: () => void; onArrange: () => void;
  uploading: boolean; creatingText: boolean; creatingAgent?: boolean; arranging: boolean; canArrange: boolean;
}) {
  useLocale();
  return <DropdownMenu open={position !== null} onOpenChange={(open) => { if (!open) onClose(); }} modal={false}>
    <DropdownMenuTrigger asChild>
      <button type="button" tabIndex={-1} aria-hidden className="pointer-events-none fixed size-px opacity-0"
        style={{ left: position?.x ?? 0, top: position?.y ?? 0 }} />
    </DropdownMenuTrigger>
    <DropdownMenuContent align="start" sideOffset={0} className="w-52 rounded-2xl p-1.5"
      aria-label={t("canvas.context.title")} onCloseAutoFocus={(event) => event.preventDefault()}
      onEscapeKeyDown={(event) => event.stopPropagation()}>
      <DropdownMenuGroup>
        <DropdownMenuSub>
          <DropdownMenuSubTrigger className="rounded-xl py-2.5"><Plus />{t("canvas.context.add")}</DropdownMenuSubTrigger>
          <DropdownMenuPortal><DropdownMenuSubContent className="w-48 rounded-2xl p-1.5" collisionPadding={MENU_COLLISION_PADDING}>
            <DropdownMenuGroup>{CREATION_OPTIONS.map(({ kind, label, icon: Icon }) =>
              <DropdownMenuItem key={kind} className="rounded-xl py-2.5" disabled={kind === "TEXT" && creatingText}
                onSelect={() => onAdd(kind)}><Icon />{t(label)}</DropdownMenuItem>)}
              {(agentPresets ?? [{ key: "agent.director", name: t("agent.defaults.director") }]).map((preset) =>
                <DropdownMenuItem key={preset.key} className="rounded-xl py-2.5" disabled={creatingAgent}
                  onSelect={() => onAdd("AGENT", preset.key)}><Sparkle />{preset.name}</DropdownMenuItem>)}
            </DropdownMenuGroup>
          </DropdownMenuSubContent></DropdownMenuPortal>
        </DropdownMenuSub>
        <DropdownMenuItem className="rounded-xl py-2.5" disabled={uploading} onSelect={onUpload}><UploadSimple />{t("canvas.context.upload")}</DropdownMenuItem>
      </DropdownMenuGroup>
      <DropdownMenuSeparator />
      <DropdownMenuGroup><DropdownMenuItem className="rounded-xl py-2.5" disabled={!canArrange || arranging}
        onSelect={onArrange}><DotsNine />{t("canvas.context.arrange")}</DropdownMenuItem></DropdownMenuGroup>
    </DropdownMenuContent>
  </DropdownMenu>;
}
