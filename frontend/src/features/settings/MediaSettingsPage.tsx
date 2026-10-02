import { Field, FieldLabel, FieldGroup, FieldSet } from "../../shared/ui/primitives/field";
import { ImageSquare,MusicNotes,PlugsConnected,Plus,VideoCamera } from "@phosphor-icons/react";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
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
import { adapterLabel,adapterMetadata,adapterModel,platformAdapters } from "./mediaAdapterCatalog";
import "./MediaSettingsPage.css";
import { RunningHubDefinitionEditor } from "./RunningHubDefinitionEditor";
type AdapterSettings = MediaCapability["settings"];
const MODEL_LIMIT = 120;

const settingsKey = ["settings", "media"] as const;
const NAME_LIMIT = 160;
const ORIGIN_LIMIT = 500;
const comfyImageFields = [{ key: "checkpoint", get label() { return t("图片 checkpoint 文件名"); } }] as const;
const comfyVideoFields = [
  { key: "diffusionModel", get label() { return t("视频扩散模型文件名"); } },
  { key: "textEncoder", get label() { return t("文本编码器文件名"); } },
  { key: "vae", get label() { return t("VAE 文件名"); } },
  { key: "clipVision", get label() { return t("CLIP Vision 文件名"); } },
] as const;

const cloudImageFields = [{ key: "model", get label() { return t("模型名（留空用内置默认）"); } }] as const;

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
    maxReferenceImages, maxReferenceAudios, pricing } = values;
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
    ...(pricing?.amount.trim() ? { pricing } : {}) };

}

function QualityChoice({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  useLocale();
  return <Field><FieldLabel className="ui-field block">{t("GPT Image 2 质量")}<Select value={value} onChange={(event) => onChange(event.target.value)}>
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
    {adapterModel(adapterId) && !["ARK_SEEDANCE_2_I2V", "VOLC_SEED_AUDIO_1"].includes(adapterId) ? <Field><FieldLabel className="ui-field block">{t("模型选项")}<Select value={values.model ? "custom" : "builtin"} onChange={(event) => onChange({ ...values,
        model: event.target.value === "builtin" ? "" : adapterModel(adapterId) })}>
        <option value="builtin">{t("内置模型 · {0}", { "0": adapterModel(adapterId) })}</option>
        <option value="custom">{t("自定义兼容模型")}</option>
      </Select>
    </FieldLabel></Field> : null}
    {["ARK_SEEDANCE_2_I2V", "VOLC_SEED_AUDIO_1"].includes(adapterId) ? <Field><FieldLabel className="ui-field block">{t("固定模型")}<Input value={adapterModel(adapterId)} readOnly />
    </FieldLabel></Field> : null}
    {fixedModelFields(adapterId).map(({ key, label }) => <Field key={key}><FieldLabel className="ui-field block">{label}
      <Input value={String(values[key as keyof AdapterSettings] ?? "")} onChange={(event) => onChange({ ...values, [key]: event.target.value })}
        required={key !== "model"} maxLength={key === "model" ? MODEL_LIMIT : NAME_LIMIT}
        placeholder={key === "model" ? adapterModel(adapterId) ?? t("留空使用内置默认") : "model.safetensors"} />
    </FieldLabel></Field>)}
    {adapterId === "GOOGLE_NANO_BANANA_2" ? <p className="ui-muted media-model-help">
      {t("直连官方服务可使用内置模型；中转站请选择“自定义兼容模型”并填写服务商提供的模型名，例如 nano-banana-2-lite。 Gemini v1 / v1beta 请求格式由连接的 API 地址决定，与模型名分开配置。")}</p> : null}
    {adapterId === "OPENAI_GPT_IMAGE_2" ? <QualityChoice value={values.quality ?? "medium"}
      onChange={(quality) => onChange({ ...values, quality: quality as AdapterSettings["quality"],
        ...(values.defaultParameters ? { defaultParameters: { ...values.defaultParameters, quality: quality as AdapterSettings["quality"] } } : {}) })} /> : null}
  </>;
}

const EDITOR_TABS = [
  { id: "model", get label() { return t("模型配置"); } }, { id: "defaults", get label() { return t("默认参数"); } },
  { id: "limits", get label() { return t("输入限制"); } }, { id: "pricing", get label() { return t("估算价格"); } },
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
    <Field><FieldLabel className="ui-field block">{creating ? t("新能力名称") : t("能力名称")}<Input required maxLength={NAME_LIMIT} value={name} onChange={(event) => onNameChange(event.target.value)} /></FieldLabel></Field>
    <Field><FieldLabel className="ui-field block">{t("主输出类型")}<Select value={adapterId} onChange={(event) => onAdapterChange(event.target.value)}>{availableAdapters.map((adapter) => <option key={adapter} value={adapter}>{adapterLabel(adapter)}</option>)}</Select></FieldLabel></Field>
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
    <TabsList className="w-full" aria-label={t("能力配置分区")}>
      {EDITOR_TABS.map((item) => <TabsTrigger key={item.id} value={item.id}>{item.label}</TabsTrigger>)}
    </TabsList>
    {EDITOR_TABS.map((item) => <TabsContent key={item.id} value={item.id} data-editor-tab={item.id} forceMount hidden={tab !== item.id}>
      <FieldSet className="media-settings-fieldset" disabled={disabled}><FieldGroup className="ui-form-grid">
        {item.id === "model" ? <>
          <Field><FieldLabel className="ui-field block">{creating ? t("新能力名称") : t("能力名称")}
            <Input value={name} onChange={(event) => onNameChange(event.target.value)} required maxLength={NAME_LIMIT} />
          </FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">{t("固定适配器")}<Select value={adapterId} onChange={(event) => onAdapterChange(event.target.value)}>
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
    {platform === "RUNNINGHUB" ? <Field><FieldLabel className="ui-field block">{t("RunningHub API 地址")}<Input value={origin} onChange={(event) => onOriginChange(event.target.value)} placeholder="https://www.runninghub.ai" /></FieldLabel></Field> : null}
    {platform === "COMFYUI" ? <Field><FieldLabel className="ui-field block">{t("本机 ComfyUI 地址")}<Input value={origin} onChange={(event) => onOriginChange(event.target.value)}
        required placeholder="http://127.0.0.1:8188" />
    </FieldLabel></Field> : null}
    {platform === "OPENAI" || platform === "GOOGLE" ? <Field><FieldLabel className="ui-field block">{t("API Base URL（留空使用官方地址）")}<Input type="url" value={origin} onChange={(event) => onOriginChange(event.target.value)}
        aria-describedby={platform === "GOOGLE" ? helpId : undefined}
        maxLength={ORIGIN_LIMIT} placeholder={platform === "GOOGLE" ? "https://generativelanguage.googleapis.com" : "https://api.openai.com/v1"} />
    </FieldLabel></Field> : null}
    {platform === "GOOGLE" ? <GoogleImageConnectionHelp id={helpId} origin={origin} /> : null}
    {platform === "AUTODL" ? <><Field><FieldLabel className="ui-field block">{t("固定 API 地址")}<Input value="https://autodl.art/api/v1/comfyui/comfyui_workflow" readOnly /></FieldLabel></Field><p className="ui-muted">{t("填写令牌管理中分组为 ComfyUI 的 Token。任务异步查询，取消不保证外部停止或退款。")}</p></> : null}
    {platform === "VOLCENGINE" ? <Field><FieldLabel className="ui-field block">{t("固定 API 地址")}<Input value="https://openspeech.bytedance.com/api/v3/tts/create" readOnly /></FieldLabel></Field> : null}
    {platform === "ARK" ? <Field><FieldLabel className="ui-field block">{t("固定 API 地址")}<Input value="https://ark.cn-beijing.volces.com/api/v3" readOnly />
    </FieldLabel></Field> : null}
    {platform === "RUNNINGHUB" || platform === "OPENAI" || platform === "ARK" || platform === "GOOGLE" || platform === "VOLCENGINE" || platform === "AUTODL" ? <Field><FieldLabel className="ui-field block">
      {creating ? "API Key" : t("替换 API Key（留空则不修改）")}
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
  return cause instanceof ApiError ? cause.message : t("保存失败，请重试。");
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
  return <Notice tone="warning" title={t("配置已有新版本")}>
    <p>{t("当前草稿已保留。载入最新版本将替换{0}的未保存内容。", { "0": scope })}</p>
    <Button variant="outline"  type="button" disabled={disabled} onClick={onReload}>{t("载入最新版本")}</Button>
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
      <TableCell><strong>{capability.kind === "VIDEO_GENERATION" ? t("{0}–{1} 秒", { "0": capability.minimumSeconds, "1": capability.maximumSeconds }) : capability.kind === "AUDIO_GENERATION" ? t("音频") : t("图片")}</strong>
        <small>{t("最多 {0} 张参考图", { "0": capability.maxReferenceImages })}</small></TableCell>
      <TableCell>{capability.settings.pricing ? <>{capability.settings.pricing.amount} {capability.settings.pricing.currency}
        <small>{t("每{0} · 估算", { "0": capability.settings.pricing.unit === "SECOND" ? t("秒") : capability.settings.pricing.unit === "VIDEO" ? t("个视频") : t("张图片") })}</small></> : <span className="ui-muted">{t("未设置")}</span>}</TableCell>
      <TableCell><div className="media-table-badges">
        <StatusBadge tone={capability.enabled && connectionEnabled ? "success" : "warning"}>{capability.enabled && connectionEnabled ? t("已启用") : t("已停用")}</StatusBadge>
        {isDefault ? <StatusBadge>{t("默认")}</StatusBadge> : null}
      </div></TableCell>
      <TableCell><div className="media-table-actions">
        <Button variant="outline"  type="button" disabled={rowBusy} onClick={() => setEditing(true)}>{t("编辑能力参数")}</Button>
        <Button variant="ghost"  type="button" disabled={rowBusy || !connectionEnabled || !capability.enabled || isDefault}
          onClick={() => act("default", capability)}>{t("设为默认")}</Button>
        <Button variant="ghost"  type="button" disabled={rowBusy}
          onClick={() => act("capability", capability)}>{capability.enabled ? t("停用") : t("启用")}</Button>
      </div></TableCell>
    </TableRow>
    {editing ? <Dialog title={t("编辑能力 · {0}", { "0": capability.name })} description={`${connectionName} · ${adapterLabel(adapterId)}`}
      onClose={() => setEditing(false)} busy={rowBusy} onSubmit={(event) => {
        event.preventDefault(); if (isStale || rowBusy) return; setError(""); save.mutate();
      }} footer={<>
        <Button variant="outline"  type="button" disabled={rowBusy} onClick={() => setEditing(false)}>{t("取消")}</Button>
        <Button variant="default"  type="submit" disabled={rowBusy || isStale}>{save.isPending ? t("正在保存…") : t("保存能力")}</Button>
      </>}>
      <div className="ui-stack">
        {isStale ? <ConfigurationUpdatedNotice scope={t("能力参数")} disabled={rowBusy} onReload={loadLatest} /> : null}
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
      if (!capability) throw new Error(t("请选择能力"));
      if (action.type === "capability") {
        return updateMediaCapability(connection.id, capability.id, {
          expectedVersion: capability.version, name: capability.name,
          enabled: !capability.enabled, adapterId: capability.adapterId,
          settings: capability.settings,
        });
      }
      const current = settings.defaults.find((item) => item.kind === capability.kind);
      if (!current) throw new Error(t("找不到默认能力版本"));
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
      <TableCell><strong>{adapterLabel(capability.adapterId)}</strong><small>{t("应用内置")}</small></TableCell>
      <TableCell>{t("图片后处理")}<small>{t("最多 {0} 张参考图", { "0": capability.maxReferenceImages })}</small></TableCell>
      <TableCell>{t("本地执行")}</TableCell><TableCell><StatusBadge tone="success">{t("已启用")}</StatusBadge></TableCell><TableCell className="ui-muted">{t("系统管理")}</TableCell>
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
      setNotice(t("连接已保存。"));
      setDialog(null);
    },
    onError: (cause) => {
      setNotice("");
      if (cause instanceof ApiError && cause.status === HTTP_STATUS.CONFLICT) {
        void queryClient.invalidateQueries({ queryKey: settingsKey });
      }
      setError(cause instanceof ApiError && cause.status === HTTP_STATUS.CONFLICT
        ? t("配置已被其他管理员修改，已请求刷新；当前草稿已保留。")
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
      setNotice(t("能力已发布。"));
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
      <TableCell><strong>{connection.name}</strong><small>{t("{0} · {1} 个能力", { "0": connection.platform, "1": connection.capabilities.length })}</small></TableCell>
      <TableCell><span className="media-table-endpoint">{connection.origin || (connection.platform === "ARK" ? "https://ark.cn-beijing.volces.com/api/v3" : systemManaged ? t("应用内置") : t("平台默认地址"))}</span>
        <small>{connection.keyMask ? t("密钥 {0} · ", { "0": connection.keyMask }) : ""}{systemManaged ? t("本地图片后处理") : t("已配置")}</small>
        {connection.platform === "GOOGLE" ? <small>{googleImageApiLabel(connection.origin)}</small> : null}</TableCell>
      <TableCell><StatusBadge tone={connection.enabled ? "success" : "warning"}>{connection.enabled ? t("已启用") : t("已停用")}</StatusBadge></TableCell>
      <TableCell>{systemManaged ? <span className="ui-muted">{t("系统管理")}</span> : <div className="media-table-actions">
        <Button variant="outline"  type="button" disabled={busy} onClick={() => setDialog("connection")}>{t("编辑连接")}</Button>
        <Button variant="outline"  type="button" disabled={busy || !connection.enabled || !availableAdapters.length}
          onClick={() => setDialog("capability")}>{t("发布能力")}</Button>
        <Button variant="ghost"  type="button" disabled={busy}
          onClick={() => mutate.mutate({ type: "connection" })}>{connection.enabled ? t("停用连接") : t("启用连接")}</Button>
      </div>}</TableCell>
    </TableRow>
    {mutate.isError || (error && !dialog) || notice ? <TableRow><TableCell colSpan={4}>{mutate.isError || (error && !dialog) ? <Notice tone="danger">{mutate.isError ? errorMessage(mutate.error) : error}</Notice> : <Notice tone="success">{notice}</Notice>}</TableCell></TableRow> : null}
    {dialog === "connection" ? <Dialog title={t("编辑连接 · {0}", { "0": connection.name })} description={t("{0} · API 地址与凭证", { "0": connection.platform })}
      onClose={() => setDialog(null)} onSubmit={submitConnection} busy={busy} footer={<>
        <Button variant="outline"  type="button" disabled={busy} onClick={() => setDialog(null)}>{t("取消")}</Button>
        <Button variant="default"  type="submit" disabled={busy || isStale}>{save.isPending ? t("正在保存…") : t("保存连接")}</Button>
      </>}>
      <div className="ui-stack">
        {isStale ? <ConfigurationUpdatedNotice scope={t("连接配置")} disabled={busy} onReload={loadLatest} /> : null}
        <FieldSet className="media-settings-fieldset" disabled={busy}><FieldGroup className="ui-form-grid">
          <Field><FieldLabel className="ui-field block">{t("连接名称")}<Input value={name} onChange={(event) => setName(event.target.value)} required maxLength={NAME_LIMIT} /></FieldLabel></Field>
          <ConnectionCredentials platform={connection.platform} origin={origin} apiKey={apiKey} onOriginChange={setOrigin} onApiKeyChange={setApiKey} />
        </FieldGroup></FieldSet>
        {error ? <Notice tone="danger">{error}</Notice> : null}
      </div>
    </Dialog> : null}
    {dialog === "capability" ? <Dialog title={t("发布新能力")} description={`${connection.name} · ${adapterLabel(adapterId)}`}
      onClose={() => setDialog(null)} busy={busy} onSubmit={(event) => {
        event.preventDefault(); if (busy || !connection.enabled) return; setError(""); addCapability.mutate();
      }} footer={<>
        <Button variant="outline"  type="button" disabled={busy} onClick={() => setDialog(null)}>{t("取消")}</Button>
        <Button variant="default"  type="submit" disabled={busy || !connection.enabled}>{addCapability.isPending ? t("正在发布…") : t("发布能力")}</Button>
      </>}>
      <div className="ui-stack">
        <CapabilityEditorFields connectionId={connection.id} name={capabilityName} onNameChange={setCapabilityName} adapterId={adapterId}
          onAdapterChange={(value) => { setAdapterId(value); setNewModelNames({}); }} availableAdapters={availableAdapters}
          values={newModelNames} onChange={setNewModelNames} creating disabled={busy || !connection.enabled} />
        {!connection.enabled ? <Notice tone="warning">{t("启用连接后可发布新能力。")}</Notice> : null}
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
  return <PageShell title={t("媒体连接与能力")}
    description={t("管理图片与视频模型，让创作使用合适的生成能力。")}
    actions={<Link className="secondary-button" to="/settings/llm">{t("LLM 设置")}</Link>}>
    <div className="media-settings-page ui-stack">
      {settings.data ? <SummaryStrip items={[
        { label: t("媒体连接"), value: t("{0} 个", { "0": settings.data.connections.length }), detail: t("本机服务与云端平台") },
        { label: t("可用能力"), value: t("{0} 个", { "0": settings.data.connections.filter((connection) => connection.enabled)
          .flatMap((connection) => connection.capabilities).filter((capability) => capability.enabled).length }),
          detail: t("已启用连接中的已启用能力") },
        { label: t("默认能力"), value: t("{0} 项", { "0": settings.data.defaults.filter((item) => item.capabilityId).length }), detail: t("新请求使用当前默认值") },
      ]} /> : null}
      <Notice title={t("配置与生成彼此独立")}>
        {t("默认配置补充未填写的草稿参数，已受理任务保留固定输入。保存配置不会调用付费生成接口，实际生成结果见调用日志。")}</Notice>
      {currentUser.isSuccess && settings.isPending ? <LoadingState label={t("正在读取媒体配置…")} /> : null}
      {settings.error instanceof ApiError && settings.error.status === HTTP_STATUS.FORBIDDEN
        ? <Notice tone="warning" title={t("需要管理员权限")}>{t("只有管理员可以查看媒体配置。")}</Notice> : null}
      {settings.isError && !(settings.error instanceof ApiError && (settings.error.status === HTTP_STATUS.UNAUTHORIZED || settings.error.status === HTTP_STATUS.FORBIDDEN))
        ? <Notice tone="danger" title={t("读取失败")}>
          <p>{t("读取失败，请刷新后重试。")}</p>
          <Button variant="outline"  type="button" onClick={() => { void settings.refetch(); }}
            disabled={settings.isFetching}>{settings.isFetching ? t("正在重试…") : t("重新读取")}</Button>
        </Notice> : null}
      {settings.data ? <>
        <Panel title={t("媒体连接")} description={t("配置平台地址与凭证，然后为连接发布能力。")}
          actions={<Button variant="default"  type="button" onClick={() => setCreating(true)}><Plus size={15} />{t("添加连接")}</Button>}>
          {settings.data.connections.length === 0 ? <EmptyState icon={<PlugsConnected size={30} />} title={t("尚无媒体连接")}
            description={t("添加一个连接，再发布图片或视频能力。")} /> : <div className="media-table-scroll" tabIndex={0} role="region" aria-label={t("媒体连接表格")}>
            <Table className="media-settings-table" aria-label={t("媒体连接")}><TableHeader><TableRow>
              <TableHead scope="col">{t("连接 / 平台")}</TableHead><TableHead scope="col">{t("地址 / 凭证")}</TableHead><TableHead scope="col">{t("状态")}</TableHead><TableHead scope="col">{t("操作")}</TableHead>
            </TableRow></TableHeader><TableBody>{settings.data.connections.map((connection) => <ConnectionRow key={connection.id}
              connection={connection} settings={settings.data} apply={(result) => queryClient.setQueryData(settingsKey, result)} />)}</TableBody></Table>
          </div>}
        </Panel>
        <Panel title={t("已发布能力")} description={t("查看模型、输入范围和估算价格，直接切换启用状态与默认能力。")}>
          {settings.data.connections.every((connection) => connection.capabilities.length === 0) ? <p className="ui-muted">{t("尚无能力，请在连接表中发布。")}</p>
            : <div className="media-table-scroll" tabIndex={0} role="region" aria-label={t("生成能力表格")}>
              <Table className="media-settings-table" aria-label={t("已发布能力")}><TableHeader><TableRow>
                <TableHead scope="col">{t("能力 / 连接")}</TableHead><TableHead scope="col">{t("模型")}</TableHead><TableHead scope="col">{t("输入范围")}</TableHead><TableHead scope="col">{t("价格")}</TableHead><TableHead scope="col">{t("状态")}</TableHead><TableHead scope="col">{t("操作")}</TableHead>
              </TableRow></TableHeader><TableBody>{settings.data.connections.map((connection) => <ConnectionCapabilities key={connection.id}
                connection={connection} settings={settings.data} apply={(result) => queryClient.setQueryData(settingsKey, result)} />)}</TableBody></Table>
            </div>}
        </Panel>
        {creating ? <Dialog title={t("添加连接")} description={t("连接本机 ComfyUI 或云端生成平台。")} busy={create.isPending}
          onClose={() => setCreating(false)} onSubmit={(event) => { event.preventDefault(); if (create.isPending) return; setError(""); create.mutate(); }}
          footer={<>
            <Button variant="outline"  type="button" disabled={create.isPending} onClick={() => setCreating(false)}>{t("取消")}</Button>
            <Button variant="default"  type="submit" disabled={create.isPending}>{create.isPending ? t("正在保存…") : t("添加连接")}</Button>
          </>}>
          <div className="ui-stack">
            <FieldSet className="media-settings-fieldset" disabled={create.isPending}><FieldGroup className="ui-form-grid">
              <Field><FieldLabel className="ui-field block">{t("连接名称")}<Input value={name} onChange={(event) => setName(event.target.value)} required maxLength={NAME_LIMIT} />
              </FieldLabel></Field>
              <Field><FieldLabel className="ui-field block">{t("平台")}<Select value={platform} onChange={(event) => { setPlatform(event.target.value as typeof platform); setOrigin(""); setApiKey(""); }}>
                  <option value="RUNNINGHUB">RunningHub</option><option value="COMFYUI">ComfyUI</option><option value="OPENAI">OpenAI</option>
                  <option value="AUTODL">{t("AutoDL · ComfyUI 工作流")}</option><option value="GOOGLE">Google Gemini · Nano Banana 2</option><option value="ARK">{t("火山方舟")}</option><option value="VOLCENGINE">{t("火山引擎 · Seed Audio")}</option>
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
