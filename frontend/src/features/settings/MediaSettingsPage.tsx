import { Field, FieldLabel, FieldGroup, FieldSet } from "../../shared/ui/primitives/field";
import { ImageSquare,MusicNotes,PlugsConnected,Plus,VideoCamera } from "@phosphor-icons/react";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { cn } from "cn";
import { useId,useRef,useState,type FormEvent } from "react";
import { Link,Navigate } from "react-router";
import {
HTTP_STATUS,ApiError,createMediaCapability,createMediaConnection,getCurrentUser,
getMediaSettings,setMediaDefault,updateMediaCapability,
updateMediaConnection,
type MediaCapability,type MediaConnection,type MediaSettings,
} from "../../shared/api/client";
import { AUTODL_ADAPTER,AUTODL_DEFAULT_WORKFLOW,autoDlResolutionTiers,resolveAutoDlWorkflow } from "../../shared/autodlWorkflows";
import { t,useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState,Notice,Panel,StatusBadge,SummaryStrip } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { Table,TableBody,TableCell,TableHead,TableHeader,TableRow } from "../../shared/ui/primitives/table";
import { Tabs,TabsContent,TabsList,TabsTrigger } from "../../shared/ui/primitives/tabs";
import { Select } from "../../shared/ui/Select";
import { AutoDlWorkflowFields } from "./AutoDlWorkflowFields";
import { CapabilityConfigurationFields } from "./CapabilityConfigurationFields";
import { GoogleImageConnectionHelp,googleImageApiLabel } from "./GoogleImageConnectionHelp";
import { MediaConnectionAddressField } from "./MediaConnectionAddressField";
import { adapterLabel,adapterMetadata,adapterModel,platformAdapters } from "./mediaAdapterCatalog";
import "./MediaSettingsPage.css";
import { RunningHubDefinitionEditor } from "./RunningHubDefinitionEditor";
type AdapterSettings = MediaCapability["settings"];
const MODEL_LIMIT = 120;

const settingsKey = ["settings", "media"] as const;
const NAME_LIMIT = 160;
const comfyImageFields = [{ key: "checkpoint", get label() { return t("settings.mediaSettings.checkpointFile"); } }] as const;
const comfyVideoFields = [
  { key: "diffusionModel", get label() { return t("settings.mediaSettings.videoModelFile"); } },
  { key: "textEncoder", get label() { return t("settings.mediaSettings.textEncoderFile"); } },
  { key: "vae", get label() { return t("settings.mediaSettings.vaeFile"); } },
  { key: "clipVision", get label() { return t("settings.mediaSettings.clipVisionFile"); } },
] as const;

const cloudImageFields = [{ key: "model", get label() { return t("settings.mediaSettings.modelName"); } }] as const;

function fixedModelFields(adapterId: string) {
  return adapterId === "COMFY_IMAGE_V1" ? comfyImageFields
    : adapterId === "COMFY_VIDEO_V1" ? comfyVideoFields
    : adapterId === "OPENAI_GPT_IMAGE_2" || adapterId === "GOOGLE_NANO_BANANA_2"
      ? cloudImageFields : [];
}

function fixedModelSettings(adapterId: string, values: AdapterSettings) {
  if (adapterId.startsWith("RUNNINGHUB_")) return { runningHub: values.runningHub, ...(values.pricing?.amount.trim() ? { pricing: values.pricing } : {}) };
  const fields = Object.fromEntries(fixedModelFields(adapterId).map(({ key }) =>
    [key, values[key as keyof AdapterSettings]?.toString().trim() ?? ""]));
  const { defaultParameters, defaultDurationSeconds, minimumSeconds, maximumSeconds,
    maxReferenceImages, maxReferenceAudios, maxReferenceVideos, pricing } = values;
  return { ...fields, ...(adapterId === AUTODL_ADAPTER ? {
    workflowId: values.workflowDefinition?.id ?? values.workflowId ?? AUTODL_DEFAULT_WORKFLOW,
    ...(values.workflowDefinition ? { workflowDefinition: values.workflowDefinition } : {}),
    ...(values.videoResolution ? { videoResolution: values.videoResolution } : {}),
    videoResolutions: values.videoResolutions ?? (values.videoResolution ? [values.videoResolution] : autoDlResolutionTiers(resolveAutoDlWorkflow(values)!)),
    ...(values.pricingByResolution ? { pricingByResolution: Object.fromEntries(Object.entries(values.pricingByResolution).filter(([, price]) => price?.amount.trim())) } : {}),
    ...(values.seed !== undefined ? { seed: values.seed } : {}),
  } : {}), ...(adapterId === "OPENAI_GPT_IMAGE_2" ? { quality: values.quality ?? "medium" } : {}),
    ...(defaultParameters ? { defaultParameters } : {}),
    ...(defaultDurationSeconds !== undefined ? { defaultDurationSeconds } : {}),
    ...(minimumSeconds !== undefined ? { minimumSeconds } : {}),
    ...(maximumSeconds !== undefined ? { maximumSeconds } : {}),
    ...(maxReferenceImages !== undefined ? { maxReferenceImages } : {}),
    ...(maxReferenceAudios !== undefined ? { maxReferenceAudios } : {}),
    ...(maxReferenceVideos !== undefined ? { maxReferenceVideos } : {}),
    ...(pricing?.amount.trim() ? { pricing } : {}) };

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
    {adapterModel(adapterId) && !["ARK_SEEDANCE_2_I2V", "VOLC_SEED_AUDIO_1"].includes(adapterId) ? <Field><FieldLabel className="ui-field block">{t("settings.mediaSettings.modelOptions")}<Select value={values.model ? "custom" : "builtin"} onChange={(event) => onChange({ ...values,
        model: event.target.value === "builtin" ? "" : adapterModel(adapterId) })}>
        <option value="builtin">{t("settings.mediaSettings.builtinModel", { "0": adapterModel(adapterId) })}</option>
        <option value="custom">{t("settings.mediaSettings.customModel")}</option>
      </Select>
    </FieldLabel></Field> : null}
    {["ARK_SEEDANCE_2_I2V", "VOLC_SEED_AUDIO_1"].includes(adapterId) ? <Field><FieldLabel className="ui-field block">{t("settings.mediaSettings.fixedModel")}<Input value={adapterModel(adapterId)} readOnly />
    </FieldLabel></Field> : null}
    {fixedModelFields(adapterId).map(({ key, label }) => <Field key={key}><FieldLabel className="ui-field block">{label}
      <Input value={String(values[key as keyof AdapterSettings] ?? "")} onChange={(event) => onChange({ ...values, [key]: event.target.value })}
        required={key !== "model"} maxLength={key === "model" ? MODEL_LIMIT : NAME_LIMIT}
        placeholder={key === "model" ? adapterModel(adapterId) ?? t("settings.mediaSettings.builtinDefaultPlaceholder") : "model.safetensors"} />
    </FieldLabel></Field>)}
    {adapterId === "GOOGLE_NANO_BANANA_2" ? <p className="ui-muted media-model-help">
      {t("settings.mediaSettings.googleModelHint")}</p> : null}
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

function CapabilityEditorFields({ connectionId, name, onNameChange, adapterId, onAdapterChange, availableAdapters,
  values, onChange, creating = false, disabled }: {
  connectionId: string; name: string; onNameChange: (value: string) => void;
  adapterId: string; onAdapterChange: (value: string) => void; availableAdapters: string[];
  values: AdapterSettings; onChange: (value: AdapterSettings) => void;
  creating?: boolean; disabled: boolean;
}) {
  useLocale();
  const [tab, setTab] = useState<EditorTab>("model");
  if (adapterId.startsWith("RUNNINGHUB_")) return <div className="ui-stack"><fieldset disabled={disabled} className="ui-stack">
    <Field><FieldLabel className="ui-field block">{creating ? t("settings.mediaSettings.newCapabilityName") : t("settings.mediaSettings.capabilityName")}<Input required maxLength={NAME_LIMIT} value={name} onChange={(event) => onNameChange(event.target.value)} /></FieldLabel></Field>
    <Field><FieldLabel className="ui-field block">{t("settings.mediaSettings.primaryOutputKind")}<Select value={adapterId} onChange={(event) => onAdapterChange(event.target.value)}>{availableAdapters.map((adapter) => <option key={adapter} value={adapter}>{adapterLabel(adapter)}</option>)}</Select></FieldLabel></Field>
    <RunningHubDefinitionEditor connectionId={connectionId} adapterId={adapterId} value={values.runningHub} onChange={(runningHub) => onChange({ ...values, runningHub })} />
    <CapabilityConfigurationFields section="pricing" adapterId={adapterId} values={values} onChange={onChange} />
  </fieldset></div>;
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
    <Tabs value={tab} onValueChange={(value) => { const next = EDITOR_TABS.find((item) => item.id === value); if (next) setTab(next.id); }}>
    <TabsList className="w-full" aria-label={t("settings.mediaSettings.configurationSections")}>
      {EDITOR_TABS.map((item) => <TabsTrigger key={item.id} value={item.id}>{item.label}</TabsTrigger>)}
    </TabsList>
    {EDITOR_TABS.map((item) => <TabsContent key={item.id} value={item.id} data-editor-tab={item.id} forceMount hidden={tab !== item.id}>
      <FieldSet className="media-settings-fieldset" disabled={disabled}><FieldGroup className="ui-form-grid">
        {item.id === "model" ? <>
          <Field><FieldLabel className="ui-field block">{creating ? t("settings.mediaSettings.newCapabilityName") : t("settings.mediaSettings.capabilityName")}
            <Input value={name} onChange={(event) => onNameChange(event.target.value)} required maxLength={NAME_LIMIT} />
          </FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">{t("settings.mediaSettings.adapter")}<Select value={adapterId} onChange={(event) => onAdapterChange(event.target.value)}>
              {availableAdapters.map((adapter) => <option key={adapter} value={adapter}>{adapterLabel(adapter)}</option>)}
            </Select>
          </FieldLabel></Field>
          <FixedModelFields adapterId={adapterId} values={values} onChange={onChange} />
        </> : <CapabilityConfigurationFields section={item.id} adapterId={adapterId} values={values} onChange={onChange} />}
      </FieldGroup></FieldSet>
    </TabsContent>)}
    </Tabs>
  </div>;
}

function ConnectionCredentials({ platform, origin, apiKey, onOriginChange, onApiKeyChange, creating = false }: {
  platform: MediaConnection["platform"];
  origin: string;
  apiKey: string;
  onOriginChange: (value: string) => void;
  onApiKeyChange: (value: string) => void;
  creating?: boolean;
}) {
  useLocale();
  const helpId = useId();
  return <>
    <MediaConnectionAddressField key={platform} platform={platform} origin={origin} onChange={onOriginChange}
      describedBy={platform === "GOOGLE" ? helpId : undefined} />
    {platform === "GOOGLE" ? <GoogleImageConnectionHelp id={helpId} origin={origin} /> : null}
    {platform === "AUTODL" ? <p className="ui-muted">{t("settings.mediaSettings.comfyTokenHint")}</p> : null}
    {platform === "RUNNINGHUB" || platform === "OPENAI" || platform === "ARK" || platform === "GOOGLE" || platform === "VOLCENGINE" || platform === "AUTODL" ? <Field><FieldLabel className="ui-field block">
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
  return cause instanceof ApiError ? cause.message : t("settings.shared.saveFailed");
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

function CapabilityRow({ connectionId, connectionName, capability, isDefault, connectionEnabled,
  availableAdapters, busy, apply, act }: {
  connectionId: string;
  connectionName: string;
  capability: MediaCapability;
  isDefault: boolean;
  connectionEnabled: boolean;
  availableAdapters: string[];
  busy: boolean;
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
  const save = useMutation({
    mutationFn: () => updateMediaCapability(connectionId, capability.id, {
      expectedVersion: baseline.version, name: name.trim(),
      enabled: baseline.enabled, adapterId,
      settings: fixedModelSettings(adapterId, modelNames),
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
  const sameKindAdapters = availableAdapters.filter((id) => adapterMetadata(id)?.kind === capability.kind);
  const rowBusy = busy || save.isPending;
  function loadLatest() {
    acceptBaseline(capability);
    setName(capability.name);
    setAdapterId(capability.adapterId);
    setModelNames(capability.settings);
    setError("");
  }
  const CapabilityIcon = capability.kind === "IMAGE_GENERATION" ? ImageSquare : capability.kind === "AUDIO_GENERATION" ? MusicNotes : VideoCamera;
  return <>
    <TableRow>
      <TableCell><div className="media-table-name"><CapabilityIcon size={19} /><div>
        <strong>{capability.name}</strong><small>{connectionName}</small>
      </div></div></TableCell>
      <TableCell><strong>{adapterLabel(capability.adapterId)}</strong>
        <small>{capability.settings.model || adapterModel(capability.adapterId) || capability.adapterId}</small></TableCell>
      <TableCell><strong>{capability.kind === "VIDEO_GENERATION" ? t("settings.mediaSettings.durationRange", { "0": capability.minimumSeconds, "1": capability.maximumSeconds }) : capability.kind === "AUDIO_GENERATION" ? t("common.audio") : t("common.image")}</strong>
        <small>{t("settings.mediaSettings.referenceImageLimit", { "0": capability.maxReferenceImages })}</small></TableCell>
      <TableCell>{capability.settings.pricing ? <>{capability.settings.pricing.amount} {capability.settings.pricing.currency}
        <small>{t("settings.mediaSettings.unitPriceEstimate", { "0": capability.settings.pricing.unit === "SECOND" ? t("settings.mediaSettings.seconds") : capability.settings.pricing.unit === "VIDEO" ? t("settings.mediaSettings.videoUnit") : t("settings.mediaSettings.imageUnit") })}</small></> : <span className="ui-muted">{t("common.unset")}</span>}</TableCell>
      <TableCell><div className="media-table-badges">
        <StatusBadge tone={capability.enabled && connectionEnabled ? "success" : "warning"}>{capability.enabled && connectionEnabled ? t("settings.mediaSettings.enabled") : t("settings.mediaSettings.disabled")}</StatusBadge>
        {isDefault ? <StatusBadge>{t("settings.mediaSettings.default")}</StatusBadge> : null}
      </div></TableCell>
      <TableCell><div className="media-table-actions">
        <Button variant="outline"  type="button" disabled={rowBusy} onClick={() => setEditing(true)}>{t("settings.mediaSettings.editCapability")}</Button>
        <Button variant="ghost"  type="button" disabled={rowBusy || !connectionEnabled || !capability.enabled || isDefault}
          onClick={() => act("default", capability)}>{t("settings.mediaSettings.setDefault")}</Button>
        <Button variant="ghost"  type="button" disabled={rowBusy}
          onClick={() => act("capability", capability)}>{capability.enabled ? t("settings.mediaSettings.disable") : t("settings.mediaSettings.enable")}</Button>
      </div></TableCell>
    </TableRow>
    {editing ? <Dialog className={cn(adapterId === AUTODL_ADAPTER && "autodl-capability-dialog")} title={t("settings.mediaSettings.editCapabilityNamed", { "0": capability.name })} description={`${connectionName} · ${adapterLabel(adapterId)}`}
      onClose={() => setEditing(false)} busy={rowBusy} onSubmit={(event) => {
        event.preventDefault(); if (isStale || rowBusy) return; setError(""); save.mutate();
      }} footer={<>
        <Button variant="outline"  type="button" disabled={rowBusy} onClick={() => setEditing(false)}>{t("common.cancel")}</Button>
        <Button variant="default"  type="submit" disabled={rowBusy || isStale}>{save.isPending ? t("common.savingProgress") : t("settings.mediaSettings.saveCapability")}</Button>
      </>}>
      <div className="ui-stack">
        {isStale ? <ConfigurationUpdatedNotice scope={t("settings.mediaSettings.capabilityParameters")} disabled={rowBusy} onReload={loadLatest} /> : null}
        <CapabilityEditorFields connectionId={connectionId} name={name} onNameChange={setName} adapterId={adapterId}
          onAdapterChange={(value) => { setAdapterId(value); setModelNames({}); }} availableAdapters={sameKindAdapters}
          values={modelNames} onChange={setModelNames} disabled={rowBusy} />
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

function ConnectionCapabilities({ connection, settings, apply }: {
  connection: MediaConnection; settings: MediaSettings; apply: (value: MediaSettings) => void;
}) {
  useLocale();
  const mutate = useMediaActions(connection, settings, apply);
  const systemManaged = connection.platform === "LOCAL";
  const availableAdapters = platformAdapters[connection.platform];
  return <>
    {connection.capabilities.map((capability) => systemManaged ? <TableRow key={capability.id}>
      <TableCell><strong>{capability.name}</strong><small>{connection.name}</small></TableCell>
      <TableCell><strong>{adapterLabel(capability.adapterId)}</strong><small>{t("settings.mediaSettings.builtin")}</small></TableCell>
      <TableCell>{t("settings.mediaSettings.imageProcessing")}<small>{t("settings.mediaSettings.referenceImageLimit", { "0": capability.maxReferenceImages })}</small></TableCell>
      <TableCell>{t("settings.mediaSettings.localExecution")}</TableCell><TableCell><StatusBadge tone="success">{t("settings.mediaSettings.enabled")}</StatusBadge></TableCell><TableCell className="ui-muted">{t("settings.mediaSettings.administration")}</TableCell>
    </TableRow> : <CapabilityRow key={capability.id} connectionId={connection.id} connectionName={connection.name}
      capability={capability} isDefault={settings.defaults.some((item) => item.kind === capability.kind && item.capabilityId === capability.id)}
      connectionEnabled={connection.enabled} availableAdapters={availableAdapters} busy={mutate.isPending} apply={apply}
      act={(type, selected) => mutate.mutate({ type, capability: selected })} />)}
    {mutate.isError ? <TableRow><TableCell colSpan={6}><Notice tone="danger">{errorMessage(mutate.error)}</Notice></TableCell></TableRow> : null}
  </>;

}

function ConnectionRow({ connection, settings, apply }: {
  connection: MediaConnection;
  settings: MediaSettings;
  apply: (value: MediaSettings) => void;
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
  const [dialog, setDialog] = useState<"connection" | "capability" | null>(null);
  const capabilityCreateKey = useRef<{ payload: string; key: string } | null>(null);
  const save = useMutation({
    mutationFn: () => updateMediaConnection(connection.id, {
      expectedVersion: baseline.version, name: name.trim(), enabled: baseline.enabled,
      origin: connection.platform === "RUNNINGHUB" || connection.platform === "COMFYUI" || connection.platform === "OPENAI" || connection.platform === "GOOGLE"
        ? origin.trim() || null : null,
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
        settings: fixedModelSettings(adapterId, newModelNames) };
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
      setDialog(null);
    },
    onError: (cause) => {
      if (cause instanceof ApiError && cause.status === HTTP_STATUS.CONFLICT) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
      setError(errorMessage(cause));
    },
  });
  const mutate = useMediaActions(connection, settings, apply);
  const busy = save.isPending || addCapability.isPending || mutate.isPending;
  const systemManaged = connection.platform === "LOCAL";
  const availableAdapters = systemManaged ? [] : platformAdapters[connection.platform];

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
    <TableRow>
      <TableCell><strong>{connection.name}</strong><small>{t("settings.mediaSettings.capabilityCount", { "0": connection.platform, "1": connection.capabilities.length })}</small></TableCell>
      <TableCell><span className="media-table-endpoint">{connection.origin || (connection.platform === "ARK" ? "https://ark.cn-beijing.volces.com/api/v3" : systemManaged ? t("settings.mediaSettings.builtin") : t("settings.mediaSettings.platformDefaultUrl"))}</span>
        <small>{connection.keyMask ? t("settings.mediaSettings.credentialPrefix", { "0": connection.keyMask }) : ""}{systemManaged ? t("settings.mediaSettings.localImageProcessing") : t("common.configured")}</small>
        {connection.platform === "GOOGLE" ? <small>{googleImageApiLabel(connection.origin)}</small> : null}</TableCell>
      <TableCell><StatusBadge tone={connection.enabled ? "success" : "warning"}>{connection.enabled ? t("settings.mediaSettings.enabled") : t("settings.mediaSettings.disabled")}</StatusBadge></TableCell>
      <TableCell>{systemManaged ? <span className="ui-muted">{t("settings.mediaSettings.administration")}</span> : <div className="media-table-actions">
        <Button variant="outline"  type="button" disabled={busy} onClick={() => setDialog("connection")}>{t("settings.mediaSettings.editConnection")}</Button>
        <Button variant="outline"  type="button" disabled={busy || !connection.enabled || !availableAdapters.length}
          onClick={() => setDialog("capability")}>{t("settings.mediaSettings.publishCapability")}</Button>
        <Button variant="ghost"  type="button" disabled={busy}
          onClick={() => mutate.mutate({ type: "connection" })}>{connection.enabled ? t("settings.mediaSettings.disableConnection") : t("settings.mediaSettings.enableConnection")}</Button>
      </div>}</TableCell>
    </TableRow>
    {mutate.isError || (error && !dialog) || notice ? <TableRow><TableCell colSpan={4}>{mutate.isError || (error && !dialog) ? <Notice tone="danger">{mutate.isError ? errorMessage(mutate.error) : error}</Notice> : <Notice tone="success">{notice}</Notice>}</TableCell></TableRow> : null}
    {dialog === "connection" ? <Dialog title={t("settings.mediaSettings.editConnectionNamed", { "0": connection.name })} description={t("settings.mediaSettings.connectionTitle", { "0": connection.platform })}
      onClose={() => setDialog(null)} onSubmit={submitConnection} busy={busy} footer={<>
        <Button variant="outline"  type="button" disabled={busy} onClick={() => setDialog(null)}>{t("common.cancel")}</Button>
        <Button variant="default"  type="submit" disabled={busy || isStale}>{save.isPending ? t("common.savingProgress") : t("settings.shared.saveConnection")}</Button>
      </>}>
      <div className="ui-stack">
        {isStale ? <ConfigurationUpdatedNotice scope={t("settings.mediaSettings.connectionConfiguration")} disabled={busy} onReload={loadLatest} /> : null}
        <FieldSet className="media-settings-fieldset" disabled={busy}><FieldGroup className="ui-form-grid">
          <Field><FieldLabel className="ui-field block">{t("settings.shared.connectionName")}<Input value={name} onChange={(event) => setName(event.target.value)} required maxLength={NAME_LIMIT} /></FieldLabel></Field>
          <ConnectionCredentials platform={connection.platform} origin={origin} apiKey={apiKey} onOriginChange={setOrigin} onApiKeyChange={setApiKey} />
        </FieldGroup></FieldSet>
        {error ? <Notice tone="danger">{error}</Notice> : null}
      </div>
    </Dialog> : null}
    {dialog === "capability" ? <Dialog className={cn(adapterId === AUTODL_ADAPTER && "autodl-capability-dialog")} title={t("settings.mediaSettings.publishNewCapability")} description={`${connection.name} · ${adapterLabel(adapterId)}`}
      onClose={() => setDialog(null)} busy={busy} onSubmit={(event) => {
        event.preventDefault(); if (busy || !connection.enabled) return; setError(""); addCapability.mutate();
      }} footer={<>
        <Button variant="outline"  type="button" disabled={busy} onClick={() => setDialog(null)}>{t("common.cancel")}</Button>
        <Button variant="default"  type="submit" disabled={busy || !connection.enabled}>{addCapability.isPending ? t("settings.mediaSettings.publishing") : t("settings.mediaSettings.publishCapability")}</Button>
      </>}>
      <div className="ui-stack">
        <CapabilityEditorFields connectionId={connection.id} name={capabilityName} onNameChange={setCapabilityName} adapterId={adapterId}
          onAdapterChange={(value) => { setAdapterId(value); setNewModelNames({}); }} availableAdapters={availableAdapters}
          values={newModelNames} onChange={setNewModelNames} creating disabled={busy || !connection.enabled} />
        {!connection.enabled ? <Notice tone="warning">{t("settings.mediaSettings.enableBeforePublish")}</Notice> : null}
        {error ? <Notice tone="danger">{error}</Notice> : null}
      </div>
    </Dialog> : null}
  </>;
}

export function MediaSettingsPage() {
  useLocale();
  const queryClient = useQueryClient();
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const settings = useQuery({ queryKey: settingsKey, queryFn: getMediaSettings,
    enabled: currentUser.isSuccess, retry: false });
  const [name, setName] = useState("");
  const [platform, setPlatform] = useState<"RUNNINGHUB" | "COMFYUI" | "OPENAI" | "ARK" | "GOOGLE" | "VOLCENGINE" | "AUTODL">("COMFYUI");
  const [origin, setOrigin] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [error, setError] = useState("");
  const [creating, setCreating] = useState(false);
  const connectionCreateKey = useRef<{ payload: string; key: string } | null>(null);
  const create = useMutation({
    mutationFn: () => {
      const payload = { name: name.trim(), platform,
        origin: platform === "RUNNINGHUB" || platform === "COMFYUI" || platform === "OPENAI" || platform === "GOOGLE"
          ? origin.trim() || null : null,
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
  return <PageShell title={t("settings.mediaSettings.title")}
    description={t("settings.mediaSettings.managementHint")}
    actions={<Link className="secondary-button" to="/settings/llm">{t("settings.mediaSettings.llmSettings")}</Link>}>
    <div className="media-settings-page ui-stack">
      {settings.data ? <SummaryStrip items={[
        { label: t("settings.mediaSettings.connections"), value: t("settings.mediaSettings.itemCount", { "0": settings.data.connections.length }), detail: t("settings.mediaSettings.connectionTypes") },
        { label: t("settings.mediaSettings.capabilities"), value: t("settings.mediaSettings.itemCount", { "0": settings.data.connections.filter((connection) => connection.enabled)
          .flatMap((connection) => connection.capabilities).filter((capability) => capability.enabled).length }),
          detail: t("settings.mediaSettings.enabledCapabilities") },
        { label: t("settings.mediaSettings.defaultCapability"), value: t("settings.mediaSettings.fieldCount", { "0": settings.data.defaults.filter((item) => item.capabilityId).length }), detail: t("settings.mediaSettings.defaultsForNewRequests") },
      ]} /> : null}
      <Notice title={t("settings.mediaSettings.configurationHint")}>
        {t("settings.mediaSettings.defaultsHint")}</Notice>
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
        <Panel title={t("settings.mediaSettings.connections")} description={t("settings.mediaSettings.publishHint")}
          actions={<Button variant="default"  type="button" onClick={() => setCreating(true)}><Plus size={15} />{t("settings.mediaSettings.addConnection")}</Button>}>
          {settings.data.connections.length === 0 ? <EmptyState icon={<PlugsConnected size={30} />} title={t("settings.mediaSettings.connectionsEmpty")}
            description={t("settings.mediaSettings.connectionsEmptyHint")} /> : <div className="media-table-scroll" tabIndex={0} role="region" aria-label={t("settings.mediaSettings.connectionsTable")}>
            <Table className="media-settings-table" aria-label={t("settings.mediaSettings.connections")}><TableHeader><TableRow>
              <TableHead scope="col">{t("settings.mediaSettings.connectionPlatform")}</TableHead><TableHead scope="col">{t("settings.mediaSettings.credentials")}</TableHead><TableHead scope="col">{t("settings.mediaSettings.status")}</TableHead><TableHead scope="col">{t("settings.mediaSettings.operations")}</TableHead>
            </TableRow></TableHeader><TableBody>{settings.data.connections.map((connection) => <ConnectionRow key={connection.id}
              connection={connection} settings={settings.data} apply={(result) => queryClient.setQueryData(settingsKey, result)} />)}</TableBody></Table>
          </div>}
        </Panel>
        <Panel title={t("settings.mediaSettings.publishedCapabilities")} description={t("settings.mediaSettings.description")}>
          {settings.data.connections.every((connection) => connection.capabilities.length === 0) ? <p className="ui-muted">{t("settings.mediaSettings.capabilitiesEmpty")}</p>
            : <div className="media-table-scroll" tabIndex={0} role="region" aria-label={t("settings.mediaSettings.capabilitiesTable")}>
              <Table className="media-settings-table" aria-label={t("settings.mediaSettings.publishedCapabilities")}><TableHeader><TableRow>
                <TableHead scope="col">{t("settings.mediaSettings.capabilityConnection")}</TableHead><TableHead scope="col">{t("common.model")}</TableHead><TableHead scope="col">{t("settings.mediaSettings.inputRange")}</TableHead><TableHead scope="col">{t("settings.mediaSettings.price")}</TableHead><TableHead scope="col">{t("settings.mediaSettings.status")}</TableHead><TableHead scope="col">{t("settings.mediaSettings.operations")}</TableHead>
              </TableRow></TableHeader><TableBody>{settings.data.connections.map((connection) => <ConnectionCapabilities key={connection.id}
                connection={connection} settings={settings.data} apply={(result) => queryClient.setQueryData(settingsKey, result)} />)}</TableBody></Table>
            </div>}
        </Panel>
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
                  <option value="AUTODL">{t("settings.mediaSettings.autoDlWorkflow")}</option><option value="GOOGLE">Google Gemini · Nano Banana 2</option><option value="ARK">{t("settings.mediaSettings.arkPlatform")}</option><option value="VOLCENGINE">{t("settings.mediaSettings.seedAudioPlatform")}</option>
                </Select>
              </FieldLabel></Field>
              <ConnectionCredentials platform={platform} origin={origin} apiKey={apiKey}
                onOriginChange={setOrigin} onApiKeyChange={setApiKey} creating />
            </FieldGroup></FieldSet>
            {error ? <Notice tone="danger">{error}</Notice> : null}
          </div>
        </Dialog> : null}
      </> : null}
    </div>
  </PageShell>;
}
