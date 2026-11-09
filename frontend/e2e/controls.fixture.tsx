import { useState } from "react";
import { createRoot } from "react-dom/client";
import { Check, MagnifyingGlass, MusicNotes, Plus, SlidersHorizontal, VideoCamera } from "../src/shared/ui/icons";
import { Panel } from "../src/shared/ui/PagePrimitives";
import { Range } from "../src/shared/ui/Range";
import { Select } from "../src/shared/ui/Select";
import { Button } from "../src/shared/ui/primitives/button";
import { Checkbox } from "../src/shared/ui/primitives/checkbox";
import { DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "../src/shared/ui/primitives/dropdown-menu";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "../src/shared/ui/primitives/field";
import { Input } from "../src/shared/ui/primitives/input";
import { Switch } from "../src/shared/ui/primitives/switch";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../src/shared/ui/primitives/tabs";
import { Textarea } from "../src/shared/ui/primitives/textarea";
import { ToggleGroup, ToggleGroupItem } from "../src/shared/ui/primitives/toggle-group";
import "../src/styles.css";
import "./controls.fixture.css";

// Real production components with synthetic content. This page makes no API requests.
function Controls() {
  const [brightness, setBrightness] = useState(24), [temperature, setTemperature] = useState(5200), [position, setPosition] = useState(38);
  const [selection, setSelection] = useState("video"), [action, setAction] = useState("尚未执行操作");
  const [checked, setChecked] = useState(true), [mixed, setMixed] = useState<boolean | "indeterminate">("indeterminate"), [enabled, setEnabled] = useState(true);
  return <main className="app-page controls-fixture">
    <header><p>AGENVAS / COMPONENT SYSTEM</p><h1>更轻、更清晰，始终一致。</h1><span>统一的输入、选择与编辑体验。共用生产组件，支持鼠标、触屏与键盘。</span><div className="controls-summary"><span>36px 常规</span><span>32px 紧凑</span><span>14px 编辑正文</span><span><Check aria-hidden />真实组件 · 合成内容</span></div></header>
    <div className="controls-grid">
      <Panel title="输入与编辑" description="常规表单和紧凑工具栏共用尺寸、边框与焦点。">
        <FieldGroup>
          <div className="controls-pair"><Field><FieldLabel htmlFor="control-input">常规输入</FieldLabel><Input id="control-input" placeholder="为创作命名" defaultValue="雨后的城市漫游" /></Field>
            <Field><FieldLabel htmlFor="control-input-compact">紧凑输入</FieldLabel><Input id="control-input-compact" density="compact" placeholder="关键词" defaultValue="电影感" /></Field></div>
          <Field><FieldLabel htmlFor="control-search-input">搜索</FieldLabel><div id="control-search" className="ui-search-field"><MagnifyingGlass aria-hidden /><Input id="control-search-input" variant="ghost" type="search" placeholder="搜索项目、素材与灵感…" /></div></Field>
          <Field><FieldLabel htmlFor="control-search-compact-input">紧凑搜索</FieldLabel><div id="control-search-compact" className="ui-search-field" data-density="compact"><MagnifyingGlass aria-hidden /><Input id="control-search-compact-input" density="compact" variant="ghost" type="search" placeholder="搜索当前目录…" /></div></Field>
          <Field><FieldLabel htmlFor="control-textarea">多行编辑</FieldLabel><Textarea id="control-textarea" rows={4} defaultValue={"清晨的薄雾沿着街道缓缓散开，湿润的路面映出柔和的天空。\n镜头平稳向前，保留建筑的细节与自然的色彩。"} /><FieldDescription>正文使用 14px 字体与舒适行距，长内容在编辑区内滚动。</FieldDescription></Field>
          <div className="controls-pair"><Field data-invalid><FieldLabel htmlFor="control-input-invalid">错误状态</FieldLabel><Input id="control-input-invalid" aria-invalid="true" aria-describedby="control-input-error" placeholder="补充必填信息" /><FieldError id="control-input-error">请填写名称后继续。</FieldError></Field>
            <Field data-disabled><FieldLabel htmlFor="control-input-disabled">等待期间</FieldLabel><Input id="control-input-disabled" disabled defaultValue="配置读取中" /></Field></div>
        </FieldGroup>
      </Panel>
      <Panel title="选择与筛选" description="统一触发器、选中标记与菜单层级。">
        <FieldGroup>
          <Field><FieldLabel htmlFor="control-format">常规选择</FieldLabel><Select id="control-format" value={selection} onChange={(event) => setSelection(event.target.value)}>
            <optgroup label="媒体类型"><option value="video">视频 / Video</option><option value="audio">音频 / Audio</option><option value="image">图片 / Image</option></optgroup>
            <optgroup label="暂不可用" disabled><option value="text">文本 / Text</option></optgroup>
          </Select><FieldDescription>分组、禁用项与长名称使用同一交互。</FieldDescription></Field>
          <Field><FieldLabel htmlFor="control-compact">紧凑工具栏</FieldLabel><Select id="control-compact" density="compact" variant="ghost" defaultValue="720"><option value="720">720P · 适合预览</option><option value="1080">1080P · 高清导出</option></Select></Field>
          <Field><FieldLabel htmlFor="control-long">长列表与长名称</FieldLabel><Select id="control-long" defaultValue="0">{Array.from({ length: 64 }, (_, index) => <option key={index} value={String(index)}>{String(index + 1).padStart(2, "0")} · 具有较长名称的合成素材选项 / A longer synthetic option</option>)}</Select></Field>
          <Field><FieldLabel htmlFor="control-model">富信息选择</FieldLabel><Select id="control-model" defaultValue="video" optionDetails={{ video: { icon: <VideoCamera />, description: "合成视频能力 · 16:9 / 9:16" }, audio: { icon: <MusicNotes />, description: "合成音频能力 · WAV / MP3" } }}><option value="video">视频创作</option><option value="audio">音频创作</option></Select></Field>
          <Field data-invalid><FieldLabel htmlFor="control-invalid">待补充的配置</FieldLabel><Select id="control-invalid" defaultValue="" aria-invalid="true" aria-describedby="control-error"><option value="">请选择可用配置</option><option value="ready">已就绪配置</option></Select><FieldError id="control-error">请选择一项后继续。</FieldError></Field>
          <Field><FieldLabel htmlFor="control-disabled">等待期间</FieldLabel><Select id="control-disabled" defaultValue="waiting" disabled><option value="waiting">配置读取中</option></Select></Field>
        </FieldGroup>
      </Panel>
      <Panel title="勾选与开关" description="清晰区分选中、半选与禁用；空格键即可操作。">
        <FieldGroup>
          <FieldSet><FieldLegend variant="label">多选状态</FieldLegend><FieldGroup>
            <Field orientation="horizontal"><Checkbox id="control-checkbox" checked={checked} onCheckedChange={(value) => setChecked(value === true)} /><FieldLabel htmlFor="control-checkbox">保留创作历史</FieldLabel></Field>
            <Field orientation="horizontal"><Checkbox id="control-checkbox-mixed" checked={mixed} onCheckedChange={setMixed} /><FieldLabel htmlFor="control-checkbox-mixed">已选择部分素材</FieldLabel></Field>
            <Field orientation="horizontal"><Checkbox id="control-checkbox-empty" /><FieldLabel htmlFor="control-checkbox-empty">包含归档内容</FieldLabel></Field>
            <Field orientation="horizontal" data-disabled><Checkbox id="control-checkbox-disabled" checked disabled /><FieldLabel htmlFor="control-checkbox-disabled">等待任务完成</FieldLabel></Field>
          </FieldGroup></FieldSet>
          <FieldSet><FieldLegend variant="label">即时开关</FieldLegend><FieldGroup>
            <Field orientation="horizontal"><Switch id="control-switch" checked={enabled} onCheckedChange={setEnabled} /><FieldLabel htmlFor="control-switch">显示画布网格</FieldLabel></Field>
            <Field orientation="horizontal"><Switch id="control-switch-small" size="sm" /><FieldLabel htmlFor="control-switch-small">紧凑开关</FieldLabel></Field>
            <Field orientation="horizontal" data-disabled><Switch id="control-switch-disabled" checked disabled /><FieldLabel htmlFor="control-switch-disabled">等待配置就绪</FieldLabel></Field>
          </FieldGroup></FieldSet>
        </FieldGroup>
      </Panel>
      <Panel title="操作与选项组" description="主次、危险与选中状态拥有一致的视觉层级。">
        <FieldGroup><FieldSet><FieldLegend variant="label">常规操作</FieldLegend><div className="controls-buttons">
          <Button id="control-button"><Plus data-icon="inline-start" aria-hidden />新建项目</Button><Button variant="outline">保存草稿</Button><Button variant="secondary">次要操作</Button><Button variant="ghost">取消</Button><Button variant="destructive">删除</Button><Button variant="link">查看详情</Button><Button disabled>处理中</Button>
        </div></FieldSet><FieldSet><FieldLegend variant="label">紧凑工具栏</FieldLegend><div className="controls-buttons"><Button id="control-button-compact" size="sm">保存</Button><Button variant="outline" size="sm">预览</Button><Button variant="ghost" size="icon-sm" aria-label="参数设置"><SlidersHorizontal aria-hidden /></Button></div></FieldSet>
        <FieldSet><FieldLegend variant="label">媒体上的轻量操作</FieldLegend><div className="controls-media-actions">
          <div className="controls-media-light"><Button id="control-media-light" variant="media-overlay" size="icon-lg" aria-label="预览合成白色素材" onClick={() => setAction("已预览合成素材")}><Plus aria-hidden /></Button></div>
          <div className="controls-media-dark"><Button id="control-media-dark" variant="media-overlay" size="icon-lg" aria-label="预览合成黑色素材"><Plus aria-hidden /></Button><Button id="control-media-disabled" variant="media-overlay" size="icon-lg" disabled aria-label="素材处理中"><Plus aria-hidden /></Button></div>
        </div></FieldSet>
        <FieldSet><FieldLegend variant="label">带说明的选择行</FieldLegend><Button id="control-content-button" size="content" variant="outline" className="justify-start text-left"><span><span className="block">城市漫游 · 叙事音色</span><span className="block">温和、自然，适合多行内容与说明。</span></span></Button></FieldSet>
          <FieldSet><FieldLegend variant="label">多选选项组</FieldLegend><ToggleGroup type="multiple" variant="outline" spacing={2} defaultValue={["image", "video"]} className="controls-options" aria-label="输出类型"><ToggleGroupItem value="image">图片</ToggleGroupItem><ToggleGroupItem value="video">视频</ToggleGroupItem><ToggleGroupItem value="audio">音频</ToggleGroupItem><ToggleGroupItem value="text" disabled>文本</ToggleGroupItem></ToggleGroup></FieldSet>
        </FieldGroup>
        <div className="controls-action"><DropdownMenu><DropdownMenuTrigger asChild><Button id="control-actions" variant="outline">素材操作</Button></DropdownMenuTrigger><DropdownMenuContent align="start"><DropdownMenuGroup><DropdownMenuItem onSelect={() => setAction("已选择：查看详情")}>查看详情</DropdownMenuItem><DropdownMenuItem disabled>导出准备中</DropdownMenuItem></DropdownMenuGroup><DropdownMenuSeparator /><DropdownMenuGroup><DropdownMenuItem variant="destructive" onSelect={() => setAction("已选择合成移除动作；没有删除任何数据")}>移除合成素材</DropdownMenuItem></DropdownMenuGroup></DropdownMenuContent></DropdownMenu><output aria-live="polite">{action}</output></div>
      </Panel>
      <Panel title="标签页" description="分段、线形和带说明的导航；方向键跟随各自方向。" className="controls-wide">
        <div className="controls-tabs-examples"><Tabs defaultValue="overview"><TabsList id="control-tabs" aria-label="项目视图"><TabsTrigger id="control-tab-overview" value="overview">概览</TabsTrigger><TabsTrigger id="control-tab-assets" value="assets">素材</TabsTrigger><TabsTrigger value="history" disabled>历史</TabsTrigger></TabsList><TabsContent value="overview"><p className="controls-tab-copy">在相同的位置找到当前内容，切换保持轻盈。</p></TabsContent><TabsContent value="assets"><p className="controls-tab-copy">素材视图已打开。</p></TabsContent></Tabs>
          <Tabs defaultValue="image"><TabsList id="control-tabs-line" variant="line" aria-label="媒体目录"><TabsTrigger value="image">图片</TabsTrigger><TabsTrigger value="video">视频</TabsTrigger><TabsTrigger value="audio">音频</TabsTrigger></TabsList><TabsContent value="image"><p className="controls-tab-copy">线形标签适合页面内的内容分类。</p></TabsContent><TabsContent value="video"><p className="controls-tab-copy">视频目录已打开。</p></TabsContent><TabsContent value="audio"><p className="controls-tab-copy">音频目录已打开。</p></TabsContent></Tabs></div>
        <Tabs defaultValue="creation" orientation="vertical" className="controls-navigation"><TabsList id="control-navigation" variant="navigation" aria-label="设置分类"><TabsTrigger id="control-nav-creation" value="creation"><span><strong>创作设置</strong><small>模型、格式与输出偏好</small></span></TabsTrigger><TabsTrigger id="control-nav-display" value="display"><span><strong>显示设置</strong><small>工作区与显示密度</small></span></TabsTrigger></TabsList>
          <TabsContent value="creation"><Tabs defaultValue="parameters"><TabsList id="control-nested-tabs" size="sm" aria-label="创作设置详情"><TabsTrigger id="control-nested-parameters" value="parameters">参数</TabsTrigger><TabsTrigger id="control-nested-output" value="output">输出</TabsTrigger></TabsList><TabsContent value="parameters"><p className="controls-tab-copy">外层纵向导航、内层横向标签，方向和焦点各自独立。</p></TabsContent><TabsContent value="output"><p className="controls-tab-copy">输出配置已打开。</p></TabsContent></Tabs></TabsContent><TabsContent value="display"><p className="controls-tab-copy">显示配置已打开。</p></TabsContent></Tabs>
      </Panel>
      <Panel title="参数与播放进度" description="细轨道、小拖柄；热区与键盘焦点保持清晰。" className="controls-wide">
        <FieldGroup className="controls-range-grid">
          <Field><FieldLabel htmlFor="control-brightness">亮度 <output>{brightness > 0 ? "+" : ""}{brightness}</output></FieldLabel><Range id="control-brightness" min={-100} max={100} value={brightness} onChange={(event) => setBrightness(event.target.valueAsNumber)} /></Field>
          <Field><FieldLabel htmlFor="control-temperature">色温 <output>{temperature}K</output></FieldLabel><Range id="control-temperature" variant="temperature" min={2000} max={10000} step={100} value={temperature} onChange={(event) => setTemperature(event.target.valueAsNumber)} /></Field>
          <Field><FieldLabel htmlFor="control-position">媒体播放 <output>{position} / 100</output></FieldLabel><Range id="control-position" variant="media" min={0} max={100} value={position} onChange={(event) => setPosition(event.target.valueAsNumber)} /></Field>
          <Field><FieldLabel htmlFor="control-locked">暂不可用</FieldLabel><Range id="control-locked" variant="media" defaultValue={48} disabled /></Field>
        </FieldGroup>
      </Panel>
    </div>
    <footer>此页仅验证公共组件外观与交互，不创建项目、不调用模型、不读取业务数据。</footer>
  </main>;
}
createRoot(document.getElementById("root")!).render(<Controls />);
