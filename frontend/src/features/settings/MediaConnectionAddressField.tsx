import { Select } from "../../shared/ui/Select";
import { Question } from "@/shared/ui/icons";
import { useId,useState } from "react";
import type { MediaConnection } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Field,FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Tooltip,TooltipContent,TooltipProvider,TooltipTrigger } from "../../shared/ui/primitives/tooltip";
import { KLING_API_ORIGIN, KLING_API_ORIGINS } from "../../shared/kling";
import { mediaConnectionAddresses } from "./mediaConnectionConfiguration";

const ORIGIN_LIMIT = 500;

/** Address guidance follows the existing adapter paths; it never rewrites user input. */
export function MediaConnectionAddressField({ platform, origin, onChange, describedBy }: {
  platform: MediaConnection["platform"]; origin: string; onChange: (value: string) => void; describedBy?: string;
}) {
  useLocale();
  const id = useId();
  const [open, setOpen] = useState(false);
  const config = mediaConnectionAddresses[platform];
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
    {platform === "KLING" ? <Select id={id} value={origin || KLING_API_ORIGIN} aria-describedby={describedBy ?? `${id}-hint`} onChange={(event) => onChange(event.target.value)}>
      {KLING_API_ORIGINS.map((address) => <option key={address} value={address}>{address}</option>)}
    </Select> : platform === "MINIMAX" ? <Select id={id} value={origin || "https://api.minimax.cn"} aria-describedby={describedBy ?? `${id}-hint`} onChange={(event) => onChange(event.target.value)}>
      <option value="https://api.minimax.cn">https://api.minimax.cn</option>
      <option value="https://api.minimax.io">https://api.minimax.io</option>
    </Select> : <Input id={id} type="url" value={config.fixed ?? origin} readOnly={Boolean(config.fixed)} required={config.required}
      maxLength={ORIGIN_LIMIT} placeholder={config.placeholder} aria-describedby={describedBy ?? `${id}-hint`}
      onChange={(event) => onChange(event.target.value)} />}
    {!describedBy ? <span id={`${id}-hint`} className="sr-only">{hint}</span> : null}
  </Field>;
}
