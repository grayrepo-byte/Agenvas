import { Check,GearSix } from "@phosphor-icons/react";
import { useRef,useState } from "react";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { DropdownMenu,DropdownMenuContent,DropdownMenuGroup,DropdownMenuItem,DropdownMenuTrigger } from "../../shared/ui/primitives/dropdown-menu";
import type { CanvasDisplayPreferences } from "./useCanvasDisplayPreferences";

export function CanvasSettingsMenu({ preferences, onPreferenceChange, persistenceError, onRetrySave, disabled }: {
  preferences: CanvasDisplayPreferences;
  onPreferenceChange: (key: keyof CanvasDisplayPreferences, enabled: boolean) => void;
  persistenceError: string | null;
  onRetrySave: () => void;
  disabled: boolean;
}) {
  useLocale();
  const [open, setOpen] = useState(false);
  const container = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  return <DropdownMenu open={open} onOpenChange={setOpen} modal={false}><div className="workspace-settings" ref={container}>
    <DropdownMenuTrigger asChild><Button variant="ghost" size="icon-sm" ref={trigger} type="button" className="workspace-settings-trigger" aria-label={t("画布设置")}
      title={t("画布设置")} aria-haspopup="menu" aria-expanded={open} disabled={disabled}><GearSix size={20} /></Button></DropdownMenuTrigger>
    {open ? <DropdownMenuContent aria-labelledby={undefined} onEscapeKeyDown={(event) => event.stopPropagation()} className="workspace-settings-menu" aria-label={t("画布设置")}><DropdownMenuGroup>
      <p>{t("连线显示")}</p>
      <DropdownMenuItem role="menuitemcheckbox" aria-checked={preferences.alwaysShowConnections} onSelect={(event) => { event.preventDefault(); onPreferenceChange("alwaysShowConnections", !preferences.alwaysShowConnections); }}>
        <span className="workspace-settings-check">{preferences.alwaysShowConnections ? <Check size={14} /> : null}</span>
        <span><strong>{t("始终显示连线")}</strong><small>{t("关闭后仅显示所选节点的上下级连线")}</small></span>
      </DropdownMenuItem>
      <DropdownMenuItem role="menuitemcheckbox" aria-checked={preferences.connectionFlowEnabled} onSelect={(event) => { event.preventDefault(); onPreferenceChange("connectionFlowEnabled", !preferences.connectionFlowEnabled); }}>
        <span className="workspace-settings-check">{preferences.connectionFlowEnabled ? <Check size={14} /> : null}</span>
        <span><strong>{t("连线流光效果")}</strong><small>{t("沿连线从上级流向下级")}</small></span>
      </DropdownMenuItem>
      <p>{t("自动保存在此浏览器，按账户和项目独立记忆。")}</p>
      {persistenceError ? <>
        <p role="alert">{persistenceError}</p>
        <DropdownMenuItem role="menuitem" onSelect={(event) => { event.preventDefault(); onRetrySave(); }}>{t("重试保存设置")}</DropdownMenuItem>
      </> : null}
    </DropdownMenuGroup></DropdownMenuContent> : null}
  </div></DropdownMenu>;
}
