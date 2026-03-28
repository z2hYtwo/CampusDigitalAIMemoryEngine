package com.campus.memory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponse {
    /**
     * AI 综合汇总后的答案
     */
    private String answer;

    /**
     * 检索到的原始记忆片段
     */
    private List<String> memories;

    /**
     * 是否需要用户进一步澄清意图
     */
    private boolean needsClarification;

    /**
     * 澄清建议问题列表
     */
    private List<String> clarificationSuggestions;

    /**
     * 相关的原始文件列表
     */
    private List<RelevantFile> relevantFiles;

    /**
     * AI 执行状态追踪与持久化管理 (State Management)
     */
    private TraceInfo trace;
}
