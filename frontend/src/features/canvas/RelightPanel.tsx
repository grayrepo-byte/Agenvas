import { t, useLocale } from "../../shared/i18n";
import { Select } from "../../shared/ui/Select";
import { ArrowUp, Stack, X } from "@phosphor-icons/react";
import { useState, type PointerEvent } from "react";
import { createPortal } from "react-dom";
import { ApiError, type MediaCapability, type RunImageOperationRequest } from "../../shared/api/client";

type RelightParameters = RunImageOperationRequest["parameters"];
type LightingPreset = NonNullable<RelightParameters["lightingPreset"]>;

const MIN_BRIGHTNESS = -100;
const MAX_BRIGHTNESS = 100;
const MIN_COLOR_TEMPERATURE = 2000;
const MAX_COLOR_TEMPERATURE = 10000;

const PRESETS: ReadonlyArray<{
  id: LightingPreset;
  label: string;
  brightness: number;
  colorTemperature: number;
  filter: string;
}> = [
  { id: "GOLDEN_HOUR", get label() { return t("黄金时刻"); }, brightness: 10, colorTemperature: 3200,
    filter: "brightness(1.08) saturate(1.12) sepia(.22)" },
  { id: "BLUE_HOUR", get label() { return t("蓝调时刻"); }, brightness: -6, colorTemperature: 7600,
    filter: "brightness(.94) saturate(.9) hue-rotate(172deg)" },
  { id: "OVERCAST_SOFT", get label() { return t("阴天柔光"); }, brightness: 5, colorTemperature: 6500,
    filter: "brightness(1.05) saturate(.72) contrast(.9)" },
  { id: "MOONLIGHT", get label() { return t("月光"); }, brightness: -24, colorTemperature: 8200,
    filter: "brightness(.76) saturate(.62) hue-rotate(174deg) contrast(1.08)" },
  { id: "SOFT_STUDIO", get label() { return t("柔光棚拍"); }, brightness: 16, colorTemperature: 5200,
    filter: "brightness(1.16) saturate(.86) contrast(.92)" },
  { id: "NEON_NIGHT", get label() { return t("霓虹夜色"); }, brightness: 2, colorTemperature: 7000,
    filter: "brightness(1.02) saturate(1.42) hue-rotate(22deg) contrast(1.12)" },
];

/** Dedicated AI relighting workspace based on the selected canvas image. */
export function RelightPanel({ sourceUrl, capabilities, busy, error, onClose, onSubmit }: {
  sourceUrl: string;
  capabilities: MediaCapability[];
  busy: boolean;
  error: Error | null;
  onClose: () => void;
  onSubmit: (parameters: RelightParameters, instruction?: string,
    capabilityId?: string) => void;
}) {
  useLocale();
  const [preset, setPreset] = useState<LightingPreset>("GOLDEN_HOUR");
  const [brightness, setBrightness] = useState(10);
  const [colorTemperature, setColorTemperature] = useState(3200);
  const [light, setLight] = useState({ x: 0.15, y: 0.75 });
  const [instruction, setInstruction] = useState("");
  const [capabilityId, setCapabilityId] = useState(capabilities[0]?.id ?? "");
  const temperatureOffset = (colorTemperature - 6000) / 4000;
  const previewFilter = `brightness(${Math.max(0.2, 1 + brightness / 100)}) `
    + `sepia(${Math.max(0, -temperatureOffset) * 0.3}) `
    + `hue-rotate(${Math.max(0, temperatureOffset) * 165}deg)`;
  const canSubmit = !busy && Boolean(capabilityId);

  function selectPreset(next: typeof PRESETS[number]) {
    setPreset(next.id);
    setBrightness(next.brightness);
    setColorTemperature(next.colorTemperature);
  }

  function moveLight(event: PointerEvent<HTMLDivElement>) {
    const bounds = event.currentTarget.getBoundingClientRect();
    if (!bounds.width || !bounds.height) return;
    setLight({
      x: Math.min(1, Math.max(0, (event.clientX - bounds.left) / bounds.width)),
      y: Math.min(1, Math.max(0, (event.clientY - bounds.top) / bounds.height)),
    });
  }

  function submit() {
    if (!canSubmit) return;
    onSubmit({ lightingPreset: preset, brightness, colorTemperature,
      lightX: Number(light.x.toFixed(3)), lightY: Number(light.y.toFixed(3)) },
    instruction.trim(), capabilityId);
  }

  return createPortal(<div className="relight-dialog nodrag nowheel nopan" role="dialog"
    aria-label={t("打光")} onKeyDown={(event) => { if (event.key === "Escape") onClose(); }}>
    <header className="relight-dialog-header">
      <strong>{t("打光")}</strong>
      <button type="button" aria-label={t("关闭打光面板")} onClick={onClose}><X size={19} /></button>
    </header>

    <div className="relight-dialog-body">
      <section className="relight-controls" aria-label={t("光线参数")}>
        <div className="relight-preview-stage" aria-label={t("点击设置光源位置")}
          onPointerDown={moveLight}>
          <div className="relight-preview-orbit">
            <img src={sourceUrl} alt={t("当前图片打光预览")} draggable={false}
              style={{ filter: previewFilter }} />
            <span className="relight-source-dot" aria-hidden="true" style={{
              left: `${light.x * 100}%`, top: `${light.y * 100}%`,
            }} />
          </div>
        </div>

        <label className="relight-slider-label">
          <span>{t("亮度 ")}<output>{brightness > 0 ? "+" : ""}{brightness}</output></span>
          <input type="range" min={MIN_BRIGHTNESS} max={MAX_BRIGHTNESS} value={brightness}
            onChange={(event) => setBrightness(Number(event.target.value))} />
        </label>
        <label className="relight-slider-label relight-temperature">
          <span>{t("色温 ")}<output>{colorTemperature}K</output></span>
          <input type="range" min={MIN_COLOR_TEMPERATURE} max={MAX_COLOR_TEMPERATURE}
            step={100} value={colorTemperature}
            onChange={(event) => setColorTemperature(Number(event.target.value))} />
        </label>
      </section>

      <section className="relight-style-section" aria-label={t("预设风格")}>
        <span className="relight-section-label">{t("预设风格")}</span>
        <div className="relight-preset-grid">
          {PRESETS.map((entry) => <button type="button" key={entry.id}
            className={preset === entry.id ? "is-selected" : ""}
            aria-pressed={preset === entry.id} onClick={() => selectPreset(entry)}>
            <img src={sourceUrl} alt="" draggable={false} style={{ filter: entry.filter }} />
            <span>{entry.label}</span>
          </button>)}
        </div>
        <textarea value={instruction} maxLength={4000}
          aria-label={t("补充打光描述")} placeholder={t("简单描述你想要的打光效果")}
          onChange={(event) => setInstruction(event.target.value)} />
      </section>
    </div>

    {error ? <p className="relight-error" role="alert">
      {error instanceof ApiError ? error.message : t("打光任务受理失败，请重试。")}</p> : null}

    <footer className="relight-dialog-footer">
      <label className="relight-capability-select">
        <span>{t("AI 图片能力")}</span>
        <Select density="compact" value={capabilityId} aria-label={t("AI 图片能力")}
          onChange={(event) => setCapabilityId(event.target.value)}>
          {capabilities.length ? capabilities.map((capability) => <option key={capability.id}
            value={capability.id}>{capability.name}</option>)
            : <option value="">{t("请先配置 OpenAI 或 Google")}</option>}
        </Select>
      </label>
      <div className="relight-submit-group">
        <span title={t("将按所选 AI 图片能力计费")}><Stack size={19} weight="fill" />AI</span>
        <button type="button" className="relight-submit" disabled={!canSubmit}
          aria-label={busy ? t("正在受理打光任务") : t("开始打光")} onClick={submit}>
          <ArrowUp size={21} weight="bold" />
        </button>
      </div>
    </footer>
  </div>, document.body);
}
