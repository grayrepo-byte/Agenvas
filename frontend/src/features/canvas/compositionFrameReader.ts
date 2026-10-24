const MAX_CACHED_FRAMES = 128;
const FRAME_WIDTH = 192;
const FRAME_HEIGHT = 108;
const MEDIA_TIMEOUT_MS = 8000;
const aborted = () => new DOMException('Frame request canceled', 'AbortError');

export interface CompositionFrameDecoder {
  read: (src: string, sourceMs: number, signal: AbortSignal) => Promise<Blob>;
  dispose: () => void;
}

function waitForMedia(video: HTMLVideoElement, ready: () => boolean, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const events = ['loadedmetadata', 'loadeddata', 'seeked', 'canplay'];
    const cleanup = () => { window.clearTimeout(timer); events.forEach((event) => video.removeEventListener(event, check)); video.removeEventListener('error', fail); signal.removeEventListener('abort', cancel); };
    const fail = () => { cleanup(); reject(new Error('Video frame unavailable')); };
    const cancel = () => { cleanup(); reject(aborted()); };
    const check = () => { if (signal.aborted) cancel(); else if (video.error) fail(); else if (ready()) { cleanup(); resolve(); } };
    const timer = window.setTimeout(fail, MEDIA_TIMEOUT_MS);
    events.forEach((event) => video.addEventListener(event, check)); video.addEventListener('error', fail); signal.addEventListener('abort', cancel, { once: true }); check();
  });
}

class BrowserFrameDecoder implements CompositionFrameDecoder {
  private video = document.createElement('video');
  private canvas = document.createElement('canvas');
  constructor() { this.video.muted = true; this.video.playsInline = true; this.video.preload = 'auto'; }
  async read(src: string, sourceMs: number, signal: AbortSignal) {
    const video = this.video;
    try {
      if (signal.aborted) throw aborted();
      if (video.getAttribute('src') !== src) { video.src = src; video.load(); }
      await waitForMedia(video, () => video.readyState >= 1, signal);
      const target = Math.min(sourceMs / 1000, Math.max(0, video.duration - .000001));
      if (Math.abs(video.currentTime - target) > .000001) video.currentTime = target;
      await waitForMedia(video, () => video.readyState >= 2 && !video.seeking && Math.abs(video.currentTime - target) < .01, signal);
      if (!video.videoWidth || !video.videoHeight) throw new Error('Video frame has no dimensions');
      const scale = Math.min(FRAME_WIDTH / video.videoWidth, FRAME_HEIGHT / video.videoHeight);
      this.canvas.width = Math.max(1, Math.round(video.videoWidth * scale)); this.canvas.height = Math.max(1, Math.round(video.videoHeight * scale));
      const context = this.canvas.getContext('2d'); if (!context) throw new Error('Frame capture unavailable');
      context.drawImage(video, 0, 0, this.canvas.width, this.canvas.height);
      return await new Promise<Blob>((resolve, reject) => {
        const cleanup = () => { window.clearTimeout(timer); signal.removeEventListener('abort', cancel); };
        const cancel = () => { cleanup(); reject(aborted()); };
        const timer = window.setTimeout(() => { cleanup(); reject(new Error('Frame capture timed out')); }, MEDIA_TIMEOUT_MS);
        signal.addEventListener('abort', cancel, { once: true });
        try { this.canvas.toBlob((blob) => { cleanup(); if (signal.aborted) reject(aborted()); else if (blob) resolve(blob); else reject(new Error('Frame capture failed')); }, 'image/jpeg', .75); }
        catch (error) { cleanup(); reject(error); }
      });
    } finally { if (signal.aborted) this.dispose(); }
  }
  dispose() { if (this.video.getAttribute('src')) { this.video.removeAttribute('src'); this.video.load(); } }
}

type FrameJob = { src: string; sourceMs: number; key: string; controller: AbortController; resolve: (url: string) => void; reject: (error: unknown) => void };

/** One silent decoder, a bounded LRU and cancelable visible-frame requests. Separate from
 * composition playback so extracting or retrying thumbnails never seeks the user's player. */
export class CompositionFrameReader {
  private cache = new Map<string, string>();
  private failures = new Map<string, unknown>();
  private queue: FrameJob[] = [];
  private active: FrameJob | undefined;
  private decoder: CompositionFrameDecoder | undefined;
  private running = false;
  constructor(private createDecoder: () => CompositionFrameDecoder = () => new BrowserFrameDecoder()) {}
  read(src: string, sourceMs: number, signal: AbortSignal): Promise<string> {
    if (signal.aborted) return Promise.reject(aborted());
    const key = `${src}:${Math.round(sourceMs * 1000)}`;
    const cached = this.cached(key); if (cached) return Promise.resolve(cached);
    if (this.failures.has(src)) return Promise.reject(this.failures.get(src));
    return new Promise((resolve, reject) => {
      const cleanup = () => signal.removeEventListener('abort', cancel);
      const job: FrameJob = { src, sourceMs, key, controller: new AbortController(), resolve: (url) => { cleanup(); resolve(url); }, reject: (error) => { cleanup(); reject(error); } };
      const cancel = () => { job.controller.abort(); this.queue = this.queue.filter((item) => item !== job); job.reject(aborted()); };
      signal.addEventListener('abort', cancel, { once: true }); this.queue.push(job); void this.drain();
    });
  }
  private cached(key: string) {
    const url = this.cache.get(key); if (url) { this.cache.delete(key); this.cache.set(key, url); } return url;
  }
  private async drain() {
    if (this.running) return; this.running = true;
    try {
      let job: FrameJob | undefined;
      while ((job = this.queue.shift())) {
        if (job.controller.signal.aborted) continue;
        this.active = job;
        try {
          const cached = this.cached(job.key);
          if (cached) { job.resolve(cached); continue; }
          if (this.failures.has(job.src)) throw this.failures.get(job.src);
          this.decoder ??= this.createDecoder();
          const blob = await this.decoder.read(job.src, job.sourceMs, job.controller.signal);
          if (job.controller.signal.aborted) throw aborted();
          const url = URL.createObjectURL(blob); this.cache.set(job.key, url);
          if (this.cache.size > MAX_CACHED_FRAMES) { const oldest = this.cache.keys().next().value; if (oldest !== undefined) { URL.revokeObjectURL(this.cache.get(oldest)!); this.cache.delete(oldest); } }
          job.resolve(url);
        } catch (error) { if (!job.controller.signal.aborted) this.failures.set(job.src, error); job.reject(error); }
        finally { this.active = undefined; }
      }
    } finally { this.decoder?.dispose(); this.decoder = undefined; this.running = false; }
  }
  cancelPending() {
    this.active?.controller.abort(); this.active?.reject(aborted());
    this.queue.splice(0).forEach((job) => { job.controller.abort(); job.reject(aborted()); });
    this.decoder?.dispose(); this.decoder = undefined;
  }
  /** Release media and object URLs on close/retry; reusable after React's effect replay. */
  clear() {
    this.cancelPending(); this.cache.forEach((url) => URL.revokeObjectURL(url)); this.cache.clear(); this.failures.clear();
  }
}
