import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { Plugin } from 'vite';
import { sendMedia } from './settings-design-media';

/** Only the dedicated synthetic editor fixture gets these locally generated signals.
 * Normal application and Provider paths are never intercepted. Generation is lazy and bounded. */
export function videoEditMediaPreview(): Plugin {
  const cache = new Map<string, { bytes: Buffer; type: string }>();
  function generate(id: string, thumbnail: boolean) {
    const key = `${id}:${thumbnail}`; const existing = cache.get(key); if (existing) return existing;
    const folder = mkdtempSync(join(tmpdir(), 'agenvas-video-edit-signals-'));
    try {
      const video = id === '5' || id === '6';
      const output = join(folder, thumbnail ? 'frame.png' : video ? 'clip.mp4' : 'sound.m4a');
      const base = ['-hide_banner', '-loglevel', 'error', '-nostdin', '-y'];
      const args = video ? [...base, '-f', 'lavfi', '-i', `color=c=${id === '5' ? '0x254e63' : '0x70454f'}:s=640x360:r=30:d=4`,
        '-f', 'lavfi', '-i', `sine=frequency=${id === '5' ? 440 : 660}:sample_rate=48000:duration=4`,
        ...(thumbnail ? ['-frames:v', '1', '-an'] : ['-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '64k', '-movflags', '+faststart'])]
        : [...base, '-f', 'lavfi', '-i', `sine=frequency=${id === '3' ? 220 : 880}:sample_rate=48000:duration=${id === '3' ? 12 : 1.1}`,
          '-c:a', 'aac', '-b:a', '64k', '-movflags', '+faststart'];
      const result = spawnSync(process.env.AGENVAS_E2E_FFMPEG ?? 'ffmpeg', [...args, output], { timeout: 10000, maxBuffer: 1024 * 1024 });
      if (result.status !== 0) throw new Error('Local FFmpeg is required for synthetic editor playback evidence');
      const asset = { bytes: readFileSync(output), type: thumbnail ? 'image/png' : video ? 'video/mp4' : 'audio/mp4' }; cache.set(key, asset); return asset;
    } finally { rmSync(folder, { recursive: true, force: true }); }
  }
  const install = (server: { middlewares: import('vite').ViteDevServer['middlewares'] }) => {
    server.middlewares.use((request, response, next) => {
      const match = /^\/api\/v1\/projects\/synthetic-video-edit\/assets\/synthetic-library-([3456])\/(content|thumbnail)$/.exec(request.url?.split('?')[0] ?? '');
      if (!match) { next(); return; }
      if (request.method !== 'GET' && request.method !== 'HEAD') { response.statusCode = 405; response.end(); return; }
      try {
        const asset = generate(match[1]!, match[2] === 'thumbnail');
        response.setHeader('Cache-Control', 'no-store'); response.setHeader('X-Agenvas-Synthetic-Preview', 'true'); sendMedia(request, response, asset.bytes, asset.type);
      } catch { response.statusCode = 503; response.end('Synthetic preview media could not be generated'); }
    });
  };
  return { name: 'agenvas-synthetic-video-edit-media', apply: 'serve', configureServer: install, configurePreviewServer: install };
}
