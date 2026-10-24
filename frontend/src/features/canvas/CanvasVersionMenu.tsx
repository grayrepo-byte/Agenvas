import { Check,WarningCircle } from "@/shared/ui/icons";
import { cn } from "cn";
import { useId,type ComponentProps,type ReactNode } from "react";
import { getFormatLocale,t } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Alert,AlertDescription } from "../../shared/ui/primitives/alert";
import { Badge } from "../../shared/ui/primitives/badge";
import { DropdownMenuContent,DropdownMenuItem,DropdownMenuLabel } from "../../shared/ui/primitives/dropdown-menu";
import { Separator } from "../../shared/ui/primitives/separator";
import "./CanvasVersionMenu.css";

const VERSION_SUMMARY_LIMIT = 240;
const versionDateFormatters = new Map<string, Intl.DateTimeFormat>();

function formatVersionDate(value: string) {
  const locale = getFormatLocale();
  let formatter = versionDateFormatters.get(locale);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat(locale, {
      month: "short", day: "numeric", hour: "2-digit", minute: "2-digit",
    });
    versionDateFormatters.set(locale, formatter);
  }
  return formatter.format(new Date(value));
}

export const VERSION_SOURCE_LABELS = {
  USER: "artifacts.versions.sourceUser", AGENT: "artifacts.versions.sourceAgent",
  TASK: "artifacts.versions.sourceTask", EXTERNAL_TOOL: "artifacts.versions.sourceExternalTool",
} as const;

/** Common presentation only: each node kind retains its own version numbers and write command. */
export function CanvasVersionMenuContent({ heading, count, footer, className, children, ...props }:
  ComponentProps<typeof DropdownMenuContent> & { heading: string; count?: number; footer?: ReactNode }) {
  return <DropdownMenuContent align="end" sideOffset={8} collisionPadding={12}
    aria-labelledby={undefined} onEscapeKeyDown={(event) => event.stopPropagation()} role="menu"
    {...props} className={cn("canvas-version-menu", className)}>
    <DropdownMenuLabel className="canvas-version-header">
      <span>{heading}</span>
      {count !== undefined ? <Badge variant="secondary" className="canvas-version-count">{t("media.versions.versionCount", { "0": count })}</Badge> : null}
    </DropdownMenuLabel>
    <Separator className="canvas-version-separator" />
    {children}
    {footer ? <><Separator className="canvas-version-separator" /><div className="canvas-version-footer">{footer}</div></> : null}
  </DropdownMenuContent>;
}

export function CanvasVersionMenuOption({ versionNo, selected, summary, source, createdAt, preview, className, ...props }:
  Omit<ComponentProps<typeof DropdownMenuItem>, "children"> & {
    versionNo: number; selected: boolean; summary: string; source?: string; createdAt: string; preview?: ReactNode;
  }) {
  const descriptionId = useId();
  // Keep full immutable content in the query cache, not in every menu row and native tooltip.
  const compactSummary = summary.replace(/\s+/gu, " ").trim();
  const previewSummary = compactSummary.length > VERSION_SUMMARY_LIMIT
    ? `${compactSummary.slice(0, VERSION_SUMMARY_LIMIT - 1).trimEnd()}…` : compactSummary;
  return <DropdownMenuItem variant="rich" role="menuitem" aria-current={selected ? "true" : undefined}
    aria-describedby={`${descriptionId}-summary ${descriptionId}-metadata`}
    aria-label={`v${versionNo} ${selected ? t("artifacts.versions.selected") : t("artifacts.versions.select")}`}
    {...props} className={cn("canvas-version-option", className)}>
    {preview}
    <span className="canvas-version-copy">
      <span className="canvas-version-heading"><span className="canvas-version-number">v{versionNo}</span>
        {selected ? <Badge variant="outline" className="canvas-version-selected"><Check aria-hidden="true" />{t("artifacts.versions.selected")}</Badge> : null}
      </span>
      <span className="canvas-version-summary" id={`${descriptionId}-summary`} title={previewSummary}>{previewSummary}</span>
      <span className="canvas-version-meta" id={`${descriptionId}-metadata`}>
        {source ? <span>{source}</span> : null}
        <time dateTime={createdAt}>{formatVersionDate(createdAt)}</time>
      </span>
    </span>
  </DropdownMenuItem>;
}

export function CanvasVersionMenuStatus({ label }: { label: string }) {
  return <div className="canvas-version-status"><LoadingState compact label={label} /></div>;
}

export function CanvasVersionMenuError({ message, action }: { message: string; action?: ReactNode }) {
  return <Alert variant="destructive" className="canvas-version-alert">
    <WarningCircle aria-hidden="true" /><AlertDescription>{message}{action}</AlertDescription>
  </Alert>;
}

export function CanvasVersionMenuNotice({ children }: { children: ReactNode }) {
  return <Alert className="canvas-version-notice" role="note">
    <WarningCircle aria-hidden="true" /><AlertDescription>{children}</AlertDescription>
  </Alert>;
}
