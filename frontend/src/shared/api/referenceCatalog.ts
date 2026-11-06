import type { components, paths } from "./schema";
import { readJson, writeJson } from "./client";
import { t } from "../i18n";

export type ReferenceKind = components["schemas"]["ReferenceCatalogKind"];
export type ReferenceVariant = components["schemas"]["ReferenceCatalogVariant"];
export type ReferenceEntry = components["schemas"]["ReferenceCatalogEntry"];
export type ReferenceSource = components["schemas"]["ReferenceCatalogSource"];
export type CreateReferenceSource = components["schemas"]["CreateReferenceCatalogSource"];
export type ReferenceImportRequest = components["schemas"]["ReferenceCatalogImportRequest"];
export type ReferenceImport = components["schemas"]["ReferenceCatalogImport"];
export type ReferenceFilters = NonNullable<paths["/api/v1/reference-catalog"]["get"]["parameters"]["query"]>;
export function listReferenceSources(): Promise<components["schemas"]["ReferenceCatalogSources"]> { return readJson("/api/v1/reference-catalog/sources", t("reference.readFailed")); }
export function listReferenceEntries(filters: ReferenceFilters): Promise<components["schemas"]["ReferenceCatalogPage"]> {
  const query = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => { if (value !== undefined && value !== "") query.set(key, String(value)); });
  return readJson(`/api/v1/reference-catalog?${query}`, t("reference.readFailed"));
}
export function addReferenceSource(request: CreateReferenceSource): Promise<ReferenceSource> { return writeJson("/api/v1/settings/reference-catalog/sources", { method: "POST", body: JSON.stringify(request) }); }
export function updateReferenceSource(id: string, request: { expectedVersion: number; enabled: boolean; imageDistributionApproved: boolean }): Promise<ReferenceSource> { return writeJson(`/api/v1/settings/reference-catalog/sources/${encodeURIComponent(id)}`, { method: "PATCH", body: JSON.stringify(request) }); }
export function syncReferenceSource(id: string, expectedVersion: number): Promise<components["schemas"]["ReferenceCatalogSyncResult"]> { return writeJson(`/api/v1/settings/reference-catalog/sources/${encodeURIComponent(id)}/sync`, { method: "POST", body: JSON.stringify({ expectedVersion }) }); }
export function importReference(projectId: string, request: ReferenceImportRequest): Promise<ReferenceImport> { return writeJson(`/api/v1/projects/${encodeURIComponent(projectId)}/reference-catalog/import`, { method: "POST", body: JSON.stringify(request) }); }
/** Images are rendered only through same-origin catalog delivery, never remote hotlinks. */
export function referencePreviewUrl(variant: ReferenceVariant): string | undefined { return variant.importAllowed && variant.availability !== "LINK_ONLY" && variant.previewUrl?.startsWith("/api/v1/reference-catalog/") ? variant.previewUrl : undefined; }
export function referenceExternalUrl(value?: string | null): string | undefined { try { const url = new URL(value ?? ""); return url.protocol === "https:" ? url.href : undefined; } catch { return undefined; } }
