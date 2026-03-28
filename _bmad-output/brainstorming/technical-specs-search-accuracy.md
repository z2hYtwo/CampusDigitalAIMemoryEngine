# CDAME 搜索准确性增强技术规格书 (Technical Specification)

## 1. 概述 (Overview)
本项目旨在通过引入混合检索、知识图谱及大模型推理能力，彻底解决 CDAME 校史记忆引擎在处理长文本、相似语义及跨域数据干扰时的准确性瓶颈。

## 2. 系统架构 (System Architecture)
系统采用多层级过滤与增强架构：
1. **输入层**：意图澄清对话 (Category #6)
2. **检索层**：混合检索 (Category #3) + 元数据过滤 (Category #2)
3. **逻辑层**：校史知识图谱 (Category #5)
4. **后处理层**：语义去模糊 (Category #1) + 重排序 (Category #3)
5. **输出层**：智能合成与核查 (Category #4) + 史料溯源 (Category #7)

---

## 3. 核心组件详细规格 (Detailed Component Specs)

### 3.1 语义去模糊 (Semantic Blur Reduction)
- **目标**：区分语义极度接近的校史事件。
- **实现路径**：
    - 为高频相似事件（校庆、运动会、校运会）建立**特征关键词库**。
    - 在向量检索的基础上，对包含特征词的片段进行 **Dynamic Boosting** (动态加分)。
- **技术栈**：Elasticsearch Keyword Matching, Custom Scoring Functions.

### 3.2 元数据过滤 (Contextual Metadata Filtering)
- **目标**：物理或逻辑上隔离不同领域的数据，如隔离代码文档与校史档案。
- **实现路径**：
    - 在数据入库阶段（Ingestion），利用 LLM 自动为片段标记 `domain` 标签（如 `history`, `system_doc`, `admin`）。
    - 检索时根据用户查询意图，自动在 Metadata Filter 中加入 `domain` 限制。
- **技术栈**：Pinecone/Milvus Metadata Filtering, LangChain Indexing.

### 3.3 混合检索与重排序 (Hybrid Search & Reranking)
- **目标**：结合语义理解的广度与关键词匹配的精度。
- **实现路径**：
    - **第一路**：BM25 关键词匹配，确保“1920年”等关键事实不丢失。
    - **第二路**：Dense Vector Embedding，捕捉模糊语义。
    - **Fusion**：使用 RRF (Reciprocal Rank Fusion) 算法合并两路结果。
    - **Rerank**：使用 BGE-Reranker 等专用模型对 Top-10 片段进行二次打分。
- **技术栈**：BGE-M3 (Embedding), BGE-Reranker, Elasticsearch.

### 3.4 智能合成与核查 (LLM-Based Verification & Synthesis)
- **目标**：将原始片段转化为准确、通顺的直接答案。
- **实现路径**：
    - **Verification**：LLM 评估检索片段与 Query 的相关性（Binary Score: 0/1）。
    - **Synthesis**：采用 Multi-chunk Summarization 策略，将相关片段合成为一段校史回答。
- **技术栈**：GPT-4o/Claude 3.5, LangGraph for RAG Flow.

### 3.5 校史知识图谱 (School History Knowledge Graph)
- **目标**：提供基于事实逻辑的硬性约束。
- **实现路径**：
    - 建立 Entity-Relation 模型：`Person` -[Founded]-> `Institution`, `Event` -[HappenedAt]-> `Time`.
    - **Graph-RAG**：检索时先从图谱中查询实体关联，再引导向量搜索方向。
- **技术栈**：Neo4j, Cypher Query Language, LLM-based Entity Extraction.

### 3.6 意图澄清对话 (Intent Clarification Dialog)
- **目标**：在用户表达模糊时主动互动。
- **实现路径**：
    - 设定检索置信度阈值（Confidence Score < 0.6）。
    - 触发澄清逻辑：LLM 生成 3 个可能的候选问题供用户点击确认。
- **技术栈**：Frontend React State Machine, Backend Decision Tree.

### 3.7 史料溯源与冲突呈现 (Source Attribution & Conflict Resolution)
- **目标**：建立用户信任，尊重历史真相的多样性。
- **实现路径**：
    - 为每个片段记录 `source_type`（官方/民间/回忆录）。
    - 冲突检测算法：当不同来源对同一实体的属性（如时间）有不同赋值时，触发“对比展示”模板。
- **技术栈**：Structured Output Parser, React Comparative UI Components.

---

## 4. 技术栈建议 (Recommended Tech Stack)
- **Vector DB**: Pinecone (托管型) 或 Milvus (自建)
- **Graph DB**: Neo4j
- **Embedding**: BGE-M3 (支持多语言且性能卓越)
- **LLM**: GPT-4o 或 DeepSeek-V3 (校史领域表现稳定)
- **Framework**: LangChain / LangGraph (处理复杂的混合工作流)

## 5. 结论 (Conclusion)
通过上述 7 个层级的技术协同，CDAME 将具备处理校史复杂性的能力，从一个简单的文档检索器进化为真正的“校园数字记忆大脑”。
