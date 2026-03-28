package com.campus.memory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * AI 执行状态追踪与持久化管理 (State Management)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceInfo {
    /**
     * 原始查询
     */
    private String originalQuery;

    /**
     * 重写后的检索查询 (Context Condensation)
     */
    private String condensedQuery;

    /**
     * 识别出的意图类型 (DOCUMENT / DATA)
     */
    private String intentType;

    /**
     * 动态计算的相似度阈值
     */
    private Double threshold;

    /**
     * 向量库召回数量
     */
    private Integer rawMatchCount;

    /**
     * 过滤/重排后保留的数量
     */
    private Integer filteredMatchCount;

    /**
     * 关键文档打分快照 (文件名 -> 最高分)
     */
    private Map<String, Double> scoreSnapshot;

    private Integer rerankTopK;

    private Integer finalTopK;

    private String toolSequence;

    private Double routeConfidence;

    /**
     * 执行耗时 (ms)
     */
    private Long executionTimeMs;
}
