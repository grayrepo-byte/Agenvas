# video-studio Specification

## Purpose

独立视频工作台让创作者不用创建或打开画布，就能通过文字或静态首尾帧生成视频、查看真实任务状态，并从自己的历史中播放视频和继续编辑原来的生成设置。

## Requirements

### Requirement: Generate videos without a canvas project

系统 MUST 提供独立的视频生成工作台，使用所有者私有的任务、历史与素材范围，不创建画布节点或修改画布上的媒体选用。

#### Scenario: Enter Studio without any canvas projects

- **WHEN** 已登录用户从主导航进入视频工作台，即使还没有创建过项目
- **THEN** 用户可以查看已发布且适用于工作台的 Mock 或 Seedance 2.0 视频能力并使用其支持的模式；未配置可用能力时，显示设置入口而不是可提交的占位模型
- **AND** 进入、生成、刷新和浏览历史都不要求选择项目，也不向画布或生图工作台写入节点、结果或历史

#### Scenario: Prevent cross-account and cross-workspace access

- **WHEN** 未登录者或其他账户请求工作台历史、帧图、结果文件、封面或视频字节范围，或者用户把画布或生图工作台的版本 ID 作为视频工作台帧图提交
- **THEN** 服务端拒绝未经授权或不属于本工作台的访问；内部作用域不出现在项目列表，也不能通过普通画布、项目管理或运行入口操作

### Requirement: Enforce explicit video input modes and frame roles

系统 MUST 按所选已发布能力校验文生视频与首尾帧模式，固定有效的时长、参数和每个帧角色的不可变素材版本；不支持的模式与参数不得显示成可用选项。

#### Scenario: Submit text-to-video without frames

- **WHEN** 用户选择文生视频并提交非空提示词和符合能力的整数秒时长，没有帧或其他素材引用
- **THEN** 服务端按文字模式受理，固定能力和连接版本、有效时长、输出画幅及其他适用参数，不继承画布画幅
- **AND** 文生视频请求携带任何素材、采用能力不支持的设置，或使用已失效的能力版本时，明确拒绝并保留草稿供用户修正

#### Scenario: Generate from the first frame and optional last frame

- **WHEN** 用户选择首尾帧模式并上传可实际解码的静态图片作为首帧，并在能力支持时选择性上传尾帧
- **THEN** 工作台按角色展示已选帧图，提交固定的原版本；供应商收到与输入相应的首帧及尾帧角色，有首帧但无尾帧属于有效输入
- **AND** 缺首帧、仅有尾帧、不支持的尾帧、动态图片、非图片或任意外部素材 URL 均不被受理；不会退回文字模式替用户提交

#### Scenario: Change mode or model without silently losing frames

- **WHEN** 用户切换模式或模型，目标不支持现有的一个或多个帧角色
- **THEN** 工作台列明将移除的帧并等待明确确认；取消则保留原模式、模型及草稿，确认仅移除不兼容的引用并保留仍有效的输入

### Requirement: Accept one video generation without duplicate submission

系统 MUST 仅由显式生成动作创建一个视频生成任务，固定输入与用量，并对同一生成命令保持幂等。工作台 MUST 区分本地受理未确认、供应商受理不确定、失败和归档受阻。

#### Scenario: The page loses the acceptance receipt

- **WHEN** 浏览器超时或断线而未收到受理结果，用户选择核对原请求
- **THEN** 工作台使用冻结的原请求及原幂等键，同键同请求返回原记录，同键不同请求冲突，不创建第二条任务或用量预留

#### Scenario: The original provider attempt needs recovery

- **WHEN** 供应商受理不确定、查询或下载中断，或原结果归档失败
- **THEN** 工作台按真实状态反馈，不展示模拟进度；可核对或恢复时只处理原请求、原结果或已保存字节，不再次调用生成
- **AND** 等待、查询、刷新、重载播放器与历史回填都不发起新一轮生成；需要新尝试时，由用户另行显式生成

### Requirement: Play and download private archived videos

工作台 MUST 允许所有者播放、定位、放大和下载已归档的原视频，保留供应商返回的音轨。归档成功与播放器已就绪 MUST 分开呈现，切换结果不得把上一条视频或声音留在新页面。

#### Scenario: The video was saved but the browser is still loading

- **WHEN** 状态已返回归档视频，但浏览器尚未载入可播放数据
- **THEN** 工作台用可用封面并提示在加载播放，视频加载后提供播放、暂停、定位和声音控制，不自动外放或让另一个播放器继续发声
- **AND** 播放失败时保持真实归档状态，允许重新读取同一媒体，并保留独立的下载入口；不新建生成任务

#### Scenario: Continue reviewing saved videos

- **WHEN** 用户播放、快进、放大预览或下载历史结果
- **THEN** 浏览器使用认证保护的归档文件，可按需读取视频字节范围；不暴露供应商地址或凭据、不自动去音或转码替换原文件
- **AND** 若任务原生返回多个归档结果，按记录顺序都可选看和下载，不追加生成任务

### Requirement: Browse persistent history and reuse the original inputs

工作台 MUST 按账户保留生成记录和固定参数，支持无副作用的历史浏览和回填。自动回填只修改提示词；完整参数回填必须由用户显式触发。

#### Scenario: Open a historical video and edit the prompt

- **WHEN** 编辑栏未锁定，用户打开有归档视频的历史记录
- **THEN** 切换到该视频并直接载入原提示词，不改变模型、模式、时长或帧图；实际覆盖时显示来源并提供一次撤销
- **AND** 页面初始化、刷新、轮询、切换同批结果、选择无视频记录或受理未确认时的历史浏览，不改写草稿

#### Scenario: Restore a complete configuration explicitly

- **WHEN** 用户对所选历史记录或提示词来源点击「回填完整设置」
- **THEN** 按该记录固定的提示词、模型、模式、有效参数、首尾帧版本恢复草稿，不生成视频，也不改写历史
- **AND** 历史能力已不可用、版本变化或帧文件不可读时，提示差异；不可读的来源不覆盖草稿，过期设置不得静默升级或直接提交

### Requirement: Keep preview and controls usable on desktop and mobile

工作台 MUST 保持视频预览区与编辑区的中心对齐，并让每个可用控制能通过可见文字或无障碍名称识别。

#### Scenario: Use the workspace on desktop and narrow screens

- **WHEN** 用户在桌面开合历史栏、切换横竖视频，或在窄屏使用历史抽屉
- **THEN** 桌面播放器按比例完整展示并与编辑区沿整页中轴对齐，历史栏不遮挡预览；窄屏无整页横向滚动，模型、模式、参数和帧角色可识别，播放器和生成动作可键盘操作
- **AND** 关闭窄屏历史抽屉归还触发焦点，不因回填自动唤起软键盘
