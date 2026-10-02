import { ArrowUp,Stack,X } from "@phosphor-icons/react";
import { useState,type PointerEvent } from "react";
import { createPortal } from "react-dom";
import { ApiError,type MediaCapability,type RunImageOperationRequest } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { Select } from "../../shared/ui/Select";

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
  { id: "GOLDEN_HOUR", get label() { return t("image.relight.goldenHour"); }, brightness: 10, colorTemperature: 3200,
    filter: "brightness(1.08) saturate(1.12) sepia(.22)" },
  { id: "BLUE_HOUR", get label() { return t("image.relight.blueHour"); }, brightness: -6, colorTemperature: 7600,
    filter: "brightness(.94) saturate(.9) hue-rotate(172deg)" },
  { id: "OVERCAST_SOFT", get label() { return t("image.relight.cloudy"); }, brightness: 5, colorTemperature: 6500,
    filter: "brightness(1.05) saturate(.72) contrast(.9)" },
  { id: "MOONLIGHT", get label() { return t("image.relight.moonlight"); }, brightness: -24, colorTemperature: 8200,
    filter: "brightness(.76) saturate(.62) hue-rotate(174deg) contrast(1.08)" },
  { id: "SOFT_STUDIO", get label() { return t("image.relight.studio"); }, brightness: 16, colorTemperature: 5200,
    filter: "brightness(1.16) saturate(.86) contrast(.92)" },
  { id: "NEON_NIGHT", get label() { return t("image.relight.neon"); }, brightness: 2, colorTemperature: 7000,
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
    aria-label={t("media.card.lighting")} onKeyDown={(event) => { if (event.key === "Escape") onClose(); }}>
    <header className="relight-dialog-header">
      <strong>{t("media.card.lighting")}</strong>
      <Button variant="ghost" type="button" aria-label={t("image.relight.close")} onClick={onClose}><X size={19} /></Button>
    </header>

    <div className="relight-dialog-body">
      <section className="relight-controls" aria-label={t("image.relight.parameters")}>
        <div className="relight-preview-stage" aria-label={t("image.relight.lightPositionHint")}
          onPointerDown={moveLight}>
          <div className="relight-preview-orbit">
            <img src={sourceUrl} alt={t("image.relight.preview")} draggable={false}
              style={{ filter: previewFilter }} />
            <span className="relight-source-dot" aria-hidden="true" style={{
              left: `${light.x * 100}%`, top: `${light.y * 100}%`,
            }} />
          </div>
        </div>

        <label className="relight-slider-label">
          <span>{t("image.relight.brightness")}<output>{brightness > 0 ? "+" : ""}{brightness}</output></span>
          <input type="range" min={MIN_BRIGHTNESS} max={MAX_BRIGHTNESS} value={brightness}
            onChange={(event) => setBrightness(Number(event.target.value))} />
        </label>
        <label className="relight-slider-label relight-temperature">
          <span>{t("image.relight.colorTemperature")}<output>{colorTemperature}K</output></span>
          <input type="range" min={MIN_COLOR_TEMPERATURE} max={MAX_COLOR_TEMPERATURE}
            step={100} value={colorTemperature}
            onChange={(event) => setColorTemperature(Number(event.target.value))} />
        </label>
      </section>

      <section className="relight-style-section" aria-label={t("image.relight.presets")}>
        <span className="relight-section-label">{t("image.relight.presets")}</span>
        <div className="relight-preset-grid">
          {PRESETS.map((entry) => <Button variant="ghost" type="button" key={entry.id}
            className={preset === entry.id ? "is-selected" : ""}
            aria-pressed={preset === entry.id} onClick={() => selectPreset(entry)}>
            <img src={sourceUrl} alt="" draggable={false} style={{ filter: entry.filter }} />
            <span>{entry.label}</span>
          </Button>)}
        </div>
        <Textarea value={instruction} maxLength={4000}
          aria-label={t("image.relight.additionalPrompt")} placeholder={t("image.relight.promptPlaceholder")}
          onChange={(event) => setInstruction(event.target.value)} />
      </section>
    </div>

    {error ? <p className="relight-error" role="alert">
      {error instanceof ApiError ? error.message : t("image.relight.submitFailed")}</p> : null}

    <footer className="relight-dialog-footer">
      <label className="relight-capability-select">
        <span>{t("image.relight.imageCapability")}</span>
        <Select variant="ghost" density="compact" value={capabilityId} aria-label={t("image.relight.imageCapability")}
          onChange={(event) => setCapabilityId(event.target.value)}>
          {capabilities.length ? capabilities.map((capability) => <option key={capability.id}
            value={capability.id}>{capability.name}</option>)
            : <option value="">{t("image.relight.configureProvider")}</option>}
        </Select>
      </label>
      <div className="relight-submit-group">
        <span title={t("image.relight.billingHint")}><Stack size={19} weight="fill" />AI</span>
        <Button variant="ghost" type="button" className="relight-submit" disabled={!canSubmit}
          aria-label={busy ? t("image.relight.submitting") : t("image.relight.start")} onClick={submit}>
          <ArrowUp size={21} weight="bold" />
        </Button>
      </div>
    </footer>
  </div>, document.body);
}
