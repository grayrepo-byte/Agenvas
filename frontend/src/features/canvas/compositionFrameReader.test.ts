import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CompositionFrameReader } from './compositionFrameReader';

const NativeURL = URL;
beforeEach(() => {
  let next = 0;
  vi.stubGlobal('URL', class extends NativeURL {
    static createObjectURL = vi.fn(() => `blob:frame-${++next}`);
    static revokeObjectURL = vi.fn();
  });
});
afterEach(() => vi.unstubAllGlobals());
const blob = () => new Blob(['synthetic image'], { type: 'image/jpeg' });
describe('composition frame reader', () => {
  it('serializes decoding and reuses the same pinned source frame across clips', async () => {
    const decode = vi.fn(async () => blob()), dispose = vi.fn();
    const reader = new CompositionFrameReader(() => ({ read: decode, dispose }));
    const signal = new AbortController().signal;
    const [first, repeated, next] = await Promise.all([reader.read('/exact-version', 1000, signal), reader.read('/exact-version', 1000, signal), reader.read('/exact-version', 2000, signal)]);
    expect(first).toBe(repeated); expect(next).not.toBe(first); expect(decode).toHaveBeenCalledTimes(2);
    expect(await reader.read('/exact-version', 1000, signal)).toBe(first);
    expect(dispose).toHaveBeenCalledOnce();
    reader.clear(); expect(dispose).toHaveBeenCalledOnce(); expect(URL.revokeObjectURL).toHaveBeenCalledTimes(2);
  });
  it('bounds cached images and revokes all retained URLs on close', async () => {
    const reader = new CompositionFrameReader(() => ({ read: async () => blob(), dispose: vi.fn() }));
    const signal = new AbortController().signal;
    for (let index = 0; index < 129; index++) await reader.read('/source', index * 1000 / 30, signal);
    expect(URL.revokeObjectURL).toHaveBeenCalledExactlyOnceWith('blob:frame-1');
    reader.clear(); expect(URL.revokeObjectURL).toHaveBeenCalledTimes(129);
  });
  it('cancels queued and active requests without producing stale images', async () => {
    const decode = vi.fn((_src: string, _time: number, signal: AbortSignal) => new Promise<Blob>((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(new DOMException('canceled', 'AbortError')), { once: true });
    }));
    const reader = new CompositionFrameReader(() => ({ read: decode, dispose: vi.fn() }));
    const active = new AbortController(), queued = new AbortController();
    const first = reader.read('/source', 0, active.signal), second = reader.read('/source', 1000, queued.signal);
    const secondCanceled = expect(second).rejects.toMatchObject({ name: 'AbortError' }); queued.abort(); await secondCanceled;
    const firstCanceled = expect(first).rejects.toMatchObject({ name: 'AbortError' }); reader.clear(); await firstCanceled;
    expect(decode).toHaveBeenCalledOnce(); expect(URL.createObjectURL).not.toHaveBeenCalled();
  });
  it('does not repeatedly reload a failed source and permits an explicit retry', async () => {
    const decode = vi.fn().mockRejectedValueOnce(new Error('unavailable')).mockResolvedValue(blob());
    const reader = new CompositionFrameReader(() => ({ read: decode, dispose: vi.fn() }));
    const signal = new AbortController().signal;
    const results = await Promise.allSettled([reader.read('/private-source', 0, signal), reader.read('/private-source', 1000, signal)]);
    expect(results.every((item) => item.status === 'rejected')).toBe(true); expect(decode).toHaveBeenCalledOnce();
    reader.clear(); expect(await reader.read('/private-source', 1000, signal)).toBe('blob:frame-1'); reader.clear();
  });
});
