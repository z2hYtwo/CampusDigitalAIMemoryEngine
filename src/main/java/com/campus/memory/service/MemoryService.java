package com.campus.memory.service;

import com.campus.memory.dto.SearchResponse;
import com.campus.memory.dto.TraceInfo;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.campus.memory.context.OrchestrationContext;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentBySentenceSplitter;
import com.campus.memory.dto.RelevantFile;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import java.util.concurrent.ConcurrentHashMap;

import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryService {
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChatLanguageModel chatLanguageModel;
    private final ScoreService scoreService;
    private final PlanningLayer planningLayer;
    private final JdbcTemplate jdbcTemplate;
    @Value("${memory.retrieval.topk.default:40}")
    private int defaultRetrievalTopK;
    @Value("${memory.retrieval.topk.multimedia:60}")
    private int multimediaRetrievalTopK;

    // --- 荣誉墙元数据常量 ---
    public static final String METADATA_IS_HONOR = "isHonor";
    public static final String METADATA_HONOR_LEVEL = "honorLevel"; // 校级, 省级, 国家级
    public static final String METADATA_HONOR_CATEGORY = "honorCategory"; // 学术, 体育, 艺术, 社会实践
    public static final String METADATA_TIMESTAMP = "timestamp"; // 精确获得时间
    public static final String METADATA_HONOR_YEAR = "honorYear"; // 获得年份
    private static final String HONOR_DELETE_TABLE = "honor_deleted_records";
    private static final String[] INVALID_RECALL_MARKERS = {
        "ocr识别失败", "ocr 识别失败", "识别失败", "无法识别", "未识别到文本", "未提取到文本",
        "无文本内容", "无有效信息", "内容为空", "仅文件元数据", "仅包含文件元数据"
    };
    private static final String[] TEMPLATE_PREFIXES = {
        "文件名:", "分类:", "媒体类型:", "检索标签:", "识别语言:", "source:", "filename:", "category:"
    };
    private static final Pattern SELF_REFERENCE_PATTERN = Pattern.compile("(?<!们)我(?!们)");

    // 已迁移至 OrchestrationContext

    /**
     * 定义 AI Agent 接口 (Agentic Workflow)
     */
    interface Assistant {
        @SystemMessage({
            "你是校园智能助理。你的任务是基于检索到的文档、链接、多媒体资料或数据库信息，为用户提供准确、专业的回答。",
            "### 核心规则：",
            "1. **引用增强 (Citations)**: 当你的回答参考了工具返回的文档片段时，必须在对应的句子末尾使用 [n] 标注来源，其中 n 是工具结果中 facts 数组的索引（从 1 开始）。例如：'校园开放日定于10月24日 [1]。'",
            "2. **多模态引导**: 如果检索结果包含视频、音频或链接，请主动告知用户可以点击下方的卡片进行观看、收听或访问。",
            "3. **诚实原则**: 如果检索结果中没有相关信息，请诚实回答不知道，不要编造任何校史或政策。",
            "4. **工具调用**: 涉及全局资料检索（文档、链接、视频、音频、图片、多媒体）统一调用 searchCampusDocuments；涉及成绩、课程信息、名单或学生学籍资料调用 ScoreService。",
            "5. **多轮对话**: 结合之前的对话历史，如果当前问题指代不明（如“它在哪里”），请利用上下文理解其含义。",
            "6. **严禁生成下载链接**: 绝对禁止在回答正文中生成以 `/api/asset/download/` 开头的原始下载链接、附件列表或 Markdown 格式的下载按钮。前端已统一提供‘相关资源’卡片供用户下载，你的回答中仅允许使用 [n] 格式组织引用标注。",
            "7. **表格展示规范**: 当查询结果包含多条同类数据（如多门课程成绩、多个学生名单等）时，必须使用 Markdown 表格进行展示。若工具结果中包含 `markdownTable` 字段，必须优先原样输出该字段，并在表格前后各留一个空行。每行数据必须独占一行，严禁将多行数据合并到一行中。表格示例：\n\n| 标题1 | 标题2 |\n|---|---|\n| 内容1 | 内容2 |\n\n",
            "8. **图形召唤协议 (Chart Summoning)**: 当工具返回的结果中包含 `chartConfig` 对象时，你**必须**在回答的最末尾，使用特定的 JSON 标签来召唤图形卡片。不要在正文中提及 JSON 内容，只需在最后一行输出标签。\n\n格式：`[CHART_DATA: <chartConfig内容>]` \n\n例如：如果工具返回了雷达图配置，你的回答末尾应为：\n[CHART_DATA: {\"type\":\"radar\",\"title\":\"xxx\",\"labels\":[\"A\",\"B\"],\"datasets\":[{\"label\":\"Score\",\"data\":[80,90]}]}]\n\n**召唤时机**：\n- 涉及能力分析、天赋挖掘时，优先召唤 **radar** (雷达图)。\n- 涉及占比、构成分析（如学分分布）时，优先召唤 **pie** (饼图)。\n- 涉及多项成绩对比、趋势分析时，优先召唤 **bar** (柱状图) 或 **line** (折线图)。"
        })
        String chat(@MemoryId String sessionId, @UserMessage String userMessage);
    }

    /**
     * 定义资产自动分类与实体提取接口 (Asset-to-Knowledge)
     */
    public interface AssetClassifier {
        @SystemMessage({
            "你是一个校园资产分类与知识提取专家。你的任务是根据给定的文件名、描述和提取出的文本内容，将该资产归类到预定义的校园分类中，并提取关键实体信息。",
            "当输入中出现“拍摄质量评分”时，必须区分“拍摄质量”与“OCR文本可读性”：若评分>=80，不得输出“图像质量差/拍摄质量差”，可描述为“拍摄质量较好但文本语义不可用”。",
            "### 预定义分类：",
            "- **校史 (History)**: 包含学校发展历程、老照片描述、校报、校史馆资料等。",
            "- **荣誉 (Honor)**: 包含各类获奖证书、奖杯照片描述、荣誉名单等。需要额外提取：获奖等级(校级/省级/国家级)、类别(学术/体育/艺术/社会实践)、获奖年份。",
            "- **学业 (Academic)**: 包含课程大纲、成绩单、学术报告、研究成果等。",
            "- **政策 (Policy)**: 包含学校规章制度、管理办法、官方通知等。",
            "- **招生 (Admission)**: 包含招生简章、录取政策、报考指南等。",
            "- **通用 (General)**: 其他无法归入上述分类的校园资料。",
            "### 输出格式：",
            "请直接返回 JSON 格式结果，不要包含任何 Markdown 标签或多余解释。格式示例：",
            "{\"category\": \"荣誉\", \"sourceType\": \"official\", \"isHonor\": true, \"honorLevel\": \"省级\", \"honorCategory\": \"学术\", \"honorYear\": \"2023\", \"extractedEntities\": [\"张三\", \"数学竞赛\"], \"summary\": \"2023年张三获得的省级数学竞赛一等奖证书\"}"
        })
        String classify(@UserMessage String content);
    }

    private Assistant assistant;
    private AssetClassifier assetClassifier;

    @PostConstruct
    public void init() {
        this.assistant = AiServices.builder(Assistant.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(memoryId -> getChatMemory((String) memoryId))
                .tools(scoreService, this) // 注入 ScoreService 和 MemoryService 里的 @Tool 方法
                .build();
        
        this.assetClassifier = AiServices.builder(AssetClassifier.class)
                .chatLanguageModel(chatLanguageModel)
                .build();

        initHonorDeleteStore();
    }

    /**
     * 调用 AI 对资产内容进行深度分类与知识提取
     */
    public Map<String, Object> classifyAsset(String fileName, String description, String extractedText) {
        return classifyAsset(fileName, description, extractedText, null);
    }

    public Map<String, Object> classifyAsset(String fileName, String description, String extractedText, Integer cameraQualityScore) {
        StringBuilder content = new StringBuilder();
        content.append("文件名: ").append(fileName).append("\n");
        if (description != null && !description.isBlank()) {
            content.append("描述: ").append(description).append("\n");
        }
        if (cameraQualityScore != null) {
            content.append("拍摄质量评分: ").append(cameraQualityScore).append("/100\n");
        }
        if (extractedText != null && !extractedText.isBlank()) {
            content.append("提取文本: ").append(extractedText);
        }

        try {
            String jsonResult = assetClassifier.classify(content.toString());
            // 简单清理可能存在的 markdown 标签 (兜底)
            jsonResult = jsonResult.replaceAll("```json", "").replaceAll("```", "").trim();
            log.info("AI 资产分类结果: {}", jsonResult);
            
            // 使用 Jackson 解析 JSON (MemoryService 已经引入了 Jackson)
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(jsonResult, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("AI 资产分类失败，回退到默认逻辑", e);
            return null;
        }
    }

    /**
     * 语音/多模态问答接口
     */
    public String chat(String sessionId, String message) {
        String effectiveSessionId = (sessionId == null || sessionId.isBlank()) ? "default-session" : sessionId;
        return assistant.chat(effectiveSessionId, message);
    }

    // 用于存储会话记忆，实际生产建议使用持久化存储（如 Redis）
    private final Map<String, ChatMemory> memoryCache = new ConcurrentHashMap<>();
    private final Set<String> deletedHonorObjectNames = ConcurrentHashMap.newKeySet();

    private ChatMemory getChatMemory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return MessageWindowChatMemory.withMaxMessages(30);
        }
        return memoryCache.computeIfAbsent(sessionId, id -> MessageWindowChatMemory.withMaxMessages(30));
    }

    private static final Pattern YEAR_PATTERN = Pattern.compile("(1[89]\\d{2}|20\\d{2})[\\s\\S]{0,2}年");
    private static final Pattern STUDENT_ID_PATTERN = Pattern.compile("\\b\\d{8,16}\\b");
    private enum MediaIntent {
        NONE, VIDEO, AUDIO, IMAGE, MULTIMEDIA
    }
    private record RetrievalWeightProfile(
            double semanticWeight,
            double keywordWeight,
            double sourceWeight,
            double multimediaWeight,
            double metadataWeight,
            double exactBoostCap
    ) {}

    /**
     * 存入校史记忆片段，带元数据 (支持自动分段)
     */
    public String addMemory(String text, Map<String, Object> metadataMap) {
        if (text == null || text.trim().isEmpty()) return "文本内容为空，跳过存入";
        
        log.info("正在存入带元数据的记忆片段 (分段处理): {}", metadataMap);
        
        // --- 处理元数据类型兼容性 (LangChain4j Metadata 不支持 ArrayList) ---
        Map<String, Object> cleanedMetadata = new java.util.HashMap<>();
        if (metadataMap != null) {
            metadataMap.forEach((key, value) -> {
                if (value instanceof List) {
                    List<?> list = (List<?>) value;
                    if (list.isEmpty()) {
                        cleanedMetadata.put(key, ""); // 空列表转为空字符串
                    } else {
                        cleanedMetadata.put(key, String.join(", ", list.stream()
                            .filter(Objects::nonNull)
                            .map(Object::toString)
                            .toList()));
                    }
                } else if (value != null) {
                    cleanedMetadata.put(key, value);
                }
            });
        }
        
        Metadata metadata = Metadata.from(cleanedMetadata);
        
        // --- 自动提取年份信息 ---
        Set<String> years = extractYears(text);
        if (!years.isEmpty()) {
            metadata.add("years", String.join(",", years));
        }

        // 使用更细的分块参数 (300字符/30重叠)，基于句子进行切分，提升检索精度
        Document document = Document.from(text, metadata);
        DocumentBySentenceSplitter splitter = new DocumentBySentenceSplitter(300, 30);
        List<TextSegment> segments = splitter.split(document);
        
        log.info("文档 [{}] 已切分为 {} 个片段", metadataMap.getOrDefault("fileName", "unknown"), segments.size());
        
        List<Embedding> embeddings = segments.stream()
                .map(segment -> embeddingModel.embed(segment).content())
                .collect(Collectors.toList());
        
        embeddingStore.addAll(embeddings, segments);
        
        return "记忆片段存入成功，共处理 " + segments.size() + " 个分段！";
    }

    /**
     * 存入荣誉记忆片段
     * @param text 荣誉描述/提取出的文本
     * @param metadataMap 荣誉元数据 (isHonor, honorLevel, honorCategory, timestamp, etc.)
     */
    public String addHonor(String text, Map<String, Object> metadataMap) {
        if (text == null || text.trim().isEmpty()) return "文本内容为空，跳过存入";
        if (metadataMap == null) metadataMap = new java.util.HashMap<>();
        
        // --- 荣誉资产元数据验证逻辑 ---
        List<String> missingFields = new ArrayList<>();
        if (metadataMap.get(METADATA_HONOR_LEVEL) == null) missingFields.add(METADATA_HONOR_LEVEL);
        if (metadataMap.get(METADATA_HONOR_CATEGORY) == null) missingFields.add(METADATA_HONOR_CATEGORY);
        if (metadataMap.get(METADATA_TIMESTAMP) == null) missingFields.add(METADATA_TIMESTAMP);
        
        if (!missingFields.isEmpty()) {
            return "荣誉资产缺少必填元数据字段: " + String.join(", ", missingFields);
        }

        // 验证 honorCategory 枚举值
        List<String> validCategories = Arrays.asList("学术", "体育", "艺术", "社会实践");
        String category = String.valueOf(metadataMap.get(METADATA_HONOR_CATEGORY));
        if (!validCategories.contains(category)) {
            return "honorCategory 值无效，可选值: " + String.join(", ", validCategories);
        }

        // 验证 timestamp 日期格式 (ISO_LOCAL_DATE_TIME)
        try {
            java.time.LocalDateTime.parse(String.valueOf(metadataMap.get(METADATA_TIMESTAMP)), 
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (java.time.format.DateTimeParseException e) {
            return "timestamp 格式无效，需遵循 ISO_LOCAL_DATE_TIME 格式（例如：2024-05-20T14:30:00）";
        }

        metadataMap.put(METADATA_IS_HONOR, "true");
        // 自动提取年份作为荣誉年份，如果 metadataMap 中没有
        if (!metadataMap.containsKey(METADATA_HONOR_YEAR)) {
            Set<String> years = extractYears(text);
            if (!years.isEmpty()) {
                metadataMap.put(METADATA_HONOR_YEAR, years.iterator().next());
            } else {
                // 如果没有提取到年份，从 timestamp 中提取
                String ts = String.valueOf(metadataMap.get(METADATA_TIMESTAMP));
                metadataMap.put(METADATA_HONOR_YEAR, ts.substring(0, 4));
            }
        }
        return addMemory(text, metadataMap);
    }

    /**
     * 获取荣誉生长树数据 (按年份和类别聚合)
     * 这里通过查询所有标记为荣誉的片段并进行内存聚合
     */
    public List<Map<String, Object>> getHonorTreeData() {
        log.info("开始生成校园荣誉生长树数据...");
        List<TextSegment> honorSegments = collectHonorSegments(meta -> true);
        return aggregateHonorTreeData(honorSegments);
    }

    public List<Map<String, Object>> getPersonalHonorTreeData(String userId) {
        String safeUserId = userId == null ? "" : userId.trim();
        if (safeUserId.isBlank()) {
            return Collections.emptyList();
        }
        List<TextSegment> honorSegments = collectHonorSegments(meta -> {
            String ownerId = meta.getString("userId");
            String role = meta.getString("role");
            if (ownerId == null || !safeUserId.equals(ownerId.trim())) {
                return false;
            }
            if (role == null || role.isBlank()) {
                return true;
            }
            return "private".equalsIgnoreCase(role.trim());
        });
        return aggregateHonorTreeData(honorSegments);
    }

    private List<TextSegment> collectHonorSegments(Predicate<Metadata> extraFilter) {
        Embedding queryEmbedding = embeddingModel.embed("校园荣誉 奖项 证书 比赛").content();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(queryEmbedding, 260);
        return matches.stream()
                .map(EmbeddingMatch::embedded)
                .filter(Objects::nonNull)
                .filter(segment -> segment.metadata() != null)
                .filter(segment -> segment.metadata().getString(METADATA_IS_HONOR) != null)
                .filter(segment -> !isHonorDeleted(segment.metadata().getString("objectName")))
                .filter(segment -> extraFilter == null || extraFilter.test(segment.metadata()))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> aggregateHonorTreeData(List<TextSegment> honorSegments) {
        Map<String, Map<String, LinkedHashMap<String, Map<String, Object>>>> aggregated = new TreeMap<>(Collections.reverseOrder());

        for (TextSegment segment : honorSegments) {
            Metadata meta = segment.metadata();
            String year = meta.getString(METADATA_HONOR_YEAR);
            if (year == null) year = "未知";
            
            String category = meta.getString(METADATA_HONOR_CATEGORY);
            if (category == null) category = "通用";

            String itemText = sanitizeHonorNodeText(segment.text(), meta.getString("description"), meta.getString("fileName"));
            if (itemText == null || itemText.isBlank()) {
                continue;
            }
            String nodeName = itemText.length() > 26 ? itemText.substring(0, 26) + "..." : itemText;
            String objectName = meta.getString("objectName");
            String fileName = meta.getString("fileName");
            String timestamp = meta.getString(METADATA_TIMESTAMP);
            String dedupKey = buildHonorDedupKey(objectName, fileName, timestamp, year, category);
            Map<String, Object> item = new HashMap<>();
            item.put("name", nodeName);
            item.put("text", itemText);
            item.put("category", category);
            item.put("level", meta.getString(METADATA_HONOR_LEVEL));
            item.put("fileName", fileName);
            item.put("objectName", objectName);
            item.put("timestamp", timestamp);

            LinkedHashMap<String, Map<String, Object>> categoryItems = aggregated
                    .computeIfAbsent(year, k -> new HashMap<>())
                    .computeIfAbsent(category, k -> new LinkedHashMap<>());
            Map<String, Object> existing = categoryItems.get(dedupKey);
            if (existing == null) {
                categoryItems.put(dedupKey, item);
            } else {
                String existingText = String.valueOf(existing.getOrDefault("text", ""));
                if (scoreHonorDisplayText(itemText) > scoreHonorDisplayText(existingText)) {
                    categoryItems.put(dedupKey, item);
                }
            }
        }

        // 转化为前端树状组件需要的格式
        List<Map<String, Object>> treeData = new ArrayList<>();
        for (Map.Entry<String, Map<String, LinkedHashMap<String, Map<String, Object>>>> yearEntry : aggregated.entrySet()) {
            Map<String, Object> yearNode = new HashMap<>();
            yearNode.put("name", yearEntry.getKey() + "年");
            yearNode.put("type", "year");
            
            List<Map<String, Object>> categoryNodes = new ArrayList<>();
            for (Map.Entry<String, LinkedHashMap<String, Map<String, Object>>> catEntry : yearEntry.getValue().entrySet()) {
                Map<String, Object> catNode = new HashMap<>();
                catNode.put("name", catEntry.getKey());
                catNode.put("type", "category");
                catNode.put("children", new ArrayList<>(catEntry.getValue().values())); // 叶子节点即为具体荣誉项
                categoryNodes.add(catNode);
            }
            yearNode.put("children", categoryNodes);
            treeData.add(yearNode);
        }
        return treeData;
    }

    private String sanitizeHonorNodeText(String rawText, String description, String fileName) {
        String normalizedRaw = normalizeHonorDisplayText(rawText);
        String cleanDescription = normalizeHonorDisplayText(description);
        String normalizedFileName = normalizeHonorFileName(fileName);

        if (!cleanDescription.isBlank() && !isGarbledHonorText(cleanDescription)) {
            return cleanDescription;
        }
        if (!normalizedFileName.isBlank()) {
            return normalizedFileName;
        }
        if (!normalizedRaw.isBlank() && !isGarbledHonorText(normalizedRaw)) {
            return normalizedRaw;
        }

        return "";
    }

    private String normalizeHonorDisplayText(String text) {
        if (text == null) return "";
        String normalized = text
                .replace("\u0000", "")
                .replaceAll("[\\t\\r\\f]+", " ")
                .replaceAll("\\n+", " ")
                .replaceAll(" +", " ")
                .replace("�", "")
                .trim();
        if (normalized.startsWith("荣誉文件:")) {
            String possibleFile = normalized.substring("荣誉文件:".length()).trim();
            String fromFile = normalizeHonorFileName(possibleFile);
            if (!fromFile.isBlank()) return fromFile;
        }
        return normalized;
    }

    private String normalizeHonorFileName(String fileName) {
        if (fileName == null) return "";
        String normalized = fileName
                .replace("\\", "/")
                .trim();
        int slashIndex = normalized.lastIndexOf('/');
        if (slashIndex >= 0 && slashIndex + 1 < normalized.length()) {
            normalized = normalized.substring(slashIndex + 1);
        }
        normalized = normalized.replaceAll("\\.[^.]+$", "").trim();
        if (normalized.isBlank()) return "";
        if (isGarbledHonorText(normalized)) return "";
        return normalized;
    }

    private String buildHonorDedupKey(String objectName, String fileName, String timestamp, String year, String category) {
        String normalizedObjectName = normalizeObjectName(objectName);
        if (normalizedObjectName != null) {
            return "object:" + normalizedObjectName;
        }
        String safeFileName = fileName == null ? "" : fileName.trim();
        String safeTimestamp = timestamp == null ? "" : timestamp.trim();
        return "meta:" + year + "|" + category + "|" + safeFileName + "|" + safeTimestamp;
    }

    private int scoreHonorDisplayText(String text) {
        if (text == null) return Integer.MIN_VALUE;
        String normalized = text.trim();
        if (normalized.isEmpty()) return Integer.MIN_VALUE / 2;
        int score = Math.min(normalized.length(), 240);
        if (normalized.startsWith("荣誉文件:")) {
            score -= 180;
        } else {
            score += 120;
        }
        if (isGarbledHonorText(normalized)) {
            score -= 220;
        }
        return score;
    }

    private boolean isGarbledHonorText(String text) {
        if (text == null || text.isBlank()) return true;
        if (text.indexOf('\uFFFD') >= 0) return true;
        if (text.chars().anyMatch(c -> c >= 0x2500 && c <= 0x257F)) return true;
        String compact = text.replaceAll("\\s+", "");
        if (compact.length() < 4) return true;

        int letterDigitOrCjk = 0;
        int cjk = 0;
        int noise = 0;
        int total = 0;
        for (char c : compact.toCharArray()) {
            total++;
            if (Character.isLetterOrDigit(c)) {
                letterDigitOrCjk++;
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                    cjk++;
                }
            } else if ("，。！？；：、“”‘’（）()《》【】[]-—·.,!?;:'\"@#%&*+=_".indexOf(c) < 0) {
                noise++;
            }
        }

        double informativeRatio = (double) letterDigitOrCjk / Math.max(total, 1);
        double noiseRatio = (double) noise / Math.max(total, 1);
        boolean tooShort = letterDigitOrCjk < 6 && cjk < 3;
        String tokenBase = text.replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ").trim();
        String[] tokens = tokenBase.isBlank() ? new String[0] : tokenBase.split("\\s+");
        int latinTokenCount = 0;
        int shortLatinTokenCount = 0;
        for (String token : tokens) {
            if (token.matches("[A-Za-z]+")) {
                latinTokenCount++;
                if (token.length() <= 3) {
                    shortLatinTokenCount++;
                }
            }
        }
        double shortLatinTokenRatio = (double) shortLatinTokenCount / Math.max(latinTokenCount, 1);
        boolean fragmentedLatinNoise = cjk <= 6 && latinTokenCount >= 3 && shortLatinTokenRatio > 0.65;
        boolean mixedCorruptionNoise = cjk > 0 && cjk <= 12 && latinTokenCount >= 4 && shortLatinTokenRatio > 0.55;

        return tooShort || informativeRatio < 0.3 || noiseRatio > 0.3 || fragmentedLatinNoise || mixedCorruptionNoise;
    }

    /**
     * 存入简单记忆片段 (兜底增加默认元数据)
     */
    public String addMemory(String text) {
        java.util.Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("fileName", "手动录入片段");
        metadata.put("source", "manual_entry");
        return addMemory(text, metadata);
    }

    /**
     * 专门针对校园荣誉墙进行 RAG 检索
     * 结合了语义检索、时间权重 (Temporal Weighting) 和分类增强
     */
    @Tool("检索校园荣誉墙相关信息，包括奖项详情、获得时间、级别和相关感言")
    public String searchHonorWall(String query) {
        log.info("Tool: 正在执行校园荣誉墙深度检索: {}", query);
        OrchestrationContext.recordToolInvoke("HONOR");
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        String currentUserId = OrchestrationContext.getSessionId();
        String currentRole = OrchestrationContext.getUserRole();
        boolean selfScopedQuery = isSelfReferentialQuery(query) && currentUserId != null && !currentUserId.isBlank();
        Set<String> queryStudentIds = new LinkedHashSet<>(extractStudentIds(query));
        if (queryStudentIds.isEmpty() && selfScopedQuery) {
            queryStudentIds.add(currentUserId.trim());
        }
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(queryEmbedding, 50);
        int currentYear = java.time.Year.now().getValue();
        Set<String> queryYears = extractYears(query);

        List<Map<String, Object>> scoredResults = matches.stream()
            .filter(match -> match != null && match.embedded() != null && match.embedded().metadata() != null)
            .map(match -> {
                TextSegment segment = match.embedded();
                Metadata meta = segment.metadata();
                if (!"true".equalsIgnoreCase(meta.getString(METADATA_IS_HONOR))) {
                    return null;
                }
                String objectName = meta.getString("objectName");
                if (isHonorDeleted(objectName)) {
                    return null;
                }
                String ownerId = meta.getString("userId");
                if (!queryStudentIds.isEmpty()) {
                    if (ownerId == null || !queryStudentIds.contains(ownerId.trim())) {
                        return null;
                    }
                }
                String displayText = sanitizeHonorNodeText(segment.text(), meta.getString("description"), meta.getString("fileName"));
                if (displayText == null || displayText.isBlank()) {
                    return null;
                }
                double score = match.score() + 0.3;
                String yearStr = meta.getString(METADATA_HONOR_YEAR);
                if (yearStr != null) {
                    try {
                        int honorYear = Integer.parseInt(yearStr);
                        if (!queryYears.isEmpty()) {
                            if (queryYears.contains(yearStr)) {
                                score += 0.4;
                            }
                        } else {
                            double timeWeight = 1.0 - (double)(currentYear - honorYear) / 20.0;
                            score += Math.max(0, timeWeight * 0.15);
                        }
                    } catch (NumberFormatException ignored) {}
                }
                Map<String, Object> result = new HashMap<>();
                result.put("text", displayText);
                result.put("score", score);
                result.put("metadata", meta.asMap());
                return result;
            })
            .filter(Objects::nonNull)
            .sorted((a, b) -> Double.compare((double)b.get("score"), (double)a.get("score")))
            .limit(10)
            .collect(Collectors.toList());

        if (scoredResults.isEmpty()) {
            if (!queryStudentIds.isEmpty()) {
                return "未检索到该学号可确认的荣誉记录。";
            }
            return "未检索到可确认的荣誉记录。";
        }

        StringBuilder sb = new StringBuilder("检索到以下相关荣誉信息：\n");
        for (int i = 0; i < scoredResults.size(); i++) {
            Map<String, Object> res = scoredResults.get(i);
            Map<String, Object> meta = (Map<String, Object>) res.get("metadata");
            sb.append("[").append(i + 1).append("] ")
              .append(meta.getOrDefault(METADATA_HONOR_YEAR, "未知")).append("年 ")
              .append(meta.getOrDefault(METADATA_HONOR_LEVEL, "")).append(" ")
              .append(meta.getOrDefault(METADATA_HONOR_CATEGORY, "")).append(": ")
              .append(res.get("text")).append("\n");
        }

        List<RelevantFile> files = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (Map<String, Object> res : scoredResults) {
            Map<String, Object> meta = (Map<String, Object>) res.get("metadata");
            String text = String.valueOf(res.getOrDefault("text", ""));
            if (text != null && !text.isBlank()) {
                texts.add(text);
            }
            String objectName = meta == null ? null : Objects.toString(meta.get("objectName"), null);
            String fileName = meta == null ? null : Objects.toString(meta.get("fileName"), null);
            String ownerId = meta == null ? null : Objects.toString(meta.get("userId"), null);
            String sourceType = meta == null ? null : Objects.toString(meta.get("sourceType"), null);
            if (sourceType == null || sourceType.isBlank()) {
                sourceType = (ownerId != null && !ownerId.isBlank()) ? "private" : "official";
            }
            boolean isPrivate = ownerId != null && currentUserId != null && ownerId.trim().equals(currentUserId.trim());
            String url = "";
            if (objectName != null && !objectName.isBlank()) {
                String encodedObjectName = objectName;
                String encodedUserId = "";
                String encodedRole = "";
                try { encodedObjectName = java.net.URLEncoder.encode(objectName, StandardCharsets.UTF_8).replace("+", "%20"); } catch (Exception ignored) {}
                try {
                    encodedUserId = currentUserId == null ? "" : java.net.URLEncoder.encode(currentUserId, StandardCharsets.UTF_8).replace("+", "%20");
                    encodedRole = currentRole == null ? "" : java.net.URLEncoder.encode(currentRole, StandardCharsets.UTF_8).replace("+", "%20");
                } catch (Exception ignored) {}
                url = "/api/asset/view?objectName=" + encodedObjectName + "&userId=" + encodedUserId + "&role=" + encodedRole;
            }
            files.add(RelevantFile.builder()
                    .fileName((fileName == null || fileName.isBlank()) ? objectName : fileName)
                    .objectName(objectName)
                    .url(url)
                    .sourceType(sourceType)
                    .isPrivate(isPrivate)
                    .build());
        }
        OrchestrationContext.getToolFiles().addAll(files);
        OrchestrationContext.getToolMemories().addAll(texts);
        
        return sb.toString();
    }

    private Set<String> extractStudentIds(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = STUDENT_ID_PATTERN.matcher(query);
        while (matcher.find()) {
            ids.add(matcher.group());
        }
        return ids;
    }

    public String buildHonorNarrative(String honorText, String level, String category) {
        String safeText = honorText == null ? "" : honorText.trim();
        String safeLevel = level == null ? "未知级别" : level.trim();
        String safeCategory = category == null ? "通用" : category.trim();
        String retrievalQuery = "校园荣誉 叙事 " + safeText + " " + safeLevel + " " + safeCategory;
        String honorFacts = searchHonorWall(retrievalQuery);
        String prompt = "请基于以下荣誉事实，生成一段庄重、真实、鼓舞后辈的校园荣誉叙事，控制在180字以内，不要虚构信息。\n\n荣誉标题：" +
            safeText + "\n荣誉级别：" + safeLevel + "\n荣誉分类：" + safeCategory + "\n\n参考事实：\n" + honorFacts;
        String sessionId = "honor-wall-" + java.util.UUID.randomUUID();
        try {
            return assistant.chat(sessionId, prompt);
        } catch (Exception e) {
            log.error("生成荣誉叙事失败，回退为检索结果", e);
            return honorFacts;
        }
    }

    /**
     * 工具函数：执行语义文档检索 (RAG)
     * @param query 检索关键词
     * @return 检索到的文本片段
     */
    @Tool("全局搜索校史馆资料、规章政策、招生信息、链接以及视频音频图片等多媒体内容")
    public String searchCampusDocuments(String query) {
        log.info("Tool: 正在执行全局资料检索: {}", query);
        OrchestrationContext.recordToolInvoke("DOCUMENT");
        
        String userRole = OrchestrationContext.getUserRole();
        String currentUserId = OrchestrationContext.getSessionId();
        boolean selfScopedQuery = isSelfReferentialQuery(query) && currentUserId != null && !currentUserId.isBlank();
        TraceInfo.TraceInfoBuilder traceBuilder = OrchestrationContext.getTraceBuilder();

        boolean documentQuery = isDocumentQuery(query);
        boolean multimediaQuery = isMultimediaQuery(query);
        MediaIntent mediaIntent = detectMediaIntent(query);
        String effectiveQuery = buildRetrievalQueryForIntent(query, mediaIntent, multimediaQuery);
        int retrievalTopK = resolveRetrievalTopK(mediaIntent, multimediaQuery);

        // 1. 执行向量检索
        Embedding queryEmbedding = embeddingModel.embed(effectiveQuery).content();
        List<EmbeddingMatch<TextSegment>> relevant = embeddingStore.findRelevant(queryEmbedding, retrievalTopK);
        if (traceBuilder != null) traceBuilder.rawMatchCount(relevant.size());

        // 2. 动态阈值与打分
        double dynamicThreshold = calculateDynamicThreshold(query, documentQuery, mediaIntent);
        if (traceBuilder != null) traceBuilder.threshold(dynamicThreshold);

        List<String> tokens = tokenizeForKeywordMatch(query);
        Map<String, Double> scoreSnapshot = new HashMap<>();
        RetrievalWeightProfile weightProfile = resolveWeightProfile(documentQuery, multimediaQuery, mediaIntent, query);

        record ScoredMatch(EmbeddingMatch<TextSegment> match, double score) {}

        List<EmbeddingMatch<TextSegment>> authorized = relevant.stream()
            .filter(match -> {
                if (match == null || match.embedded() == null) return false;
                Metadata metadata = match.embedded().metadata();
                if (metadata == null) return true;
                String requiredRole = metadata.getString("role");
                String ownerId = metadata.getString("userId");
                // 权限过滤逻辑：
                // 1. 角色为 "all" 的文档所有人可见
                // 2. 管理员可见所有文档
                // 3. 非私有资源 (sourceType 不为 private) 所有人可见
                // 4. 私有资源仅所有者可见
                String sourceType = metadata.getString("sourceType");
                String objectName = metadata.getString("objectName");
                if (metadata.getString(METADATA_IS_HONOR) != null && isHonorDeleted(objectName)) {
                    return false;
                }
                if ("private".equalsIgnoreCase(requiredRole)) {
                    if ("admin".equalsIgnoreCase(userRole)) return false;
                    return ownerId != null && ownerId.equals(currentUserId);
                }
                if ("all".equalsIgnoreCase(requiredRole)) return true;
                if ("private".equalsIgnoreCase(sourceType)) {
                    if ("admin".equalsIgnoreCase(userRole)) return false;
                    return ownerId != null && ownerId.equals(currentUserId);
                }
                if (sourceType != null && !"private".equalsIgnoreCase(sourceType)) return true;
                if (sourceType == null && ownerId == null) return true; // 兜底：无类型且无所有者视为公共

                if (userRole != null && userRole.equalsIgnoreCase(requiredRole)) return true;
                if (ownerId != null && ownerId.equals(currentUserId)) return true;
                return false;
            })
            .collect(Collectors.toList());

        List<ScoredMatch> scored = authorized.stream()
            .map(match -> {
                String text = match.embedded().text();
                Metadata metadata = match.embedded().metadata();
                String fileName = (metadata != null) ? metadata.getString("fileName") : null;
                String objectName = (metadata != null) ? metadata.getString("objectName") : null;
                String ownerId = (metadata != null) ? metadata.getString("userId") : null;
                String sourceType = (metadata != null && metadata.getString("sourceType") != null) ? metadata.getString("sourceType") : "official";
                String mediaType = (metadata != null) ? metadata.getString("mediaType") : null;
                String sourceName = (fileName == null || fileName.isBlank()) ? objectName : fileName;
                boolean isPrivate = ownerId != null && ownerId.equals(currentUserId);
                
                String effectiveSourceTypeForWeight = isPrivate ? "private" : sourceType;
                double sourceWeight = (multimediaQuery || mediaIntent != MediaIntent.NONE)
                    ? calculateMultimediaAwareSourceWeight(effectiveSourceTypeForWeight)
                    : calculateSourceWeight(effectiveSourceTypeForWeight);

                double semanticScore = match.score();
                String category = (metadata != null) ? metadata.getString("category") : null;
                String description = (metadata != null) ? metadata.getString("description") : null;
                double keywordScore = keywordMatchScore(text, sourceName, category, description, tokens, query);
                
                double multimediaBoost = 0.0;
                boolean mediaMatched = matchesMediaIntent(sourceName, text, sourceType, mediaType, mediaIntent);
                if (mediaIntent != MediaIntent.NONE) {
                    multimediaBoost = mediaMatched ? 0.55 : -0.25;
                } else if (multimediaQuery && "multimedia".equalsIgnoreCase(sourceType)) {
                    multimediaBoost = 0.35;
                } else if (multimediaQuery) {
                    multimediaBoost = -0.08;
                }
                
                double metadataSignal = metadataMatchScore(sourceName, category, description, tokens, query);
                double exactBoost = exactMetadataBoost(sourceName, description, query, weightProfile.exactBoostCap());
                
                double totalScore = (semanticScore * weightProfile.semanticWeight())
                        + (keywordScore * weightProfile.keywordWeight())
                        + (sourceWeight * weightProfile.sourceWeight())
                        + (multimediaBoost * weightProfile.multimediaWeight())
                        + (metadataSignal * weightProfile.metadataWeight())
                        + exactBoost;
                if (sourceName != null) scoreSnapshot.merge(sourceName, totalScore, Math::max);
                return new ScoredMatch(match, totalScore);
            })
            .sorted((a, b) -> Double.compare(b.score(), a.score()))
            .collect(Collectors.toList());

        List<ScoredMatch> rankedCandidates = scored.stream()
            .filter(item -> item.score() > dynamicThreshold)
            .filter(item -> {
                TextSegment segment = item.match().embedded();
                Metadata metadata = segment.metadata();
                String sourceName = metadata == null ? null : metadata.getString("fileName");
                if (sourceName == null || sourceName.isBlank()) {
                    sourceName = metadata == null ? null : metadata.getString("objectName");
                }
                String sourceType = metadata == null ? null : metadata.getString("sourceType");
                String mediaType = metadata == null ? null : metadata.getString("mediaType");
                String category = metadata == null ? null : metadata.getString("category");
                String description = metadata == null ? null : metadata.getString("description");
                if (!hasEffectiveRecallInformation(segment.text(), description, sourceName, sourceType, mediaType)) {
                    return false;
                }
                return passesRelevanceGate(
                    segment.text(),
                    sourceName,
                    sourceType,
                    mediaType,
                    category,
                    description,
                    tokens,
                    query,
                    item.match().score(),
                    documentQuery,
                    multimediaQuery,
                    mediaIntent
                );
            })
            .collect(Collectors.toList());

        List<ScoredMatch> ranked = rankedCandidates;
        if (mediaIntent != MediaIntent.NONE) {
            List<ScoredMatch> intentMatches = rankedCandidates.stream()
                .filter(item -> {
                    TextSegment segment = item.match().embedded();
                    Metadata metadata = segment.metadata();
                    String sourceName = metadata == null ? null : metadata.getString("fileName");
                    if (sourceName == null || sourceName.isBlank()) {
                        sourceName = metadata == null ? null : metadata.getString("objectName");
                    }
                    String sourceType = metadata == null ? null : metadata.getString("sourceType");
                    String mediaType = metadata == null ? null : metadata.getString("mediaType");
                    return matchesMediaIntent(sourceName, segment.text(), sourceType, mediaType, mediaIntent);
                })
                .collect(Collectors.toList());
            if (!intentMatches.isEmpty()) {
                ranked = intentMatches;
            }
            if (ranked.isEmpty()) {
                ranked = scored.stream()
                    .filter(item -> {
                        TextSegment segment = item.match().embedded();
                        Metadata metadata = segment.metadata();
                        String sourceName = metadata == null ? null : metadata.getString("fileName");
                        if (sourceName == null || sourceName.isBlank()) {
                            sourceName = metadata == null ? null : metadata.getString("objectName");
                        }
                        String sourceType = metadata == null ? null : metadata.getString("sourceType");
                        String mediaType = metadata == null ? null : metadata.getString("mediaType");
                        return matchesMediaIntent(sourceName, segment.text(), sourceType, mediaType, mediaIntent);
                    })
                    .limit(12)
                    .collect(Collectors.toList());
            }
        }
        if (selfScopedQuery) {
            Predicate<ScoredMatch> minePredicate = item -> {
                Metadata metadata = item.match().embedded().metadata();
                String ownerId = metadata == null ? null : metadata.getString("userId");
                return ownerId != null && ownerId.equals(currentUserId);
            };
            Predicate<ScoredMatch> publicPredicate = item -> {
                Metadata metadata = item.match().embedded().metadata();
                String sourceType = metadata == null ? null : metadata.getString("sourceType");
                return sourceType == null || !"private".equalsIgnoreCase(sourceType);
            };
            LinkedHashSet<ScoredMatch> merged = new LinkedHashSet<>();
            ranked.stream().filter(minePredicate).findFirst()
                    .or(() -> scored.stream().filter(minePredicate).findFirst())
                    .ifPresent(merged::add);
            ranked.stream().filter(publicPredicate).findFirst()
                    .or(() -> scored.stream().filter(publicPredicate).findFirst())
                    .ifPresent(merged::add);
            merged.addAll(ranked);
            ranked = new ArrayList<>(merged);
        }
        ranked = ranked.stream().limit(10).collect(Collectors.toList());

        if (traceBuilder != null) {
            traceBuilder.scoreSnapshot(scoreSnapshot);
            traceBuilder.filteredMatchCount(ranked.size());
            traceBuilder.finalTopK(ranked.size());
        }

        // 3. 收集相关文件和记忆 (执行文件级去重，确保每个文件只对应一个最高分片段)
        List<RelevantFile> files = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<String> textSources = new ArrayList<>();
        Set<String> seenInTool = new HashSet<>();
        
        for (ScoredMatch item : ranked) {
            Metadata metadata = item.match().embedded().metadata();
            String objectName = metadata.getString("objectName");
            String fileName = metadata.getString("fileName");
            String sourceType = (metadata.getString("sourceType") != null) ? metadata.getString("sourceType") : "official";
            String ownerId = metadata.getString("userId");
            boolean isPrivate = ownerId != null && ownerId.equals(currentUserId);
            
            // 使用“显示名称 + 类型 + 权限”作为唯一键，彻底解决视觉上的重复文件问题
            String displayFileName = fileName != null ? fileName : extractOriginalFileName(objectName);
            String displayKey = displayFileName + "|" + sourceType + "|" + isPrivate;
            String segmentText = item.match().embedded().text();
            if (segmentText != null && !segmentText.isBlank()) {
                texts.add(segmentText);
                textSources.add(displayFileName == null || displayFileName.isBlank() ? "unknown" : displayFileName);
            }
            
            if (seenInTool.add(displayKey)) {
                String encodedObjectName = objectName;
                try { encodedObjectName = java.net.URLEncoder.encode(objectName, StandardCharsets.UTF_8).replace("+", "%20"); } catch (Exception e) {}
                String encodedUserId = "";
                String encodedRole = "";
                try {
                    encodedUserId = currentUserId == null ? "" : java.net.URLEncoder.encode(currentUserId, StandardCharsets.UTF_8).replace("+", "%20");
                    encodedRole = userRole == null ? "" : java.net.URLEncoder.encode(userRole, StandardCharsets.UTF_8).replace("+", "%20");
                } catch (Exception e) {}

                String url = (objectName != null && objectName.startsWith("link://")) 
                    ? metadata.getString("url")
                    : "/api/asset/view?objectName=" + encodedObjectName + "&userId=" + encodedUserId + "&role=" + encodedRole;

                files.add(RelevantFile.builder()
                    .fileName(displayFileName)
                    .objectName(objectName)
                    .url(url)
                    .sourceType(sourceType)
                    .isPrivate(isPrivate)
                    .build());
            }
        }
        
        OrchestrationContext.getToolFiles().addAll(files);
        OrchestrationContext.getToolMemories().addAll(texts);

        return buildDocumentToolPayload(query, texts, textSources, files);
    }

    public void markHonorDeleted(String objectName) {
        String normalizedObjectName = normalizeObjectName(objectName);
        if (normalizedObjectName == null) {
            return;
        }
        deletedHonorObjectNames.add(normalizedObjectName);
        try {
            jdbcTemplate.update(
                    "INSERT IGNORE INTO " + HONOR_DELETE_TABLE + " (object_name) VALUES (?)",
                    normalizedObjectName
            );
        } catch (Exception e) {
            log.error("写入荣誉删除标记失败: {}", normalizedObjectName, e);
        }
    }

    public boolean isHonorDeleted(String objectName) {
        String normalizedObjectName = normalizeObjectName(objectName);
        if (normalizedObjectName == null) return false;
        return deletedHonorObjectNames.contains(normalizedObjectName);
    }

    private void initHonorDeleteStore() {
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS " + HONOR_DELETE_TABLE + " (" +
                            "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                            "object_name VARCHAR(512) NOT NULL UNIQUE, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );
            List<String> objectNames = jdbcTemplate.query(
                    "SELECT object_name FROM " + HONOR_DELETE_TABLE,
                    (rs, rowNum) -> rs.getString(1)
            );
            deletedHonorObjectNames.addAll(objectNames);
        } catch (Exception e) {
            log.error("初始化荣誉删除标记存储失败", e);
        }
    }

    private String normalizeObjectName(String objectName) {
        if (objectName == null) return null;
        String normalized = objectName.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 增强版搜索：支持多轮对话上下文
     */
    public SearchResponse searchWithAi(String query, int maxResults, String userRole, String sessionId) {
        long startTime = System.currentTimeMillis();
        log.info("正在执行智能编排搜索 (角色: {}, 会话: {}): {}", userRole, sessionId, query);

        String cleanQuery = (query == null) ? "" : query.trim();
        if (cleanQuery.isEmpty()) {
            return SearchResponse.builder().answer("请输入检索关键词。").build();
        }

        TraceInfo.TraceInfoBuilder traceBuilder = TraceInfo.builder()
            .originalQuery(cleanQuery)
            .executionTimeMs(0L);

        PlanningLayer.PlanningDecision plan = planningLayer.plan(cleanQuery, sessionId);
        traceBuilder.condensedQuery(plan.condensedQuery());
        traceBuilder.routeConfidence(plan.confidence());
        String normalizedRole = normalizeRole(userRole);
        boolean guestRole = isGuestRole(normalizedRole);
        boolean selfScopedQuery = !guestRole && sessionId != null && !sessionId.isBlank() && isSelfReferentialQuery(cleanQuery);
        boolean studentSensitiveQuery = isStudentSensitiveQuery(cleanQuery);
        if (guestRole && studentSensitiveQuery) {
            traceBuilder.intentType(plan.planType().name());
            traceBuilder.executionTimeMs(System.currentTimeMillis() - startTime);
            return SearchResponse.builder()
                .answer("无访问权限")
                .memories(Collections.emptyList())
                .relevantFiles(Collections.emptyList())
                .trace(traceBuilder.build())
                .build();
        }

        // 1. 设置工具调用上下文 (ThreadLocal via OrchestrationContext)
        OrchestrationContext.clear();
        OrchestrationContext.setTraceBuilder(traceBuilder);
        OrchestrationContext.setUserRole(normalizedRole);
        OrchestrationContext.setSessionId(sessionId);

        try {
            String plannedMessage = buildPlannedUserMessage(cleanQuery, plan);
            if (selfScopedQuery) {
                plannedMessage = buildSelfScopedMessage(plannedMessage, sessionId);
            }
            if (guestRole && isDataRelatedPlan(plan)) {
                plannedMessage = buildGuestDocumentOnlyMessage(cleanQuery, plan);
            }
            String effectiveSessionId = (sessionId == null || sessionId.isBlank()) ? "default-session" : sessionId;
            // 2. 调用 Assistant 执行智能编排 (Agentic Workflow)
            // LLM 会根据提示词自主决策：调用 searchCampusDocuments 还是 ScoreService 工具
            String answer = assistant.chat(effectiveSessionId, plannedMessage);

            // 4. 从上下文提取工具调用产生的数据 (并进行显示级去重)
            List<RelevantFile> allRelevant = OrchestrationContext.getToolFiles();
            List<RelevantFile> relevantFiles = new ArrayList<>();
            Set<String> seenDisplays = new HashSet<>();
            for (RelevantFile f : allRelevant) {
                // 使用“文件名 + 类型 + 权限”作为视觉上的唯一键
                String displayKey = f.getFileName() + "|" + f.getSourceType() + "|" + f.getIsPrivate();
                if (seenDisplays.add(displayKey)) {
                    relevantFiles.add(f);
                }
            }

            List<String> memories = new ArrayList<>(OrchestrationContext.getToolMemories());
            Set<String> invokedTools = OrchestrationContext.getInvokedTools();

            // 意图类型识别 (基于工具调用记录)
            String intentType = resolveIntentType(invokedTools, plan.planType().name());
            traceBuilder.intentType(intentType);
            traceBuilder.toolSequence(resolveToolSequence(invokedTools, plan.suggestedTools()));
            traceBuilder.routeConfidence(Math.max(plan.confidence(), calculateRouteConfidence(intentType, invokedTools)));

            // 文件去重与排序处理 (确保与 AI [n] 引用索引一致)
            // AI 的 [n] 是基于 buildDocumentToolPayload 中的 facts 索引生成的，对应 relevantFiles 的前 5 个
            List<RelevantFile> finalFiles = new ArrayList<>();
            Set<String> finalSeen = new HashSet<>();
            
            if (!relevantFiles.isEmpty()) {
                // 默认包含前 5 个文件（AI 实际看到的全部文件），确保 [1]-[5] 索引永远有效
                int limit = Math.min(relevantFiles.size(), 5);
                for (int i = 0; i < limit; i++) {
                    RelevantFile f = relevantFiles.get(i);
                    String displayKey = f.getFileName() + "|" + f.getSourceType() + "|" + f.getIsPrivate();
                    if (finalSeen.add(displayKey)) {
                        finalFiles.add(f);
                    }
                }
                
                // 如果回答中还提到了 5 之后的文件（虽然工具限制了 5，但以防万一），或者有其他逻辑
                if (answer != null) {
                    Pattern citePattern = Pattern.compile("\\[(\\d+)\\]");
                    Matcher citeMatcher = citePattern.matcher(answer);
                    while (citeMatcher.find()) {
                        try {
                            int idx = Integer.parseInt(citeMatcher.group(1)) - 1;
                            if (idx >= 0 && idx < relevantFiles.size()) {
                                RelevantFile f = relevantFiles.get(idx);
                                String displayKey = f.getFileName() + "|" + f.getSourceType() + "|" + f.getIsPrivate();
                                if (finalSeen.add(displayKey)) {
                                    finalFiles.add(f);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
            
            // 补充逻辑：如果此时列表里还没有“公共文档” (sourceType != private)，
            // 且原始检索结果中有，则至少补充一个，解决用户提到的“只显示私人文档”感官问题
            if (!relevantFiles.isEmpty()) {
                boolean hasPublic = finalFiles.stream().anyMatch(f -> !"private".equalsIgnoreCase(f.getSourceType()));
                if (!hasPublic) {
                    relevantFiles.stream()
                        .filter(f -> !"private".equalsIgnoreCase(f.getSourceType()))
                        .findFirst()
                        .ifPresent(f -> {
                            String displayKey = f.getFileName() + "|" + f.getSourceType() + "|" + f.getIsPrivate();
                            if (finalSeen.add(displayKey)) {
                                finalFiles.add(f);
                            }
                        });
                }
            }

            traceBuilder.executionTimeMs(System.currentTimeMillis() - startTime);
            
            return SearchResponse.builder()
                .answer(answer)
                .memories(memories)
                .relevantFiles(finalFiles)
                .trace(traceBuilder.build())
                .build();

        } catch (Exception e) {
            log.error("智能编排搜索发生异常", e);
            return SearchResponse.builder()
                .answer("系统响应异常：" + e.getMessage())
                .trace(traceBuilder.executionTimeMs(System.currentTimeMillis() - startTime).build())
                .build();
        } finally {
            // 5. 必须清理 ThreadLocal 防止线程池污染
            OrchestrationContext.remove();
        }
    }

    private boolean isDocumentQuery(String query) {
        if (query == null) return false;
        String q = query.toLowerCase();
        return q.contains("文档")
            || q.contains("报告")
            || q.contains("资料")
            || q.contains("文件")
            || q.contains("pdf")
            || q.contains("doc")
            || q.contains("招生")
            || q.contains("简章")
            || q.contains("报考")
            || q.contains("录取")
            || q.contains("志愿")
            || q.contains("cdame");
    }

    private boolean isMultimediaQuery(String query) {
        if (query == null) return false;
        String q = query.toLowerCase();
        return q.contains("视频") || q.contains("音频") || q.contains("图片") || q.contains("多媒体");
    }

    private double calculateDynamicThreshold(String query, boolean isDocumentQuery, MediaIntent mediaIntent) {
        if (mediaIntent != MediaIntent.NONE) return 0.12;
        if (isDocumentQuery) return 0.20;
        if (query != null && query.length() < 4) return 0.35;
        return 0.25;
    }

    private int resolveRetrievalTopK(MediaIntent mediaIntent, boolean multimediaQuery) {
        if (mediaIntent != MediaIntent.NONE || multimediaQuery) {
            return Math.max(20, multimediaRetrievalTopK);
        }
        return Math.max(10, defaultRetrievalTopK);
    }

    private String buildRetrievalQueryForIntent(String query, MediaIntent mediaIntent, boolean multimediaQuery) {
        String safeQuery = query == null ? "" : query.trim();
        if (safeQuery.isBlank()) return safeQuery;
        if (mediaIntent == MediaIntent.NONE && !multimediaQuery) return safeQuery;
        StringBuilder expanded = new StringBuilder(safeQuery);
        expanded.append(" 多媒体 校园素材");
        if (mediaIntent == MediaIntent.VIDEO || (multimediaQuery && safeQuery.contains("视频"))) {
            expanded.append(" 视频 宣传片 影像 校园风光");
        } else if (mediaIntent == MediaIntent.AUDIO || (multimediaQuery && safeQuery.contains("音频"))) {
            expanded.append(" 音频 录音 播客");
        } else if (mediaIntent == MediaIntent.IMAGE || (multimediaQuery && safeQuery.contains("图片"))) {
            expanded.append(" 图片 照片 海报 图像");
        } else {
            expanded.append(" 视频 音频 图片");
        }
        return expanded.toString();
    }

    private Set<String> extractYears(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        Set<String> years = new HashSet<>();
        Matcher matcher = YEAR_PATTERN.matcher(text);
        while (matcher.find()) {
            years.add(matcher.group(1)); // 只提取 4 位数字年份
        }
        return years;
    }

    private double calculateSourceWeight(String sourceType) {
        if (sourceType == null) return 0.5;
        return switch (sourceType.toLowerCase()) {
            case "private" -> 1.1;    // 用户私有资产权重最高 (针对用户本人)
            case "official" -> 1.0;   // 官方档案
            case "link" -> 0.9;       // 外部链接
            case "multimedia" -> 0.8; // 多媒体资产
            case "news" -> 0.7;       // 新闻报道
            case "alumni" -> 0.6;     // 校友回忆录
            default -> 0.5;
        };
    }

    private double calculateMultimediaAwareSourceWeight(String sourceType) {
        if (sourceType == null) return 0.6;
        return switch (sourceType.toLowerCase()) {
            case "multimedia" -> 1.25;
            case "private" -> 1.0;
            case "official" -> 0.75;
            case "link" -> 0.70;
            default -> 0.65;
        };
    }

    private List<String> tokenizeForKeywordMatch(String query) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        Set<String> tokens = new LinkedHashSet<>();
        String normalized = query.replaceAll("[？?！!，。,.、:：;；()（）\\[\\]{}\"'“”‘’]", " ").trim();
        for (String part : normalized.split("\\s+|/|-|\\.|_")) {
            String p = part.trim();
            if (!p.isEmpty()) tokens.add(p);
        }
        for (int i = 0; i < normalized.length() - 1; i++) {
            String bi = normalized.substring(i, i + 2).trim();
            if (bi.length() == 2 && !bi.contains(" ")) {
                tokens.add(bi);
            }
        }
        return new ArrayList<>(tokens);
    }

    private double keywordMatchScore(String text, String sourceName, String category, String description, List<String> tokens, String query) {
        if ((text == null || text.isBlank()) && (sourceName == null || sourceName.isBlank()) && (category == null || category.isBlank()) && (description == null || description.isBlank())) return 0.0;
        String content = (text == null ? "" : text.toLowerCase()) + " "
            + (sourceName == null ? "" : sourceName.toLowerCase()) + " "
            + (category == null ? "" : category.toLowerCase()) + " "
            + (description == null ? "" : description.toLowerCase());
        String q = query == null ? "" : query.toLowerCase();
        double score = 0.0;
        for (String token : tokens) {
            if (token == null) continue;
            String tk = token.toLowerCase();
            if (tk.length() >= 2 && content.contains(tk)) {
                score += 0.12;
            }
        }
        if (!q.isBlank() && content.contains(q)) {
            score += 0.5;
        }
        return Math.min(score, 1.2);
    }

    private RetrievalWeightProfile resolveWeightProfile(boolean documentQuery, boolean multimediaQuery, MediaIntent mediaIntent, String query) {
        if (mediaIntent != MediaIntent.NONE) {
            return new RetrievalWeightProfile(0.34, 0.28, 0.10, 0.24, 0.04, 0.14);
        }
        if (multimediaQuery) {
            return new RetrievalWeightProfile(0.36, 0.28, 0.12, 0.20, 0.04, 0.12);
        }
        if (documentQuery) {
            if (query != null && query.trim().length() <= 4) {
                return new RetrievalWeightProfile(0.38, 0.34, 0.10, 0.06, 0.12, 0.18);
            }
            return new RetrievalWeightProfile(0.30, 0.42, 0.10, 0.06, 0.12, 0.22);
        }
        return new RetrievalWeightProfile(0.46, 0.30, 0.12, 0.08, 0.04, 0.12);
    }

    private double metadataMatchScore(String sourceName, String category, String description, List<String> tokens, String query) {
        String source = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        String cat = category == null ? "" : category.toLowerCase(Locale.ROOT);
        String desc = description == null ? "" : description.toLowerCase(Locale.ROOT);
        int tokenCount = 0;
        int hitCount = 0;
        for (String token : tokens) {
            if (token == null) continue;
            String tk = token.trim().toLowerCase(Locale.ROOT);
            if (tk.length() < 2) continue;
            tokenCount++;
            if (source.contains(tk)) hitCount += 3;
            if (cat.contains(tk)) hitCount += 2;
            if (desc.contains(tk)) hitCount += 1;
        }
        double tokenSignal = tokenCount == 0 ? 0.0 : Math.min((double) hitCount / (double) (tokenCount * 3), 1.0);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        double phraseSignal = 0.0;
        if (!q.isBlank()) {
            if (source.contains(q)) phraseSignal = Math.max(phraseSignal, 1.0);
            if (cat.contains(q)) phraseSignal = Math.max(phraseSignal, 0.8);
            if (desc.contains(q)) phraseSignal = Math.max(phraseSignal, 0.6);
        }
        return Math.max(tokenSignal, phraseSignal);
    }

    private double exactMetadataBoost(String sourceName, String description, String query, double cap) {
        if (query == null || query.isBlank()) return 0.0;
        String q = query.trim().toLowerCase(Locale.ROOT);
        String source = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        String desc = description == null ? "" : description.toLowerCase(Locale.ROOT);
        double boost = 0.0;
        if (!source.isBlank() && source.contains(q)) {
            boost += 0.12;
        }
        if (!desc.isBlank() && desc.contains(q)) {
            boost += 0.07;
        }
        return Math.min(boost, cap);
    }

    private boolean hasEffectiveRecallInformation(
        String text,
        String description,
        String sourceName,
        String sourceType,
        String mediaType
    ) {
        String merged = ((text == null ? "" : text) + " " + (description == null ? "" : description)).toLowerCase(Locale.ROOT);
        if (containsInvalidRecallMarker(merged)) {
            return false;
        }

        String content = normalizeRecallContent(extractLabeledValue(text, "内容:"));
        String summary = normalizeRecallContent(extractLabeledValue(text, "AI 摘要:"));
        String desc = normalizeRecallContent(description);
        String source = normalizeRecallContent(sourceName);
        boolean mediaLike = isMediaLikeAsset(sourceName, sourceType, mediaType);

        if (isMeaningfulRecallText(content)) return true;
        if (isMeaningfulRecallText(summary)) return true;
        if (!mediaLike && isMeaningfulRecallText(desc)) return true;

        if (!mediaLike && isMeaningfulRecallText(source) && source.length() >= 6) {
            return true;
        }
        return false;
    }

    private String extractLabeledValue(String text, String label) {
        if (text == null || text.isBlank() || label == null || label.isBlank()) {
            return "";
        }
        int index = text.indexOf(label);
        if (index < 0) {
            return "";
        }
        int start = index + label.length();
        String tail = text.substring(start).trim();
        int nextLine = tail.indexOf('\n');
        if (nextLine >= 0) {
            return tail.substring(0, nextLine).trim();
        }
        return tail;
    }

    private String normalizeRecallContent(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = value.split("\\R");
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isBlank()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            boolean isTemplateLine = false;
            for (String prefix : TEMPLATE_PREFIXES) {
                if (lower.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                    isTemplateLine = true;
                    break;
                }
            }
            if (isTemplateLine) continue;
            sb.append(line).append(" ");
        }
        return sb.toString().trim();
    }

    private boolean containsInvalidRecallMarker(String value) {
        if (value == null || value.isBlank()) return false;
        for (String marker : INVALID_RECALL_MARKERS) {
            if (marker != null && !marker.isBlank() && value.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMeaningfulRecallText(String value) {
        if (value == null || value.isBlank()) return false;
        if (containsInvalidRecallMarker(value.toLowerCase(Locale.ROOT))) return false;

        int infoChars = 0;
        for (char c : value.toCharArray()) {
            if (Character.isLetterOrDigit(c) || Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                infoChars++;
            }
        }
        if (infoChars < 8) return false;

        String[] pieces = value.split("[\\s,，。！？；：、/|（）()\\[\\]【】<>《》]+");
        int tokenCount = 0;
        for (String piece : pieces) {
            if (piece == null) continue;
            String token = piece.trim();
            if (token.length() < 2) continue;
            if (token.chars().anyMatch(ch -> Character.isLetterOrDigit(ch) || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN)) {
                tokenCount++;
            }
        }
        return tokenCount >= 2 || infoChars >= 12;
    }

    private boolean isMediaLikeAsset(String sourceName, String sourceType, String mediaType) {
        String name = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        String source = sourceType == null ? "" : sourceType.toLowerCase(Locale.ROOT);
        String media = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        if ("multimedia".equals(source)) return true;
        if (!media.isBlank()) return true;
        if (name.contains("camera-scan") || name.contains("扫描") || name.contains("拍照")) return true;
        return endsWithAny(name, ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".tiff", ".tif");
    }

    private boolean passesRelevanceGate(
        String text,
        String sourceName,
        String sourceType,
        String mediaType,
        String category,
        String description,
        List<String> tokens,
        String query,
        double semanticScore,
        boolean documentQuery,
        boolean multimediaQuery,
        MediaIntent mediaIntent
    ) {
        StringBuilder contentBuilder = new StringBuilder();
        if (text != null) contentBuilder.append(text).append(" ");
        if (sourceName != null) contentBuilder.append(sourceName).append(" ");
        if (category != null) contentBuilder.append(category).append(" ");
        if (description != null) contentBuilder.append(description);
        String content = contentBuilder.toString().toLowerCase();
        String q = query == null ? "" : query.trim().toLowerCase();
        boolean fullQueryHit = !q.isBlank() && content.contains(q);
        int tokenHits = 0;
        for (String token : tokens) {
            if (token == null) continue;
            String tk = token.trim().toLowerCase();
            if (tk.length() >= 2 && content.contains(tk)) {
                tokenHits++;
            }
        }
        if (mediaIntent != MediaIntent.NONE) {
            boolean mediaMatched = matchesMediaIntent(sourceName, text, sourceType, mediaType, mediaIntent);
            if (!mediaMatched) return false;
            if (fullQueryHit || tokenHits >= 1) return true;
            if (mediaIntent != MediaIntent.IMAGE && "multimedia".equalsIgnoreCase(sourceType) && semanticScore >= 0.28) return true;
            return false;
        }
        if (multimediaQuery && "multimedia".equalsIgnoreCase(sourceType)) {
            if (tokenHits >= 1) return true;
            if (semanticScore >= 0.30) return true;
            if (content.contains("视频") || content.contains("音频") || content.contains("多媒体")) return true;
        }
        if (fullQueryHit) return true;
        if (tokenHits >= 2) return true;
        if (tokenHits >= 1 && semanticScore >= 0.45) return true;
        if (!documentQuery && semanticScore >= 0.72) return true;
        return false;
    }

    private MediaIntent detectMediaIntent(String query) {
        if (query == null || query.isBlank()) return MediaIntent.NONE;
        String q = query.toLowerCase(Locale.ROOT);
        if (containsAny(q, "视频", "影片", "影像", "短片", "录像", "宣传片", "纪录片", "video", "mv", "vlog")) return MediaIntent.VIDEO;
        if (containsAny(q, "音频", "音乐", "录音", "播客", "audio")) return MediaIntent.AUDIO;
        if (containsAny(q, "图片", "照片", "海报", "图像", "image", "photo")) return MediaIntent.IMAGE;
        if (containsAny(q, "多媒体", "素材")) return MediaIntent.MULTIMEDIA;
        return MediaIntent.NONE;
    }

    private boolean matchesMediaIntent(String sourceName, String text, String sourceType, String mediaType, MediaIntent mediaIntent) {
        if (mediaIntent == MediaIntent.NONE) return true;
        String name = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        String body = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String source = sourceType == null ? "" : sourceType.toLowerCase(Locale.ROOT);
        String media = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        boolean hasVideoExt = endsWithAny(name, ".mp4", ".avi", ".mov", ".mkv", ".webm", ".flv", ".wmv");
        boolean hasAudioExt = endsWithAny(name, ".mp3", ".wav", ".aac", ".m4a", ".flac", ".ogg");
        boolean hasImageExt = endsWithAny(name, ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp");
        boolean hasMediaIdentity = "multimedia".equals(source)
            || media.equals("视频") || media.equals("video")
            || media.equals("音频") || media.equals("audio")
            || media.equals("图片") || media.equals("image")
            || hasVideoExt || hasAudioExt || hasImageExt
            || body.contains("媒体类型: 视频") || body.contains("媒体类型: 音频")
            || body.contains("多媒体资产:");
        if (!hasMediaIdentity) return false;
        boolean isVideo = media.equals("视频") || media.equals("video")
            || hasVideoExt
            || body.contains("媒体类型: 视频")
            || body.contains("bilibili.com") || body.contains("youtube.com") || body.contains("youtu.be") || body.contains("v.qq.com") || body.contains("youku.com");
        boolean isAudio = media.equals("音频") || media.equals("audio")
            || hasAudioExt
            || body.contains("媒体类型: 音频");
        boolean isImage = media.equals("图片") || media.equals("image")
            || hasImageExt
            || body.contains("图片资产:");
        boolean isMultimedia = "multimedia".equals(source) || isVideo || isAudio || isImage || body.contains("多媒体资产:");
        return switch (mediaIntent) {
            case VIDEO -> isVideo;
            case AUDIO -> isAudio;
            case IMAGE -> isImage;
            case MULTIMEDIA -> isMultimedia;
            case NONE -> true;
        };
    }

    private boolean endsWithAny(String value, String... suffixes) {
        if (value == null || value.isBlank() || suffixes == null) return false;
        for (String suffix : suffixes) {
            if (suffix != null && !suffix.isBlank() && value.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String value, String... candidates) {
        if (value == null || value.isBlank() || candidates == null) return false;
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String buildDocumentToolPayload(String query, List<String> texts, List<String> textSources, List<RelevantFile> files) {
        List<String> cappedTexts = texts == null ? Collections.emptyList() : texts.stream().filter(Objects::nonNull).limit(8).collect(Collectors.toList());
        List<String> cappedTextSources = textSources == null ? Collections.emptyList() : textSources.stream().filter(Objects::nonNull).limit(8).collect(Collectors.toList());
        List<RelevantFile> cappedFiles = files == null ? Collections.emptyList() : files.stream().filter(Objects::nonNull).limit(5).collect(Collectors.toList());
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"tool\":\"searchCampusDocuments\",");
        sb.append("\"query\":\"").append(escapeJson(query)).append("\",");
        sb.append("\"hitCount\":").append(texts == null ? 0 : texts.size()).append(",");
        sb.append("\"facts\":[");
        for (int i = 0; i < cappedTexts.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{");
            sb.append("\"id\":").append(i + 1).append(",");
            sb.append("\"content\":\"").append(escapeJson(cappedTexts.get(i))).append("\",");
            sb.append("\"source\":\"").append(escapeJson(cappedTextSources.size() > i ? cappedTextSources.get(i) : "unknown")).append("\"");
            sb.append("}");
        }
        sb.append("],");
        sb.append("\"sourceFiles\":[");
        for (int i = 0; i < cappedFiles.size(); i++) {
            RelevantFile file = cappedFiles.get(i);
            if (i > 0) sb.append(",");
            sb.append("{");
            sb.append("\"fileName\":\"").append(escapeJson(file.getFileName())).append("\",");
            sb.append("\"objectName\":\"").append(escapeJson(file.getObjectName())).append("\",");
            sb.append("\"url\":\"").append(escapeJson(file.getUrl())).append("\",");
            sb.append("\"sourceType\":\"").append(escapeJson(file.getSourceType())).append("\"");
            sb.append("}");
        }
        sb.append("]");
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
    }

    private double calculateRouteConfidence(String intentType, Set<String> invokedTools) {
        if ("MIXED".equals(intentType)) return 0.92;
        if ("DOCUMENT".equals(intentType) || "DATA".equals(intentType)) return 0.86;
        if (invokedTools != null && !invokedTools.isEmpty()) return 0.75;
        return 0.55;
    }

    private String resolveIntentType(Set<String> invokedTools, String plannedType) {
        if (invokedTools != null && invokedTools.contains("DOCUMENT") && invokedTools.contains("DATA")) {
            return "MIXED";
        }
        if (invokedTools != null && invokedTools.contains("DOCUMENT")) {
            return "DOCUMENT";
        }
        if (invokedTools != null && invokedTools.contains("DATA")) {
            return "DATA";
        }
        if (plannedType == null || plannedType.isBlank()) {
            return "GENERAL";
        }
        return plannedType;
    }

    private String resolveToolSequence(Set<String> invokedTools, List<String> suggestedTools) {
        if (invokedTools != null && !invokedTools.isEmpty()) {
            return String.join(" -> ", invokedTools);
        }
        if (suggestedTools == null || suggestedTools.isEmpty()) {
            return "";
        }
        return "PLAN(" + String.join(" -> ", suggestedTools) + ")";
    }

    private String buildPlannedUserMessage(String originalQuery, PlanningLayer.PlanningDecision plan) {
        if (plan == null) return originalQuery;
        StringBuilder sb = new StringBuilder();
        sb.append("【PlanningLayer】");
        sb.append("intent=").append(plan.planType());
        sb.append(",confidence=").append(String.format(java.util.Locale.ROOT, "%.2f", plan.confidence()));
        if (plan.suggestedTools() != null && !plan.suggestedTools().isEmpty()) {
            sb.append(",tools=").append(String.join("|", plan.suggestedTools()));
        }
        sb.append("\n");
        sb.append(plan.condensedQuery() == null || plan.condensedQuery().isBlank() ? originalQuery : plan.condensedQuery());
        return sb.toString();
    }

    private String normalizeRole(String role) {
        if (role == null) return "guest";
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "guest" : normalized;
    }

    private boolean isGuestRole(String role) {
        return "guest".equals(role) || "anonymous".equals(role) || "visitor".equals(role) || "all".equals(role);
    }

    private boolean isDataRelatedPlan(PlanningLayer.PlanningDecision plan) {
        if (plan == null || plan.planType() == null) return false;
        return plan.planType() == PlanningLayer.PlanType.DATA || plan.planType() == PlanningLayer.PlanType.MIXED;
    }

    private boolean isStudentSensitiveQuery(String query) {
        if (query == null || query.isBlank()) return false;
        String q = query.toLowerCase(Locale.ROOT);

        if (containsAny(q, "学号", "绩点", "分数", "成绩", "排名", "个人档案", "个人信息", "联系方式", "手机号", "邮箱", "身份证", "学生名单", "学生档案")) {
            return true;
        }

        if (q.contains("学生")) {
            return containsAny(q, "信息", "成绩", "绩点", "排名", "名单", "档案", "学号", "姓名", "班级", "宿舍", "联系方式", "手机号", "邮箱", "身份证");
        }

        return false;
    }

    private String buildGuestDocumentOnlyMessage(String originalQuery, PlanningLayer.PlanningDecision plan) {
        String base = buildPlannedUserMessage(originalQuery, plan);
        return "【游客权限约束】当前用户为游客，只能查询公开文档与公开政策，禁止调用任何学生成绩、学生档案、学生名单等数据工具。"
                + "若问题涉及个人学生信息，直接回答“无访问权限”。\n" + base;
    }

    private String buildSelfScopedMessage(String baseMessage, String currentUserId) {
        String safeBase = baseMessage == null ? "" : baseMessage;
        if (currentUserId == null || currentUserId.isBlank()) {
            return safeBase;
        }
        return "【登录用户上下文】当前用户标识=" + currentUserId
                + "。当问题中出现“我/我的/本人”等指代时，默认指向该用户。检索范围需覆盖公共资料与该用户私有空间资料，并在回答中优先给出与该用户直接相关的信息。\n"
                + safeBase;
    }

    private boolean isSelfReferentialQuery(String query) {
        if (query == null || query.isBlank()) return false;
        String q = query.toLowerCase(Locale.ROOT);
        return q.contains("我的")
                || q.contains("本人")
                || q.contains("我自己")
                || q.contains("本人的")
                || q.contains("我的资料")
                || q.contains("我的信息")
                || SELF_REFERENCE_PATTERN.matcher(q).find();
    }

    private boolean isUuidPrefixed(String value) {
        if (value == null || value.length() <= 37 || value.charAt(36) != '-') {
            return false;
        }
        try {
            UUID.fromString(value.substring(0, 36));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String extractOriginalFileName(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            return null;
        }
        // 处理私有目录路径，如 private/student/UUID-filename.pdf
        String baseName = objectName;
        if (objectName.contains("/")) {
            baseName = objectName.substring(objectName.lastIndexOf("/") + 1);
        }
        
        if (isUuidPrefixed(baseName)) {
            return baseName.substring(37);
        }
        return baseName;
    }

    private String normalizePossibleObjectName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String baseName = fileName;
        if (fileName.contains("/")) {
            baseName = fileName.substring(fileName.lastIndexOf("/") + 1);
        }
        if (isUuidPrefixed(baseName)) {
            return baseName.substring(37);
        }
        return baseName;
    }

    private List<String> parseSuggestions(String text) {
        try {
            int start = text.indexOf("[");
            int end = text.indexOf("]");
            if (start != -1 && end != -1) {
                String listStr = text.substring(start + 1, end);
                return List.of(listStr.split(", "));
            }
        } catch (Exception e) {
            log.error("解析建议问题失败", e);
        }
        return Collections.emptyList();
    }

    /**
     * 旧版搜索接口 (兼容原有代码)，默认使用 student 角色，sessionId 为空
     */
    public List<String> search(String query, int maxResults) {
        return searchWithAi(query, maxResults, "student", null).getMemories();
    }
}
