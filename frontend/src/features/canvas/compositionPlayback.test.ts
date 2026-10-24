import { afterEach, describe, expect, it, vi } from 'vitest';
import { CompositionPlayback, type CompositionPlaybackModel } from './compositionPlayback';
import { createCompositionTimeline, frameTimeMs } from './compositionTimeline';
function media<T extends HTMLMediaElement>(element: T) {
  let paused = true, seeking = false, ready = 4;
  Object.defineProperties(element, { paused: { get: () => paused }, seeking: { get: () => seeking }, readyState: { get: () => ready } });
  element.play = vi.fn(async () => { paused = false; }); element.pause = vi.fn(() => { paused = true; }); element.load = vi.fn();
  return { element, paused: () => paused, ready: (value: number) => { ready = value; }, seeking: (value: boolean) => { seeking = value; } };
}
function setup(overrides: Partial<CompositionPlaybackModel> = {}) {
  vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1)); vi.stubGlobal('cancelAnimationFrame', vi.fn());
  const video = media(document.createElement('video')), music = media(document.createElement('audio')), voice = media(document.createElement('audio'));
  const timeline = createCompositionTimeline([{ id: 'a', title: 'a', src: '/a.mp4', sourceDurationMs: 8000, sourceStartMs: 1000, sourceEndMs: 5000, speed: 2, fadeMs: 0 },
    { id: 'b', title: 'b', src: '/b.mp4', sourceDurationMs: 3000, sourceStartMs: 0, sourceEndMs: 3000, speed: 1, fadeMs: 0 }]);
  const model = { timeline, originalAudioMode: 'BACKGROUND' as const, voices: [], ...overrides };
  const controller = new CompositionPlayback(); controller.configure(model);
  const detach = controller.attach(video.element, music.element, voice.element);
  return { controller, video, music, voice, model, detach };
}
afterEach(() => vi.unstubAllGlobals());
describe('composition playback', () => {
  it('plays the whole ordered composition, applies speed and original audio gain, and stops at the final output frame', async () => {
    const { controller, video, detach } = setup(); controller.toggle(); await Promise.resolve();
    expect(video.element.currentTime).toBe(1); expect(video.element.playbackRate).toBe(2); expect(video.element.preservesPitch).toBe(true); expect(video.element.volume).toBe(.15);
    video.element.currentTime = 3; video.element.dispatchEvent(new Event('timeupdate'));
    expect(controller.getSnapshot().positionMs).toBe(1000);
    video.element.currentTime = 5; video.element.dispatchEvent(new Event('timeupdate'));
    expect(video.element.getAttribute('src')).toBe('/b.mp4'); expect(video.element.currentTime).toBe(0); expect(video.element.playbackRate).toBe(1);
    expect(controller.getSnapshot().positionMs).toBe(2000);
    video.element.currentTime = 3; video.element.dispatchEvent(new Event('timeupdate'));
    expect(controller.getSnapshot()).toMatchObject({ positionMs: 5000, playing: false });
    expect(video.paused()).toBe(true); detach();
  });
  it('seeks music at output speed and voice at its planned rate, handles natural music end and preview mute', () => {
    const { controller, video, music, voice, model, detach } = setup({ music: { src: '/music.wav', sourceStartMs: 500, durationMs: 4000, volume: .4 },
      voices: [{ id: 'voice', src: '/voice.wav', startMs: 500, endMs: 1500, trimStartMs: 100, speed: 1.1 }] });
    controller.seek(1000);
    expect(video.element.currentTime).toBe(3); expect(music.element.currentTime).toBe(1.5); expect(music.element.playbackRate).toBe(1); expect(music.element.volume).toBe(.4);
    expect(voice.element.currentTime).toBeCloseTo(.65); expect(voice.element.playbackRate).toBe(1.1);
    controller.setMuted(true); expect(video.element.muted && music.element.muted && voice.element.muted).toBe(true);
    controller.setMuted(false); controller.seek(4000); expect(music.paused()).toBe(true); expect(voice.paused()).toBe(true);
    controller.configure({ ...model, originalAudioMode: 'MUTE' }); expect(video.element.muted).toBe(true); detach();
  });
  it('pauses the complete mix while seeking or buffering and resumes only after all streams are ready', async () => {
    const { controller, video, music, detach } = setup({ music: { src: '/music.wav', sourceStartMs: 0, durationMs: 10000, volume: .2 } });
    music.ready(1); controller.toggle(); expect(controller.getSnapshot().buffering).toBe(true); expect(video.element.play).not.toHaveBeenCalled();
    music.ready(4); music.element.dispatchEvent(new Event('canplay')); await Promise.resolve();
    expect(video.element.play).toHaveBeenCalled(); expect(music.element.play).toHaveBeenCalled();
    video.ready(1); video.element.dispatchEvent(new Event('waiting'));
    expect(video.paused()).toBe(true); expect(music.paused()).toBe(true);
    video.ready(4); video.element.dispatchEvent(new Event('canplay')); expect(controller.getSnapshot().buffering).toBe(false);
    detach();
  });
  it('keeps the same source frame when changing speed or reordering, and cleans up on detach', () => {
    const { controller, video, model, music, voice, detach } = setup(); controller.seek(1000);
    const changed = createCompositionTimeline([{ ...model.timeline.clips[1]! }, { ...model.timeline.clips[0]!, speed: 1 }]);
    controller.configure({ ...model, timeline: changed });
    expect(controller.getSnapshot().positionMs).toBe(5000); expect(video.element.currentTime).toBe(3); expect(video.element.playbackRate).toBe(1);
    controller.previewTrim('a', 'end'); controller.configure({ ...model, timeline: createCompositionTimeline([{ ...model.timeline.clips[0]!, sourceEndMs: 4000 }]) });
    expect(controller.getSnapshot().positionMs).toBeCloseTo(1500 - frameTimeMs(1));
    detach(); expect(video.paused() && music.paused() && voice.paused()).toBe(true); expect(controller.getSnapshot().playing).toBe(false);
  });
  it('reports native play rejection without leaving music audible and permits retry', async () => {
    const { controller, video, music, detach } = setup({ music: { src: '/music.wav', sourceStartMs: 0, durationMs: 10000, volume: .2 } });
    video.element.play = vi.fn().mockRejectedValue(new DOMException('Not allowed', 'NotAllowedError'));
    controller.toggle(); await Promise.resolve(); await Promise.resolve();
    expect(controller.getSnapshot()).toMatchObject({ error: true, playing: false }); expect(music.paused()).toBe(true);
    controller.retry(); expect(video.element.load).toHaveBeenCalled(); expect(controller.getSnapshot().error).toBe(false); detach();
  });
});
