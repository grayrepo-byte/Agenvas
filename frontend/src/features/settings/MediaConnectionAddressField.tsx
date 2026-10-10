import { Question } from "@/shared/ui/icons";
import { useId,useState } from "react";
import type { MediaConnection } from "../../shared/api/client";
import { t,useLocale,type MessageKey } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Field,FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Tooltip,TooltipContent,TooltipProvider,TooltipTrigger } from "../../shared/ui/primitives/tooltip";

const ORIGIN_LIMIT = 500;
const ADDRESS_FIELDS: Partial<Record<MediaConnection["platform"], {
  label: MessageKey; hint: MessageKey; placeholder?: string; fixed?: string; required?: boolean;
}>> = {
  COMFYUI: { label: "settings.mediaSettings.comfyUrl", hint: "settings.mediaSettings.comfyAddressHint", placeholder: "http://127.0.0.1:8188", required: true },
  RUNNINGHUB: { label: "settings.mediaSettings.runningHubApi", hint: "settings.mediaSettings.runningHubAddressHint", placeholder: "https://www.runninghub.ai" },
  OPENAI: { label: "settings.mediaSettings.baseUrl", hint: "settings.mediaSettings.openAiAddressHint", placeholder: "https://api.openai.com/v1" },
  GOOGLE: { label: "settings.mediaSettings.baseUrl", hint: "settings.mediaSettings.googleAddressHint", placeholder: "https://generativelanguage.googleapis.com" },
  ARK: { label: "settings.mediaSettings.fixedApiUrl", hint: "settings.mediaSettings.arkAddressHint", fixed: "https://ark.cn-beijing.volces.com/api/v3" },
  VOLCENGINE: { label: "settings.mediaSettings.fixedApiUrl", hint: "settings.mediaSettings.volcengineAddressHint", fixed: "https://openspeech.bytedance.com/api/v3/tts/create" },
  AUTODL: { label: "settings.mediaSettings.fixedApiUrl", hint: "settings.mediaSettings.autoDlAddressHint", fixed: "https://autodl.art/api/v1/comfyui/comfyui_workflow" },
};

/** Address guidance follows the existing adapter paths; it never rewrites user input. */
export function MediaConnectionAddressField({ platform, origin, onChange, describedBy }: {
  platform: MediaConnection["platform"]; origin: string; onChange: (value: string) => void; describedBy?: string;
}) {
  useLocale();
  const id = useId();
  const [open, setOpen] = useState(false);
  const config = ADDRESS_FIELDS[platform];
  if (!config) return null;
  const hint = t(config.hint);
  return <Field className="gap-2">
    <div className="flex items-center gap-1">
      <FieldLabel className="ui-field" htmlFor={id}>{t(config.label)}</FieldLabel>
      <TooltipProvider><Tooltip key={platform} open={open} onOpenChange={setOpen}>
        <TooltipTrigger asChild onClick={(event) => { event.preventDefault(); setOpen(true); }}>
          <Button type="button" variant="ghost" size="icon-xs" className="size-4" aria-label={t("settings.mediaSettings.addressHelp")}><Question /></Button>
        </TooltipTrigger>
        <TooltipContent className="media-connection-address-tooltip" side="top"><p>{hint}</p></TooltipContent>
      </Tooltip></TooltipProvider>
    </div>
    <Input id={id} type="url" value={config.fixed ?? origin} readOnly={Boolean(config.fixed)} required={config.required}
      maxLength={ORIGIN_LIMIT} placeholder={config.placeholder} aria-describedby={describedBy ?? `${id}-hint`}
      onChange={(event) => onChange(event.target.value)} />
    {!describedBy ? <span id={`${id}-hint`} className="sr-only">{hint}</span> : null}
  </Field>;
}
