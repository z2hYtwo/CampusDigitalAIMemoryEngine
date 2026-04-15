package com.campus.memory.service;

import com.campus.memory.service.MemoryService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.SlideShowFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.text.Normalizer;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssetService {

    private final MinioClient minioClient;
    private final MemoryService memoryService;
    private final WhisperAsrService whisperAsrService;

    @Value("${minio.bucket-name}")
    private String bucketName;
    @Value("${ocr.enabled:true}")
    private boolean ocrEnabled;
    @Value("${ocr.language:chi_sim}")
    private String ocrLanguage;
    @Value("${ocr.tessdata-path:}")
    private String tessdataPath;
    @Value("${memory.indexing.skip-low-info:true}")
    private boolean skipLowInfoIndexing;
    @Value("${memory.indexing.min-effective-chars:10}")
    private int minEffectiveChars;

    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_TEACHER = "teacher";
    private static final Pattern OCR_NOISE_SEQUENCE = Pattern.compile("([`~^_=\\-|/\\\\·•.,:;!?])\\1{3,}");
    private static final Pattern OCR_WORD_CHAR = Pattern.compile("[\\p{IsHan}A-Za-z0-9]");
    private static final Pattern OCR_CJK_CHAR = Pattern.compile("[\\p{IsHan}]");
    private static final Pattern OCR_COMBINING_MARK = Pattern.compile("\\p{M}+");
    private record OcrResult(String text, int score) {}

    /**
     * 核心资产处理逻辑：MinIO 存储 -> OCR -> AI 分类与知识提取 -> 向量化入库
     */
    public String processAsset(byte[] fileBytes, String fileName, String contentType,
                               String uploadRole, String effectiveUserId,
                               String requestedCategory, String sourceType,
                               String description, Integer cameraQualityScore, Map<String, Object> honorMetadata,
                               AssetProcessor processor) throws Exception {
        
        String prefix = ("private".equalsIgnoreCase(uploadRole))
                ? "private/" + effectiveUserId + "/"
                : "public/";
        String objectName = prefix + UUID.randomUUID() + "-" + fileName;

        // 1. 上传到 MinIO
        try (InputStream uploadStream = new ByteArrayInputStream(fileBytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(uploadStream, fileBytes.length, -1)
                            .contentType(contentType)
                            .build()
            );
        }
        log.info("资产已上传至 MinIO: {}", objectName);

        // 2. 深度处理与向量化
        if (processor.isSupportedAsset(fileName)) {
            String extractedText = "";
            String recognizedLanguage = null;
            if (processor.isImage(fileName)) {
                extractedText = processor.extractImageText(fileBytes, fileName);
            } else if (processor.isMultimedia(fileName)) {
                WhisperAsrService.TranscriptionResult transcription = transcribeMultimediaSafely(fileBytes, fileName, contentType);
                if (transcription != null) {
                    extractedText = transcription.text();
                    recognizedLanguage = transcription.language();
                }
                if (extractedText == null || extractedText.isBlank()) {
                    extractedText = "多媒体资产: " + fileName + " (媒体类型: " + resolveMediaLabel(fileName) + ")";
                }
            } else {
                extractedText = processor.extractDocumentTextSafely(fileBytes, fileName);
            }

            // --- 创意 4 核心：AI 自动理解与分类 ---
            Map<String, Object> aiResult = memoryService.classifyAsset(fileName, description, extractedText, cameraQualityScore);
            
            String finalCategory = (aiResult != null && aiResult.containsKey("category")) 
                ? (String) aiResult.get("category") 
                : processor.inferCategory(requestedCategory, fileName, description, extractedText);
            
            String finalSourceType = (aiResult != null && aiResult.containsKey("sourceType"))
                ? (String) aiResult.get("sourceType")
                : sourceType;

            boolean isHonor = (aiResult != null && Boolean.TRUE.equals(aiResult.get("isHonor"))) 
                || (honorMetadata != null);
            String aiSummary = aiResult != null && aiResult.containsKey("summary")
                ? Objects.toString(aiResult.get("summary"), "")
                : "";
            aiSummary = normalizeAiSummaryByQuality(aiSummary, cameraQualityScore);
            if (aiResult != null && !aiSummary.isBlank()) {
                aiResult.put("summary", aiSummary);
            }
            boolean hasIndexableContent = hasIndexableContent(extractedText, description, aiSummary, fileName, processor.isMultimedia(fileName));
            if (skipLowInfoIndexing && !hasIndexableContent && !isHonor) {
                log.info("资产缺少有效信息，跳过向量化: {}", fileName);
                return "资产已上传，但未提取到有效内容，已跳过向量化入库";
            }

            // 构造向量库元数据
            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("fileName", fileName);
            metadata.put("objectName", objectName);
            metadata.put("role", uploadRole);
            metadata.put("category", finalCategory);
            metadata.put("sourceType", processor.isMultimedia(fileName) ? "multimedia" : finalSourceType);
            if (processor.isMultimedia(fileName)) {
                metadata.put("mediaType", resolveMediaLabel(fileName));
            }
            if (recognizedLanguage != null && !recognizedLanguage.isBlank()) {
                metadata.put("recognizedLanguage", recognizedLanguage);
            }
            if (cameraQualityScore != null) metadata.put("cameraQualityScore", cameraQualityScore);
            if (description != null) metadata.put("description", description);
            if (effectiveUserId != null) metadata.put("userId", effectiveUserId);
            if (aiResult != null && aiResult.containsKey("summary")) {
                metadata.put("aiSummary", aiResult.get("summary"));
            }

            // 合并 AI 提取的实体 (将 List 转换为 String, LangChain4j Metadata 不支持 ArrayList)
            if (aiResult != null && aiResult.get("extractedEntities") instanceof List) {
                List<?> entities = (List<?>) aiResult.get("extractedEntities");
                if (!entities.isEmpty()) {
                    metadata.put("entities", String.join(", ", entities.stream().map(Object::toString).toList()));
                }
            }

            // 构造检索文本
            StringBuilder searchableText = new StringBuilder();
            if (aiResult != null && aiResult.containsKey("summary")) {
                searchableText.append("AI 摘要: ").append(aiResult.get("summary")).append("\n");
            }
            searchableText.append("文件名: ").append(fileName).append("\n");
            searchableText.append("分类: ").append(finalCategory).append("\n");
            if (cameraQualityScore != null) {
                searchableText.append("拍摄质量评分: ").append(cameraQualityScore).append("/100\n");
            }
            if (extractedText != null && !extractedText.isBlank()) {
                searchableText.append("内容: ").append(extractedText);
            }
            if (processor.isMultimedia(fileName)) {
                searchableText.append("\n媒体类型: ").append(resolveMediaLabel(fileName));
                searchableText.append("\n检索标签: ").append(resolveMediaLabel(fileName)).append(" 多媒体 校园素材");
                if (recognizedLanguage != null && !recognizedLanguage.isBlank()) {
                    searchableText.append("\n识别语言: ").append(recognizedLanguage);
                }
            }

            // 3. 区分存入普通记忆还是荣誉墙
            if (isHonor) {
                // 如果是荣誉，合并 AI 提取的荣誉元数据
                if (aiResult != null) {
                    if (aiResult.containsKey("honorLevel")) metadata.put("honorLevel", aiResult.get("honorLevel"));
                    if (aiResult.containsKey("honorCategory")) metadata.put("honorCategory", aiResult.get("honorCategory"));
                    if (aiResult.containsKey("honorYear")) metadata.put("honorYear", aiResult.get("honorYear"));
                }
                // 优先级：手动输入 > AI 提取 > 默认值
                if (honorMetadata != null) metadata.putAll(honorMetadata);
                
                // 确保必填项
                metadata.putIfAbsent("honorLevel", "校级");
                metadata.putIfAbsent("honorCategory", "其他");
                metadata.putIfAbsent("timestamp", ZonedDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                
                memoryService.addHonor(searchableText.toString(), metadata);
                return "资产已作为【荣誉】入库并向量化";
            } else {
                memoryService.addMemory(searchableText.toString(), metadata);
                return "资产已作为【" + finalCategory + "】入库并向量化";
            }
        }

        return "资产已上传，但该类型不支持内容提取";
    }

    private String normalizeAiSummaryByQuality(String aiSummary, Integer cameraQualityScore) {
        if (aiSummary == null) return "";
        String normalized = aiSummary.trim();
        if (normalized.isBlank()) return normalized;
        if (cameraQualityScore == null || cameraQualityScore < 80) return normalized;
        return normalized
            .replace("图像质量差", "拍摄质量评分较高")
            .replace("拍摄质量差", "拍摄质量评分较高");
    }

    private boolean hasIndexableContent(String extractedText, String description, String aiSummary, String fileName, boolean multimediaAsset) {
        if (isMeaningfulIndexText(extractedText)) return true;
        if (isMeaningfulIndexText(aiSummary)) return true;
        if (isMeaningfulIndexText(description)) return true;
        if (!multimediaAsset && isMeaningfulIndexText(fileName)) return true;
        return false;
    }

    private boolean isMeaningfulIndexText(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value
            .replace("多媒体资产:", " ")
            .replace("媒体类型:", " ")
            .replace("检索标签:", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (normalized.isBlank()) {
            return false;
        }
        if (isLikelyGarbledOcr(normalized)) {
            return false;
        }
        int infoChars = 0;
        for (char c : normalized.toCharArray()) {
            if (Character.isLetterOrDigit(c) || Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                infoChars++;
            }
        }
        int threshold = Math.max(6, minEffectiveChars);
        if (infoChars < threshold) {
            return false;
        }
        String[] tokens = normalized.split("[\\s,，。！？；：、/|（）()\\[\\]【】<>《》]+");
        int tokenCount = 0;
        for (String token : tokens) {
            if (token != null && token.trim().length() >= 2) {
                tokenCount++;
            }
        }
        return tokenCount >= 2 || infoChars >= threshold + 4;
    }

    /**
     * 回调接口：用于解耦 AssetService 与具体的处理实现（如 Controller 中定义的工具方法）
     */
    /**
     * 默认资产处理器实现
     */
    public class DefaultAssetProcessor implements AssetProcessor {
        @Override
        public boolean isSupportedAsset(String fileName) {
            return AssetService.this.isSupportedAsset(fileName);
        }

        @Override
        public boolean isImage(String fileName) {
            return AssetService.this.isImage(fileName);
        }

        @Override
        public boolean isMultimedia(String fileName) {
            return AssetService.this.isMultimedia(fileName);
        }

        @Override
        public String extractImageText(byte[] bytes, String fileName) throws Exception {
            return AssetService.this.extractImageText(bytes, fileName);
        }

        @Override
        public String extractDocumentTextSafely(byte[] bytes, String fileName) throws Exception {
            return AssetService.this.extractDocumentTextSafely(bytes, fileName);
        }

        @Override
        public String inferCategory(String requestedCategory, String fileName, String description, String extractedText) {
            return AssetService.this.inferCategory(requestedCategory, fileName, description, extractedText);
        }
    }

    public interface AssetProcessor {
        boolean isSupportedAsset(String fileName);
        boolean isImage(String fileName);
        boolean isMultimedia(String fileName);
        String extractImageText(byte[] bytes, String fileName) throws Exception;
        String extractDocumentTextSafely(byte[] bytes, String fileName) throws Exception;
        String inferCategory(String requestedCategory, String fileName, String description, String extractedText);
    }

    public String inferCategory(String requestedCategory, String fileName, String description, String extractedText) {
        String category = requestedCategory == null ? "" : requestedCategory.trim();
        if (!category.isBlank() && !"auto".equalsIgnoreCase(category) && !"自动".equals(category) && !"自动分类".equals(category) && !"校史".equals(category)) {
            return category;
        }
        StringBuilder signal = new StringBuilder();
        if (fileName != null) signal.append(fileName).append(" ");
        if (description != null) signal.append(description).append(" ");
        if (extractedText != null) signal.append(extractedText);
        String text = signal.toString().toLowerCase();
        if (containsAny(text, "专业", "专业介绍", "专业概览", "专业目录", "培养方案", "培养目标", "培养计划", "课程体系", "课程设置", "学位", "学科", "实验班")) {
            return "专业";
        }
        if (containsAny(text, "招生", "简章", "报考", "录取", "志愿", "招生办")) {
            return "招生";
        }
        if (containsAny(text, "政策", "规定", "办法", "制度", "条例", "通知")) {
            return "政策";
        }
        if (containsAny(text, "校史", "沿革", "发展历程", "建校", "历史")) {
            return "校史";
        }
        if (isImage(fileName)) {
            return "图片";
        }
        if (isMultimedia(fileName)) {
            return "多媒体";
        }
        return "文档";
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank() || keywords == null) return false;
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    public boolean isSupportedAsset(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".txt") || lower.endsWith(".doc")
                || lower.endsWith(".ppt") || lower.endsWith(".pptx")
                || isMultimedia(fileName) || isImage(fileName);
    }

    public boolean isMultimedia(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".mp4") || lower.endsWith(".mp3") || lower.endsWith(".wav")
                || lower.endsWith(".avi") || lower.endsWith(".mov")
                || lower.endsWith(".m4a") || lower.endsWith(".ogg")
                || lower.endsWith(".webm") || lower.endsWith(".mpeg") || lower.endsWith(".mpga");
    }

    public boolean isImage(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    public String resolveMediaLabel(String fileName) {
        if (fileName == null) return "多媒体";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp4") || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".webm")) return "视频";
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a") || lower.endsWith(".ogg") || lower.endsWith(".mpeg") || lower.endsWith(".mpga")) return "音频";
        return "多媒体";
    }

    public String extractDocumentTextSafely(byte[] fileBytes, String fileName) {
        String lowerName = fileName == null ? "" : fileName.toLowerCase();
        try {
            if (isMultimedia(fileName)) {
                WhisperAsrService.TranscriptionResult transcription = transcribeMultimediaSafely(fileBytes, fileName, resolveMediaContentType(fileName));
                if (transcription != null && transcription.text() != null) {
                    return transcription.text().trim();
                }
                return null;
            }
            if (lowerName.endsWith(".pdf")) {
                return extractPdfText(fileBytes, fileName);
            }
            if (lowerName.endsWith(".doc") || lowerName.endsWith(".docx")) {
                return extractWordText(fileBytes, fileName);
            }
            if (lowerName.endsWith(".ppt") || lowerName.endsWith(".pptx")) {
                return extractPowerPointText(fileBytes, fileName);
            }
            ApacheTikaDocumentParser parser = new ApacheTikaDocumentParser();
            Document document = parser.parse(new ByteArrayInputStream(fileBytes));
            String text = document == null ? null : document.text();
            return text == null ? null : text.trim();
        } catch (Exception e) {
            log.warn("文档内容提取失败，将使用文件元数据参与向量化: {}", fileName, e);
            return null;
        }
    }

    private WhisperAsrService.TranscriptionResult transcribeMultimediaSafely(byte[] fileBytes, String fileName, String contentType) {
        try {
            return whisperAsrService.transcribe(fileBytes, fileName, contentType, null);
        } catch (Exception e) {
            log.warn("多媒体 Whisper 转写失败，回退文件元数据参与向量化: {}", fileName, e);
            return null;
        }
    }

    private String resolveMediaContentType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".webm")) return "video/webm";
        return "application/octet-stream";
    }

    private String extractPdfText(byte[] fileBytes, String fileName) {
        try (PDDocument pdf = PDDocument.load(new ByteArrayInputStream(fileBytes))) {
            String text = new PDFTextStripper().getText(pdf);
            String normalized = text == null ? "" : text.trim();
            if (!normalized.isEmpty()) {
                log.info("PDF 文本提取完成: {}, 文本长度: {}", fileName, normalized.length());
                return normalized;
            }
            String ocrText = extractPdfTextWithOcr(pdf, fileName);
            if (ocrText != null && !ocrText.isBlank()) {
                log.info("PDF OCR 提取完成: {}, 文本长度: {}", fileName, ocrText.length());
                return ocrText.trim();
            }
            return null;
        } catch (Exception e) {
            log.warn("PDF 文本提取失败: {}", fileName, e);
            return null;
        }
    }

    private String extractPdfTextWithOcr(PDDocument pdf, String fileName) {
        if (!ocrEnabled) return null;
        try {
            int pageCount = pdf.getNumberOfPages();
            if (pageCount <= 0) return null;
            int maxPages = Math.min(pageCount, 12);
            PDFRenderer renderer = new PDFRenderer(pdf);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < maxPages; i++) {
                BufferedImage pageImage = renderer.renderImageWithDPI(i, 220, ImageType.RGB);
                if (pageImage == null) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(pageImage, "png", out);
                String pageText = extractImageText(out.toByteArray(), fileName + "#page-" + (i + 1) + ".png");
                if (pageText != null && !pageText.isBlank()) {
                    if (!text.isEmpty()) text.append("\n");
                    text.append(pageText.trim());
                }
            }
            String normalized = text.toString().trim();
            return normalized.isEmpty() ? null : normalized;
        } catch (Exception e) {
            log.warn("PDF OCR 提取失败: {}", fileName, e);
            return null;
        }
    }

    private String extractWordText(byte[] fileBytes, String fileName) {
        String lowerName = fileName == null ? "" : fileName.toLowerCase();
        try {
            if (lowerName.endsWith(".docx")) {
                try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileBytes));
                     XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                    String text = extractor.getText();
                    String normalized = text == null ? "" : text.trim();
                    if (!normalized.isEmpty()) {
                        log.info("DOCX 文本提取完成: {}, 文本长度: {}", fileName, normalized.length());
                        return normalized;
                    }
                }
            } else if (lowerName.endsWith(".doc")) {
                try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(fileBytes));
                     WordExtractor extractor = new WordExtractor(document)) {
                    String text = extractor.getText();
                    String normalized = text == null ? "" : text.trim();
                    if (!normalized.isEmpty()) {
                        log.info("DOC 文本提取完成: {}, 文本长度: {}", fileName, normalized.length());
                        return normalized;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Word 文本提取失败: {}", fileName, e);
            return null;
        }
    }

    private String extractPowerPointText(byte[] fileBytes, String fileName) {
        String lowerName = fileName == null ? "" : fileName.toLowerCase();
        try {
            try (SlideShow<?, ?> slideShow = SlideShowFactory.create(new ByteArrayInputStream(fileBytes));
                 SlideShowExtractor<?, ?> extractor = new SlideShowExtractor<>(slideShow)) {
                String text = extractor.getText();
                String normalized = cleanPowerPointText(text);
                if (!normalized.isEmpty()) {
                    log.info("PPT 文本提取完成: {}, 文本长度: {}", fileName, normalized.length());
                    return normalized;
                }
            }
            if (lowerName.endsWith(".pptx")) {
                String pptxText = extractPptxTextBySlides(fileBytes);
                if (pptxText != null && !pptxText.isBlank()) {
                    log.info("PPTX 文本提取完成(逐页提取): {}, 文本长度: {}", fileName, pptxText.length());
                    String ocrText = extractPowerPointTextBySlideOcr(fileBytes, fileName, 80, 1280);
                    return mergeTextBlocks(pptxText, ocrText);
                }
            }
            String ocrOnlyText = extractPowerPointTextBySlideOcr(fileBytes, fileName, 80, 1280);
            if (ocrOnlyText != null && !ocrOnlyText.isBlank()) {
                log.info("PPT 文本提取完成(OCR): {}, 文本长度: {}", fileName, ocrOnlyText.length());
                return ocrOnlyText;
            }
            ApacheTikaDocumentParser parser = new ApacheTikaDocumentParser();
            Document document = parser.parse(new ByteArrayInputStream(fileBytes));
            String tikaText = document == null ? null : document.text();
            String normalized = cleanPowerPointText(tikaText);
            if (!normalized.isEmpty() && !isLikelyPptTemplateNoise(normalized)) {
                log.info("PPT 文本提取完成(Tika 兜底): {}, 文本长度: {}", fileName, normalized.length());
                String ocrText = extractPowerPointTextBySlideOcr(fileBytes, fileName, 80, 1280);
                return mergeTextBlocks(normalized, ocrText);
            }
            return null;
        } catch (Exception e) {
            log.warn("PPT 文本提取失败: {}", fileName, e);
            return null;
        }
    }

    private String extractPptxTextBySlides(byte[] fileBytes) {
        try (XMLSlideShow slideshow = new XMLSlideShow(new ByteArrayInputStream(fileBytes))) {
            StringBuilder all = new StringBuilder();
            int page = 1;
            for (XSLFSlide slide : slideshow.getSlides()) {
                StringBuilder pageText = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String value = cleanPowerPointText(textShape.getText());
                        if (value.isEmpty() || isPptPlaceholderText(value)) continue;
                        if (!pageText.isEmpty()) pageText.append("\n");
                        pageText.append(value);
                        continue;
                    }
                    if (shape instanceof XSLFPictureShape pictureShape) {
                        try {
                            byte[] pictureBytes = pictureShape.getPictureData() == null ? null : pictureShape.getPictureData().getData();
                            if (pictureBytes == null || pictureBytes.length == 0) continue;
                            String ocrText = extractImageText(pictureBytes, "pptx-slide-" + page + "-image");
                            String value = cleanPowerPointText(ocrText);
                            if (value.isEmpty()) continue;
                            if (!pageText.isEmpty()) pageText.append("\n");
                            pageText.append(value);
                        } catch (Exception ignored) {
                        }
                    }
                }
                String normalizedPage = pageText.toString().trim();
                if (!normalizedPage.isEmpty()) {
                    if (!all.isEmpty()) all.append("\n\n");
                    all.append("第").append(page).append("页").append("\n").append(normalizedPage);
                }
                page++;
            }
            String normalized = all.toString().trim();
            return normalized.isEmpty() ? null : normalized;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractPowerPointTextBySlideOcr(byte[] fileBytes, String fileName, int maxSlides, int width) {
        if (!ocrEnabled) return null;
        int total = countPowerPointSlides(fileBytes, fileName);
        int limit = Math.min(Math.max(maxSlides, 1), total);
        if (limit <= 0) return null;
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            try {
                byte[] imageBytes = renderPowerPointSlideImage(fileBytes, fileName, i, width);
                if (imageBytes == null || imageBytes.length == 0) continue;
                String ocrText = extractImageText(imageBytes, fileName + "#slide-" + (i + 1) + ".jpg");
                String value = cleanPowerPointText(ocrText);
                if (value.isEmpty()) continue;
                if (!all.isEmpty()) all.append("\n\n");
                all.append("第").append(i + 1).append("页").append("\n").append(value);
            } catch (Exception ignored) {
            }
        }
        String result = all.toString().trim();
        return result.isEmpty() ? null : result;
    }

    public int countPowerPointSlides(byte[] fileBytes, String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (fileBytes == null || fileBytes.length == 0) {
            log.error("PPT 解析失败: 文件字节流为空: {}", fileName);
            return 0;
        }
        if (lower.endsWith(".pptx")) {
            try (XMLSlideShow xslf = new XMLSlideShow(new ByteArrayInputStream(fileBytes))) {
                int size = xslf.getSlides().size();
                log.info("PPTX 解析成功: {}, 页数: {}", fileName, size);
                return size;
            } catch (Throwable t) {
                log.warn("PPTX 解析失败，尝试其他解析器: {}, 错误类型: {}, 消息: {}", fileName, t.getClass().getName(), t.getMessage());
            }
        }
        if (lower.endsWith(".ppt")) {
            try (HSLFSlideShow hslf = new HSLFSlideShow(new ByteArrayInputStream(fileBytes))) {
                int size = hslf.getSlides().size();
                log.info("PPT 解析成功: {}, 页数: {}", fileName, size);
                return size;
            } catch (Throwable t) {
                log.warn("PPT 解析失败，尝试通用解析器: {}, 错误类型: {}, 消息: {}", fileName, t.getClass().getName(), t.getMessage());
            }
        }
        try (SlideShow<?, ?> slideShow = SlideShowFactory.create(new ByteArrayInputStream(fileBytes))) {
            int size = slideShow.getSlides().size();
            log.info("PPT (SlideShowFactory) 解析成功: {}, 页数: {}", fileName, size);
            return size;
        } catch (Throwable t) {
            log.error("PPT 幻灯片总页数读取失败: {}, 错误类型: {}, 消息: {}", fileName, t.getClass().getName(), t.getMessage(), t);
            return 0;
        }
    }

    public byte[] renderPowerPointSlideImage(byte[] fileBytes, String fileName, int slideIndex, int targetWidth) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (fileBytes == null || fileBytes.length == 0) {
            return null;
        }
        if (lower.endsWith(".pptx")) {
            try (XMLSlideShow xslf = new XMLSlideShow(new ByteArrayInputStream(fileBytes))) {
                if (slideIndex < 0 || slideIndex >= xslf.getSlides().size()) return null;
                return renderSlideImageBytes(xslf.getSlides().get(slideIndex), xslf.getPageSize(), targetWidth);
            } catch (Throwable t) {
                log.warn("PPTX 渲染失败，尝试其他解析器: {}, slideIndex={}, 错误类型: {}, 消息: {}", fileName, slideIndex, t.getClass().getName(), t.getMessage());
            }
        }
        if (lower.endsWith(".ppt")) {
            try (HSLFSlideShow hslf = new HSLFSlideShow(new ByteArrayInputStream(fileBytes))) {
                if (slideIndex < 0 || slideIndex >= hslf.getSlides().size()) return null;
                return renderSlideImageBytes(hslf.getSlides().get(slideIndex), hslf.getPageSize(), targetWidth);
            } catch (Throwable t) {
                log.warn("PPT 渲染失败，尝试通用解析器: {}, slideIndex={}, 错误类型: {}, 消息: {}", fileName, slideIndex, t.getClass().getName(), t.getMessage());
            }
        }
        try (SlideShow<?, ?> slideShow = SlideShowFactory.create(new ByteArrayInputStream(fileBytes))) {
            if (slideIndex < 0 || slideIndex >= slideShow.getSlides().size()) return null;
            return renderSlideImageBytes(slideShow.getSlides().get(slideIndex), slideShow.getPageSize(), targetWidth);
        } catch (Throwable t) {
            log.warn("PPT 幻灯片图片渲染失败: {}, slideIndex={}, 错误类型: {}, 消息: {}", fileName, slideIndex, t.getClass().getName(), t.getMessage(), t);
            return null;
        }
    }

    private byte[] renderSlideImageBytes(Slide<?, ?> slide, java.awt.Dimension pageSize, int targetWidth) throws Exception {
        if (pageSize == null || pageSize.width <= 0 || pageSize.height <= 0) return null;
        double scale = targetWidth * 1.0 / pageSize.width;
        int width = Math.max((int) (pageSize.width * scale), 1);
        int height = Math.max((int) (pageSize.height * scale), 1);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.scale(scale, scale);
        slide.draw(graphics);
        graphics.dispose();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean written = ImageIO.write(image, "jpg", output);
        if (!written) {
            output.reset();
            ImageIO.write(image, "png", output);
        }
        return output.toByteArray();
    }

    private String cleanPowerPointText(String text) {
        if (text == null) return "";
        String[] lines = text.replace("\u0000", "").split("\\r?\\n");
        StringBuilder out = new StringBuilder();
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) continue;
            if (line.matches("(?i)^ppt/.*\\.xml$")) continue;
            if (line.matches("(?i)^_rels/.*")) continue;
            if (line.matches("(?i)^docprops/.*")) continue;
            if (isPptPlaceholderText(line)) continue;
            if (!out.isEmpty()) out.append("\n");
            out.append(line);
        }
        return out.toString().trim();
    }

    private boolean isPptPlaceholderText(String value) {
        String line = value == null ? "" : value.trim();
        if (line.isEmpty()) return true;
        return line.contains("单击此处编辑母版") ||
                line.contains("单击此处编辑标题") ||
                line.contains("单击此处编辑文本") ||
                line.equalsIgnoreCase("Click to edit Master title style") ||
                line.equalsIgnoreCase("Click to edit Master text styles");
    }

    private boolean isLikelyPptTemplateNoise(String text) {
        if (text == null || text.isBlank()) return false;
        if (text.contains("ppt/slideLayouts/slideLayout")) return true;
        String[] lines = text.split("\\r?\\n");
        if (lines.length == 0) return false;
        int noisy = 0;
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.matches("(?i)^ppt/.*\\.xml$") || line.matches("(?i)^_rels/.*") || line.matches("(?i)^docprops/.*")) {
                noisy++;
            }
        }
        return noisy * 1.0 / lines.length > 0.2;
    }

    private String mergeTextBlocks(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        if (left.isEmpty()) return right.isEmpty() ? null : right;
        if (right.isEmpty()) return left;
        if (left.contains(right)) return left;
        if (right.contains(left)) return right;
        return left + "\n\n" + right;
    }

    public String extractImageText(byte[] bytes, String fileName) {
        if (!ocrEnabled) return "";
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) return "";
            
            Tesseract tesseract = new Tesseract();
            String resolvedTessdataPath = resolveTessdataPath();
            
            if (resolvedTessdataPath != null) {
                tesseract.setDatapath(resolvedTessdataPath);
            } else {
                log.warn("OCR 未找到可用 tessdata 目录，跳过本次 OCR: fileName={}, configPath={}", fileName, tessdataPath);
                return "";
            }

            List<String> availableLanguages = resolveAvailableOcrLanguages(resolvedTessdataPath);
            if (availableLanguages.isEmpty()) {
                log.warn("OCR 可用语言为空，跳过本次 OCR: fileName={}", fileName);
                return "";
            }
            try {
                tesseract.setTessVariable("debug_file", "NUL");
            } catch (Throwable ignored) {
            }
            tesseract.setTessVariable("user_defined_dpi", "300");
            tesseract.setTessVariable("preserve_interword_spaces", "1");
            tesseract.setTessVariable("textord_tabfind_vertical_text", "0");
            BufferedImage enhanced = preprocessForOcr(image);
            BufferedImage binary = toBinaryImage(enhanced);
            BufferedImage invertedBinary = shouldUseInvertedCandidate(enhanced) ? invertBinaryImage(binary) : null;
            List<String> languageCandidates = buildOcrLanguageCandidates(availableLanguages);
            String bestText = "";
            int bestScore = Integer.MIN_VALUE;
            for (String languageCandidate : languageCandidates) {
                try {
                    tesseract.setLanguage(languageCandidate);
                } catch (Throwable ignored) {
                    continue;
                }
                if (!languageCandidate.contains("chi") && containsCjk(fileName)) {
                    continue;
                }
                OcrResult result = pickBestOcrText(tesseract, image, enhanced, binary, invertedBinary);
                if (result.score() > bestScore) {
                    bestScore = result.score();
                    bestText = result.text();
                }
            }
            if (bestText.isBlank()) {
                return "";
            }
            String normalized = bestText;
            normalized = sanitizeHonorExtractedText(normalized);
            if (!normalized.isEmpty()) {
                log.info("图片 OCR 完成: {}, 文本长度: {}", fileName, normalized.length());
            }
            return normalized;
        } catch (Throwable e) {
            log.warn("图片 OCR 失败: {}. 原因: {}. OCR 已自动降级，不影响上传。", fileName, e.getMessage());
            return "";
        }
    }

    private String resolveTessdataPath() {
        List<String> candidates = new ArrayList<>();
        String envTessdataPrefix = System.getenv("TESSDATA_PREFIX");
        if (envTessdataPrefix != null && !envTessdataPrefix.isBlank()) {
            String normalized = normalizePathCandidate(envTessdataPrefix);
            candidates.add(normalized);
            candidates.add(new File(normalized, "tessdata").getAbsolutePath());
        }
        if (tessdataPath != null && !tessdataPath.isBlank()) {
            String normalized = normalizePathCandidate(tessdataPath);
            candidates.add(normalized);
            candidates.add(new File(normalized, "tessdata").getAbsolutePath());
            File configured = new File(normalized);
            if (!configured.isAbsolute()) {
                candidates.add(new File(System.getProperty("user.dir"), normalized).getAbsolutePath());
                candidates.add(new File(new File(System.getProperty("user.dir"), normalized), "tessdata").getAbsolutePath());
            }
        }
        candidates.add(new File(System.getProperty("user.dir"), "tessdata").getAbsolutePath());
        File parent = new File(System.getProperty("user.dir")).getParentFile();
        if (parent != null) {
            candidates.add(new File(parent, "tessdata").getAbsolutePath());
        }
        candidates.add(new File(System.getProperty("user.dir"), "_bmad-output\\tessdata").getAbsolutePath());
        candidates.add("E:\\Tesseract\\tessdata");

        for (String candidate : candidates) {
            File dir = new File(candidate);
            if (!dir.exists() || !dir.isDirectory()) continue;
            if (ocrLanguage == null || ocrLanguage.isBlank()) {
                if (hasValidTrainedData(dir.getAbsolutePath(), "eng") || hasValidTrainedData(dir.getAbsolutePath(), "chi_sim")) {
                    return dir.getAbsolutePath();
                }
                continue;
            }
            for (String language : parseConfiguredOcrLanguages()) {
                if (hasValidTrainedData(dir.getAbsolutePath(), language)) {
                    return dir.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private String normalizePathCandidate(String path) {
        if (path == null) return "";
        String trimmed = path.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 1) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private List<String> parseConfiguredOcrLanguages() {
        List<String> languages = new ArrayList<>();
        String[] raw = (ocrLanguage == null ? "" : ocrLanguage).split("\\+");
        for (String language : raw) {
            String trimmed = language == null ? "" : language.trim();
            if (trimmed.isBlank()) continue;
            if (trimmed.endsWith("_vert")) {
                String fallback = trimmed.substring(0, trimmed.length() - "_vert".length());
                if (!fallback.isBlank()) {
                    languages.add(fallback);
                }
                continue;
            }
            languages.add(trimmed);
        }
        if (languages.isEmpty()) {
            languages.add("chi_sim");
            languages.add("eng");
        }
        return new ArrayList<>(new LinkedHashSet<>(languages));
    }

    private List<String> resolveAvailableOcrLanguages(String tessdataDir) {
        List<String> configured = parseConfiguredOcrLanguages();
        LinkedHashSet<String> available = new LinkedHashSet<>();
        for (String language : configured) {
            if (hasValidTrainedData(tessdataDir, language)) {
                available.add(language);
                continue;
            }
            if (language.endsWith("_vert")) {
                String fallback = language.substring(0, language.length() - "_vert".length());
                if (hasValidTrainedData(tessdataDir, fallback)) {
                    log.warn("OCR 语言 {} 不可用，自动降级为 {}", language, fallback);
                    available.add(fallback);
                } else {
                    log.warn("OCR 训练文件异常: {}", new File(tessdataDir, language + ".traineddata").getAbsolutePath());
                }
            } else {
                log.warn("OCR 训练文件异常: {}", new File(tessdataDir, language + ".traineddata").getAbsolutePath());
            }
        }
        if (available.isEmpty()) {
            if (hasValidTrainedData(tessdataDir, "chi_sim")) available.add("chi_sim");
            if (hasValidTrainedData(tessdataDir, "eng")) available.add("eng");
        }
        return new ArrayList<>(available);
    }

    private List<String> buildOcrLanguageCandidates(List<String> availableLanguages) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (availableLanguages != null && !availableLanguages.isEmpty()) {
            candidates.add(String.join("+", availableLanguages));
            if (availableLanguages.contains("chi_sim") && availableLanguages.contains("eng")) {
                candidates.add("chi_sim+eng");
            }
            if (availableLanguages.contains("eng")) {
                candidates.add("eng");
            }
            if (availableLanguages.contains("chi_sim")) {
                candidates.add("chi_sim");
            }
            candidates.addAll(availableLanguages);
        }
        return new ArrayList<>(candidates);
    }

    private boolean hasValidTrainedData(String tessdataDir, String language) {
        if (tessdataDir == null || tessdataDir.isBlank() || language == null || language.isBlank()) return false;
        File trainedData = new File(tessdataDir, language + ".traineddata");
        return trainedData.exists() && trainedData.length() >= 256 * 1024;
    }

    private OcrResult pickBestOcrText(Tesseract tesseract, BufferedImage original, BufferedImage enhanced, BufferedImage binary, BufferedImage invertedBinary) {
        String best = "";
        int bestScore = Integer.MIN_VALUE;
        int[] psmModes = new int[] {6, 4, 11, 3};
        BufferedImage[] candidates = new BufferedImage[] {enhanced, binary, invertedBinary, original};
        for (BufferedImage candidate : candidates) {
            if (candidate == null) continue;
            for (int psm : psmModes) {
                String text = runOcr(tesseract, candidate, psm);
                int score = scoreOcrText(text);
                if (score > bestScore) {
                    bestScore = score;
                    best = text;
                }
            }
        }
        if (best == null || best.isBlank()) return new OcrResult("", Integer.MIN_VALUE);
        if (bestScore < 25 || isLikelyGarbledOcr(best)) {
            return new OcrResult("", bestScore);
        }
        return new OcrResult(best, bestScore);
    }

    private String runOcr(Tesseract tesseract, BufferedImage image, int psmMode) {
        try {
            tesseract.setPageSegMode(psmMode);
            String text = tesseract.doOCR(image);
            return normalizeOcrText(text);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String normalizeOcrText(String text) {
        if (text == null) return "";
        String normalized = text.replace("\u0000", "")
                .replace('\u3000', ' ')
                .replaceAll("[\\t\\f\\r]+", " ")
                .replaceAll(" +", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return normalized;
    }

    private String sanitizeHonorExtractedText(String text) {
        if (text == null || text.isBlank()) return "";
        String normalized = normalizeOcrText(text);
        String[] lines = normalized.split("\\n");
        Set<String> kept = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank()) continue;
            if (isLikelyGarbledOcr(trimmed)) continue;
            kept.add(trimmed);
        }
        String merged = String.join("\n", kept).trim();
        if (isLikelyGarbledOcr(merged)) {
            return "";
        }
        return merged;
    }

    private boolean isLikelyGarbledOcr(String text) {
        if (text == null || text.isBlank()) return true;
        String normalized = text.replaceAll("\\s+", "");
        if (normalized.length() < 4) return true;
        if (OCR_NOISE_SEQUENCE.matcher(normalized).find()) return true;
        int wordChars = 0;
        int cjkChars = 0;
        int noiseChars = 0;
        int total = 0;
        for (char c : normalized.toCharArray()) {
            total++;
            if (OCR_WORD_CHAR.matcher(String.valueOf(c)).find()) {
                wordChars++;
                if (OCR_CJK_CHAR.matcher(String.valueOf(c)).find()) {
                    cjkChars++;
                }
            } else if ("，。！？；：、“”‘’（）()《》【】[]-—·.,!?;:'\"@#%&*+=_".indexOf(c) < 0) {
                noiseChars++;
            }
        }
        double wordRatio = (double) wordChars / Math.max(total, 1);
        double noiseRatio = (double) noiseChars / Math.max(total, 1);
        int diacriticCount = countDiacriticMarks(normalized);
        int latinExtendedCount = countLatinExtendedLetters(normalized);
        double diacriticRatio = (double) diacriticCount / Math.max(total, 1);
        boolean hasMeaningfulLength = wordChars >= 8 || cjkChars >= 4;
        if (!hasMeaningfulLength) return true;
        if (wordRatio < 0.35) return true;
        if (cjkChars < 2 && (diacriticRatio > 0.08 || latinExtendedCount >= 6)) return true;
        return noiseRatio > 0.28;
    }

    private boolean containsCjk(String text) {
        if (text == null || text.isBlank()) return false;
        return OCR_CJK_CHAR.matcher(text).find();
    }

    private int scoreOcrText(String text) {
        if (text == null || text.isBlank()) return Integer.MIN_VALUE / 2;
        int cjk = 0;
        int alphaNum = 0;
        int punct = 0;
        int noise = 0;
        int repeatPenalty = 0;
        char prev = 0;
        int streak = 0;
        for (char c : text.toCharArray()) {
            if (c == '\n' || c == '\r' || c == ' ') {
                punct++;
                continue;
            }
            if (Character.isLetterOrDigit(c)) {
                alphaNum++;
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                    cjk++;
                }
            } else if ("，。！？；：、“”‘’（）()《》【】[]-—·.,!?;:'\"".indexOf(c) >= 0) {
                punct++;
            } else {
                noise++;
            }
            if (c == prev) {
                streak++;
                if (streak >= 5) repeatPenalty++;
            } else {
                prev = c;
                streak = 1;
            }
        }
        int len = text.length();
        int info = cjk * 4 + alphaNum * 2 + punct;
        int diacriticPenalty = countDiacriticMarks(text) * 6;
        int latinExtendedPenalty = countLatinExtendedLetters(text) * 4;
        int penalties = noise * 5 + repeatPenalty * 8 + diacriticPenalty + latinExtendedPenalty;
        int shortPenalty = len < 8 ? 20 : 0;
        return info + len - penalties - shortPenalty;
    }

    private int countDiacriticMarks(String text) {
        if (text == null || text.isBlank()) return 0;
        String nfd = Normalizer.normalize(text, Normalizer.Form.NFD);
        int count = 0;
        for (char c : nfd.toCharArray()) {
            if (OCR_COMBINING_MARK.matcher(String.valueOf(c)).matches()) {
                count++;
            }
        }
        return count;
    }

    private int countLatinExtendedLetters(String text) {
        if (text == null || text.isBlank()) return 0;
        int count = 0;
        for (char c : text.toCharArray()) {
            if (!Character.isLetter(c)) continue;
            if (Character.UnicodeScript.of(c) != Character.UnicodeScript.LATIN) continue;
            if (c > 127) {
                count++;
            }
        }
        return count;
    }

    private BufferedImage preprocessForOcr(BufferedImage source) {
        int width = Math.max(source.getWidth(), 1);
        int height = Math.max(source.getHeight(), 1);
        int targetWidth = Math.min(width * 2, 6000);
        int targetHeight = Math.min(height * 2, 6000);

        BufferedImage gray = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return stretchGrayLevels(gray);
    }

    private BufferedImage stretchGrayLevels(BufferedImage gray) {
        if (gray == null) return null;
        int width = gray.getWidth();
        int height = gray.getHeight();
        int[] histogram = new int[256];
        int total = Math.max(width * height, 1);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = gray.getRaster().getSample(x, y, 0);
                histogram[Math.max(0, Math.min(255, v))]++;
            }
        }
        int lowTarget = (int) (total * 0.02);
        int highTarget = (int) (total * 0.98);
        int low = 0;
        int high = 255;
        int cumulative = 0;
        for (int i = 0; i < 256; i++) {
            cumulative += histogram[i];
            if (cumulative >= lowTarget) {
                low = i;
                break;
            }
        }
        cumulative = 0;
        for (int i = 0; i < 256; i++) {
            cumulative += histogram[i];
            if (cumulative >= highTarget) {
                high = i;
                break;
            }
        }
        if (high <= low + 8) {
            return gray;
        }
        BufferedImage stretched = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = gray.getRaster().getSample(x, y, 0);
                int nv = (v - low) * 255 / Math.max(1, high - low);
                nv = Math.max(0, Math.min(255, nv));
                stretched.getRaster().setSample(x, y, 0, nv);
            }
        }
        return stretched;
    }

    private BufferedImage toBinaryImage(BufferedImage gray) {
        if (gray == null) return null;
        int width = gray.getWidth();
        int height = gray.getHeight();
        int[] histogram = new int[256];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = gray.getRaster().getSample(x, y, 0);
                histogram[Math.max(0, Math.min(255, v))]++;
            }
        }
        int threshold = otsuThreshold(histogram, width * height);
        threshold = Math.max(70, Math.min(205, threshold));
        BufferedImage binary = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = gray.getRaster().getSample(x, y, 0);
                int rgb = v >= threshold ? 0xFFFFFF : 0x000000;
                binary.setRGB(x, y, rgb);
            }
        }
        return binary;
    }

    private int otsuThreshold(int[] histogram, int totalPixels) {
        if (histogram == null || histogram.length != 256 || totalPixels <= 0) {
            return 128;
        }
        long sum = 0;
        for (int t = 0; t < 256; t++) {
            sum += (long) t * histogram[t];
        }
        long sumBackground = 0;
        int weightBackground = 0;
        double maxVariance = -1;
        int threshold = 128;
        for (int t = 0; t < 256; t++) {
            weightBackground += histogram[t];
            if (weightBackground == 0) continue;
            int weightForeground = totalPixels - weightBackground;
            if (weightForeground == 0) break;
            sumBackground += (long) t * histogram[t];
            double meanBackground = (double) sumBackground / weightBackground;
            double meanForeground = (double) (sum - sumBackground) / weightForeground;
            double betweenVariance = (double) weightBackground * weightForeground * (meanBackground - meanForeground) * (meanBackground - meanForeground);
            if (betweenVariance > maxVariance) {
                maxVariance = betweenVariance;
                threshold = t;
            }
        }
        return threshold;
    }

    private boolean shouldUseInvertedCandidate(BufferedImage gray) {
        if (gray == null) return false;
        int width = gray.getWidth();
        int height = gray.getHeight();
        if (width <= 0 || height <= 0) return false;
        long sum = 0;
        int stepX = Math.max(1, width / 320);
        int stepY = Math.max(1, height / 320);
        int count = 0;
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                sum += gray.getRaster().getSample(x, y, 0);
                count++;
            }
        }
        double mean = (double) sum / Math.max(1, count);
        return mean < 118;
    }

    private BufferedImage invertBinaryImage(BufferedImage binary) {
        if (binary == null) return null;
        int width = binary.getWidth();
        int height = binary.getHeight();
        BufferedImage inverted = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = binary.getRGB(x, y) & 0x00FFFFFF;
                inverted.setRGB(x, y, rgb == 0x000000 ? 0xFFFFFFFF : 0xFF000000);
            }
        }
        return inverted;
    }

    public String normalize(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    public String normalizeRole(String role) {
        if (role == null) return "student";
        String r = role.trim().toLowerCase();
        return r.isEmpty() ? "student" : r;
    }

    public boolean isManagerRole(String role) {
        return ROLE_TEACHER.equalsIgnoreCase(role) || ROLE_ADMIN.equalsIgnoreCase(role);
    }

    public boolean hasPrivateAccess(String requesterId, String requesterRole, String requestedUserId) {
        return requesterId != null && requesterId.equals(requestedUserId);
    }

    public boolean canAccessObject(String objectName, String requesterId, String requesterRole) {
        if (objectName == null || objectName.isBlank()) return false;
        if (!objectName.startsWith("private/")) return true;
        String ownerId = extractOwnerId(objectName);
        return ownerId != null && ownerId.equals(requesterId);
    }

    public boolean isHonorObject(String objectName) {
        if (objectName == null || objectName.isBlank()) return false;
        return objectName.startsWith("public/honor/") || objectName.contains("/honor/");
    }

    public String extractOwnerId(String objectName) {
        if (objectName == null || !objectName.startsWith("private/")) return null;
        String[] parts = objectName.split("/");
        if (parts.length < 3) return null;
        return parts[1];
    }
}
