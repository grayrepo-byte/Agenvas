import { clipEndMs, clipStartMs, compositionClipAt, compositionSourceTime, frameTimeMs, COMPOSITION_FPS,
  type CompositionAudio, type CompositionTimeline, type CompositionTimelineClip, type CompositionVoice } from './compositionTimeline';

export type CompositionPlaybackState = { positionMs: number; playing: boolean; buffering: boolean; muted: boolean; error: boolean };
export type CompositionPlaybackModel = { timeline: CompositionTimeline; originalAudioMode: 'KEEP' | 'MUTE' | 'BACKGROUND'; music?: CompositionAudio; voices: readonly CompositionVoice[]; blocked?: boolean };
const EMPTY: CompositionTimeline = { clips: [], frames: 0, valid: false };
const DRIFT_SECONDS = .08;

/** The video is the master clock. Buffering, seeks and source changes pause the entire audio mix.
 * UI subscribers receive at most one update per output frame; the editor draft never stores playback state. */
export class CompositionPlayback {
  private snapshot: CompositionPlaybackState = { positionMs: 0, playing: false, buffering: false, muted: false, error: false };
  private listeners = new Set<() => void>();
  private model: CompositionPlaybackModel = { timeline: EMPTY, originalAudioMode: 'KEEP', voices: [] };
  private signature = '';
  private video: HTMLVideoElement | null = null;
  private music: HTMLAudioElement | null = null;
  private voice: HTMLAudioElement | null = null;
  private clip: CompositionTimelineClip | undefined;
  private wanted = false;
  private frame: number | undefined;
  private epoch = 0;
  private pending = new Set<HTMLMediaElement>();
  private seekTarget = 0;
  private deferredSeek: { clipId: string; edge: 'start' | 'end' } | undefined;
  private stopAtClip: string | undefined;
  private removeEvents: (() => void) | undefined;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  getSnapshot = () => this.snapshot;
  private update(patch: Partial<CompositionPlaybackState>) {
    const next = { ...this.snapshot, ...patch };
    if (Object.keys(next).every((key) => next[key as keyof CompositionPlaybackState] === this.snapshot[key as keyof CompositionPlaybackState])) return;
    this.snapshot = next; this.listeners.forEach((listener) => listener());
  }
  attach(video: HTMLVideoElement, music: HTMLAudioElement, voice: HTMLAudioElement) {
    this.detach(); this.video = video; this.music = music; this.voice = voice;
    const refresh = () => this.synchronize();
    const mediaError = (event: Event) => { if ((event.currentTarget as HTMLMediaElement).getAttribute('src')) this.fail(); };
    const waiting = () => { if (this.wanted) { this.epoch++; this.pauseElements(); this.update({ buffering: true }); } };
    const ended = () => {
      if (this.wanted && this.clip && video.currentTime * 1000 >= this.clip.sourceStartMs + frameTimeMs(this.clip.frames) * this.clip.speed - 1) this.advance();
    };
    const events = ['loadedmetadata', 'loadeddata', 'canplay', 'seeked', 'durationchange'];
    [video, music, voice].forEach((element) => {
      events.forEach((event) => element.addEventListener(event, refresh));
      element.addEventListener('error', mediaError); element.addEventListener('waiting', waiting);
    });
    video.addEventListener('timeupdate', this.tick); video.addEventListener('ended', ended);
    this.removeEvents = () => {
      [video, music, voice].forEach((element) => {
        events.forEach((event) => element.removeEventListener(event, refresh));
        element.removeEventListener('error', mediaError); element.removeEventListener('waiting', waiting);
      });
      video.removeEventListener('timeupdate', this.tick); video.removeEventListener('ended', ended);
    };
    this.seek(this.snapshot.positionMs);
    return () => this.detach();
  }
  private detach() {
    this.pause(); this.removeEvents?.(); this.removeEvents = undefined;
    [this.video, this.music, this.voice].forEach((element) => {
      if (element?.getAttribute('src')) { element.removeAttribute('src'); element.load(); }
    });
    this.video = null; this.music = null; this.voice = null; this.clip = undefined;
  }
  configure(model: CompositionPlaybackModel) {
    const signature = JSON.stringify(model);
    if (signature === this.signature) return;
    this.signature = signature;
    const timingChanged = JSON.stringify(model.timeline) !== JSON.stringify(this.model.timeline);
    if (!timingChanged && !this.deferredSeek) {
      this.model = model;
      if (model.blocked || !model.timeline.valid) this.pause();
      this.synchronize();
      return;
    }
    const old = compositionClipAt(this.model.timeline, this.snapshot.positionMs);
    const sourceTime = old ? compositionSourceTime(old, this.snapshot.positionMs) : 0;
    const next = old ? model.timeline.clips.find((clip) => clip.id === old.id) : undefined;
    const position = next ? clipStartMs(next) + Math.max(0, Math.min(frameTimeMs(next.frames), (sourceTime - next.sourceStartMs) / next.speed)) : this.snapshot.positionMs;
    this.model = model;
    if (model.blocked || !model.timeline.valid) this.pause();
    const target = this.deferredSeek && model.timeline.clips.find((clip) => clip.id === this.deferredSeek?.clipId);
    if (target) { this.seek(this.deferredSeek?.edge === 'end' ? Math.max(clipStartMs(target), clipEndMs(target) - frameTimeMs(1)) : clipStartMs(target)); this.deferredSeek = undefined; }
    else this.seek(position);
  }
  seekToClip(clipId: string, edge: 'start' | 'end' = 'start') {
    const clip = this.model.timeline.clips.find((item) => item.id === clipId);
    if (clip) this.seek(edge === 'start' ? clipStartMs(clip) : Math.max(clipStartMs(clip), clipEndMs(clip) - frameTimeMs(1)));
  }
  previewTrim(clipId: string, edge: 'start' | 'end') { this.pause(); this.deferredSeek = { clipId, edge }; }
  seek(positionMs: number) {
    const total = frameTimeMs(this.model.timeline.frames);
    const position = Number.isFinite(positionMs) ? Math.max(0, Math.min(total, Math.round(positionMs * COMPOSITION_FPS / 1000) * 1000 / COMPOSITION_FPS)) : 0;
    this.epoch++; this.pauseElements();
    this.update({ positionMs: position, error: false });
    this.clip = compositionClipAt(this.model.timeline, position);
    if (!this.clip || !this.video) return;
    // At the final endpoint show the last complete frame, rather than the next source frame.
    this.seekTarget = compositionSourceTime(this.clip, position === total ? Math.max(0, position - frameTimeMs(1)) : position) / 1000;
    if (this.video.getAttribute('src') !== this.clip.src) this.video.src = this.clip.src;
    this.video.playbackRate = this.clip.speed; this.video.preservesPitch = true;
    if (this.video.readyState >= 1 && Math.abs(this.video.currentTime - this.seekTarget) > .002) this.video.currentTime = this.seekTarget;
    this.synchronize();
  }
  toggle = () => {
    if (this.wanted) { this.pause(); return; }
    if (!this.model.timeline.valid || this.model.blocked || !this.video || this.snapshot.error) return;
    if (this.snapshot.positionMs >= frameTimeMs(this.model.timeline.frames)) this.seek(0);
    this.stopAtClip = undefined;
    this.wanted = true; this.update({ playing: true, error: false }); this.synchronize(); this.schedule();
  };
  playClip(clipId: string) {
    if (!this.model.timeline.valid || this.model.blocked || !this.video || this.snapshot.error
      || !this.model.timeline.clips.some((clip) => clip.id === clipId)) return;
    this.pause(); this.seekToClip(clipId); this.stopAtClip = clipId;
    this.wanted = true; this.update({ playing: true, error: false }); this.synchronize(); this.schedule();
  }
  pause = () => {
    this.wanted = false; this.epoch++; this.pauseElements();
    if (this.frame !== undefined) window.cancelAnimationFrame(this.frame); this.frame = undefined;
    this.update({ playing: false, buffering: false });
  };
  setMuted = (muted: boolean) => { this.update({ muted }); this.synchronize(); };
  private pauseElements() { [this.video, this.music, this.voice].forEach((element) => { if (element && !element.paused) element.pause(); }); }
  private fail() { this.pause(); this.update({ error: true }); }
  retry = () => {
    this.pause(); this.update({ error: false });
    [this.video, this.music, this.voice].forEach((element) => { if (element?.getAttribute('src')) element.load(); });
    this.seek(this.snapshot.positionMs);
  };
  private prepareAudio(element: HTMLAudioElement | null, audio: { src: string; time: number; volume: number; speed: number } | undefined) {
    if (!element) return true;
    if (!audio) {
      if (!element.paused) element.pause(); element.muted = true;
      if (element.getAttribute('src')) { element.removeAttribute('src'); element.load(); }
      return true;
    }
    if (element.getAttribute('src') !== audio.src) element.src = audio.src;
    element.muted = this.snapshot.muted; element.volume = audio.volume; element.playbackRate = audio.speed; element.preservesPitch = true;
    if (element.readyState < 1) return false;
    if (Math.abs(element.currentTime - audio.time) > DRIFT_SECONDS) element.currentTime = audio.time;
    return element.readyState >= 2 && !element.seeking;
  }
  private synchronize() {
    const video = this.video;
    if (!video || !this.clip || this.snapshot.error) return;
    const mode = this.model.originalAudioMode;
    video.muted = this.snapshot.muted || mode === 'MUTE'; video.volume = mode === 'BACKGROUND' ? .15 : 1;
    video.playbackRate = this.clip.speed;
    if (video.readyState >= 1 && video.paused && Math.abs(video.currentTime - this.seekTarget) > .002) video.currentTime = this.seekTarget;
    const position = this.snapshot.positionMs;
    const music = this.model.music;
    const musicTime = music ? music.sourceStartMs + position : 0;
    const activeMusic = music && Number.isFinite(musicTime) && musicTime >= 0 && musicTime < music.durationMs
      ? { src: music.src, time: musicTime / 1000, volume: music.volume, speed: 1 } : undefined;
    const voice = this.model.voices.find((item) => position >= item.startMs && position < item.endMs);
    const activeVoice = voice ? { src: voice.src, time: (voice.trimStartMs + (position - voice.startMs) * voice.speed) / 1000, volume: 1, speed: voice.speed } : undefined;
    const musicReady = this.prepareAudio(this.music, activeMusic), voiceReady = this.prepareAudio(this.voice, activeVoice);
    const ready = video.readyState >= 2 && !video.seeking && musicReady && voiceReady;
    if (!this.wanted) { this.update({ buffering: false }); return; }
    if (!ready) { if ([this.video, this.music, this.voice].some((element) => element && !element.paused)) this.epoch++; this.pauseElements(); this.update({ buffering: true }); return; }
    this.update({ buffering: false });
    [video, activeMusic ? this.music : null, activeVoice ? this.voice : null].forEach((element) => {
      if (!element || !element.paused || this.pending.has(element)) return;
      this.pending.add(element); const epoch = this.epoch;
      void element.play().catch(() => { if (epoch === this.epoch && this.wanted && element.getAttribute('src')) this.fail(); }).finally(() => {
        this.pending.delete(element); if (!this.wanted && !element.paused) element.pause();
      });
    });
  }
  private advance() {
    if (!this.clip) return;
    const end = clipEndMs(this.clip);
    if (this.stopAtClip === this.clip.id) { this.pause(); this.seek(Math.max(clipStartMs(this.clip), end - frameTimeMs(1))); }
    else if (this.clip.startFrame + this.clip.frames >= this.model.timeline.frames) { this.pause(); this.seek(frameTimeMs(this.model.timeline.frames)); }
    else this.seek(end);
  }
  private schedule() { if (this.wanted && this.frame === undefined) this.frame = window.requestAnimationFrame(() => { this.frame = undefined; this.tick(); this.schedule(); }); }
  private tick = () => {
    if (!this.wanted || !this.clip || !this.video || this.snapshot.buffering || this.video.seeking) return;
    const position = clipStartMs(this.clip) + Math.max(0, (this.video.currentTime * 1000 - this.clip.sourceStartMs) / this.clip.speed);
    if (position >= clipEndMs(this.clip) - .5) { this.advance(); return; }
    this.seekTarget = this.video.currentTime;
    this.update({ positionMs: Math.floor(position * COMPOSITION_FPS / 1000 + 1e-7) * 1000 / COMPOSITION_FPS });
    this.synchronize();
  };
}
