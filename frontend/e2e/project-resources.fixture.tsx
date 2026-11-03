import { QueryClientProvider } from "@tanstack/react-query";
import { createRoot } from "react-dom/client";
import { createQueryClient } from "../src/app/queryClient";
import { ProjectResourceBrowser } from "../src/features/canvas/ProjectResourceBrowser";
import type { ResourceResult } from "../src/shared/api/client";
import { resolveLocale,setLocale,t } from "../src/shared/i18n";
import { Tabs,TabsContent,TabsList,TabsTrigger } from "../src/shared/ui/primitives/tabs";
import "../src/styles.css";

// Independently generated content. Native media uses the existing serve-only synthetic namespace.
const projectId = "synthetic-project-resources";
const parameters = new URL(location.href).searchParams;
setLocale(resolveLocale(parameters.get("locale")) ?? "zh");
const RESULT_KINDS = ["IMAGE", "VIDEO", "AUDIO", "TEXT"] as const;
const results: ResourceResult[] = RESULT_KINDS.flatMap((kind, index) => {
  return [2, 1].map((versionNo) => ({ projectId, projectName: "Synthetic project", artifactId: `${kind}-identity`,
    versionId: `${kind}-${versionNo}`, versionNo, kind, title: kind === "IMAGE" ? "海边的房子 · 草图与光影" : kind === "VIDEO" ? "日落前的镜头" : kind === "AUDIO" ? "海风与旁白" : "开场文案",
    createdAt: "2026-10-24T09:00:00Z", content: kind === "TEXT"
      ? { format: "PLAIN_TEXT" as const, text: `第 ${versionNo} 份文字结果。\n海边的小屋，清晨的风。\n此内容仅用于合成测试。` }
      : { sourceType: "UPLOAD" as const, assetId: `synthetic-library-${[0, 5, 3][index] ?? 0}` },
  }));
});
const writes: unknown[] = [];
const unexpected: string[] = [];
let failPlacement = true;
window.fetch = async (input, init) => {
  const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url, location.origin);
  if (url.pathname === "/api/v1/auth/csrf") return Response.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" });
  if (url.pathname === `/api/v1/projects/${projectId}/resources`) return Response.json({ items: parameters.has("empty") ? []
    : results.filter((resource) => resource.title.includes(url.searchParams.get("query") ?? "")), nextCursor: null });
  if (url.pathname === `/api/v1/projects/${projectId}/canvas/commands`) {
    writes.push(JSON.parse(String(init?.body)));
    if (failPlacement) { failPlacement = false; return Response.json({ detail: "Synthetic temporary failure" }, { status: 503 }); }
    return Response.json({ items: [] });
  }
  if (url.pathname === `/api/v1/projects/${projectId}/artifacts`) return Response.json({ id: "synthetic-text-copy" });
  unexpected.push(url.pathname); return new Response(null, { status: 404 });
};
Object.assign(window, { __projectResources: { evidence: () => ({ writes,unexpected }) } });
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={createQueryClient()}>
  <main className="workspace-shell"><aside className="workspace-drawer" aria-label={t("canvas.workspace.resourceTitle")}>
    <div className="workspace-drawer-heading"><h2>{t("canvas.workspace.resourceTitle")}</h2></div>
    <Tabs defaultValue="PROJECT"><TabsList><TabsTrigger value="PROJECT">{t("canvas.workspace.resourceTitle")}</TabsTrigger>
      <TabsTrigger value="LIBRARY">{t("library.title")}</TabsTrigger></TabsList>
      <TabsContent value="PROJECT"><ProjectResourceBrowser projectId={projectId} position={() => ({ x: 160, y: 120 })}
        zIndex={0} onPlaced={() => { document.documentElement.dataset.placed = "true"; }} /></TabsContent>
    </Tabs>
  </aside></main>
</QueryClientProvider>);
