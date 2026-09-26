import { Handle, Position } from "@xyflow/react";
import { ArrowDownLeft, ArrowUpRight, type Icon } from "@phosphor-icons/react";

/** 图标静止尺寸；悬停与连接态只做 CSS 缩放，不再改这个值。 */
const HANDLE_ICON_SIZE = 11;

type CanvasHandleConfig = {
  /**
   * out：卡片右侧出口，选中卡片后显示，是唯一可拖出手势的连接点。
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
    icon: ArrowUpRight, hint: "拖到 Agent 输入或可引用的卡片" },
  "artifact-input": { role: "in", position: Position.Left, direction: "target",
    icon: ArrowDownLeft, hint: "接收素材引用或 Agent 输出组连线" },
  "agent-output": { role: "anchor", position: Position.Right, direction: "source",
    icon: null, hint: "Agent 输出组锚点，不接受手工连线" },
  "agent-input": { role: "in", position: Position.Left, direction: "target",
    icon: ArrowDownLeft, hint: "接收素材卡片连线" },
} as const satisfies Record<string, CanvasHandleConfig>;

export type CanvasHandleId = keyof typeof CANVAS_HANDLES;

/**
 * 连接点分两层：外层是 React Flow 的命中盒，中心必须留在卡片边框上（连线端点和落点判定都用它）；
 * 可见圆点是内层，整体挪到卡片外，避免被卡片背景和选中外圈切掉一半。显隐规则见 styles.css。
 */
export function CanvasHandle({ id }: { id: CanvasHandleId }) {
  const { direction, hint, icon: IconComponent, position, role } = CANVAS_HANDLES[id];
  return (
    <Handle className={`canvas-handle canvas-handle--${role}`} id={id}
      isConnectable={role !== "anchor"} position={position} title={hint} type={direction}>
      {IconComponent
        ? <span className="canvas-handle-dot">
          <IconComponent className="canvas-handle-icon" size={HANDLE_ICON_SIZE} weight="bold" />
        </span>
        : null}
    </Handle>
  );
}
