import { fireEvent, render, screen } from '@testing-library/react';
import { useLayoutEffect, useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { CompositionPlayback } from './compositionPlayback';
import { createCompositionTimeline, type CompositionSourceClip } from './compositionTimeline';
import { VideoCompositionTimeline } from './VideoCompositionTimeline';
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
