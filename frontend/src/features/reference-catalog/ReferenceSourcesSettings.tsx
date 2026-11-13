import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { listReferenceSources } from "../../shared/api/referenceCatalog";
import { t, useLocale } from "../../shared/i18n";
import { Panel } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { ReferenceSourcesDialog } from "./ReferenceSourcesDialog";
import { ReferenceCacheManager } from "./ReferenceCacheManager";

export function ReferenceSourcesSettings({ enabled }: { enabled: boolean }) {
  useLocale();
  const [open, setOpen] = useState(false);
  const sources = useQuery({
    queryKey: ["reference-catalog-sources"],
    queryFn: listReferenceSources,
    enabled,
    retry: false,
  });
  return (
    <Panel
      title={t("reference.sources")}
      actions={
        <Button type="button" variant="outline" disabled={!enabled} onClick={() => setOpen(true)}>
          {t("reference.manageSources")}
        </Button>
      }
    >
      {enabled ? <ReferenceCacheManager sources={sources.data?.items ?? []} /> : null}
      {open ? <ReferenceSourcesDialog onClose={() => setOpen(false)} /> : null}
    </Panel>
  );
}
