---
stepsCompleted: [1, 2, 3, 4]
inputDocuments: ['src/main/java/com/campus/memory/service/ScoreService.java', 'cdame-front/src/App.tsx']
session_topic: '成绩分析中心 (Score Analytics Center) 升级与智能化 - 校园荣誉墙专场'
session_goals: '设计基于 RAG 检索的校园荣誉生长树，实现荣誉资产的向量化入库与跨时空叙事'
selected_approach: 'AI-Recommended'
techniques_used: ['Analogical Thinking']
ideas_generated: ['Chronological Honor Tree', 'Memory Bubble Narrative', 'Intergenerational Textual Mentoring']
context_file: ''
---

# Brainstorming Session Results

**Facilitator:** z2h
**Date:** 2026-03-24

**Context Loading:** 
已加载 [ScoreService.java](file:///e:/AI%20Campus%20Memory%20Engine/src/main/java/com/campus/memory/service/ScoreService.java) 和 [App.tsx](file:///e:/AI%20Campus%20Memory%20Engine/cdame-front/src/App.tsx) 核心业务逻辑。

**Context-Based Guidance:** 
当前系统已实现学生维度的“雷达图+AI建议+趋势图”，并为教师端增加了“学号/姓名检索”及“回退”功能。接下来的重点应放在：
1. **检索深度**：如何让教师更快速锁定特定问题的学生？
2. **建议质量**：AI生成的建议如何更具行动导向？
3. **交互闭环**：教师发现问题后如何一键反馈或采取行动？

---

## 创意碰撞实时记录

**[Category #1]**: 时间轴上的“荣誉生长树” (Chronological Honor Tree)
_Concept_: 将校园荣誉资产设计为一颗可视化的、可交互的“校园树”。以建校时间为树根，每一个新加入的荣誉（奖状、证书、影像资料）作为树干上的一个新节点或一片叶子，通过 RAG 检索实现荣誉间的语义关联。
_Novelty_: 改变了传统列表式的展示，将枯燥的数据向量化入库转化为一种“生命演化”的视觉叙事，用户可以通过点击树枝溯源到几十年前的关联荣誉。

**[Category #2]**: 记忆气泡溯源叙事 (Memory Bubble Narrative)
_Concept_: 每一个荣誉节点都是一个承载多维数据的“记忆气泡”。当用户点击时，RAG 系统不仅展示该荣誉本身，还会自动检索并织入相关的“背景上下文”：如当时的校园报纸头条、指导教师的寄语、同期的校园重大事件，由 AI 合成为一段具有时代感的叙事白描。
_Novelty_: 实现了从“孤立荣誉点”到“连续记忆线”的跨越。利用 [MemoryService.java](file:///e:/AI%20Campus%20Memory%20Engine/src/main/java/com/campus/memory/service/MemoryService.java) 中的 `searchCampusDocuments` 工具，让每个节点都能自动寻找其历史坐标和关联人物。

**[Category #3]**: 跨时空“精神养分”留言 (Intergenerational Textual Mentoring)
_Concept_: 为“荣誉生长树”的每一个气泡节点增加文字留言功能。初期聚焦于纯文字互动，允许后来者对前人的荣誉进行“致敬”或“提问”，留言内容同步向量化入库，成为该荣誉资产的一部分元数据（Metadata）。
_Novelty_: 留言不再是孤立的评论，而是成为了“校园资产”的增量。通过 RAG 检索，后来的学生搜索相关领域时，能同时搜到前辈的成就以及后辈的感悟，形成一种跨越时空的“精神传承”反馈回路。

---

## 技术路径还原：校园荣誉生长树 (Technical Implementation Path)

### 1. 资产向量化与元数据增强 (Ingestion Layer)
- **核心逻辑**：在调用 [MemoryService.java](file:///e:/AI%20Campus%20Memory%20Engine/src/main/java/com/campus/memory/service/MemoryService.java) 的 `addMemory` 时，强制要求或自动提取以下元数据：
    - `isHonor`: 标记是否为荣誉资产。
    - `honorLevel`: 级别（校级、省级、国家级），用于决定“树枝”的粗细。
    - `honorCategory`: 类别（学术、体育、艺术、社会实践），用于决定“分枝”的方向。
    - `timestamp`: 荣誉获得的精确时间，用于确定在“树干”上的高度。
- **存储**：利用 Milvus 的 Metadata 过滤功能，确保检索荣誉墙时只针对 `isHonor=true` 的向量片段。

### 2. RAG 关联检索与叙事合成 (Retrieval Layer)
- **多维关联算法**：当用户点击一个节点时，执行混合检索：
    - **语义召回**：寻找内容最相似的荣誉（例如：同为机器人大赛）。
    - **时空召回**：寻找同一时间段或同一地点的相关记录。
- **叙事引擎**：利用 `Assistant` 的 `SystemMessage` 规则，将检索到的片段、元数据（文件名、年份）合成为一段具有温度的“记忆气泡”描述。

### 3. 前端可视化与交互 (Frontend Layer)
- **可视化选型**：建议使用 ECharts 的 `tree` 或 `graph` 布局，或者 D3.js 实现更灵动的生长效果。
- **交互逻辑**：
    - **初始化**：后端返回按时间排序的荣誉节点列表及它们的关联权重。
    - **生长动画**：新荣誉入库时，前端触发“新芽初绽”的动效。
    - **气泡弹窗**：点击节点展示 [Category #2] 定义的叙事内容和 [Category #3] 的留言列表。

### 4. 留言系统与权限控制
- **数据结构**：留言作为主荣誉资产的关联实体存入 MySQL，同时其文本摘要向量化存入 Milvus。
- **严肃性保护**：设置留言审核机制，确保“精神传承”不被无关内容稀释。
