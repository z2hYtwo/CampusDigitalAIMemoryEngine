package com.campus.memory.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PlanningLayer {

    public enum PlanType {
        DOCUMENT, DATA, MIXED, GENERAL
    }

    public record PlanningDecision(
        PlanType planType,
        String condensedQuery,
        List<String> suggestedTools,
        double confidence
    ) {}

    private final java.util.Map<String, PlanType> sessionIntentCache = new java.util.concurrent.ConcurrentHashMap<>();

    public PlanningDecision plan(String query, String sessionId) {
        String normalized = normalizeQuery(query);
        boolean multimediaIntent = containsAny(normalized, List.of(
            "视频", "影片", "影像", "短片", "宣传片", "录像", "mv", "vlog",
            "音频", "音乐", "录音", "播客",
            "图片", "照片", "海报", "图像", "多媒体"
        ));
        boolean documentIntent = containsAny(normalized, List.of(
            "文档", "资料", "文件", "校史", "政策", "规章", "通知", "报告", "pdf", "doc",
            "招生", "简章", "报考", "录取", "志愿", "专业介绍", "专业", "学院", "学科", "招生办"
        ));
        boolean dataIntent = containsAny(normalized, List.of(
            "成绩", "分数", "学生", "名单", "统计", "排名", "教务", "多少人", "多少名",
            "学号", "学籍", "档案", "个人信息", "学生信息"
        ));
        boolean nameOnlyQuery = looksLikeChineseName(normalized);

        if (nameOnlyQuery && !documentIntent) {
            dataIntent = true;
        }
        if (multimediaIntent) {
            documentIntent = true;
        }

        PlanType decidedType = PlanType.DOCUMENT; // 默认为文档/全局搜索意图
        double confidence = 0.55;

        if (documentIntent && dataIntent) {
            decidedType = PlanType.MIXED;
            confidence = 0.92;
        } else if (documentIntent) {
            decidedType = PlanType.DOCUMENT;
            confidence = 0.88;
        } else if (dataIntent) {
            decidedType = PlanType.DATA;
            confidence = 0.86;
        } else if (sessionId != null && normalized.length() < 10) {
            // 意图继承：对于短查询，尝试继承上一轮的意图
            PlanType lastIntent = sessionIntentCache.get(sessionId);
            if (lastIntent != null && lastIntent != PlanType.GENERAL) {
                decidedType = lastIntent;
                confidence = 0.75; // 继承的意图置信度稍低
            }
        }

        // 兜底：如果既不是纯数据意图，也不是混合意图，则统一走 DOCUMENT 路由执行全局语义检索
        if (decidedType == PlanType.GENERAL) {
            decidedType = PlanType.DOCUMENT;
        }
        if (multimediaIntent && decidedType == PlanType.DOCUMENT) {
            confidence = Math.max(confidence, 0.92);
        }

        // 更新缓存
        if (sessionId != null && decidedType != PlanType.GENERAL) {
            sessionIntentCache.put(sessionId, decidedType);
        }

        List<String> suggestedTools = new java.util.ArrayList<>();
        if (decidedType == PlanType.MIXED) {
            suggestedTools.add("searchCampusDocuments");
            suggestedTools.add("ScoreService");
        } else if (decidedType == PlanType.DOCUMENT) {
            suggestedTools.add("searchCampusDocuments");
        } else if (decidedType == PlanType.DATA) {
            suggestedTools.add("ScoreService");
        }

        return new PlanningDecision(
            decidedType,
            normalized,
            suggestedTools,
            confidence
        );
    }

    private String normalizeQuery(String query) {
        if (query == null) return "";
        String q = query.trim()
            .replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("\\s{2,}", " ");
        String[] softWords = {"请问", "帮我", "麻烦", "一下", "请", "想知道"};
        for (String softWord : softWords) {
            q = q.replace(softWord, "");
        }
        return q.trim();
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null || text.isBlank() || keywords == null || keywords.isEmpty()) return false;
        List<String> hits = new ArrayList<>();
        String lower = text.toLowerCase();
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && lower.contains(keyword.toLowerCase())) {
                hits.add(keyword);
            }
        }
        return !hits.isEmpty();
    }

    private boolean looksLikeChineseName(String text) {
        if (text == null) return false;
        String normalized = text.trim();
        if (normalized.length() < 2 || normalized.length() > 4) return false;
        return normalized.matches("^[\\u4e00-\\u9fa5·]{2,4}$");
    }
}
