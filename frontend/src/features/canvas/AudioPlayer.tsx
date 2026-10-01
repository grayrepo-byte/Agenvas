import { t, useLocale } from "../../shared/i18n";
import { Pause, Play, SpeakerHigh, ArrowClockwise } from "@phosphor-icons/react";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import "./AudioPlayer.css";

const WAVEFORM_BARS = 72;
const MAX_WAVEFORM_BYTES = 50 * 1024 * 1024;
function timeLabel(value: number) {
  const seconds = Number.isFinite(value) ? Math.max(0, Math.floor(value)) : 0;
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

/** Waveform amplitudes are decoded from the selected immutable audio, never demo bars. */
export function AudioPlayer({ src, title, selected = true, demo = false }: {
  src: string; title: string; selected?: boolean; demo?: boolean;
}) {
  useLocale();
  const audio = useRef<HTMLAudioElement>(null);
  const [playing, setPlaying] = useState(false);
  const [duration, setDuration] = useState(0);
  const [position, setPosition] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const waveform = useQuery({ queryKey: ["audio-waveform", src], enabled: selected,
    staleTime: Infinity, retry: false, queryFn: async ({ signal }) => {
      const response = await fetch(src, { credentials: "same-origin", signal });
      if (!response.ok) throw new Error(t("波形读取失败"));
      const bytes = await response.arrayBuffer();
      if (bytes.byteLength > MAX_WAVEFORM_BYTES) throw new Error(t("音频过大，波形暂不可用"));
      const context = new AudioContext();
      try {
        const buffer = await context.decodeAudioData(bytes);
        const amplitudes = Array.from({ length: WAVEFORM_BARS }, () => 0);
        for (let channel = 0; channel < buffer.numberOfChannels; channel++) {
          const samples = buffer.getChannelData(channel);
          for (let bar = 0; bar < WAVEFORM_BARS; bar++) {
            const start = Math.floor(bar * samples.length / WAVEFORM_BARS);
            const end = Math.floor((bar + 1) * samples.length / WAVEFORM_BARS);
            let peak = 0;
            for (let index = start; index < end; index++) peak = Math.max(peak, Math.abs(samples[index] ?? 0));
            amplitudes[bar] = Math.max(amplitudes[bar] ?? 0, peak);
          }
        }
        const peak = Math.max(...amplitudes, 0.001);
        return amplitudes.map((value) => value / peak);
      } finally { await context.close(); }
    } });
  useEffect(() => { const element = audio.current; return () => { element?.pause(); }; }, [src]);
  async function toggle() {
    const element = audio.current;
    if (!element) return;
    if (!element.paused) { element.pause(); return; }
    setError(null);
    try { await element.play(); } catch { setError(t("音频播放失败，请重试。")); }
  }
  return <div className="audio-player nodrag nowheel nopan" onPointerDown={(event) => event.stopPropagation()}>
    <audio ref={audio} src={src} preload="metadata" aria-label={t("{0} 的音频", { "0": title })}
      onLoadedMetadata={(event) => setDuration(event.currentTarget.duration)}
      onTimeUpdate={(event) => setPosition(event.currentTarget.currentTime)}
      onPlay={() => setPlaying(true)} onPause={() => setPlaying(false)} onEnded={() => setPlaying(false)}
      onError={() => { setError(t("音频读取失败，请重试。")); setPlaying(false); }} />
    <div className="audio-player-controls">
      <button type="button" className="audio-player-play" onClick={() => void toggle()}
        aria-label={playing ? t("暂停音频") : t("播放音频")}>{playing ? <Pause size={20} weight="fill" /> : <Play size={20} weight="fill" />}</button>
      <span className="audio-player-time">{timeLabel(position)} / {timeLabel(duration)}</span>
      <SpeakerHigh size={18} aria-hidden="true" />
    </div>
    <div className="audio-player-waveform" aria-hidden="true">
      {waveform.data ? waveform.data.map((amplitude, index) => <span key={index}
        className={duration > 0 && index / WAVEFORM_BARS <= position / duration ? "is-played" : ""}
        style={{ height: `${Math.max(3, amplitude * 80)}%` }} />)
        : <span className="audio-player-waveform-placeholder" />}
    </div>
    <input className="audio-player-seek" type="range" aria-label={t("音频播放进度")} min={0}
      max={duration || 1} step={0.01} value={Math.min(position, duration || 1)} disabled={duration <= 0}
      onChange={(event) => { const value = Number(event.target.value); if (audio.current) audio.current.currentTime = value; setPosition(value); }} />
    {waveform.isFetching ? <small role="status">{t("正在读取波形…")}</small> : null}
    {waveform.error ? <small>{t("波形暂不可用 ")}<button type="button" onClick={() => void waveform.refetch()}>{t("重试波形")}</button></small> : null}
    {error ? <div className="audio-player-error" role="alert">{error}<button type="button"
      onClick={() => { setError(null); audio.current?.load(); }}><ArrowClockwise size={14} />{t("重试播放")}</button></div> : null}
    {demo ? <small className="audio-player-demo">{t("Mock 演示音频（非语音合成）")}</small> : null}
  </div>;
}
