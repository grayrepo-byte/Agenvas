import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ArrowClockwise,ArrowsOut,Crop,Cube,Eraser,HighDefinition,ImageSquare,MagicWand,Scissors,SlidersHorizontal,Smiley,SpeakerHigh,Stack,Sun,type Icon } from "../../shared/ui/icons";
import { Link, Navigate } from "react-router";
import { HTTP_STATUS, ApiError, getCurrentUser, getMediaFunctions, getMediaSettings, updateMediaFunction,
  type MediaFunctionSetting, type MediaSettings } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { MEDIA_FUNCTIONS_QUERY_KEY, IMAGE_OPERATIONS, VIDEO_OPERATIONS, imageFunction, videoFunction, mediaFunctionChoices, mediaFunctionLabel } from "../../shared/mediaFunctions";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../shared/ui/primitives/tabs";
import { Select } from "../../shared/ui/Select";
import { Input } from "../../shared/ui/primitives/input";
import { ToggleGroup, ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../../shared/ui/primitives/table";
import { mediaDisplayName } from "../../shared/mediaDisplayNames";
import "./MediaSettingsPage.css";
import "./MediaFunctionSettingsPage.css";

const FUNCTION_TABS = ["image", "video"] as const;
type FunctionTab = typeof FUNCTION_TABS[number];
const FUNCTION_FILTERS = ["all", "ready", "unconfigured"] as const;
type FunctionFilter = typeof FUNCTION_FILTERS[number];
const FUNCTION_ICONS: Record<MediaFunctionSetting["operation"], Icon> = {
  IMAGE_SMART_EDIT: MagicWand, IMAGE_RELIGHT: Sun, IMAGE_OUTPAINT: ArrowsOut,
  IMAGE_THREE_VIEW: Cube, IMAGE_LAYER_SPLIT: Stack, IMAGE_EXPRESSION_EDIT: Smiley,
  IMAGE_REMOVE_BACKGROUND: Scissors, IMAGE_OBJECT_REMOVE: Eraser, IMAGE_VIEW_ANGLE: Cube,
  IMAGE_DEPTH_MAP: Stack, IMAGE_UPSCALE: HighDefinition, IMAGE_RESIZE: ArrowsOut,
  IMAGE_CROP: Crop, IMAGE_ROTATE: ArrowClockwise, IMAGE_FLIP_HORIZONTAL: ImageSquare,
  IMAGE_FLIP_VERTICAL: ImageSquare, VIDEO_UPSCALE: HighDefinition, VIDEO_DEPTH_MAP: Stack,
  VIDEO_EXTRACT_AUDIO: SpeakerHigh,
};

function FunctionRow({ setting, settings, hidden }: { setting: MediaFunctionSetting; settings: MediaSettings; hidden: boolean }) {
  const client = useQueryClient();
  const [draft, setDraft] = useState({ capabilityId: setting.capabilityId ?? "", version: setting.version });
  const [saved, setSaved] = useState(false);
  const choices = mediaFunctionChoices(settings, setting.operation);
  const choiceGroups = settings.connections.map((connection) => ({ connection,
    choices: choices.filter(({ capability }) => connection.capabilities.some((entry) => entry.id === capability.id)),
  })).filter((group) => group.choices.length > 0);
  const dirty = draft.capabilityId !== (setting.capabilityId ?? "");
  const stale = draft.version !== setting.version;
  const save = useMutation({
    mutationFn: () => updateMediaFunction(setting.operation, draft.version, draft.capabilityId || null),
    onSuccess: (result) => {
      client.setQueryData(MEDIA_FUNCTIONS_QUERY_KEY, result);
      const updated = result.find((entry) => entry.operation === setting.operation);
      if (updated) setDraft({ capabilityId: updated.capabilityId ?? "", version: updated.version });
      setSaved(true);
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) void client.invalidateQueries({ queryKey: MEDIA_FUNCTIONS_QUERY_KEY });
    },
  });
  const label = mediaFunctionLabel(setting.operation);
  const boundChoice = choices.find(({ capability }) => capability.id === setting.capabilityId);
  const selectedConnection = settings.connections.find((connection) => connection.capabilities.some((capability) => capability.id === draft.capabilityId));
  const service = selectedConnection ? mediaDisplayName(selectedConnection) : "—";
  const description = setting.operation.startsWith("IMAGE_") ? label : setting.operation === "VIDEO_UPSCALE" ? t("media.functions.upscaleHint")
    : setting.operation === "VIDEO_DEPTH_MAP" ? t("media.functions.depthHint") : t("media.functions.audioHint");
  const FunctionIcon = FUNCTION_ICONS[setting.operation] ?? SlidersHorizontal;
  return <TableBody className="media-function-entry" aria-label={label} hidden={hidden}>
    <TableRow className="media-function-row" aria-label={label}>
    <TableCell><div className="media-table-name"><FunctionIcon aria-hidden /><strong title={description}>{label}</strong></div></TableCell>
    <TableCell data-label={t("settings.mediaWorkspace.serviceColumn")}><span className="media-cell-text" title={service}>{service}</span></TableCell>
    <TableCell className="media-function-method"><FieldGroup className="media-function-controls">
      <Field data-disabled={save.isPending || undefined}>
        <FieldLabel className="sr-only">{t("media.functions.method")}</FieldLabel>
        <Select density="compact" value={draft.capabilityId} disabled={save.isPending}
        aria-label={`${label} · ${t("media.functions.method")}`} onChange={(event) => {
          setDraft({ ...draft, capabilityId: event.target.value }); setSaved(false); save.reset();
        }}>
        <option value="">{t("media.functions.unconfigured")}</option>
        {choiceGroups.map(({ connection, choices: connectionChoices }) => <optgroup key={connection.id} label={mediaDisplayName(connection)}>
          {connectionChoices.map(({ capability }) => <option key={capability.id} value={capability.id}>{mediaDisplayName(capability)}</option>)}
        </optgroup>)}
        {draft.capabilityId && !choices.some(({ capability }) => capability.id === draft.capabilityId)
          ? <option value={draft.capabilityId} disabled>{t("media.functions.unavailable")}</option> : null}
      </Select></Field>
    </FieldGroup></TableCell>
    <TableCell className="media-function-choice-count" data-label={t("media.functions.choicesColumn")} title={t("media.functions.choiceCount", { "0": choices.length })}><span className="ui-muted">{choices.length}</span></TableCell>
    <TableCell className="media-function-status"><span role={saved ? "status" : undefined}><StatusBadge tone={dirty ? "warning" : saved || boundChoice ? "success" : setting.capabilityId ? "warning" : "neutral"}>{saved ? t("media.functions.saved") : dirty ? t("common.unsavedChanges") : boundChoice ? t("media.functions.readyStatus") : setting.capabilityId ? t("media.functions.unavailableStatus") : t("media.functions.unconfiguredStatus")}</StatusBadge></span></TableCell>
    <TableCell><div className="media-table-actions media-function-actions">
      <Button variant={dirty ? "default" : "outline"} type="button" size="sm" disabled={!dirty || stale || save.isPending}
        onClick={() => { setSaved(false); save.mutate(); }}>{save.isPending ? t("common.savingProgress") : t("common.saveConfig")}</Button>
    </div></TableCell>
    </TableRow>
    {save.error || stale ? <TableRow className="media-function-feedback-row"><TableCell colSpan={6}><div className="media-function-feedback">
      {save.error ? <Notice tone="danger">{save.error.message}</Notice> : null}
      {stale ? <Notice tone="warning">{t("media.functions.changed")}<Button variant="outline" size="sm" type="button" disabled={save.isPending}
        onClick={() => { setDraft({ capabilityId: setting.capabilityId ?? "", version: setting.version }); save.reset(); setSaved(false); }}>{t("common.refresh")}</Button></Notice> : null}
    </div></TableCell></TableRow> : null}
  </TableBody>;
}

export function MediaFunctionSettingsPage() {
  useLocale();
  const [selectedTab, setSelectedTab] = useState<FunctionTab>("image");
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState<FunctionFilter>("all");
  const session = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const functions = useQuery({ queryKey: MEDIA_FUNCTIONS_QUERY_KEY, queryFn: getMediaFunctions, enabled: session.isSuccess, retry: false });
  const settings = useQuery({ queryKey: ["settings", "media"], queryFn: getMediaSettings, enabled: session.isSuccess, retry: false });
  const loadError = session.error ?? functions.error ?? settings.error;
  const groups = [
    { tab: "image", operations: IMAGE_OPERATIONS.map(imageFunction) },
    { tab: "video", operations: VIDEO_OPERATIONS.map(videoFunction) },
  ].map((group) => ({ ...group, entries: group.operations.flatMap((operation) => {
    const setting = functions.data?.find((entry) => entry.operation === operation);
    if (!setting || !settings.data) return [];
    const choices = mediaFunctionChoices(settings.data, operation);
    const ready = choices.some(({ capability }) => capability.id === setting.capabilityId);
    const keywords = [mediaFunctionLabel(operation), ...choices.map(({ label }) => label)].join(" ").toLocaleLowerCase();
    const visible = (!search.trim() || keywords.includes(search.trim().toLocaleLowerCase())) && (filter === "all" || (filter === "ready" ? ready : !ready));
    return [{ setting, visible }];
  }) }));
  const activeEntries = groups.find((group) => group.tab === selectedTab)?.entries ?? [];
  if (loadError instanceof ApiError && loadError.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  return <PageShell title={t("media.functions.title")} description={t("media.functions.description")}
    actions={<Button asChild variant="outline"><Link to="/settings/media">{t("ui.pageShell.mediaSettings")}</Link></Button>}>
    {!loadError && (functions.isPending || settings.isPending) ? <LoadingState label={t("app.pageLoading")} /> : null}
    {loadError ? <Notice tone="danger"><p>{loadError.message}</p>
      <Button variant="outline" type="button" onClick={() => { void session.refetch(); void functions.refetch(); void settings.refetch(); }}>{t("common.retry")}</Button></Notice> : null}
    {functions.data && settings.data ? <Tabs className="media-function-workspace media-catalog" value={selectedTab} onValueChange={(value) => {
      const tab = FUNCTION_TABS.find((entry) => entry === value);
      if (tab) setSelectedTab(tab);
    }}>
      <div className="media-catalog-topline"><TabsList variant="line" aria-label={t("media.functions.title")}>
        <TabsTrigger value="image">{t("media.functions.imageTools")}</TabsTrigger>
        <TabsTrigger value="video">{t("media.functions.videoTools")}</TabsTrigger>
      </TabsList><span className="media-function-results-summary" role="status">{t("media.functions.filteredCount", { "0": activeEntries.filter(({ visible }) => visible).length, "1": activeEntries.length })}</span></div>
      <div className="media-function-toolbar">
        <FieldGroup className="media-function-search"><Field><FieldLabel className="sr-only" htmlFor="media-function-search">{t("media.functions.searchLabel")}</FieldLabel>
          <Input id="media-function-search" type="search" value={search} onChange={(event) => setSearch(event.target.value)} placeholder={t("media.functions.searchPlaceholder")} /></Field></FieldGroup>
        <ToggleGroup type="single" variant="outline" size="sm" value={filter} aria-label={t("media.functions.filterLabel")} onValueChange={(value) => {
          const next = FUNCTION_FILTERS.find((entry) => entry === value); if (next) setFilter(next);
        }}>
          <ToggleGroupItem value="all">{t("media.functions.filterAll")}</ToggleGroupItem>
          <ToggleGroupItem value="ready">{t("media.functions.filterReady")}</ToggleGroupItem>
          <ToggleGroupItem value="unconfigured">{t("media.functions.filterUnconfigured")}</ToggleGroupItem>
        </ToggleGroup>
        {search || filter !== "all" ? <Button variant="ghost" size="sm" type="button" onClick={() => { setSearch(""); setFilter("all"); }}>{t("media.functions.resetFilters")}</Button> : null}
      </div>
      <p className="media-function-catalog-hint ui-muted">{t("media.functions.catalogHint")}</p>
      {/* Keep both panels mounted so switching categories preserves drafts and save feedback. */}
      {groups.map((group) => {
      const { entries } = group;
      const visibleCount = entries.filter(({ visible }) => visible).length;
      return <TabsContent key={group.tab} value={group.tab} forceMount hidden={selectedTab !== group.tab} className="media-function-panel">
      {group.tab === "image" ? <p className="media-function-catalog-hint ui-muted">{t("media.functions.imageHint")}</p> : null}
      <div className="media-table-scroll media-function-list" tabIndex={0} role="region" aria-label={t(group.tab === "image" ? "media.functions.imageTools" : "media.functions.videoTools")}>
      <Table className="media-settings-table media-function-table" aria-label={t(group.tab === "image" ? "media.functions.imageTools" : "media.functions.videoTools")}>
      <TableHeader><TableRow>
        <TableHead scope="col">{t("media.functions.nameColumn")}</TableHead>
        <TableHead scope="col">{t("settings.mediaWorkspace.serviceColumn")}</TableHead>
        <TableHead scope="col">{t("media.functions.method")}</TableHead>
        <TableHead scope="col">{t("media.functions.choicesColumn")}</TableHead>
        <TableHead scope="col">{t("settings.mediaSettings.status")}</TableHead>
        <TableHead scope="col">{t("settings.mediaSettings.operations")}</TableHead>
      </TableRow></TableHeader>
      {entries.map(({ setting, visible }) => <FunctionRow key={setting.operation} setting={setting} settings={settings.data} hidden={!visible} />)}
      {visibleCount === 0 ? <TableBody><TableRow><TableCell colSpan={6}><EmptyState title={t("media.functions.noMatch")} description={t("media.functions.noMatchHint")} /></TableCell></TableRow></TableBody> : null}
      </Table></div>
    </TabsContent>; })}
    </Tabs> : null}
    <p className="ui-muted">{t("media.functions.compatibilityHint")}</p>
  </PageShell>;
}
