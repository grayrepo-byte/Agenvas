import { t, useLocale } from "../../shared/i18n";
import { Handle, Position } from "@xyflow/react";
import { PlusCircle, type Icon } from "@/shared/ui/icons";
import { useRef, type PointerEvent } from "react";

/** 图标静止尺寸；悬停与连接态只做 CSS 缩放，不再改这个值。 */
const HANDLE_ICON_SIZE = 32;
const HANDLE_HOVER_RADIUS = 28;
const HANDLE_MAX_OFFSET = 6;
const HANDLE_FOLLOW_FACTOR = 0.35;
const REDUCED_MOTION_QUERY = "(prefers-reduced-motion: reduce)";
const HANDLE_OFFSET_X = "--canvas-handle-offset-x";
const HANDLE_OFFSET_Y = "--canvas-handle-offset-y";

type CanvasHandleConfig = {
  /**
   * out：卡片右侧出口，悬停或选中卡片时显示，是唯一可拖出手势的连接点。
   * in：卡片左侧落点，默认不可见也不可点，落点靠 connectionRadius 的距离判定。
   * anchor：Agent 输出组锚点，只承接投影连线。
   */
  role: "out" | "in" | "anchor";
  position: Position;
  direction: "source" | "target";
  icon: Icon | null;
  hint: string;
};

/** 每个连接点只在这里声明一次，卡片之间不会出现位置、方向或图标分叉。 */
const CANVAS_HANDLES = {
  "artifact-output": { role: "out", position: Position.Right, direction: "source",
    icon: PlusCircle, get hint() { return t("canvas.connections.connectHint"); } },
  "artifact-input": { role: "in", position: Position.Left, direction: "target",
    icon: PlusCircle, get hint() { return t("canvas.connections.mixedInputHint"); } },
  "agent-output": { role: "anchor", position: Position.Right, direction: "source",
    icon: null, get hint() { return t("canvas.connections.agentOutputHint"); } },
  "agent-input": { role: "in", position: Position.Left, direction: "target",
    icon: PlusCircle, get hint() { return t("canvas.connections.mediaInputHint"); } },
} as const satisfies Record<string, CanvasHandleConfig>;

export type CanvasHandleId = keyof typeof CANVAS_HANDLES;

/**
 * React Flow 的几何盒固定在卡片边框；home 是卡片外的静止位置，只有内层圆点跟随指针。
 * 使用不动的 home 测距离，避免圆点移动后重新测量导致追逐抖动，也不改变连线端点。
 */
export function CanvasHandle({ id }: { id: CanvasHandleId }) {
  useLocale();
  const { direction, hint, icon: IconComponent, position, role } = CANVAS_HANDLES[id];
  const homeRef = useRef<HTMLSpanElement>(null);

  function resetOffset(event: PointerEvent<HTMLDivElement>) {
    event.currentTarget.style.removeProperty(HANDLE_OFFSET_X);
    event.currentTarget.style.removeProperty(HANDLE_OFFSET_Y);
  }

  function followPointer(event: PointerEvent<HTMLDivElement>) {
    const home = homeRef.current;
    if (role !== "out" || !home || event.pointerType !== "mouse" || event.buttons !== 0
      || window.matchMedia?.(REDUCED_MOTION_QUERY).matches) {
      resetOffset(event);
      return;
    }
    const bounds = home.getBoundingClientRect();
    // home 的布局宽度不随悬停变化；屏幕距离除以缩放，保持各级画布缩放下的位移上限。
    const scale = bounds.width / home.offsetWidth;
    if (!Number.isFinite(scale) || scale <= 0) {
      resetOffset(event);
      return;
    }
    const dx = (event.clientX - bounds.left - bounds.width / 2) / scale;
    const dy = (event.clientY - bounds.top - bounds.height / 2) / scale;
    const distance = Math.hypot(dx, dy);
    if (distance > HANDLE_HOVER_RADIUS) {
      resetOffset(event);
      return;
    }
    const factor = distance === 0 ? 0 : Math.min(HANDLE_FOLLOW_FACTOR, HANDLE_MAX_OFFSET / distance);
    event.currentTarget.style.setProperty(HANDLE_OFFSET_X, `${dx * factor}px`);
    event.currentTarget.style.setProperty(HANDLE_OFFSET_Y, `${dy * factor}px`);
  }

  return (
    <Handle className={`canvas-handle canvas-handle--${role}`} id={id}
      onPointerMove={followPointer} onPointerLeave={resetOffset}
      onPointerDown={resetOffset} onPointerCancel={resetOffset}
      isConnectable={role !== "anchor"} position={position} title={hint} type={direction}>
      {IconComponent
        ? <span className="canvas-handle-home" ref={homeRef}>
          <span className="canvas-handle-dot">
            <IconComponent aria-hidden="true" className="canvas-handle-icon" size={HANDLE_ICON_SIZE} weight="light" />
          </span>
        </span>
        : null}
    </Handle>
  );
}
