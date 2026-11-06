import { useState } from "react";
import { t, useLocale } from "../../shared/i18n";
import { Panel } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { ReferenceSourcesDialog } from "./ReferenceSourcesDialog";

export function ReferenceSourcesSettings({ enabled }: { enabled: boolean }) {
  useLocale(); const [open, setOpen] = useState(false);
  return <Panel title={t("reference.sources")} description={t("reference.manualSyncHint")} actions={<Button type="button" variant="outline" disabled={!enabled} onClick={() => setOpen(true)}>{t("reference.manageSources")}</Button>}>
    <p>{t("reference.permissionHint")}</p>{open ? <ReferenceSourcesDialog onClose={() => setOpen(false)} /> : null}
  </Panel>;
}
