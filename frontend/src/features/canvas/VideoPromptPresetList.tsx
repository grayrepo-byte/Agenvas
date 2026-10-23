import { cn } from "cn";
import { useLayoutEffect, useMemo, useRef, useState } from "react";
import { CaretRight, Check, MagnifyingGlass } from "@/shared/ui/icons";
import { t, useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "../../shared/ui/primitives/empty";
import type { VideoPromptPreset } from "./videoPromptPresets";
import "./VideoPromptPresetList.css";

type Props = {
  presets: readonly VideoPromptPreset[];
  selectedId: string | undefined;
  onSelect: (preset: VideoPromptPreset) => void;
  resetKey: string;
  onReset: () => void;
};

const MIN_ROW_HEIGHT = 104;
const ROW_GAP = 6;
const CARD_WIDTH = 212;
const CARD_GAP = 8;
const OVERSCAN = 3;
const MOBILE_QUERY = "(max-width: 700px)";

/** Changing filters remounts only the scroll surface, resetting its DOM and virtual offset together. */
export function VideoPromptPresetList(props: Props) {
  return <VirtualPresetList key={props.resetKey} {...props} />;
}

function VirtualPresetList({ presets, selectedId, onSelect, onReset }: Props) {
  const locale = useLocale();
  const viewportRef = useRef<HTMLDivElement>(null);
  const nodes = useRef(new Map<string, HTMLButtonElement>());
  const rowRefs = useRef(new Map<string, (node: HTMLButtonElement | null) => void>());
  const observerRef = useRef<ResizeObserver | null>(null);
  const pendingFocus = useRef<string | null>(null);
  const resizeFocus = useRef<string | null>(null);
  const programmaticOffset = useRef<number | null>(null);
  const [viewport, setViewport] = useState(() => ({ horizontal: window.matchMedia?.(MOBILE_QUERY).matches ?? false, width: 320, size: 600 }));
  const viewportState = useRef(viewport);
  const [heights, setHeights] = useState<ReadonlyMap<string, number>>(() => new Map());
  const [scrollOffset, setScrollOffset] = useState(0);
  const [focusedId, setFocusedId] = useState<string | null>(null);
  const horizontal = viewport.horizontal;

  const layout = useMemo(() => {
    let total = 0;
    const offsets = presets.map((preset) => {
      const start = total;
      const size = horizontal ? CARD_WIDTH : heights.get(preset.id) ?? MIN_ROW_HEIGHT;
      total += size + (horizontal ? CARD_GAP : ROW_GAP);
      return { start, size };
    });
    return { offsets, total: Math.max(0, total - (horizontal ? CARD_GAP : ROW_GAP)) };
  }, [presets, heights, horizontal]);
  const maxOffset = Math.max(0, layout.total - viewport.size);
  const offset = Math.min(scrollOffset, maxOffset);
  let first = layout.offsets.findIndex((row) => row.start + row.size > offset);
  if (first < 0) first = Math.max(0, presets.length - 1);
  let last = first;
  while (last < presets.length - 1 && layout.offsets[last]!.start < offset + viewport.size) last++;
  const start = Math.max(0, first - OVERSCAN);
  const end = Math.min(presets.length - 1, last + OVERSCAN);
  const visible = presets.flatMap((preset, index) => index >= start && index <= end || preset.id === focusedId ? [{ preset, index }] : []);

  useLayoutEffect(() => {
    const element = viewportRef.current;
    if (!element) return;
    const media = window.matchMedia?.(MOBILE_QUERY);
    const readViewport = () => {
      // client sizes include padding. Virtual offsets start at the inner content edge, so keyboard
      // alignment must use the content viewport to leave the final card/row fully visible.
      const styles = getComputedStyle(element);
      const horizontalPadding = (Number.parseFloat(styles.paddingLeft) || 0) + (Number.parseFloat(styles.paddingRight) || 0);
      const verticalPadding = (Number.parseFloat(styles.paddingTop) || 0) + (Number.parseFloat(styles.paddingBottom) || 0);
      const width = Math.max(1, (element.clientWidth || 320) - horizontalPadding);
      const next = { horizontal: media?.matches ?? false, width, size: media?.matches ? width : Math.max(1, (element.clientHeight || 600) - verticalPadding) };
      const previous = viewportState.current;
      viewportState.current = next;
      if (previous.width !== next.width || previous.horizontal !== next.horizontal || previous.size !== next.size) {
        const active = document.activeElement;
        resizeFocus.current = active instanceof HTMLButtonElement && element.contains(active) ? active.dataset.presetId ?? null : null;
      }
      if (previous.width !== next.width || previous.horizontal !== next.horizontal) {
        const measured = new Map<string, number>();
        for (const [id, node] of nodes.current) {
          const height = node.getBoundingClientRect().height;
          if (height > 0) measured.set(id, Math.max(MIN_ROW_HEIGHT, Math.ceil(height)));
        }
        setHeights(measured);
      }
      if (previous.horizontal !== next.horizontal) {
        element.scrollTop = 0;
        element.scrollLeft = 0;
        setScrollOffset(0);
      }
      if (previous.width !== next.width || previous.horizontal !== next.horizontal || previous.size !== next.size) setViewport(next);
    };
    const observer = new ResizeObserver((entries) => {
      if (entries.some((entry) => entry.target === element)) readViewport();
      if (viewportState.current.horizontal) return;
      setHeights((previous) => {
        let next: Map<string, number> | undefined;
        for (const entry of entries) {
          if (!(entry.target instanceof HTMLButtonElement)) continue;
          const id = entry.target.dataset.presetId;
          const measured = entry.borderBoxSize?.[0]?.blockSize ?? entry.target.getBoundingClientRect().height;
          if (!id || measured <= 0) continue;
          const height = Math.max(MIN_ROW_HEIGHT, Math.ceil(measured));
          if ((next ?? previous).get(id) === height) continue;
          next ??= new Map(previous);
          next.set(id, height);
        }
        return next ?? previous;
      });
    });
    observerRef.current = observer;
    observer.observe(element);
    for (const node of nodes.current.values()) observer.observe(node);
    readViewport();
    media?.addEventListener?.("change", readViewport);
    return () => {
      observer.disconnect();
      observerRef.current = null;
      media?.removeEventListener?.("change", readViewport);
    };
  }, []);

  // Names can wrap differently after a locale switch; remeasure mounted rows and discard stale offscreen sizes.
  useLayoutEffect(() => {
    const next = new Map<string, number>();
    for (const [id, node] of nodes.current) {
      const height = node.getBoundingClientRect().height;
      if (height > 0) next.set(id, Math.max(MIN_ROW_HEIGHT, Math.ceil(height)));
    }
    setHeights(next);
  }, [locale]);

  useLayoutEffect(() => {
    const id = pendingFocus.current;
    const node = id ? nodes.current.get(id) : undefined;
    if (node) { pendingFocus.current = null; node.focus({ preventScroll: true }); }
  });

  // A breakpoint can move the focused row to the other scroll axis. Follow its measured position
  // while resize measurements settle; an explicit user scroll cancels this alignment.
  useLayoutEffect(() => {
    const id = resizeFocus.current;
    if (id) focusOption(presets.findIndex((preset) => preset.id === id));
  }, [layout, viewport]);

  function rowRef(id: string) {
    let callback = rowRefs.current.get(id);
    if (!callback) {
      callback = (node) => {
        const previous = nodes.current.get(id);
        if (previous) observerRef.current?.unobserve(previous);
        if (node) { nodes.current.set(id, node); observerRef.current?.observe(node); }
        else nodes.current.delete(id);
      };
      rowRefs.current.set(id, callback);
    }
    return callback;
  }

  function focusOption(index: number) {
    const preset = presets[index];
    const row = layout.offsets[index];
    const element = viewportRef.current;
    if (!preset || !row || !element) return;
    // A short landscape viewport may be smaller than one card: keep its title at the leading edge.
    const targetOffset = row.size > viewport.size || row.start < offset ? row.start
      : row.start + row.size > offset + viewport.size ? row.start + row.size - viewport.size : offset;
    const nextOffset = Math.max(0, Math.min(maxOffset, targetOffset));
    programmaticOffset.current = nextOffset;
    if (horizontal) element.scrollLeft = nextOffset;
    else element.scrollTop = nextOffset;
    setScrollOffset((previous) => previous === nextOffset ? previous : nextOffset);
    setFocusedId(preset.id);
    const mounted = nodes.current.get(preset.id);
    if (mounted) mounted.focus({ preventScroll: true });
    else pendingFocus.current = preset.id;
  }

  return <div ref={viewportRef} className="video-presets-results video-presets-virtual-list" role="group" aria-label={t("video.presets.results")}
    data-orientation={horizontal ? "horizontal" : "vertical"} tabIndex={presets.length ? 0 : undefined}
    onScroll={(event) => {
      const next = Math.max(0, Math.min(maxOffset, horizontal ? event.currentTarget.scrollLeft : event.currentTarget.scrollTop));
      if (next !== programmaticOffset.current) resizeFocus.current = null;
      setScrollOffset((previous) => previous === next ? previous : next);
    }}
    onFocus={(event) => {
      if (event.target !== event.currentTarget) return;
      const fromAfter = event.relatedTarget instanceof Node && Boolean(event.currentTarget.compareDocumentPosition(event.relatedTarget) & Node.DOCUMENT_POSITION_FOLLOWING);
      focusOption(fromAfter ? presets.length - 1 : 0);
    }}
    onBlur={(event) => {
      if (!event.currentTarget.contains(event.relatedTarget)) { event.currentTarget.tabIndex = presets.length ? 0 : -1; resizeFocus.current = null; setFocusedId(null); }
    }}
    onKeyDown={(event) => {
      if (event.key !== "Tab" || !(event.target instanceof HTMLButtonElement)) return;
      const id = event.target.dataset.presetId;
      const index = presets.findIndex((preset) => preset.id === id);
      if (index < 0) return;
      const next = index + (event.shiftKey ? -1 : 1);
      if (next >= 0 && next < presets.length) { event.preventDefault(); focusOption(next); }
      // The group is the single native tab entry. Skip it when leaving backwards from its first option.
      else if (event.shiftKey) event.currentTarget.tabIndex = -1;
    }}>
    {presets.length ? <div className="video-presets-virtual-content" style={horizontal ? { width: layout.total, height: "100%" } : { height: layout.total }}>
      {visible.map(({ preset, index }) => <Button ref={rowRef(preset.id)} key={preset.id} type="button" variant="ghost" tabIndex={-1}
        className={cn("video-preset-option", selectedId === preset.id && "is-selected")} data-preset-id={preset.id}
        style={horizontal ? { left: layout.offsets[index]!.start, top: 0, width: CARD_WIDTH, height: "100%" } : { top: layout.offsets[index]!.start, left: 0, width: "100%" }}
        aria-label={preset.copy[locale].name} aria-pressed={selectedId === preset.id} onFocus={() => {
          if (resizeFocus.current !== preset.id) resizeFocus.current = null;
          setFocusedId(preset.id);
        }} onClick={() => onSelect(preset)}>
        <span className="video-preset-option-copy"><strong>{preset.copy[locale].name}</strong><small lang="en">{preset.englishName}</small><span>{preset.copy[locale].effect}</span></span>
        {selectedId === preset.id ? <Check aria-hidden="true" /> : <CaretRight aria-hidden="true" />}
      </Button>)}
    </div> : <Empty><EmptyHeader><EmptyMedia variant="icon"><MagnifyingGlass /></EmptyMedia><EmptyTitle>{t("video.presets.empty")}</EmptyTitle><EmptyDescription>{t("video.presets.emptyHint")}</EmptyDescription></EmptyHeader>
      <EmptyContent><Button type="button" variant="outline" onClick={onReset}>{t("video.presets.reset")}</Button></EmptyContent></Empty>}
  </div>;
}
