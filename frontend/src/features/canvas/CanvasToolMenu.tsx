import { Cursor,Hand,Plus } from "@/shared/ui/icons";
import type { ReactNode } from "react";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { ToggleGroup,ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { Tooltip,TooltipContent,TooltipProvider,TooltipTrigger } from "../../shared/ui/primitives/tooltip";
import type { CanvasTool } from "./canvasInteraction";

export function CanvasToolMenu({ tool, spaceHeld, onToolChange, onAdd, children }: {
  tool: CanvasTool; spaceHeld: boolean; onToolChange: (tool: CanvasTool) => void; onAdd: () => void;
  children?: ReactNode;
}) {
  useLocale();
  // Space changes the active gesture only; releasing it restores the chosen tool.
  const activeTool = spaceHeld ? "hand" : tool;
  return <TooltipProvider delayDuration={350}>
    <div className="workspace-tool-rail">
      <ToolHint label={t("canvas.tools.addCard")}>
        <Button variant="ghost" size="icon-sm" aria-label={t("canvas.tools.addCard")}
          className="workspace-add-button" onClick={onAdd} type="button"><Plus /></Button>
      </ToolHint>
      <ToggleGroup type="single" value={activeTool} orientation="vertical" spacing={1}
        aria-label={t("canvas.tools.mode")} className="workspace-tool-modes"
        onValueChange={(value) => {
          if (value === "select" || value === "hand") onToolChange(value);
        }}>
        <ToolHint label={t("canvas.tools.selectionTool")} shortcut="V">
          <ToggleGroupItem value="select" aria-label={t("canvas.tools.selectionTool")}
            aria-keyshortcuts="V" className="workspace-tool-item"><Cursor /></ToggleGroupItem>
        </ToolHint>
        <ToolHint label={t("canvas.tools.handTool")} shortcut="Space">
          <ToggleGroupItem value="hand" aria-label={t("canvas.tools.handTool")}
            aria-keyshortcuts="Space" className="workspace-tool-item"><Hand /></ToggleGroupItem>
        </ToolHint>
      </ToggleGroup>
      {children}
    </div>
  </TooltipProvider>;
}

function ToolHint({ label, shortcut, children }: { label: string; shortcut?: string; children: ReactNode }) {
  return <Tooltip><TooltipTrigger asChild>{children}</TooltipTrigger>
    <TooltipContent side="right" sideOffset={12}>
      {label}{shortcut ? <> <kbd>{shortcut}</kbd></> : null}
    </TooltipContent>
  </Tooltip>;
}
