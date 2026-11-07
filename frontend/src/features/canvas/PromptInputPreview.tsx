import { t } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Popover, PopoverContent, PopoverTrigger } from "../../shared/ui/primitives/popover";
import type { CanvasPromptInput } from "./canvasPromptInputs";
import "./CanvasPromptInputs.css";

/** Header disclosure keeps shared sources out of the editable prompt area. */
export function PromptInputPreview({ inputs, combinedPrompt }: { inputs: CanvasPromptInput[]; combinedPrompt: string }) {
  if (!inputs.length) return null;
  return <Popover><PopoverTrigger asChild><Button type="button" variant="ghost" className="media-draft-tab">
    {t("media.promptInputs.count", { "0": inputs.length })}
  </Button></PopoverTrigger><PopoverContent className="canvas-prompt-inputs nodrag nopan nowheel" side="top" align="start">
    <p>{t("media.promptInputs.hint")}</p>
    <ol>{inputs.map((input) => <li key={input.connectionId}>
      <strong>{input.title}</strong><pre>{input.text.trim() || t("media.promptInputs.empty")}</pre>
    </li>)}</ol>
    <strong>{t("media.promptInputs.combined")}</strong>
    <pre aria-label={t("media.promptInputs.combined")}>{combinedPrompt || t("media.promptInputs.empty")}</pre>
  </PopoverContent></Popover>;
}
