# Agenvas 创作卡片工作流

## 读取输入

先用 read_project_summary 查看当前项目及本轮执行限额，用 read_selection 确认本轮选择意图。选中卡片、标题、正文里提到的 UUID 都不授予读写权限。实际资料只读本轮已绑定的精确版本、当前工具结果返回的版本或本轮创建的版本。

调用 read_artifacts 时只传实际 versionIds，每次最多 12 个。来源统一记录为「卡片标题 · artifactId · versionId · 正文行范围/条目 ID」。标题可重复，UUID 才定位来源；引用旧版时保留旧 versionId。文字正文行号从 1 开始，适用于该固定版本，不能借用另一版的行号。

必须检查 contentTruncated。true 只表示读到了预览，不能据此完成全文索引、统计或分析；请用户把原文按章节/场次拆成文字卡并绑定所需卡片。本系统当前没有文件路径读取、全文导入或文字分页工具。缺输入时列出已有范围和缺失项，不从书名或本地地址补造内容。卡片内引用的其他版本若本轮不可读，交给用户绑定后再续接。

## 保存与续接

用户要求创作并保存、建立分析卡或修订现有卡时，使用 create_text({title,text,format:"MARKDOWN"})；创建成功会自动放到当前 Agent 的画布输出组，无需再次放置。只要求建议/讨论时直接回复。

需要更新已有文字卡时，先读本轮可见的当前版本，使用返回的 expectedVersion 或前序工具结果 artifactVersions 中的 CAS 值，调用 revise_artifact({artifactId,expectedVersion,content:{format:"MARKDOWN",text:"完整新正文"}})。正文包含保留部分；工具确认后才报告保存成功。冲突/失败时保留候选正文与问题，不把失败写成完成，不用同名新卡替代更新失败。

create_text/revise_artifact 返回的 affectedVersions 是真实内容版本 ID，artifactVersions 是修改用的数字版本。后续卡片引用真实 affectedVersions，不发明 UUID。通过 place_artifacts 和 arrange_items 整理已有输出时，每次最多 6 项，仅使用工具返回的 itemId、内容 versionId、itemVersion；布局与正文分开修改。

跨轮继续时由用户绑定原文、索引、进度及所需分析卡的固定版本。先读进度，核对来源版本，再补未完成范围。上次记录的完成状态如果本轮没有读对应分析卡，写「已记录，未重新核对」。原文新增版本后，旧分析仍属于旧版，须建立新的索引或明确重做受影响范围。

按项目返回的模型轮次/工具次数限额分批。一次完成一个可核对的小批次，把完成范围、实际产出版本、缺项和下一步保存到进度卡；长正文按章、集或场拆卡。临近限额优先保存当前批次与断点，不承诺单轮处理完整长篇。

## 媒体与跨阶段

说明、索引、剧本、视觉设定、提示词、分镜与审查都是文字卡。只有实际 IMAGE/VIDEO/AUDIO 版本才能作为媒体引用，提示词条目、PLAN- 标记和文字设定不是图片或音频。

需要观察图片时，通过 read_artifacts 请求本轮绑定的 IMAGE 版本；只有后续模型请求确实收到图片后才描述所见。视频/音频缺少直接观察能力时，区分用户说明、工具元数据与未验证内容。

用户要求媒体生成时先 list_media_capabilities，按实际能力及真实媒体 versionId 提出 propose_media_generation，由本系统审批和 Task 执行。文字卡里的「已确认」不等于媒体批准。创作正文语言跟随用户；提示词语言以用户/已绑定创作简报的明确要求为准，未指定时沿用当前材料语言。

跨 Skill 仅使用本轮已选择的候选，先 read_skill 加载对应版本；没有选择时完成当前阶段并说明后续需要哪一项，不自动切换。附件只通过 read_skill_resource 的当前 skillVersionId 与清单内精确 path 加载，按需要分页，不能把附件路径当作项目文件。
