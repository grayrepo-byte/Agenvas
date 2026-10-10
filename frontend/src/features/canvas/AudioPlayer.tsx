import { ArrowClockwise,Pause,Play } from "@/shared/ui/icons";
import { useQuery } from "@tanstack/react-query";
import { useEffect,useRef,useState } from "react";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import "./AudioPlayer.css";

const WAVEFORM_BARS = 48;
const MAX_WAVEFORM_BYTES = 50 * 1024 * 1024;
function timeLabel(value: number) {
  const seconds = Number.isFinite(value) ? Math.max(0, Math.floor(value)) : 0;
  return `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
}

/** Waveform amplitudes are decoded from the selected immutable audio, never demo bars. */
export function AudioPlayer({ src, title, description = title, selected = true, demo = false }: {
  src: string; title: string; description?: string; selected?: boolean; demo?: boolean;
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
      if (!response.ok) throw new Error(t("audio.player.waveformFailed"));
      const bytes = await response.arrayBuffer();
      if (bytes.byteLength > MAX_WAVEFORM_BYTES) throw new Error(t("audio.player.waveformSizeLimit"));
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
    try { await element.play(); } catch { setError(t("audio.player.playbackFailed")); }
  }
  // Only playback controls exclude node gestures; the surrounding surface selects and drags the card.
  return <div className="audio-player nowheel nopan">
    <audio ref={audio} src={src} preload="metadata" aria-label={t("audio.player.audioLabel", { "0": title })}
      onLoadedMetadata={(event) => setDuration(event.currentTarget.duration)}
      onTimeUpdate={(event) => setPosition(event.currentTarget.currentTime)}
      onPlay={() => setPlaying(true)} onPause={() => setPlaying(false)} onEnded={() => setPlaying(false)}
      onError={() => { setError(t("audio.player.loadFailed")); setPlaying(false); }} />
    <div className="audio-player-controls">
      <Button variant="ghost" type="button" className="audio-player-play nodrag" onClick={() => void toggle()}
        aria-label={playing ? t("audio.player.pause") : t("audio.player.play")}>{playing ? <Pause size={16} weight="fill" /> : <Play size={16} weight="fill" />}</Button>
      <div className="audio-player-details">
        <p className="audio-player-title" title={description}>{description}</p>
        <span className="audio-player-time">{timeLabel(position)} / {timeLabel(duration)}</span>
      </div>
    </div>
    <div className="audio-player-progress nodrag">
      <div className="audio-player-waveform" aria-hidden="true">
        {waveform.data ? waveform.data.map((amplitude, index) => <span key={index}
          className={duration > 0 && index / WAVEFORM_BARS < position / duration ? "is-played" : ""}
          style={{ height: `${Math.max(3, amplitude * 80)}%` }} />)
          : <span className="audio-player-waveform-placeholder" />}
      </div>
      <input className="audio-player-seek" type="range" aria-label={t("audio.player.progressLabel")} min={0}
        max={duration || 1} step={0.01} value={Math.min(position, duration || 1)} disabled={duration <= 0}
        onChange={(event) => { const value = Number(event.target.value); if (audio.current) audio.current.currentTime = value; setPosition(value); }} />
    </div>
    {waveform.isFetching ? <small role="status">{t("audio.player.waveformLoading")}</small> : null}
    {waveform.error ? <small>{t("audio.player.waveformUnavailable")}<Button variant="ghost" type="button" className="nodrag" onClick={() => void waveform.refetch()}>{t("audio.player.retryWaveform")}</Button></small> : null}
    {error ? <div className="audio-player-error" role="alert">{error}<Button variant="ghost" type="button" className="nodrag"
      onClick={() => { setError(null); audio.current?.load(); }}><ArrowClockwise size={14} />{t("media.retryPlayback")}</Button></div> : null}
    {demo ? <small className="audio-player-demo">{t("audio.player.mockHint")}</small> : null}
  </div>;
}
