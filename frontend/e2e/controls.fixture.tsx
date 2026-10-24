import { useState } from "react";
import { createRoot } from "react-dom/client";
import { MusicNotes, VideoCamera } from "../src/shared/ui/icons";
import { Panel } from "../src/shared/ui/PagePrimitives";
import { Range } from "../src/shared/ui/Range";
import { Select } from "../src/shared/ui/Select";
import { Button } from "../src/shared/ui/primitives/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "../src/shared/ui/primitives/dropdown-menu";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "../src/shared/ui/primitives/field";
import "../src/styles.css";
import "./controls.fixture.css";

// Real shared components with synthetic options. This page makes no API requests.
function Controls() {
  const [brightness, setBrightness] = useState(24), [temperature, setTemperature] = useState(5200), [position, setPosition] = useState(38);
  const [selection, setSelection] = useState("video"), [action, setAction] = useState("尚未执行操作");
  return <main className="app-page controls-fixture">
    <header><p>AGEN VAS / CONTROL SYSTEM</p><h1>更轻、更清晰，始终一致。</h1><span>共用生产控件的合成验证页 · 鼠标、触屏与键盘均可操作</span></header>
    <div className="controls-grid">
      <Panel title="选择与筛选" description="统一触发器、选中标记与菜单层级。">
        <FieldGroup>
          <Field><FieldLabel htmlFor="control-format">常规选择</FieldLabel><Select id="control-format" value={selection} onChange={(event) => setSelection(event.target.value)}>
            <optgroup label="媒体类型"><option value="video">视频 / Video</option><option value="audio">音频 / Audio</option><option value="image">图片 / Image</option></optgroup>
            <optgroup label="暂不可用" disabled><option value="text">文本 / Text</option></optgroup>
          </Select><FieldDescription>空值、分组、禁用项、长名称使用同一交互。</FieldDescription></Field>
          <Field><FieldLabel htmlFor="control-compact">紧凑工具栏</FieldLabel><Select id="control-compact" density="compact" variant="ghost" defaultValue="720"><option value="720">720P · 适合预览</option><option value="1080">1080P · 高清导出</option></Select></Field>
          <Field><FieldLabel htmlFor="control-long">长列表与长名称</FieldLabel><Select id="control-long" defaultValue="0">{Array.from({ length: 64 }, (_, index) => <option key={index} value={String(index)}>{String(index + 1).padStart(2, "0")} · 具有较长名称的合成素材选项 / A longer synthetic option</option>)}</Select></Field>
          <Field data-invalid><FieldLabel htmlFor="control-invalid">待补充的配置</FieldLabel><Select id="control-invalid" defaultValue="" aria-invalid="true" aria-describedby="control-error"><option value="">请选择可用配置</option><option value="ready">已就绪配置</option></Select><FieldError id="control-error">请选择一项后继续。</FieldError></Field>
          <Field><FieldLabel htmlFor="control-disabled">等待期间</FieldLabel><Select id="control-disabled" defaultValue="waiting" disabled><option value="waiting">配置读取中</option></Select></Field>
        </FieldGroup>
      </Panel>
      <div className="ui-stack">
        <Panel title="参数与播放进度" description="细轨道、小拖柄；热区与键盘焦点保持清晰。">
          <FieldGroup>
            <Field><FieldLabel htmlFor="control-brightness">亮度 <output>{brightness > 0 ? "+" : ""}{brightness}</output></FieldLabel><Range id="control-brightness" min={-100} max={100} value={brightness} onChange={(event) => setBrightness(event.target.valueAsNumber)} /></Field>
            <Field><FieldLabel htmlFor="control-temperature">色温 <output>{temperature}K</output></FieldLabel><Range id="control-temperature" variant="temperature" min={2000} max={10000} step={100} value={temperature} onChange={(event) => setTemperature(event.target.valueAsNumber)} /></Field>
            <Field><FieldLabel htmlFor="control-position">媒体播放 <output>{position} / 100</output></FieldLabel><Range id="control-position" variant="media" min={0} max={100} value={position} onChange={(event) => setPosition(event.target.valueAsNumber)} /></Field>
            <Field><FieldLabel htmlFor="control-locked">暂不可用</FieldLabel><Range id="control-locked" variant="media" defaultValue={48} disabled /></Field>
          </FieldGroup>
        </Panel>
        <Panel title="模型与动作菜单" description="富信息选项与普通菜单沿用同一表面。">
          <FieldGroup><Field><FieldLabel htmlFor="control-model">能力选择</FieldLabel><Select id="control-model" defaultValue="video" optionDetails={{ video: { icon: <VideoCamera />, description: "合成视频能力 · 16:9 / 9:16" }, audio: { icon: <MusicNotes />, description: "合成音频能力 · WAV / MP3" } }}><option value="video">视频创作</option><option value="audio">音频创作</option></Select></Field></FieldGroup>
          <div className="controls-action"><DropdownMenu><DropdownMenuTrigger asChild><Button variant="outline">素材操作</Button></DropdownMenuTrigger><DropdownMenuContent align="start"><DropdownMenuGroup><DropdownMenuItem onSelect={() => setAction("已选择：查看详情")}>查看详情</DropdownMenuItem><DropdownMenuItem disabled>导出准备中</DropdownMenuItem></DropdownMenuGroup><DropdownMenuSeparator /><DropdownMenuGroup><DropdownMenuItem variant="destructive" onSelect={() => setAction("已选择合成移除动作；没有删除任何数据")}>移除合成素材</DropdownMenuItem></DropdownMenuGroup></DropdownMenuContent></DropdownMenu><output aria-live="polite">{action}</output></div>
        </Panel>
      </div>
    </div>
  </main>;
}
createRoot(document.getElementById("root")!).render(<Controls />);
