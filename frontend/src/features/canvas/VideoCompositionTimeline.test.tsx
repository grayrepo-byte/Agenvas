import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useLayoutEffect, useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CompositionPlayback } from './compositionPlayback';
import { createCompositionTimeline, type CompositionSourceClip } from './compositionTimeline';
import { VideoCompositionTimeline } from './VideoCompositionTimeline';
import { CompositionFrameReader } from './compositionFrameReader';
beforeEach(() => vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {}));
const source: CompositionSourceClip = { id: 'one', title: 'One', src: '/one.mp4', sourceDurationMs: 6000, sourceStartMs: 1000, sourceEndMs: 5000, speed: 2, fadeMs: 0 };
function setup() {
  const controller = new CompositionPlayback(), changes = vi.fn(), selection = vi.fn(), trimming = vi.fn();
  function Harness() {
    const [clip, setClip] = useState(source);
    const timeline = createCompositionTimeline([clip]); useLayoutEffect(() => controller.configure({ timeline, voices: [], originalAudioMode: 'KEEP' }), [timeline]);
    return <VideoCompositionTimeline controller={controller} timeline={timeline} activeId="one" onSelect={selection} onReorder={vi.fn()} captions={[]} voiceCount={0}
      onAudio={vi.fn()} onCaptions={vi.fn()} onTrimmingChange={trimming} onTrim={(id, range) => { changes(id, range); setClip((current) => ({ ...current, sourceStartMs: range.startMs, sourceEndMs: range.endMs })); }} />;
  }
  render(<Harness />); return { controller, changes, selection, trimming };
}
describe('composition trim handles', () => {
  it('zooms into individual frames and seeks the clicked filmstrip position', () => {
    const { controller } = setup();
    const clip = screen.getByRole('button', { name: '时间线片段 1：One' });
    vi.spyOn(clip, 'getBoundingClientRect').mockReturnValue({ left: 100 } as DOMRect);
    fireEvent.click(clip, { detail: 1, clientX: 170 }); expect(controller.getSnapshot().positionMs).toBe(1000);
    fireEvent.click(screen.getByRole('button', { name: '逐帧' }));
    const frames = clip.querySelectorAll('[data-source-ms]');
    expect(frames[0]).toHaveAttribute('data-source-ms', '1000');
    expect(Number(frames[1]?.getAttribute('data-source-ms'))).toBeCloseTo(1066.6666667);
    expect(frames.length).toBeLessThan(12);
    fireEvent.click(clip, { detail: 1, clientX: 175 }); expect(controller.getSnapshot().positionMs).toBe(0);
    fireEvent.click(clip, { detail: 1, clientX: 250 }); expect(controller.getSnapshot().positionMs).toBeCloseTo(1000 / 30);
  });
  it('scrubs the draggable playhead without modifying the trim', () => {
    const { controller, changes } = setup();
    const head = screen.getByRole('slider', { name: '拖动播放头' });
    vi.spyOn(head, 'hasPointerCapture').mockReturnValue(true);
    fireEvent.pointerDown(head, { button: 0, pointerId: 1, clientX: 35 });
    fireEvent.pointerMove(head, { pointerId: 1, clientX: 70 });
    expect(controller.getSnapshot().positionMs).toBe(1000); expect(changes).not.toHaveBeenCalled();
  });
  it('preserves the editor after frame loading fails and retries explicitly', async () => {
    const read = vi.spyOn(CompositionFrameReader.prototype, 'read').mockRejectedValue(new Error('unavailable'));
    setup();
    expect(await screen.findByText('部分帧预览读取失败，编辑已保留。请检查素材权限后重试。')).toBeVisible();
    read.mockResolvedValue('blob:synthetic-frame');
    fireEvent.click(screen.getByRole('button', { name: '重试加载' }));
    await waitFor(() => expect(document.querySelector('[data-frame-state="ready"]')).toBeInTheDocument());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
  it('uses output-frame steps at two-times speed and keeps ruler seeking keyboard accessible', () => {
    const { controller, changes } = setup();
    const handle = screen.getByRole('slider', { name: '调整片段 1 出点' });
    fireEvent.keyDown(handle, { key: 'ArrowLeft' });
    expect(changes.mock.lastCall?.[1]).toEqual({ startMs: 1000, endMs: 4934 });
    expect(handle).toHaveAttribute('aria-valuenow', '4.934');
    const ruler = screen.getByRole('slider', { name: '时间线播放头' });
    fireEvent.keyDown(ruler, { key: 'End' }); expect(controller.getSnapshot().positionMs).toBeCloseTo(1966.6666667);
    fireEvent.keyDown(ruler, { key: 'Home' }); expect(controller.getSnapshot().positionMs).toBe(0);
  });
  it('updates trims during pointer movement and restores the original range on cancellation', () => {
    const { changes, trimming } = setup();
    const handle = screen.getByRole('slider', { name: '调整片段 1 入点' });
    fireEvent.pointerDown(handle, { button: 0, pointerId: 1, clientX: 100 });
    fireEvent.pointerMove(handle, { pointerId: 1, clientX: 135 });
    expect(changes.mock.lastCall?.[1]).toEqual({ startMs: 2000, endMs: 5000 });
    fireEvent.pointerCancel(handle, { pointerId: 1 });
    expect(changes.mock.lastCall?.[1]).toEqual({ startMs: 1000, endMs: 5000 });
    expect(trimming).toHaveBeenLastCalledWith(false);
  });
  it('commits a completed drag and treats lost pointer capture as cancellation', () => {
    const { changes } = setup(); const handle = screen.getByRole('slider', { name: '调整片段 1 出点' });
    fireEvent.pointerDown(handle, { button: 0, pointerId: 1, clientX: 200 });
    fireEvent.pointerMove(handle, { pointerId: 1, clientX: 165 });
    fireEvent.pointerUp(handle, { pointerId: 1 });
    expect(changes.mock.lastCall?.[1]).toEqual({ startMs: 1000, endMs: 4000 });
    fireEvent.lostPointerCapture(handle, { pointerId: 1 });
    expect(changes.mock.lastCall?.[1]).toEqual({ startMs: 1000, endMs: 4000 });
    fireEvent.pointerDown(handle, { button: 0, pointerId: 2, clientX: 200 });
    fireEvent.pointerMove(handle, { pointerId: 2, clientX: 165 });
    fireEvent.lostPointerCapture(handle, { pointerId: 2 });
    expect(changes.mock.lastCall?.[1]).toEqual({ startMs: 1000, endMs: 4000 });
  });
});
