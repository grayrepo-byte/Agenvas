import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ArrowClockwise,ArrowsOut,Crop,Cube,Eraser,HighDefinition,ImageSquare,MagicWand,Scissors,SlidersHorizontal,Smiley,SpeakerHigh,Stack,Sun,type Icon } from "../../shared/ui/icons";
import { Link, Navigate } from "react-router";
import { HTTP_STATUS, ApiError, getCurrentUser, getMediaFunctions, getMediaSettings, updateMediaFunction,
  type MediaFunctionSetting, type MediaSettings } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { MEDIA_FUNCTIONS_QUERY_KEY, IMAGE_OPERATIONS, VIDEO_OPERATIONS, imageFunction, videoFunction, mediaFunctionChoices, mediaFunctionLabel } from "../../shared/mediaFunctions";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../shared/ui/primitives/tabs";
import { Select } from "../../shared/ui/Select";
import { Input } from "../../shared/ui/primitives/input";
import { ToggleGroup, ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
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
  const FunctionIcon = FUNCTION_ICONS[setting.operation] ?? SlidersHorizontal;
  return <section className="media-function-row" aria-label={label} hidden={hidden}>
    <div className="media-function-summary"><span className="media-function-icon" aria-hidden><FunctionIcon weight="duotone" /></span><div><h3>{label}</h3>
      {!setting.operation.startsWith("IMAGE_") ? <p className="ui-muted">{setting.operation === "VIDEO_UPSCALE" ? t("media.functions.upscaleHint")
        : setting.operation === "VIDEO_DEPTH_MAP" ? t("media.functions.depthHint") : t("media.functions.audioHint")}</p> : null}
      <div className="media-function-status"><StatusBadge tone={dirty ? "warning" : boundChoice ? "success" : setting.capabilityId ? "warning" : "neutral"}>{dirty ? t("common.unsavedChanges") : boundChoice ? t("media.functions.readyStatus") : setting.capabilityId ? t("media.functions.unavailableStatus") : t("media.functions.unconfiguredStatus")}</StatusBadge>
        <span className="ui-muted">{t("media.functions.choiceCount", { "0": choices.length })}</span></div>
      {saved ? <p role="status" className="ui-muted">{t("media.functions.saved")}</p> : null}
    </div></div>
    <FieldGroup className="media-function-controls">
      <Field data-disabled={save.isPending || undefined}>
        <FieldLabel className="sr-only">{t("media.functions.method")}</FieldLabel>
        <Select density="compact" value={draft.capabilityId} disabled={save.isPending}
        aria-label={`${label} · ${t("media.functions.method")}`} onChange={(event) => {
          setDraft({ ...draft, capabilityId: event.target.value }); setSaved(false); save.reset();
        }}>
        <option value="">{t("media.functions.unconfigured")}</option>
        {choices.map(({ capability, label: choiceLabel }) => <option key={capability.id} value={capability.id}>{choiceLabel}</option>)}
        {draft.capabilityId && !choices.some(({ capability }) => capability.id === draft.capabilityId)
          ? <option value={draft.capabilityId} disabled>{t("media.functions.unavailable")}</option> : null}
      </Select></Field>
      <Button variant={dirty ? "default" : "outline"} type="button" size="sm" disabled={!dirty || stale || save.isPending}
        onClick={() => { setSaved(false); save.mutate(); }}>{save.isPending ? t("common.savingProgress") : t("common.saveConfig")}</Button>
    </FieldGroup>
    {save.error || stale ? <div className="media-function-feedback">
      {save.error ? <Notice tone="danger">{save.error.message}</Notice> : null}
      {stale ? <Notice tone="warning">{t("media.functions.changed")}<Button variant="outline" size="sm" type="button" disabled={save.isPending}
        onClick={() => { setDraft({ capabilityId: setting.capabilityId ?? "", version: setting.version }); save.reset(); setSaved(false); }}>{t("common.refresh")}</Button></Notice> : null}
    </div> : null}
  </section>;
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
  if (loadError instanceof ApiError && loadError.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  return <PageShell title={t("media.functions.title")} description={t("media.functions.description")}
    actions={<Link className="secondary-button" to="/settings/media">{t("ui.pageShell.mediaSettings")}</Link>}>
    {!loadError && (functions.isPending || settings.isPending) ? <LoadingState label={t("app.pageLoading")} /> : null}
    {loadError ? <Notice tone="danger"><p>{loadError.message}</p>
      <Button variant="outline" type="button" onClick={() => { void session.refetch(); void functions.refetch(); void settings.refetch(); }}>{t("common.retry")}</Button></Notice> : null}
    {functions.data && settings.data ? <Tabs className="media-function-workspace" value={selectedTab} onValueChange={(value) => {
      const tab = FUNCTION_TABS.find((entry) => entry === value);
      if (tab) setSelectedTab(tab);
    }}>
      <TabsList aria-label={t("media.functions.title")}>
        <TabsTrigger value="image">{t("media.functions.imageTools")}</TabsTrigger>
        <TabsTrigger value="video">{t("media.functions.videoTools")}</TabsTrigger>
      </TabsList>
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
      </div>
      <p className="media-function-catalog-hint ui-muted">{t("media.functions.catalogHint")}</p>
      {/* Keep both panels mounted so switching categories preserves drafts and save feedback. */}
      {[
      { tab: "image", operations: IMAGE_OPERATIONS.map(imageFunction) },
      { tab: "video", operations: VIDEO_OPERATIONS.map(videoFunction) },
    ].map((group) => {
      const entries = group.operations.flatMap((operation) => {
        const setting = functions.data.find((entry) => entry.operation === operation);
        if (!setting) return [];
        const choices = mediaFunctionChoices(settings.data, operation);
        const ready = choices.some(({ capability }) => capability.id === setting.capabilityId);
        const keywords = [mediaFunctionLabel(operation), ...choices.map(({ label }) => label)].join(" ").toLocaleLowerCase();
        const visible = (!search.trim() || keywords.includes(search.trim().toLocaleLowerCase())) && (filter === "all" || (filter === "ready" ? ready : !ready));
        return [{ setting, visible }];
      });
      const visibleCount = entries.filter(({ visible }) => visible).length;
      return <TabsContent key={group.tab} value={group.tab} forceMount hidden={selectedTab !== group.tab} className="media-function-panel">
      <div className="media-function-results-summary"><span role="status">{t("media.functions.filteredCount", { "0": visibleCount, "1": entries.length })}</span>
        {search || filter !== "all" ? <Button variant="ghost" size="sm" type="button" onClick={() => { setSearch(""); setFilter("all"); }}>{t("media.functions.resetFilters")}</Button> : null}</div>
      {group.tab === "image" ? <p className="media-function-catalog-hint ui-muted">{t("media.functions.imageHint")}</p> : null}
      <Panel className="media-function-list">
      {entries.map(({ setting, visible }) => <FunctionRow key={setting.operation} setting={setting} settings={settings.data} hidden={!visible} />)}
      {visibleCount === 0 ? <EmptyState title={t("media.functions.noMatch")} description={t("media.functions.noMatchHint")} /> : null}
      </Panel>
    </TabsContent>; })}
    </Tabs> : null}
    <p className="ui-muted">{t("media.functions.compatibilityHint")}</p>
  </PageShell>;
}
