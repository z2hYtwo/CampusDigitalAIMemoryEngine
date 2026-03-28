---
stepsCompleted: [1, 2, 3]
inputDocuments: ['技术架构.docx', 'CampusMemory.docx', '阶段设计.pdf']
session_topic: 'AI Campus Memory Engine (校史记忆引擎) 项目深化与创意头脑风暴'
session_goals: '基于现有技术架构和功能设计，探索更具创新性的功能、优化的用户体验以及潜在的应用场景'
selected_approach: 'ai-recommended'
techniques_used: ['What If Scenarios', 'Analogical Thinking', 'SCAMPER Method']
ideas_generated: [5]
context_file: ''
---

# Brainstorming Session Results

**Facilitator:** z2h
**Date:** 2026-03-11

## Session Overview

**Topic:** AI Campus Memory Engine (校史记忆引擎) 项目深化与创意头脑风暴
**Goals:** 基于现有技术架构和功能设计，探索更具创新性的功能、优化的用户体验以及潜在的应用场景

### Context Guidance

根据读取的 `技术架构.docx` 和 `CampusMemory.docx`，项目是一个基于 AI 和知识图谱的校史记忆引擎，具备数字化档案、知识图谱展示、AI 问答、3D 可视化等核心能力。

### Session Setup

用户已提供项目核心设计文档，会议将围绕这些背景展开。

## 立项方案：校史数字资产记忆引擎 (CDAME)

### 1. 核心定义 (Core Concept)
本项目旨在构建一个**以数字资产为载体、以 AI 知识图谱为纽带、以沉浸式空间为展现**的校史记忆中枢。它不仅是一个“云盘”，更是一个能理解、能策展、能传承的校史智能体。

### 2. 初期技术架构 (System Architecture)
- **展现层**: Vue3 + TS + Three.js (3D 个人沉浸馆)
- **微服务层**: Spring Cloud Alibaba (Nacos, Gateway, OpenFeign)
- **AI/数据层**: MinIO (对象存储), Neo4j (图谱), Milvus (向量库), MySQL, Elasticsearch

### 3. P1 阶段：基础建设期详细路径 (Weeks 1-8)

#### A. 核心技术选型
- **后端**: Java 17 + Spring Boot 3.x
- **中间件**: Nacos (注册/配置中心), Redis (缓存), RabbitMQ (异步元数据提取)
- **资产存储**: MinIO (支持 S3 协议)

#### B. 关键实现逻辑：MinIO 预签名直传 (Presigned URL)
为降低后端压力，P1 采用前端直传模式：
1. **请求**: 前端向 `asset-service` 请求上传许可（包含文件名、MD5）。
2. **授权**: 后端生成 MinIO 预签名 URL（有效期 15min）并返回。
3. **上传**: 前端直接将文件 PUT 到 MinIO 桶中。
4. **回调/同步**: 上传完成后，前端通知后端或后端异步监听 MinIO 事件，完成 MySQL 元数据入库。

#### C. P1 实施里程碑
- **W1-W2**: 基础框架与 Nacos 环境搭建，Docker 容器化部署 MySQL/MinIO。
- **W3-W4**: 资产管理服务开发，实现多格式（图/影/音/文）基础上传与下载。
- **W5-W6**: 用户中心与网关鉴权 (Spring Security + JWT)，确保资产私密性。
- **W7-W8**: 前端资产看板 (Vue3) 对接，实现文件列表预览与直传功能。

## 详细阶段计划表 (Phase Roadmap) - 优先 AI 与向量化核心

| 阶段 | 目标 (Objective) | 核心任务 (Core Tasks) | 交付成果 (Deliverables) |
| :--- | :--- | :--- | :--- |
| **P1: AI 核心期** | **构建 AI 引擎与向量底座** | 集成 Milvus, LangChain4j, 实现资产向量化处理 | AI 语义处理服务与向量数据库集成 |
| **P2: 知识关联期** | **资产语义化与挂载** | 引入 Neo4j, 实现 AI 自动元数据提取与校史挂载 | 结构化校史知识图谱与语义关联逻辑 |
| **P3: 沉浸式开发期** | **个人专属沉浸空间** | 3D 展馆原型, AI 自动策展引擎, WebXR 支持 | 个人专属 3D 校史馆 (Alpha版) |
| **P4: 系统完善期** | **全场景集成与交付** | 优化 AI 问答准确性, 压力测试, 系统集成交付 | 完整可商用的 CDAME 系统与文档 |

## 创意想法捕获 (Ideas Captured)

**[Category #1]**: 跨时空“无尽笔记” (The Infinite Marginalia)
_Concept_: 为数字化档案配有“动态批注层”，AI 串联不同年代师生的见解。
_Novelty_: 打破档案静态属性，形成跨越百年的共同体笔记本。

**[Category #2]**: 谱系化荣誉树 (Lineage of Honor Graph)
_Concept_: 将荣誉作为知识图谱节点，自动追溯查阅资料与导师启发形成的传承线。
_Novelty_: 让荣誉不再孤立，而是历史链条的产物。

**[Category #3]**: 记忆圣殿 (The Sanctuary of Living History)
_Concept_: 基于 WebXR 的 3D 虚拟空间，由真实档案数据生成动态扩张的校史建筑。
_Novelty_: 提供空间化、可呼吸的历史沉浸体验。

**[Category #4]**: 全息记忆仓库 (The Omni-Memory Archive)
_Concept_: 支持全格式（扫描件、实物照片等）存储，AI 自动提取记忆指纹并挂载。
_Novelty_: 实现宏观校史与微观生活的无缝对接。

**[Category #5]**: 个人记忆“策展人” (The Personal Curator)
_Concept_: AI 根据存放素材的内在联系，自动在沉浸空间布置专题展区。
_Novelty_: 赋予记忆碎片叙事性，清晰展示个人成长逻辑线。
