import { mediaDisplayName } from "../../shared/mediaDisplayNames";
import { Field, FieldLabel, FieldGroup, FieldSet } from "../../shared/ui/primitives/field";
import { ArrowLeft,ArrowRight,CheckCircle,DotsThree,HardDrives,ImageSquare,MagnifyingGlass,MusicNotes,PlugsConnected,Plus,Sparkle,VideoCamera } from "@/shared/ui/icons";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { cn } from "cn";
import { useEffect,useId,useRef,useState,type FormEvent } from "react";
import { Navigate } from "react-router";
import {
HTTP_STATUS,ApiError,deleteMediaCapability,createMediaCapability,createMediaConnection,getCurrentUser,
getMediaSettings,getKlingProfiles,getNativeMediaProfiles,setMediaDefault,updateMediaCapability,
updateMediaConnection,
type MediaCapability,type MediaConnection,type MediaSettings,type CreateMediaConnectionRequest,
} from "../../shared/api/client";
import { MINIMAX_H3_ADAPTER } from "../../shared/minimaxH3";
import { isKlingAdapter, isKlingNativeAdapter } from "../../shared/kling";
import { isNativeMediaAdapter } from "../../shared/nativeMedia";
import { AUTODL_ADAPTER } from "../../shared/autodlWorkflows";
import { MEDIA_FUNCTIONS_QUERY_KEY } from "../../shared/mediaFunctions";
import { t,useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState,Notice,Panel,StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { Badge } from "../../shared/ui/primitives/badge";
import { DropdownMenu,DropdownMenuContent,DropdownMenuGroup,DropdownMenuItem,DropdownMenuSeparator,DropdownMenuTrigger } from "../../shared/ui/primitives/dropdown-menu";
import { Table,TableBody,TableCell,TableHead,TableHeader,TableRow } from "../../shared/ui/primitives/table";
import { Tabs,TabsContent,TabsList,TabsTrigger } from "../../shared/ui/primitives/tabs";
import { Select } from "../../shared/ui/Select";
import { AutoDlWorkflowFields } from "./AutoDlWorkflowFields";
import { CapabilityConfigurationFields } from "./CapabilityConfigurationFields";
import { GoogleImageConnectionHelp,googleImageApiLabel } from "./GoogleImageConnectionHelp";
import { LocalServiceDiscovery } from "./LocalServiceDiscovery";
import { MediaConnectionAddressField } from "./MediaConnectionAddressField";
import { KlingModelFields } from "./KlingModelFields";
import { NativeModelFields } from "./NativeModelFields";
import { RUNNINGHUB_OUTPUT_ADAPTERS,adapterLabel,adapterMetadata,adapterModel,platformAdapters } from "./mediaAdapterCatalog";
import "./MediaSettingsPage.css";
import { ComfyWorkflowEditor } from "./ComfyWorkflowEditor";
import { isComfyAdapter } from "./comfyWorkflow";
import { RunningHubDefinitionEditor } from "./RunningHubDefinitionEditor";
import { buildMediaCapabilitySettings, CapabilityDraftError, type MediaCapabilityDraft } from "./mediaCapabilityDraft";
import { applyOpenAiImageTokenPreset, openAiImagePricePreset, updateOpenAiImagePricingDraft } from "./openAiImagePricingPresets";
import { mediaConnectionAddresses, mediaConnectionOrigin } from "./mediaConnectionConfiguration";
import { MediaDefaultsSection } from "./MediaDefaultsSection";

type AdapterSettings = MediaCapabilityDraft;
const MODEL_LIMIT = 120;

const settingsKey = ["settings", "media"] as const;
const MEDIA_SETTINGS_TABS = ["connections", "capabilities"] as const;
const NAME_LIMIT = 160;
const CATALOG_STATUS = ["all", "enabled", "disabled"] as const;
type CatalogStatus = typeof CATALOG_STATUS[number];
const CATALOG_KINDS = ["all", "IMAGE_GENERATION", "VIDEO_GENERATION", "AUDIO_GENERATION", "local"] as const;
type CatalogKind = typeof CATALOG_KINDS[number];
function matchesCatalog(text: string, enabled: boolean, query: string, status: CatalogStatus) {
  return text.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())
    && (status === "all" || enabled === (status === "enabled"));
}
function capabilityMatches(connection: MediaConnection, capability: MediaCapability, query: string, status: CatalogStatus, kind: CatalogKind = "all") {
  return matchesCatalog(`${mediaDisplayName(connection)} ${connection.platform} ${mediaDisplayName(capability)} ${adapterLabel(capability.adapterId)} ${capability.settings.model ?? ""}`,
    connection.enabled && capability.enabled, query, status)
    && (kind === "all" || (kind === "local" ? connection.platform === "LOCAL" : capability.kind === kind && connection.platform !== "LOCAL"));
}
function QualityChoice({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  useLocale();
  return <Field><FieldLabel className="ui-field block">{t("settings.mediaSettings.imageQuality")}<Select value={value} onChange={(event) => onChange(event.target.value)}>
      <option value="low">low</option><option value="medium">medium</option>
      <option value="high">high</option>
    </Select>
  </FieldLabel></Field>;
}

function FixedModelFields({ adapterId, values, onChange }: {
  adapterId: string;
  values: AdapterSettings;
  onChange: (value: AdapterSettings) => void;
}) {
  useLocale();
  return <>
    {adapterId === AUTODL_ADAPTER ? <AutoDlWorkflowFields values={values} onChange={onChange} /> : null}
    {adapterModel(adapterId) && !isKlingAdapter(adapterId) && !["ARK_SEEDANCE_2_I2V", "VOLC_SEED_AUDIO_1", MINIMAX_H3_ADAPTER].includes(adapterId) ? <Field><FieldLabel className="ui-field block">{t("settings.mediaSettings.modelOptions")}<Select value={values.model ? "custom" : "builtin"} onChange={(event) => onChange({ ...values,
        model: event.target.value === "builtin" ? "" : adapterModel(adapterId) })}>
        <option value="builtin">{t("settings.mediaSettings.builtinModel", { "0": adapterModel(adapterId) })}</option>
        <option value="custom">{t("settings.mediaSettings.customModel")}</option>
      </Select>
    </FieldLabel></Field> : null}
    {isKlingAdapter(adapterId) || ["ARK_SEEDANCE_2_I2V", "VOLC_SEED_AUDIO_1", MINIMAX_H3_ADAPTER].includes(adapterId) ? <Field><FieldLabel className="ui-field block">{t("settings.mediaSettings.fixedModel")}<Input value={adapterModel(adapterId)} readOnly />
    </FieldLabel></Field> : null}
    {adapterId === "OPENAI_GPT_IMAGE_2" || adapterId === "GOOGLE_NANO_BANANA_2" ? <Field><FieldLabel className="ui-field block">{t("settings.mediaSettings.modelName")}
      <Input value={values.model ?? ""} onChange={(event) => onChange({ ...values, model: event.target.value })}
        maxLength={MODEL_LIMIT} placeholder={adapterModel(adapterId) ?? t("settings.mediaSettings.builtinDefaultPlaceholder")} />
    </FieldLabel></Field> : null}
    {adapterId === "GOOGLE_NANO_BANANA_2" ? <p className="ui-muted media-model-help">
      {t("settings.mediaSettings.googleModelHint")}</p> : null}
    {isKlingAdapter(adapterId) ? <p className="ui-muted media-model-help">{t("settings.mediaSettings.klingModelHint")}</p> : null}
    {adapterId === "OPENAI_GPT_IMAGE_2" ? <QualityChoice value={values.quality ?? "medium"}
      onChange={(quality) => onChange({ ...values, quality: quality as AdapterSettings["quality"],
        ...(values.defaultParameters ? { defaultParameters: { ...values.defaultParameters, quality: quality as AdapterSettings["quality"] } } : {}) })} /> : null}
  </>;
}

const EDITOR_TABS = [
  { id: "model", get label() { return t("settings.mediaSettings.modelConfiguration"); } }, { id: "defaults", get label() { return t("settings.mediaSettings.defaultParameters"); } },
  { id: "limits", get label() { return t("media.inputLimits"); } }, { id: "pricing", get label() { return t("media.pricing.estimated"); } },
] as const;
type EditorTab = typeof EDITOR_TABS[number]["id"];

function CapabilityEditorFields({ connectionId, connectionOrigin, name, onNameChange, adapterId, onAdapterChange, availableAdapters,
  values, onChange: onSettingsChange, onWorkflowReady, onBusyChange, discoveryBusy = false, resetKey = 0, creating = false, disabled }: {
  connectionId: string; name: string; onNameChange: (value: string) => void;
  connectionOrigin?: string | null;
  adapterId: string; onAdapterChange: (value: string) => void; availableAdapters: string[];
  values: AdapterSettings; onChange: (value: AdapterSettings) => void;
  creating?: boolean; disabled: boolean; onWorkflowReady: (ready: boolean) => void;
  onBusyChange: (busy: boolean) => void; discoveryBusy?: boolean; resetKey?: number;
}) {
  const locale = useLocale();
  const fieldId = useId();
  const [tab, setTab] = useState<EditorTab>("model");
  const initializedPreset = useRef<string | null>(null);
  function onChange(next: AdapterSettings) {
    onSettingsChange(adapterId === "OPENAI_GPT_IMAGE_2" ? updateOpenAiImagePricingDraft(values, next, connectionOrigin) : next);
  }
  useEffect(() => {
    if (adapterId === "OPENAI_GPT_IMAGE_2" && values.openAiPricePresetModel && values.openAiPricePresetOrigin !== connectionOrigin) {
      onSettingsChange(updateOpenAiImagePricingDraft(values, values, connectionOrigin));
      return;
    }
    if (!creating || initializedPreset.current === adapterId) return;
    initializedPreset.current = adapterId;
    if (adapterId === "OPENAI_GPT_IMAGE_2" && !values.pricing && !values.tokenPricing
        && openAiImagePricePreset(values.model, connectionOrigin))
      onSettingsChange(applyOpenAiImageTokenPreset(values, connectionOrigin));
  }, [creating, adapterId, values, connectionOrigin, onSettingsChange]);
  const nativeKling = isKlingNativeAdapter(adapterId);
  const nativeMedia = isNativeMediaAdapter(adapterId);
  const nativeCatalog = useQuery({ queryKey: ["settings", "media", "profiles", locale], queryFn: getNativeMediaProfiles,
    enabled: nativeMedia, retry: false });
  const nativeProfiles = nativeCatalog.data?.profiles.filter((profile) => profile.adapterId === adapterId) ?? [];
  const nativeProfile = nativeProfiles.find((profile) => profile.operation === (values.operation ?? "GENERATE")
    && profile.model === (values.model ?? ""))
    ?? (values.model === undefined && values.operation === undefined ? nativeProfiles[0] : undefined);
  useEffect(() => {
    if (!nativeMedia) return;
    onWorkflowReady(nativeCatalog.isSuccess && Boolean(nativeProfile));
    if (nativeProfile && (values.model !== nativeProfile.model || values.operation !== nativeProfile.operation
        || values.nativeInputs !== nativeProfile.inputs)) {
      onChange({ ...values, model: nativeProfile.model, operation: nativeProfile.operation, nativeInputs: nativeProfile.inputs });
    }
  }, [nativeMedia, nativeCatalog.isSuccess, nativeProfile, values, onChange, onWorkflowReady]);
  const klingCatalog = useQuery({ queryKey: ["settings", "kling", "profiles", locale], queryFn: getKlingProfiles,
    enabled: nativeKling, retry: false });
  const klingProfiles = klingCatalog.data?.profiles.filter((profile) => profile.adapterId === adapterId) ?? [];
  const klingProfile = klingProfiles.find((profile) => profile.operation === (values.operation ?? "GENERATE")
    && (profile.model ?? "") === (values.model ?? ""))
    ?? (values.model === undefined && values.operation === undefined ? klingProfiles[0] : undefined);
  useEffect(() => {
    if (!nativeKling) return;
    onWorkflowReady(klingCatalog.isSuccess && Boolean(klingProfile));
    if (klingProfile && (values.model !== (klingProfile.model ?? "") || values.operation !== klingProfile.operation
        || values.klingInputs !== klingProfile.inputs)) {
      onChange({ ...values, model: klingProfile.model ?? "", operation: klingProfile.operation, klingInputs: klingProfile.inputs });
    }
  }, [nativeKling, klingCatalog.isSuccess, klingProfile, values, onChange, onWorkflowReady]);
  const currentName = useRef(name);
  currentName.current = name;
  function changeWorkflowOutput(kind: keyof typeof RUNNINGHUB_OUTPUT_ADAPTERS, runningHub = values.runningHub,
    runningHubSelectedNodeIds = values.runningHubSelectedNodeIds, runningHubSelectedFieldKeys = values.runningHubSelectedFieldKeys) {
    onAdapterChange(RUNNINGHUB_OUTPUT_ADAPTERS[kind]);
    const pricing = values.pricing?.unit === kind || (kind !== "IMAGE" && values.pricing?.unit === "SECOND")
      ? values.pricing : undefined;
    onChange({ ...values, pricing, runningHubSelectedNodeIds, runningHubSelectedFieldKeys, runningHub: runningHub ? { ...runningHub,
      outputs: runningHub.outputs.map((output) => output.primary ? { ...output, kind } : output) } : undefined });
  }
  if (isComfyAdapter(adapterId)) return <fieldset disabled={disabled} className="ui-stack media-settings-fieldset">
    <FieldGroup className="ui-form-grid">
      <Field><FieldLabel htmlFor={`${fieldId}-name`}>{creating ? t("settings.mediaSettings.newCapabilityName") : t("settings.mediaSettings.capabilityName")}</FieldLabel><Input id={`${fieldId}-name`} required maxLength={NAME_LIMIT} value={name} onChange={(event) => onNameChange(event.target.value)} /></Field>
      <Field><FieldLabel htmlFor={`${fieldId}-output-kind`}>{t("settings.mediaSettings.primaryOutputKind")}</FieldLabel><Select id={`${fieldId}-output-kind`} value={adapterId} onChange={(event) => onAdapterChange(event.target.value)}>
        {availableAdapters.map((id) => <option key={id} value={id}>{adapterLabel(id)}</option>)}
      </Select></Field>
    </FieldGroup>
    <ComfyWorkflowEditor key={`${adapterId}:${resetKey}`} connectionId={connectionId} adapterId={adapterId} values={values} onChange={onChange} onReadyChange={onWorkflowReady} />
  </fieldset>;
  if (adapterId.startsWith("RUNNINGHUB_")) return <fieldset disabled={disabled} className="media-settings-fieldset runninghub-editor-fieldset">
    <RunningHubDefinitionEditor connectionId={connectionId} adapterId={adapterId} value={values.runningHub}
      onBusyChange={onBusyChange} resetKey={resetKey} sourceDraft={values.runningHubSourceDraft}
      onSourceDraftChange={(runningHubSourceDraft) => onChange({ ...values, runningHubSourceDraft })} identityFields={<FieldGroup className="ui-form-grid">
    <Field><FieldLabel className="ui-field block">{creating ? t("settings.mediaSettings.newCapabilityName") : t("settings.mediaSettings.capabilityName")}<Input required maxLength={NAME_LIMIT} value={name} onChange={(event) => onNameChange(event.target.value)} /></FieldLabel></Field>
    <Field><FieldLabel className="ui-field block">{t("settings.mediaSettings.primaryOutputKind")}<Select disabled={discoveryBusy} value={adapterId} onChange={(event) => {
      const kind = (Object.keys(RUNNINGHUB_OUTPUT_ADAPTERS) as (keyof typeof RUNNINGHUB_OUTPUT_ADAPTERS)[])
        .find((outputKind) => RUNNINGHUB_OUTPUT_ADAPTERS[outputKind] === event.target.value);
      if (kind) changeWorkflowOutput(kind);
    }}>{availableAdapters.map((adapter) => <option key={adapter} value={adapter}>{adapterLabel(adapter)}</option>)}</Select></FieldLabel></Field>
    </FieldGroup>}
      pricingFields={<CapabilityConfigurationFields section="pricing" adapterId={adapterId} values={values} onChange={onChange} />}
      onDiscoveredName={(discoveredName) => {
        // Read the latest draft: the user can type a name while discovery is pending.
        if (!currentName.current.trim()) onNameChange(discoveredName);
      }}
      selectedNodeIds={values.runningHubSelectedNodeIds} selectedFieldKeys={values.runningHubSelectedFieldKeys} onChange={(runningHub, primaryKind, runningHubSelectedNodeIds, runningHubSelectedFieldKeys) => {
      if (primaryKind) changeWorkflowOutput(primaryKind, runningHub, runningHubSelectedNodeIds, runningHubSelectedFieldKeys);
      else onChange({ ...values, runningHub, runningHubSelectedNodeIds, runningHubSelectedFieldKeys });
    }} />
  </fieldset>;
  return <div className="media-editor ui-stack" onInvalidCapture={(event) => {
    // Reveal the invalid field before native form validation tries to focus it.
    const input = event.target;
    if (!(input instanceof HTMLElement)) return;
    const panel = input.closest<HTMLElement>("[data-editor-tab]");
    const section = panel?.dataset.editorTab;
    if (section && EDITOR_TABS.some((item) => item.id === section)) {
      if (panel?.hidden) event.preventDefault();
      setTab(section as EditorTab);
      requestAnimationFrame(() => input.focus());
    }
  }}>
    <Tabs className="capability-editor-workspace" orientation="vertical" value={tab} onValueChange={(value) => { const next = EDITOR_TABS.find((item) => item.id === value); if (next) setTab(next.id); }}>
    <TabsList className="capability-editor-navigation" aria-label={t("settings.mediaSettings.configurationSections")}>
      {EDITOR_TABS.map((item, index) => <TabsTrigger key={item.id} value={item.id} aria-label={item.label}>
        <span className="capability-step-number" aria-hidden>{String(index + 1).padStart(2, "0")}</span>
        <span><strong>{item.label}</strong><small>{t(`settings.editor.${item.id}Hint`)}</small></span>
      </TabsTrigger>)}
    </TabsList>
    {EDITOR_TABS.map((item) => <TabsContent key={item.id} value={item.id} data-editor-tab={item.id} className="capability-editor-panel" forceMount hidden={tab !== item.id}>
      <header className="capability-editor-section-heading"><span className="ui-muted">{t("settings.editor.step", { "0": EDITOR_TABS.findIndex((entry) => entry.id === item.id) + 1, "1": EDITOR_TABS.length })}</span><h3>{item.label}</h3><p>{t(`settings.editor.${item.id}Hint`)}</p></header>
      <FieldSet className="media-settings-fieldset" disabled={disabled}><FieldGroup className="ui-form-grid">
        {item.id === "model" ? <>
          <Field><FieldLabel className="ui-field block">{creating ? t("settings.mediaSettings.newCapabilityName") : t("settings.mediaSettings.capabilityName")}
            <Input value={name} onChange={(event) => onNameChange(event.target.value)} required maxLength={NAME_LIMIT} />
          </FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">{t("settings.mediaSettings.adapter")}<Select value={adapterId} onChange={(event) => onAdapterChange(event.target.value)}>
              {availableAdapters.map((adapter) => <option key={adapter} value={adapter}>{adapterLabel(adapter)}</option>)}
            </Select>
          </FieldLabel></Field>
          {nativeMedia ? nativeProfile ? <NativeModelFields profiles={nativeProfiles} selected={nativeProfile} values={values} onChange={onChange} />
            : <div className="ui-stack"><p className="ui-muted" role={nativeCatalog.error ? "alert" : "status"}>{nativeCatalog.error
              ? t("settings.mediaSettings.nativeProfilesFailed", { "0": nativeCatalog.error.message }) : nativeCatalog.isPending
                ? t("settings.mediaSettings.nativeProfilesLoading") : t("media.editor.modelUnavailableHint")}</p>
              {nativeCatalog.error ? <Button type="button" variant="outline" onClick={() => void nativeCatalog.refetch()}>{t("common.retry")}</Button> : null}
            </div>
            : nativeKling ? klingProfile ? <KlingModelFields profiles={klingProfiles} selected={klingProfile} values={values} onChange={onChange} />
            : <div className="ui-stack"><p className="ui-muted" role={klingCatalog.error ? "alert" : "status"}>{klingCatalog.error
              ? t("settings.mediaSettings.klingProfilesFailed", { "0": klingCatalog.error.message }) : klingCatalog.isPending
                ? t("settings.mediaSettings.klingProfilesLoading") : t("media.editor.modelUnavailableHint")}</p>
              {klingCatalog.error ? <Button type="button" variant="outline" onClick={() => void klingCatalog.refetch()}>{t("common.retry")}</Button> : null}
            </div>
            : <FixedModelFields adapterId={adapterId} values={values} onChange={onChange} />}
        </> : <CapabilityConfigurationFields section={item.id} adapterId={adapterId} values={values} onChange={onChange} connectionOrigin={connectionOrigin}
          nativeInputLimits={nativeProfile?.inputLimits ?? klingProfile?.inputLimits} />}
      </FieldGroup></FieldSet>
      <div className="capability-editor-paging">
        {item.id !== "model" ? <Button variant="ghost" type="button" onClick={() => setTab(EDITOR_TABS[EDITOR_TABS.findIndex((entry) => entry.id === item.id) - 1]!.id)}><ArrowLeft data-icon="inline-start" />{t("settings.editor.previous")}</Button> : <span />}
        {item.id !== "pricing" ? <Button variant="outline" type="button" onClick={() => setTab(EDITOR_TABS[EDITOR_TABS.findIndex((entry) => entry.id === item.id) + 1]!.id)}>{t("settings.editor.next")}<ArrowRight data-icon="inline-end" /></Button> : <span className="ui-muted">{t("settings.editor.readyToSave")}</span>}
      </div>
    </TabsContent>)}
    </Tabs>
  </div>;
}

function ConnectionCredentials({ platform, origin, apiKey, onOriginChange, onApiKeyChange, creating = false, disabled = false }: {
  platform: MediaConnection["platform"];
  origin: string;
  apiKey: string;
  onOriginChange: (value: string) => void;
  onApiKeyChange: (value: string) => void;
  creating?: boolean;
  disabled?: boolean;
}) {
  useLocale();
  const helpId = useId();
  return <>
    <MediaConnectionAddressField key={platform} platform={platform} origin={origin} onChange={onOriginChange}
      describedBy={platform === "GOOGLE" ? helpId : undefined} />
    {platform === "COMFYUI" ? <LocalServiceDiscovery service="COMFYUI" disabled={disabled} onSelect={onOriginChange} /> : null}
    {platform === "GOOGLE" ? <GoogleImageConnectionHelp id={helpId} origin={origin} /> : null}
    {platform === "AUTODL" ? <p className="ui-muted">{t("settings.mediaSettings.comfyTokenHint")}</p> : null}
    {mediaConnectionAddresses[platform]?.requiresApiKey ? <Field><FieldLabel className="ui-field block">
      {creating ? "API Key" : t("settings.mediaSettings.replaceApiKey")}
      <Input type="password" autoComplete="new-password" value={apiKey}
        onChange={(event) => onApiKeyChange(event.target.value)} required={creating} />
    </FieldLabel></Field> : null}
  </>;
}

function stableCreateKey(previous: { payload: string; key: string } | null,
  payload: string): { payload: string; key: string } {
  return previous?.payload === payload ? previous
    : { payload, key: crypto.randomUUID() };
}

function errorMessage(cause: unknown): string {
  return cause instanceof ApiError || cause instanceof CapabilityDraftError ? cause.message : t("settings.shared.saveFailed");
}

/** Keep a draft's CAS token until its own save or an explicit reload accepts a new baseline. */
function useConfigurationBaseline<T extends { version: number }>(current: T) {
  const [baseline, acceptBaseline] = useState(current);
  return { baseline, acceptBaseline, isStale: current.version > baseline.version };
}

function ConfigurationUpdatedNotice({ scope, disabled, onReload }: {
  scope: string; disabled: boolean; onReload: () => void;
}) {
  useLocale();
  return <Notice tone="warning" title={t("settings.mediaSettings.versionChanged")}>
    <p>{t("settings.mediaSettings.refreshDraftHint", { "0": scope })}</p>
    <Button variant="outline"  type="button" disabled={disabled} onClick={onReload}>{t("common.refreshVersion")}</Button>
  </Notice>;
}

function CapabilityRow({ connectionId, connectionName, connectionOrigin, capability, isDefault, connectionEnabled,
  availableAdapters, busy, hidden = false, apply, act }: {
  connectionId: string;
  connectionName: string;
  connectionOrigin: string | null;
  capability: MediaCapability;
  isDefault: boolean;
  connectionEnabled: boolean;
  availableAdapters: string[];
  busy: boolean; hidden?: boolean;
  apply: (value: MediaSettings) => void;
  act: (type: "capability" | "default", capability: MediaCapability) => void;
}) {
  useLocale();
  const queryClient = useQueryClient();
  const { baseline, acceptBaseline, isStale } = useConfigurationBaseline(capability);
  const [name, setName] = useState(capability.name);
  const [adapterId, setAdapterId] = useState(capability.adapterId);
  const [modelNames, setModelNames] = useState<AdapterSettings>(capability.settings);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState(false);
  const [importBusy, setImportBusy] = useState(false);
  const [editorResetKey, setEditorResetKey] = useState(0);
  const [deleting, setDeleting] = useState<MediaCapability | null>(null);
  const [workflowReady, setWorkflowReady] = useState(false);
  const save = useMutation({
    mutationFn: () => updateMediaCapability(connectionId, capability.id, {
      expectedVersion: baseline.version, name: name.trim(),
      enabled: baseline.enabled, adapterId,
      settings: buildMediaCapabilitySettings(adapterId, modelNames),
    }),
    onSuccess: (result) => {
      const saved = result.connections.find((item) => item.id === connectionId)
        ?.capabilities.find((item) => item.id === capability.id);
      if (saved) {
        acceptBaseline(saved);
        setName(saved.name);
        setAdapterId(saved.adapterId);
        setModelNames(saved.settings);
      }
      apply(result); setError(""); setEditing(false);
    },
    onError: (cause) => {
      if (cause instanceof ApiError && cause.status === HTTP_STATUS.CONFLICT) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
      setError(errorMessage(cause));
    },
  });
  const remove = useMutation({
    mutationFn: () => {
      if (!deleting) throw new Error(t("settings.mediaSettings.selectCapability"));
      return deleteMediaCapability(connectionId, deleting.id, deleting.version);
    },
    onSuccess: (result) => {
      apply(result);
      queryClient.setQueryData(["media-settings"], result);
      void queryClient.invalidateQueries({ queryKey: MEDIA_FUNCTIONS_QUERY_KEY });
      setDeleting(null);
    },
    onError: (cause) => {
      if (cause instanceof ApiError && cause.status === HTTP_STATUS.CONFLICT) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
    },
  });
  const deleteStale = deleting !== null && deleting.version !== capability.version;
  const sameKindAdapters = capability.adapterId.startsWith("RUNNINGHUB_") ? availableAdapters
    : [...new Set([capability.adapterId, ...availableAdapters.filter((id) => adapterMetadata(id)?.kind === capability.kind)])];
  const rowBusy = busy || save.isPending || remove.isPending || importBusy;
  function loadLatest() {
    acceptBaseline(capability);
    setName(capability.name);
    setAdapterId(capability.adapterId);
    setModelNames(capability.settings);
    setEditorResetKey((key) => key + 1);
    setError("");
  }
  const CapabilityIcon = capability.kind === "IMAGE_GENERATION" ? ImageSquare : capability.kind === "AUDIO_GENERATION" ? MusicNotes : VideoCamera;
  return <>
    <TableRow hidden={hidden} data-disabled={!connectionEnabled || !capability.enabled}>
      <TableCell><div className="media-table-name"><CapabilityIcon size={19} aria-hidden /><strong title={capability.name}>{capability.name}</strong></div></TableCell>
      <TableCell data-label={t("settings.mediaWorkspace.serviceColumn")}><span className="media-cell-text" title={connectionName}>{connectionName}</span></TableCell>
      <TableCell data-label={t("common.model")}><span className="media-cell-text" title={`${adapterLabel(capability.adapterId)} · ${capability.settings.model || adapterModel(capability.adapterId) || capability.adapterId}`}>
        {capability.settings.model || adapterModel(capability.adapterId) || adapterLabel(capability.adapterId)}</span></TableCell>
      <TableCell data-label={t("settings.mediaWorkspace.kindColumn")}>{capability.kind === "VIDEO_GENERATION" ? t("common.video") : capability.kind === "AUDIO_GENERATION" ? t("common.audio") : t("common.image")}</TableCell>
      <TableCell data-label={t("settings.mediaSettings.inputRange")}><span className="media-cell-text" title={t("settings.mediaSettings.referenceImageLimit", { "0": capability.maxReferenceImages })}>{capability.kind === "VIDEO_GENERATION"
        ? t("settings.mediaWorkspace.compactVideoInput", { "0": capability.minimumSeconds, "1": capability.maximumSeconds, "2": capability.maxReferenceImages })
        : t("settings.mediaSettings.referenceImageLimit", { "0": capability.maxReferenceImages })}</span></TableCell>
      <TableCell data-label={t("settings.mediaSettings.price")} title={t("settings.mediaWorkspace.estimateOnly")}>{capability.settings.tokenPricing ? <>{t("media.pricing.perMillionTokens")} · {capability.settings.tokenPricing.currency}</> : capability.settings.pricing ? <>{capability.settings.pricing.amount} {capability.settings.pricing.currency}
        <span className="media-price-unit"> / {capability.settings.pricing.unit === "SECOND" ? t("settings.mediaSettings.seconds") : capability.settings.pricing.unit === "VIDEO" ? t("settings.mediaSettings.videoUnit") : capability.settings.pricing.unit === "AUDIO" ? t("settings.editor.audioUnit") : t("settings.mediaSettings.imageUnit")}</span></> : <span className="ui-muted">{t("settings.mediaWorkspace.unknownPrice")}</span>}</TableCell>
      <TableCell><div className="media-table-badges">
        <StatusBadge tone={capability.enabled && connectionEnabled ? "success" : "warning"}>{capability.enabled && connectionEnabled ? t("settings.mediaSettings.enabled") : t("settings.mediaSettings.disabled")}</StatusBadge>
        {isDefault ? <StatusBadge>{t("settings.mediaSettings.default")}</StatusBadge> : null}
      </div></TableCell>
      <TableCell><div className="media-table-actions">
        <Button variant="outline" size="sm" type="button" disabled={rowBusy} onClick={() => setEditing(true)}>{t("settings.mediaSettings.editCapability")}</Button>
        <DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="icon-sm" type="button" disabled={rowBusy} aria-label={t("settings.mediaWorkspace.moreActions", { "0": capability.name })}><DotsThree /></Button></DropdownMenuTrigger>
          <DropdownMenuContent align="end"><DropdownMenuGroup>
            <DropdownMenuItem disabled={!connectionEnabled || !capability.enabled || isDefault} onSelect={() => act("default", capability)}>{t("settings.mediaSettings.setDefault")}</DropdownMenuItem>
            <DropdownMenuItem onSelect={() => act("capability", capability)}>{capability.enabled ? t("settings.mediaSettings.disable") : t("settings.mediaSettings.enable")}</DropdownMenuItem>
          </DropdownMenuGroup><DropdownMenuSeparator /><DropdownMenuGroup>
            <DropdownMenuItem variant="destructive" onSelect={() => { remove.reset(); setDeleting(capability); }}>{t("settings.mediaSettings.deleteCapability")}</DropdownMenuItem>
          </DropdownMenuGroup></DropdownMenuContent>
        </DropdownMenu>
      </div></TableCell>
    </TableRow>
    {deleting ? <Dialog compact title={t("settings.mediaSettings.deleteCapabilityNamed", { "0": deleting.name })}
      description={connectionName} busy={rowBusy} onClose={() => setDeleting(null)}
      onSubmit={(event) => { event.preventDefault(); if (!rowBusy && !deleteStale) remove.mutate(); }}
      footer={<>
        <Button variant="outline" type="button" disabled={rowBusy} onClick={() => setDeleting(null)}>{t("common.cancel")}</Button>
        <Button variant="destructive" type="submit" disabled={rowBusy || deleteStale}>
          {remove.isPending ? t("settings.mediaSettings.deletingCapability") : t("settings.mediaSettings.confirmDeleteCapability")}
        </Button>
      </>}>
      <p>{t("settings.mediaSettings.deleteCapabilityHint")}</p>
      {deleteStale ? <ConfigurationUpdatedNotice scope={t("settings.mediaSettings.capabilityParameters")} disabled={rowBusy}
        onReload={() => { remove.reset(); setDeleting(capability); }} /> : null}
      {remove.isError ? <Notice tone="danger">{errorMessage(remove.error)}</Notice> : null}
    </Dialog> : null}
    {editing ? <Dialog className={cn("media-capability-dialog", isComfyAdapter(adapterId) && "comfy-capability-dialog", adapterId === AUTODL_ADAPTER && "autodl-capability-dialog", adapterId.startsWith("RUNNINGHUB_") && "runninghub-capability-dialog")} title={t("settings.mediaSettings.editCapabilityNamed", { "0": capability.name })} description={`${connectionName} · ${adapterLabel(adapterId)}`}
      onClose={() => setEditing(false)} busy={rowBusy} onSubmit={(event) => {
        event.preventDefault(); if (isStale || rowBusy || (isComfyAdapter(adapterId) || isKlingNativeAdapter(adapterId) || isNativeMediaAdapter(adapterId)) && !workflowReady) return; setError(""); save.mutate();
      }} footer={<>
        <Button variant="outline"  type="button" disabled={rowBusy} onClick={() => setEditing(false)}>{t("common.cancel")}</Button>
        <Button variant="default"  type="submit" disabled={rowBusy || isStale || (isComfyAdapter(adapterId) || isKlingNativeAdapter(adapterId) || isNativeMediaAdapter(adapterId)) && !workflowReady}>{save.isPending ? t("common.savingProgress") : t("settings.mediaSettings.saveCapability")}</Button>
      </>}>
      <div className="ui-stack">
        {isStale ? <ConfigurationUpdatedNotice scope={t("settings.mediaSettings.capabilityParameters")} disabled={rowBusy} onReload={loadLatest} /> : null}
        <CapabilityEditorFields connectionId={connectionId} connectionOrigin={connectionOrigin} name={name} onNameChange={setName} adapterId={adapterId}
          onAdapterChange={(value) => { setAdapterId(value); setModelNames({}); }} availableAdapters={sameKindAdapters}
          values={modelNames} onChange={setModelNames} onWorkflowReady={setWorkflowReady} onBusyChange={setImportBusy} discoveryBusy={importBusy} resetKey={editorResetKey} disabled={busy || save.isPending || remove.isPending} />
        {error ? <Notice tone="danger">{error}</Notice> : null}
      </div>
    </Dialog> : null}
  </>;
}

function useMediaActions(connection: MediaConnection, settings: MediaSettings, apply: (value: MediaSettings) => void) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (action: { type: "connection" | "capability" | "default"; capability?: MediaCapability }) => {
      if (action.type === "connection") {
        return updateMediaConnection(connection.id, {
          expectedVersion: connection.version, name: connection.name,
          enabled: !connection.enabled, origin: connection.origin, apiKey: null,
        });
      }
      const capability = action.capability;
      if (!capability) throw new Error(t("settings.mediaSettings.selectCapability"));
      if (action.type === "capability") {
        return updateMediaCapability(connection.id, capability.id, {
          expectedVersion: capability.version, name: capability.name,
          enabled: !capability.enabled, adapterId: capability.adapterId,
          settings: capability.settings,
        });
      }
      const current = settings.defaults.find((item) => item.kind === capability.kind);
      if (!current) throw new Error(t("settings.mediaSettings.defaultVersionMissing"));
      return setMediaDefault(capability.kind, {
        expectedVersion: current.version, capabilityId: capability.id,
      });
    },
    onSuccess: apply,
    onError: (cause) => {
      if (cause instanceof ApiError && cause.status === HTTP_STATUS.CONFLICT) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
    },
  });
}

function ConnectionCapabilities({ connection, settings, apply, query, status, kind }: {
  connection: MediaConnection; settings: MediaSettings; apply: (value: MediaSettings) => void; query: string; status: CatalogStatus; kind: CatalogKind;
}) {
  useLocale();
  const mutate = useMediaActions(connection, settings, apply);
  const systemManaged = connection.platform === "LOCAL";
  const availableAdapters = platformAdapters[connection.platform];
  return <>
    {connection.capabilities.map((capability) => systemManaged ? <TableRow key={capability.id} hidden={!capabilityMatches(connection, capability, query, status, kind)}>
      <TableCell><div className="media-table-name"><HardDrives size={19} aria-hidden /><strong title={mediaDisplayName(capability)}>{mediaDisplayName(capability)}</strong></div></TableCell>
      <TableCell data-label={t("settings.mediaWorkspace.serviceColumn")}><span className="media-cell-text" title={systemManaged ? mediaDisplayName(connection) : connection.name}>{systemManaged ? mediaDisplayName(connection) : connection.name}</span></TableCell>
      <TableCell data-label={t("common.model")}><span className="media-cell-text" title={adapterLabel(capability.adapterId)}>{t("settings.mediaSettings.builtin")}</span></TableCell>
      <TableCell data-label={t("settings.mediaWorkspace.kindColumn")}>{capability.kind === "IMAGE_GENERATION" ? t("common.image") : capability.kind === "AUDIO_GENERATION" ? t("common.audio") : t("common.video")}</TableCell>
      <TableCell data-label={t("settings.mediaSettings.inputRange")}><span className="media-cell-text">{capability.kind === "IMAGE_GENERATION" ? t("settings.mediaSettings.referenceImageLimit", { "0": capability.maxReferenceImages }) : t("media.functions.videoTools")}</span></TableCell>
      <TableCell data-label={t("settings.mediaSettings.price")}>{t("settings.mediaSettings.localExecution")}</TableCell><TableCell><StatusBadge tone="success">{t("settings.mediaSettings.enabled")}</StatusBadge></TableCell><TableCell className="ui-muted">{t("settings.mediaSettings.administration")}</TableCell>
    </TableRow> : <CapabilityRow key={capability.id} connectionId={connection.id} connectionName={connection.name} connectionOrigin={connection.origin}
      capability={capability} isDefault={settings.defaults.some((item) => item.kind === capability.kind && item.capabilityId === capability.id)}
      connectionEnabled={connection.enabled} availableAdapters={availableAdapters} busy={mutate.isPending} hidden={!capabilityMatches(connection, capability, query, status, kind)} apply={apply}
      act={(type, selected) => mutate.mutate({ type, capability: selected })} />)}
    {mutate.isError ? <TableRow><TableCell colSpan={8}><Notice tone="danger">{errorMessage(mutate.error)}</Notice></TableCell></TableRow> : null}
  </>;

}

function ConnectionRow({ connection, settings, apply, hidden, publishOpen, onPublish, onPublishClose }: {
  connection: MediaConnection;
  settings: MediaSettings;
  apply: (value: MediaSettings) => void;
  hidden: boolean; publishOpen: boolean; onPublish: () => void; onPublishClose: () => void;
}) {
  useLocale();
  const queryClient = useQueryClient();
  const { baseline, acceptBaseline, isStale } = useConfigurationBaseline(connection);
  const [name, setName] = useState(connection.name);
  const [origin, setOrigin] = useState(connection.origin ?? "");
  const [apiKey, setApiKey] = useState("");
  const [capabilityName, setCapabilityName] = useState("");
  const [adapterId, setAdapterId] = useState(platformAdapters[connection.platform][0] ?? "");
  const [newModelNames, setNewModelNames] = useState<AdapterSettings>({});
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [dialog, setDialog] = useState<"connection" | null>(null);
  const [importBusy, setImportBusy] = useState(false);
  const [workflowReady, setWorkflowReady] = useState(false);
  const capabilityCreateKey = useRef<{ payload: string; key: string } | null>(null);
  const save = useMutation({
    mutationFn: () => updateMediaConnection(connection.id, {
      expectedVersion: baseline.version, name: name.trim(), enabled: baseline.enabled,
      origin: mediaConnectionOrigin(connection.platform, origin),
      apiKey: apiKey || null,
    }),
    onSuccess: (result) => {
      const saved = result.connections.find((item) => item.id === connection.id);
      if (saved) {
        acceptBaseline(saved);
        setName(saved.name);
        setOrigin(saved.origin ?? "");
      }
      apply(result);
      setApiKey("");
      setError("");
      setNotice(t("settings.mediaSettings.connectionSaved"));
      setDialog(null);
    },
    onError: (cause) => {
      setNotice("");
      if (cause instanceof ApiError && cause.status === HTTP_STATUS.CONFLICT) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
      setError(cause instanceof ApiError && cause.status === HTTP_STATUS.CONFLICT
        ? t("settings.mediaSettings.conflict")
        : errorMessage(cause));
    },
  });
  const addCapability = useMutation({
    mutationFn: () => {
      const payload = { name: capabilityName.trim(), adapterId,
        settings: buildMediaCapabilitySettings(adapterId, newModelNames) };
      capabilityCreateKey.current = stableCreateKey(capabilityCreateKey.current, JSON.stringify(payload));
      return createMediaCapability(connection.id, payload, capabilityCreateKey.current.key);
    },
    onSuccess: (result) => {
      capabilityCreateKey.current = null;
      apply(result);
      setCapabilityName("");
      setNewModelNames({});
      setError("");
      setNotice(t("settings.mediaSettings.capabilityPublished"));
      onPublishClose();
    },
    onError: (cause) => {
      if (cause instanceof ApiError && cause.status === HTTP_STATUS.CONFLICT) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
      setError(errorMessage(cause));
    },
  });
  const mutate = useMediaActions(connection, settings, apply);
  const busy = save.isPending || addCapability.isPending || mutate.isPending || importBusy;
  const systemManaged = connection.platform === "LOCAL";
  const availableAdapters = systemManaged ? [] : platformAdapters[connection.platform];
  const endpoint = connection.origin || (connection.platform === "ARK" ? "https://ark.cn-beijing.volces.com/api/v3"
    : systemManaged ? t("settings.mediaSettings.builtin") : t("settings.mediaSettings.platformDefaultUrl"));

  function loadLatest() {
    acceptBaseline(connection);
    setName(connection.name);
    setOrigin(connection.origin ?? "");
    setApiKey("");
    setError("");
    setNotice("");
  }

  function submitConnection(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (isStale || busy) return;
    setError("");
    save.mutate();
  }


  return <>
    <TableRow hidden={hidden} data-disabled={!connection.enabled}>
      <TableCell><div className="media-table-name">{systemManaged ? <HardDrives size={18} aria-hidden /> : <PlugsConnected size={18} aria-hidden />}<strong title={systemManaged ? mediaDisplayName(connection) : connection.name}>{systemManaged ? mediaDisplayName(connection) : connection.name}</strong></div></TableCell>
      <TableCell data-label={t("settings.mediaSettings.platform")}>{connection.platform}</TableCell>
      <TableCell data-label={t("settings.mediaWorkspace.capabilityCountColumn")}>{connection.capabilities.length}</TableCell>
      <TableCell data-label={t("settings.mediaWorkspace.addressColumn")}><span className="media-table-endpoint" title={`${endpoint}${connection.platform === "GOOGLE" ? ` · ${googleImageApiLabel(connection.origin)}` : ""}`}>{endpoint}</span></TableCell>
      <TableCell data-label={t("settings.mediaWorkspace.credentialsColumn")}><span className="media-cell-text">{connection.keyMask || (systemManaged ? t("media.functions.localMedia") : t("common.configured"))}</span></TableCell>
      <TableCell><StatusBadge tone={connection.enabled ? "success" : "warning"}>{connection.enabled ? t("settings.mediaSettings.enabled") : t("settings.mediaSettings.disabled")}</StatusBadge></TableCell>
      <TableCell>{systemManaged ? <span className="ui-muted">{t("settings.mediaSettings.administration")}</span> : <div className="media-table-actions">
        <Button variant="outline" size="sm" type="button" disabled={busy} onClick={() => setDialog("connection")}>{t("settings.mediaSettings.editConnection")}</Button>
        <Button variant="ghost" size="sm" type="button" disabled={busy || !connection.enabled || !availableAdapters.length}
          onClick={onPublish}>{t("settings.mediaSettings.publishCapability")}</Button>
        <DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="icon-sm" type="button" disabled={busy} aria-label={t("settings.mediaWorkspace.moreActions", { "0": connection.name })}><DotsThree /></Button></DropdownMenuTrigger>
          <DropdownMenuContent align="end"><DropdownMenuGroup><DropdownMenuItem onSelect={() => mutate.mutate({ type: "connection" })}>{connection.enabled ? t("settings.mediaSettings.disableConnection") : t("settings.mediaSettings.enableConnection")}</DropdownMenuItem></DropdownMenuGroup></DropdownMenuContent>
        </DropdownMenu>
      </div>}</TableCell>
    </TableRow>
    {mutate.isError || (error && !dialog && !publishOpen) || notice ? <TableRow><TableCell colSpan={7}>{mutate.isError || (error && !dialog && !publishOpen) ? <Notice tone="danger">{mutate.isError ? errorMessage(mutate.error) : error}</Notice> : <Notice tone="success">{notice}</Notice>}</TableCell></TableRow> : null}
    {dialog === "connection" ? <Dialog title={t("settings.mediaSettings.editConnectionNamed", { "0": connection.name })} description={t("settings.mediaSettings.connectionTitle", { "0": connection.platform })}
      onClose={() => setDialog(null)} onSubmit={submitConnection} busy={busy} footer={<>
        <Button variant="outline"  type="button" disabled={busy} onClick={() => setDialog(null)}>{t("common.cancel")}</Button>
        <Button variant="default"  type="submit" disabled={busy || isStale}>{save.isPending ? t("common.savingProgress") : t("settings.shared.saveConnection")}</Button>
      </>}>
      <div className="ui-stack">
        {isStale ? <ConfigurationUpdatedNotice scope={t("settings.mediaSettings.connectionConfiguration")} disabled={busy} onReload={loadLatest} /> : null}
        <FieldSet className="media-settings-fieldset" disabled={busy}><FieldGroup className="ui-form-grid">
          <Field><FieldLabel className="ui-field block">{t("settings.shared.connectionName")}<Input value={name} onChange={(event) => setName(event.target.value)} required maxLength={NAME_LIMIT} /></FieldLabel></Field>
          <ConnectionCredentials platform={connection.platform} origin={origin} apiKey={apiKey} onOriginChange={setOrigin} onApiKeyChange={setApiKey} disabled={busy} />
        </FieldGroup></FieldSet>
        {error ? <Notice tone="danger">{error}</Notice> : null}
      </div>
    </Dialog> : null}
    {publishOpen ? <Dialog className={cn("media-capability-dialog", isComfyAdapter(adapterId) && "comfy-capability-dialog", adapterId === AUTODL_ADAPTER && "autodl-capability-dialog", adapterId.startsWith("RUNNINGHUB_") && "runninghub-capability-dialog")} title={t("settings.mediaSettings.publishNewCapability")} description={`${connection.name} · ${adapterLabel(adapterId)}`}
      onClose={onPublishClose} busy={busy} onSubmit={(event) => {
        event.preventDefault(); if (busy || !connection.enabled || (isComfyAdapter(adapterId) || isKlingNativeAdapter(adapterId) || isNativeMediaAdapter(adapterId)) && !workflowReady) return; setError(""); addCapability.mutate();
      }} footer={<>
        <Button variant="outline"  type="button" disabled={busy} onClick={onPublishClose}>{t("common.cancel")}</Button>
        <Button variant="default"  type="submit" disabled={busy || !connection.enabled || (isComfyAdapter(adapterId) || isKlingNativeAdapter(adapterId) || isNativeMediaAdapter(adapterId)) && !workflowReady}>{addCapability.isPending ? t("settings.mediaSettings.publishing") : t("settings.mediaSettings.publishCapability")}</Button>
      </>}>
      <div className="ui-stack">
        <CapabilityEditorFields connectionId={connection.id} connectionOrigin={connection.origin} name={capabilityName} onNameChange={setCapabilityName} adapterId={adapterId}
          onAdapterChange={(value) => { setAdapterId(value); setNewModelNames({}); }} availableAdapters={availableAdapters}
          values={newModelNames} onChange={setNewModelNames} onWorkflowReady={setWorkflowReady} onBusyChange={setImportBusy} discoveryBusy={importBusy} creating disabled={save.isPending || addCapability.isPending || mutate.isPending || !connection.enabled} />
        {!connection.enabled ? <Notice tone="warning">{t("settings.mediaSettings.enableBeforePublish")}</Notice> : null}
        {error ? <Notice tone="danger">{error}</Notice> : null}
      </div>
    </Dialog> : null}
  </>;
}

export function MediaSettingsPage() {
  useLocale();
  const queryClient = useQueryClient();
  const [selectedTab, setSelectedTab] = useState<typeof MEDIA_SETTINGS_TABS[number]>("capabilities");
  const [catalogQuery, setCatalogQuery] = useState("");
  const [catalogStatus, setCatalogStatus] = useState<CatalogStatus>("all");
  const [catalogKind, setCatalogKind] = useState<CatalogKind>("all");
  const [chooseConnection, setChooseConnection] = useState(false);
  const [publishingConnectionId, setPublishingConnectionId] = useState<string | null>(null);
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const settings = useQuery({ queryKey: settingsKey, queryFn: getMediaSettings,
    enabled: currentUser.isSuccess, retry: false });
  const [name, setName] = useState("");
  const [platform, setPlatform] = useState<CreateMediaConnectionRequest["platform"]>("COMFYUI");
  const [origin, setOrigin] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [error, setError] = useState("");
  const [creating, setCreating] = useState(false);
  const connectionCreateKey = useRef<{ payload: string; key: string } | null>(null);
  const create = useMutation({
    mutationFn: () => {
      const payload = { name: name.trim(), platform,
        origin: mediaConnectionOrigin(platform, origin),
        apiKey: platform === "COMFYUI" ? null : apiKey };
      connectionCreateKey.current = stableCreateKey(connectionCreateKey.current, JSON.stringify(payload));
      return createMediaConnection(payload, connectionCreateKey.current.key);
    },
    onSuccess: (result) => {
      connectionCreateKey.current = null;
      queryClient.setQueryData(settingsKey, result);
      setName(""); setOrigin(""); setApiKey(""); setError(""); setCreating(false);
    },
    onError: (cause) => setError(errorMessage(cause)),
  });

  if (settings.error instanceof ApiError && settings.error.status === HTTP_STATUS.UNAUTHORIZED) {
    return <Navigate to="/login" replace />;
  }
  const capabilities = settings.data?.connections.flatMap((connection) => connection.capabilities) ?? [];
  const availableCount = settings.data?.connections.filter((connection) => connection.enabled)
    .flatMap((connection) => connection.capabilities).filter((capability) => capability.enabled).length ?? 0;
  const visibleCount = settings.data?.connections.reduce((count, connection) => count + (selectedTab === "connections"
    ? Number(matchesCatalog(`${mediaDisplayName(connection)} ${connection.platform} ${connection.origin ?? ""}`, connection.enabled, catalogQuery, catalogStatus))
    : connection.capabilities.filter((capability) => capabilityMatches(connection, capability, catalogQuery, catalogStatus, catalogKind)).length), 0) ?? 0;
  const hasFilters = Boolean(catalogQuery || catalogStatus !== "all" || selectedTab === "capabilities" && catalogKind !== "all");
  function resetFilters() { setCatalogQuery(""); setCatalogStatus("all"); setCatalogKind("all"); }
  return <PageShell title={t("settings.mediaSettings.title")}
    description={t("settings.mediaWorkspace.intro")}
    actions={<><Button variant="outline" type="button" onClick={() => setCreating(true)}><PlugsConnected data-icon="inline-start" />{t("settings.mediaSettings.addConnection")}</Button>
      <Button type="button" disabled={!settings.data} onClick={() => setChooseConnection(true)}><Plus data-icon="inline-start" />{t("settings.mediaSettings.publishNewCapability")}</Button></>}>
    <div className="media-settings-page ui-stack">
      {currentUser.isSuccess && settings.isPending ? <LoadingState label={t("settings.mediaSettings.loading")} /> : null}
      {settings.error instanceof ApiError && settings.error.status === HTTP_STATUS.FORBIDDEN
        ? <Notice tone="warning" title={t("settings.mediaSettings.adminRequired")}>{t("settings.mediaSettings.adminOnly")}</Notice> : null}
      {settings.isError && !(settings.error instanceof ApiError && (settings.error.status === HTTP_STATUS.UNAUTHORIZED || settings.error.status === HTTP_STATUS.FORBIDDEN))
        ? <Notice tone="danger" title={t("settings.mediaSettings.loadFailedTitle")}>
          <p>{t("settings.mediaSettings.loadFailed")}</p>
          <Button variant="outline"  type="button" onClick={() => { void settings.refetch(); }}
            disabled={settings.isFetching}>{settings.isFetching ? t("common.retrying") : t("common.refresh")}</Button>
        </Notice> : null}
      {settings.data ? <>
        <MediaDefaultsSection settings={settings.data} onApply={(result) => queryClient.setQueryData(settingsKey, result)} onPublish={() => setChooseConnection(true)} />
        <Tabs className="min-w-0 media-catalog" value={selectedTab} onValueChange={(value) => {
          const tab = MEDIA_SETTINGS_TABS.find((entry) => entry === value);
          if (tab) setSelectedTab(tab);
        }}>
        <div className="media-catalog-topline"><TabsList variant="line" aria-label={t("settings.mediaSettings.title")}>
        <TabsTrigger value="capabilities" aria-label={t("settings.mediaSettings.publishedCapabilities")}><Sparkle aria-hidden />{t("settings.mediaWorkspace.capabilityCatalog")}<span className="settings-tab-count" aria-hidden>{capabilities.length}</span></TabsTrigger>
        <TabsTrigger value="connections" aria-label={t("settings.mediaSettings.connections")}><PlugsConnected aria-hidden />{t("settings.mediaWorkspace.connectionCatalog")}<span className="settings-tab-count" aria-hidden>{settings.data.connections.length}</span></TabsTrigger>
        </TabsList>
        <Badge variant="outline" className="media-catalog-availability"><CheckCircle aria-hidden />{t("settings.mediaWorkspace.availableCount", { "0": availableCount })}</Badge></div>
        <div className="media-catalog-heading"><div><h2>{selectedTab === "capabilities" ? t("settings.mediaSettings.publishedCapabilities") : t("settings.mediaSettings.connections")}</h2>
          <p>{selectedTab === "capabilities" ? t("settings.mediaWorkspace.catalogDescription") : t("settings.mediaWorkspace.connectionDescription")}</p></div>
          <span className="media-catalog-result-count" role="status">{t("settings.mediaWorkspace.showing", { "0": visibleCount, "1": selectedTab === "capabilities" ? capabilities.length : settings.data.connections.length })}</span></div>
        <FieldGroup className="media-catalog-toolbar"><Field className="media-catalog-search"><FieldLabel className="sr-only" htmlFor="media-catalog-search">{t("settings.catalog.search")}</FieldLabel><MagnifyingGlass aria-hidden /><Input id="media-catalog-search" placeholder={t("settings.catalog.searchPlaceholder")} value={catalogQuery} onChange={(event) => setCatalogQuery(event.target.value)} /></Field>
          {selectedTab === "capabilities" ? <Field><FieldLabel className="sr-only" htmlFor="media-catalog-kind">{t("settings.mediaWorkspace.allKinds")}</FieldLabel><Select id="media-catalog-kind" value={catalogKind} onChange={(event) => { const kind = CATALOG_KINDS.find((entry) => entry === event.target.value); if (kind) setCatalogKind(kind); }}>
            <option value="all">{t("settings.mediaWorkspace.allKinds")}</option><option value="IMAGE_GENERATION">{t("settings.mediaWorkspace.imageKind")}</option><option value="VIDEO_GENERATION">{t("settings.mediaWorkspace.videoKind")}</option><option value="AUDIO_GENERATION">{t("settings.mediaWorkspace.audioKind")}</option><option value="local">{t("settings.mediaWorkspace.localKind")}</option>
          </Select></Field> : null}
          <Field><FieldLabel className="sr-only" htmlFor="media-catalog-status">{t("settings.catalog.filterStatus")}</FieldLabel><Select id="media-catalog-status" value={catalogStatus} onChange={(event) => { const status = CATALOG_STATUS.find((entry) => entry === event.target.value); if (status) setCatalogStatus(status); }}><option value="all">{t("logs.calls.allStatuses")}</option><option value="enabled">{t("settings.mediaSettings.enabled")}</option><option value="disabled">{t("settings.mediaSettings.disabled")}</option></Select></Field>
          {hasFilters ? <Button variant="ghost" type="button" onClick={resetFilters}>{t("settings.mediaWorkspace.resetFilters")}</Button> : null}
        </FieldGroup>
        {/* Keep row drafts and save feedback when switching between settings sections. */}
        <TabsContent value="connections" forceMount hidden={selectedTab !== "connections"} className="min-w-0">
        <Panel className="media-services-panel">
          {settings.data.connections.length === 0 ? <EmptyState icon={<PlugsConnected size={30} />} title={t("settings.mediaSettings.connectionsEmpty")}
            description={t("settings.mediaSettings.connectionsEmptyHint")} action={<Button type="button" onClick={() => setCreating(true)}>{t("settings.mediaSettings.addConnection")}</Button>} /> : <div className="media-table-scroll" tabIndex={0} role="region" aria-label={t("settings.mediaSettings.connectionsTable")}>
            <Table className="media-settings-table media-connections-table" aria-label={t("settings.mediaSettings.connections")}><TableHeader><TableRow>
              <TableHead scope="col">{t("settings.shared.connectionName")}</TableHead><TableHead scope="col">{t("settings.mediaSettings.platform")}</TableHead><TableHead scope="col">{t("settings.mediaWorkspace.capabilityCountColumn")}</TableHead><TableHead scope="col">{t("settings.mediaWorkspace.addressColumn")}</TableHead><TableHead scope="col">{t("settings.mediaWorkspace.credentialsColumn")}</TableHead><TableHead scope="col">{t("settings.mediaSettings.status")}</TableHead><TableHead scope="col">{t("settings.mediaSettings.operations")}</TableHead>
            </TableRow></TableHeader><TableBody>{settings.data.connections.map((connection) => <ConnectionRow key={connection.id}
              connection={connection} settings={settings.data} apply={(result) => queryClient.setQueryData(settingsKey, result)}
              hidden={!matchesCatalog(`${mediaDisplayName(connection)} ${connection.platform} ${connection.origin ?? ""}`, connection.enabled, catalogQuery, catalogStatus)}
              publishOpen={publishingConnectionId === connection.id} onPublish={() => setPublishingConnectionId(connection.id)} onPublishClose={() => setPublishingConnectionId(null)} />)}
              {visibleCount === 0 && selectedTab === "connections" ? <TableRow><TableCell colSpan={7}><EmptyState title={t("settings.catalog.noResults")} description={t("settings.catalog.tryAnotherSearch")} action={<Button variant="outline" type="button" onClick={resetFilters}>{t("settings.mediaWorkspace.resetFilters")}</Button>} /></TableCell></TableRow> : null}
              </TableBody></Table>
          </div>}
        </Panel>
        </TabsContent>
        <TabsContent value="capabilities" forceMount hidden={selectedTab !== "capabilities"} className="min-w-0">
        <Panel className="media-services-panel">
          {capabilities.length === 0 ? <EmptyState icon={<Sparkle />} title={t("settings.mediaSettings.capabilitiesEmpty")} description={t("settings.mediaWorkspace.noCapabilitiesHint")} action={<Button type="button" onClick={() => setChooseConnection(true)}><Plus data-icon="inline-start" />{t("settings.mediaSettings.publishCapability")}</Button>} />
            : <div className="media-table-scroll" tabIndex={0} role="region" aria-label={t("settings.mediaSettings.capabilitiesTable")}>
              <Table className="media-settings-table media-capabilities-table" aria-label={t("settings.mediaSettings.publishedCapabilities")}><TableHeader><TableRow>
                <TableHead scope="col">{t("settings.mediaWorkspace.capabilityColumn")}</TableHead><TableHead scope="col">{t("settings.mediaWorkspace.serviceColumn")}</TableHead><TableHead scope="col">{t("common.model")}</TableHead><TableHead scope="col">{t("settings.mediaWorkspace.kindColumn")}</TableHead><TableHead scope="col">{t("settings.mediaSettings.inputRange")}</TableHead><TableHead scope="col" title={t("settings.mediaWorkspace.estimateOnly")}>{t("media.pricing.estimated")}</TableHead><TableHead scope="col">{t("settings.mediaSettings.status")}</TableHead><TableHead scope="col">{t("settings.mediaSettings.operations")}</TableHead>
              </TableRow></TableHeader><TableBody>{settings.data.connections.map((connection) => <ConnectionCapabilities key={connection.id}
                connection={connection} settings={settings.data} apply={(result) => queryClient.setQueryData(settingsKey, result)} query={catalogQuery} status={catalogStatus} kind={catalogKind} />)}
                {visibleCount === 0 && selectedTab === "capabilities" ? <TableRow><TableCell colSpan={8}><EmptyState title={t("settings.catalog.noResults")} description={t("settings.catalog.tryAnotherSearch")} action={<Button variant="outline" type="button" onClick={resetFilters}>{t("settings.mediaWorkspace.resetFilters")}</Button>} /></TableCell></TableRow> : null}
                </TableBody></Table>
            </div>}
        </Panel>
        </TabsContent>
        </Tabs>
        <section className="media-setup-flow" aria-label={t("settings.mediaWorkspace.flowTitle")}>
          <div><span className="media-flow-number">01</span><PlugsConnected aria-hidden /><span><strong>{t("settings.mediaWorkspace.connectStep")}</strong><small>{t("settings.mediaWorkspace.connectHint")}</small></span></div><ArrowRight aria-hidden />
          <div><span className="media-flow-number">02</span><Sparkle aria-hidden /><span><strong>{t("settings.mediaWorkspace.publishStep")}</strong><small>{t("settings.mediaWorkspace.publishHint")}</small></span></div><ArrowRight aria-hidden />
          <div><span className="media-flow-number">03</span><ImageSquare aria-hidden /><span><strong>{t("settings.mediaWorkspace.defaultStep")}</strong><small>{t("settings.mediaWorkspace.defaultHint")}</small></span></div>
        </section>
        {chooseConnection ? <Dialog compact title={t("settings.catalog.chooseConnection")} description={t("settings.catalog.chooseConnectionHint")} onClose={() => setChooseConnection(false)} onSubmit={(event) => event.preventDefault()}>
          <div className="media-publish-choices">{settings.data.connections.filter((connection) => connection.enabled && connection.platform !== "LOCAL" && platformAdapters[connection.platform].length > 0).map((connection) => <Button className="media-publish-choice" variant="outline" type="button" key={connection.id} onClick={() => { setChooseConnection(false); setPublishingConnectionId(connection.id); }}><PlugsConnected aria-hidden /><span><strong>{connection.name}</strong><small>{connection.platform}</small></span><ArrowRight aria-hidden /></Button>)}</div>
          {!settings.data.connections.some((connection) => connection.enabled && connection.platform !== "LOCAL" && platformAdapters[connection.platform].length > 0) ? <EmptyState title={t("settings.catalog.noConnection")} action={<Button type="button" onClick={() => { setChooseConnection(false); setCreating(true); }}>{t("settings.mediaSettings.addConnection")}</Button>} /> : null}
        </Dialog> : null}
        {creating ? <Dialog title={t("settings.mediaSettings.addConnection")} description={t("settings.mediaSettings.connectionHint")} busy={create.isPending}
          onClose={() => setCreating(false)} onSubmit={(event) => { event.preventDefault(); if (create.isPending) return; setError(""); create.mutate(); }}
          footer={<>
            <Button variant="outline"  type="button" disabled={create.isPending} onClick={() => setCreating(false)}>{t("common.cancel")}</Button>
            <Button variant="default"  type="submit" disabled={create.isPending}>{create.isPending ? t("common.savingProgress") : t("settings.mediaSettings.addConnection")}</Button>
          </>}>
          <div className="ui-stack">
            <FieldSet className="media-settings-fieldset" disabled={create.isPending}><FieldGroup className="ui-form-grid">
              <Field><FieldLabel className="ui-field block">{t("settings.shared.connectionName")}<Input value={name} onChange={(event) => setName(event.target.value)} required maxLength={NAME_LIMIT} />
              </FieldLabel></Field>
              <Field><FieldLabel className="ui-field block">{t("settings.mediaSettings.platform")}<Select value={platform} onChange={(event) => { setPlatform(event.target.value as typeof platform); setOrigin(""); setApiKey(""); }}>
                  <option value="RUNNINGHUB">RunningHub</option><option value="COMFYUI">ComfyUI</option><option value="OPENAI">OpenAI</option>
                  <option value="WAN">Wan · Model Studio</option><option value="KLING">{t("settings.mediaSettings.klingPlatform")}</option><option value="MINIMAX">MiniMax H3</option><option value="AUTODL">{t("settings.mediaSettings.autoDlWorkflow")}</option><option value="GOOGLE">Google Gemini</option><option value="ARK">{t("settings.mediaSettings.arkPlatform")}</option><option value="VOLCENGINE">{t("settings.mediaSettings.seedAudioPlatform")}</option>
                </Select>
              </FieldLabel></Field>
              <ConnectionCredentials platform={platform} origin={origin} apiKey={apiKey}
                onOriginChange={setOrigin} onApiKeyChange={setApiKey} creating disabled={create.isPending} />
            </FieldGroup></FieldSet>
            {error ? <Notice tone="danger">{error}</Notice> : null}
          </div>
        </Dialog> : null}
      </> : <Notice title={t("settings.mediaSettings.configurationHint")}>{t("settings.mediaSettings.defaultsHint")}</Notice>}
    </div>
  </PageShell>;
}
