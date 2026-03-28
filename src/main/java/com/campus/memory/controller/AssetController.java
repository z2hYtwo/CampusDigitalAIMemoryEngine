package com.campus.memory.controller;

import com.campus.memory.service.AssetService;
import com.campus.memory.service.MemoryService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.messages.Item;
import io.minio.errors.ErrorResponseException;
import net.sourceforge.tess4j.Tesseract;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.SlideShowFactory;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

@RestController
@RequestMapping("/api/asset")
@RequiredArgsConstructor
@Slf4j
public class AssetController {

    private final MinioClient minioClient;
    private final MemoryService memoryService;
    private final AssetService assetService;

    @Value("${minio.bucket-name}")
    private String bucketName;
    @Value("${ocr.enabled:true}")
    private boolean ocrEnabled;
    @Value("${ocr.language:chi_sim}")
    private String ocrLanguage;
    @Value("${ocr.tessdata-path:}")
    private String tessdataPath;

    /**
     * 物理终端扫描回调 (反向入库)
     * 模拟扫描仪扫完文档后自动上传并触发 AI 深度理解与分类
     */
    @PostMapping("/physical/scan-callback")
    public Map<String, Object> scanCallback(@RequestParam(name = "file") MultipartFile file,
                                           @RequestParam(name = "deviceId", defaultValue = "SCANNER-001") String deviceId,
                                           @RequestParam(name = "honorLevel", required = false) String honorLevel,
                                           @RequestParam(name = "honorCategory", required = false) String honorCategory,
                                           @RequestParam(name = "honorYear", required = false) String honorYear) {
        if (file == null || file.isEmpty()) {
            return Map.of("status", "error", "message", "未收到扫描文件");
        }

        log.info("接收到来自物理终端 [{}] 的扫描任务: {}", deviceId, file.getOriginalFilename());

        try {
            // 构造可选的荣誉元数据
            Map<String, Object> honorMetadata = null;
            if (honorLevel != null || honorCategory != null || honorYear != null) {
                honorMetadata = new java.util.HashMap<>();
                if (honorLevel != null) honorMetadata.put("honorLevel", honorLevel);
                if (honorCategory != null) honorMetadata.put("honorCategory", honorCategory);
                if (honorYear != null) honorMetadata.put("honorYear", honorYear);
            }

            // 物理扫描默认为公共资产 (official)
            String result = assetService.processAsset(
                    file.getBytes(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    "all",    // uploadRole
                    "SYSTEM", // effectiveUserId
                    "auto",   // category
                    "official", // sourceType
                    "由物理终端 " + deviceId + " 扫描入库", // description
                    honorMetadata,
                    assetService.new DefaultAssetProcessor()
            );
            return Map.of("status", "success", "message", result);
        } catch (Exception e) {
            log.error("扫描入库处理失败", e);
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_TEACHER = "teacher";
    private static final Pattern OCR_NOISE_SEQUENCE = Pattern.compile("([`~^_=\\-|/\\\\·•.,:;!?])\\1{3,}");
    private static final Pattern OCR_WORD_CHAR = Pattern.compile("[\\p{IsHan}A-Za-z0-9]");
    private static final Pattern OCR_CJK_CHAR = Pattern.compile("[\\p{IsHan}]");
    private final java.util.concurrent.Semaphore slideRenderSemaphore = new java.util.concurrent.Semaphore(1);

    /**
     * 获取资产列表 (支持按 userId 过滤私有资产)
     */
    @GetMapping("/list")
    public List<Map<String, String>> listAssets(@RequestParam(name = "userId", required = false) String userId,
                                               @RequestParam(name = "role", defaultValue = "all") String role,
                                               @RequestHeader(name = "X-User-Id", required = false) String requesterId,
                                               @RequestHeader(name = "X-User-Role", required = false) String requesterRole) {
        log.info("查询资产列表: requestUserId={}, role={}, requesterId={}, requesterRole={}", userId, role, requesterId, requesterRole);
        List<Map<String, String>> assets = new ArrayList<>();
        
        try {
            String effectiveRequesterId = normalize(requesterId);
            String effectiveRequesterRole = normalizeRole(requesterRole);
            String requestedUserId = normalize(userId);

            if ("private".equalsIgnoreCase(role) && !hasPrivateAccess(effectiveRequesterId, effectiveRequesterRole, requestedUserId)) {
                return assets;
            }

            String targetUserId = (effectiveRequesterId != null && !effectiveRequesterId.isBlank())
                ? effectiveRequesterId
                : requestedUserId;
            String prefix = ("private".equalsIgnoreCase(role) && targetUserId != null && !targetUserId.isBlank())
                    ? "private/" + targetUserId + "/"
                    : "";

            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucketName)
                            .prefix(prefix)
                            .recursive(true)
                            .build()
            );

            for (Result<Item> result : results) {
                Item item = result.get();
                if (item.isDir()) continue;
                processItem(item, assets);
            }

            if ("private".equalsIgnoreCase(role) && assets.isEmpty()) {
                Iterable<Result<Item>> rootResults = minioClient.listObjects(
                        ListObjectsArgs.builder()
                                .bucket(bucketName)
                                .recursive(false)
                                .build()
                );
                for (Result<Item> result : rootResults) {
                    Item item = result.get();
                    if (item.isDir()) continue;
                    if (targetUserId != null && item.objectName().contains(targetUserId)) {
                        processItem(item, assets);
                    }
                }
            }
        } catch (Exception e) {
            log.error("获取资产列表失败", e);
        }
        return assets;
    }

    private void processItem(Item item, List<Map<String, String>> assets) {
        String objectName = item.objectName();
        String fileName = objectName.contains("-") ? 
                objectName.substring(objectName.lastIndexOf("-") + 1) : 
                objectName;
        
        String uploadTime = item.lastModified().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        long size = item.size();
        String sizeStr = size < 1024 ? size + "B" : (size < 1024 * 1024 ? (size / 1024) + "KB" : String.format("%.1fMB", size / (1024.0 * 1024.0)));

        Map<String, String> asset = new java.util.HashMap<>();
        asset.put("fileName", fileName);
        asset.put("objectName", objectName);
        asset.put("uploadTime", uploadTime);
        asset.put("size", sizeStr);
        asset.put("sourceType", objectName.startsWith("private/") ? "private" : "official");
        asset.put("category", resolveAssetCategory(objectName, fileName));
        assets.add(asset);
    }

    private String resolveAssetCategory(String objectName, String fileName) {
        try {
            var stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );
            Map<String, String> userMetadata = stat.userMetadata();
            if (userMetadata != null && !userMetadata.isEmpty()) {
                String category = userMetadata.get("category");
                if (category == null || category.isBlank()) {
                    category = userMetadata.get("x-amz-meta-category");
                }
                if (category != null && !category.isBlank()) {
                    return category;
                }
            }
        } catch (Exception e) {
            log.debug("读取资产分类元数据失败: {}", objectName, e);
        }
        return inferCategory("auto", fileName, null, null);
    }

    /**
     * 删除资产
     */
    @DeleteMapping("/delete")
    public ResponseEntity<String> deleteAsset(@RequestParam(name = "objectName") String objectName,
                                              @RequestHeader(name = "X-User-Id", required = false) String requesterId,
                                              @RequestHeader(name = "X-User-Role", required = false) String requesterRole) {
        String effectiveObjectName = decodeObjectName(objectName);
        String effectiveRequesterId = normalize(requesterId);
        String effectiveRequesterRole = normalizeRole(requesterRole);
        boolean honorObject = isHonorObject(effectiveObjectName);
        if (honorObject && !ROLE_ADMIN.equalsIgnoreCase(effectiveRequesterRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("无权限删除荣誉文件，仅管理员可操作");
        }
        if (!canAccessObject(effectiveObjectName, effectiveRequesterId, effectiveRequesterRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("无权限删除该文件");
        }
        try {
            if (honorObject) {
                memoryService.markHonorDeleted(effectiveObjectName);
            }
            try {
                minioClient.removeObject(
                        io.minio.RemoveObjectArgs.builder()
                                .bucket(bucketName)
                                .object(effectiveObjectName)
                                .build()
                );
            } catch (Exception e) {
                if (!isObjectNotFoundError(e)) {
                    throw e;
                }
            }
            return ResponseEntity.ok(honorObject ? "荣誉信息删除成功" : "删除成功");
        } catch (Exception e) {
            log.error("删除资产失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("删除失败: " + e.getMessage());
        }
    }

    /**
     * 下载/查看原始校史文档
     */
    @GetMapping({"/download/{objectName:.+}", "/view"})
    public ResponseEntity<InputStreamResource> downloadAsset(
            @PathVariable(name = "objectName", required = false) String pathObjectName,
            @RequestParam(name = "objectName", required = false) String queryObjectName,
            @RequestParam(name = "userId", required = false) String requestUserId,
            @RequestParam(name = "role", required = false) String requestRole,
            @RequestHeader(name = "X-User-Id", required = false) String requesterId,
            @RequestHeader(name = "X-User-Role", required = false) String requesterRole) {
        
        String objectName = (pathObjectName != null) ? pathObjectName : queryObjectName;
        
        if (objectName == null || objectName.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        log.info("收到文件查看/下载请求: {}", objectName);
        try {
            String decodedObjectName = decodeObjectName(objectName);
            String effectiveRequesterId = normalize(requesterId);
            if (effectiveRequesterId == null || effectiveRequesterId.isBlank()) {
                effectiveRequesterId = normalize(requestUserId);
            }
            String effectiveRequesterRole = normalizeRole(requesterRole);
            if ((requesterRole == null || requesterRole.isBlank()) && requestRole != null && !requestRole.isBlank()) {
                effectiveRequesterRole = normalizeRole(requestRole);
            }
            if (!canAccessObject(decodedObjectName, effectiveRequesterId, effectiveRequesterRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            log.info("处理后的 ObjectName: {}", decodedObjectName);

            InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(decodedObjectName)
                            .build()
            );
            
            // 尝试恢复原始文件名
            String fileName = decodedObjectName.contains("-") ? 
                    decodedObjectName.substring(decodedObjectName.lastIndexOf("-") + 1) : 
                    decodedObjectName;
            
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName)
                    .contentType(MediaType.parseMediaType(getContentType(fileName)))
                    .body(new InputStreamResource(stream));
                    
        } catch (Exception e) {
            log.error("文档读取失败: {}", objectName, e);
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/preview-text")
    public ResponseEntity<String> previewText(
            @RequestParam(name = "objectName") String objectName,
            @RequestParam(name = "userId", required = false) String requestUserId,
            @RequestParam(name = "role", required = false) String requestRole,
            @RequestHeader(name = "X-User-Id", required = false) String requesterId,
            @RequestHeader(name = "X-User-Role", required = false) String requesterRole) {
        if (objectName == null || objectName.isBlank()) {
            return ResponseEntity.badRequest().body("缺少 objectName");
        }
        try {
            String decodedObjectName = decodeObjectName(objectName);
            String effectiveRequesterId = normalize(requesterId);
            if (effectiveRequesterId == null || effectiveRequesterId.isBlank()) {
                effectiveRequesterId = normalize(requestUserId);
            }
            String effectiveRequesterRole = normalizeRole(requesterRole);
            if ((requesterRole == null || requesterRole.isBlank()) && requestRole != null && !requestRole.isBlank()) {
                effectiveRequesterRole = normalizeRole(requestRole);
            }
            if (!canAccessObject(decodedObjectName, effectiveRequesterId, effectiveRequesterRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("无权限访问该文件");
            }

            byte[] bytes;
            try (InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(decodedObjectName)
                            .build())) {
                bytes = stream.readAllBytes();
            }

            String fileName = decodedObjectName.contains("-")
                    ? decodedObjectName.substring(decodedObjectName.lastIndexOf("-") + 1)
                    : decodedObjectName;

            String text;
            if (isImage(fileName)) {
                text = extractImageText(bytes, fileName);
            } else {
                text = extractDocumentTextSafely(bytes, fileName);
            }
            if (text == null || text.trim().isEmpty()) {
                text = "未提取到可预览文本，请下载原文件查看。";
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .body(text);
        } catch (Exception e) {
            log.error("文本预览读取失败: {}", objectName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("预览失败: " + e.getMessage());
        }
    }

    @GetMapping("/preview-slides")
    public ResponseEntity<?> previewSlides(
            @RequestParam(name = "objectName") String objectName,
            @RequestParam(name = "userId", required = false) String requestUserId,
            @RequestParam(name = "role", required = false) String requestRole,
            @RequestParam(name = "maxSlides", required = false, defaultValue = "8") int maxSlides,
            @RequestParam(name = "width", required = false, defaultValue = "960") int width,
            @RequestHeader(name = "X-User-Id", required = false) String requesterId,
            @RequestHeader(name = "X-User-Role", required = false) String requesterRole) {
        if (objectName == null || objectName.isBlank()) {
            return ResponseEntity.badRequest().body("缺少 objectName");
        }
        try {
            log.info("幻灯片预览请求: objectName={}", objectName);
            String decodedObjectName = decodeObjectName(objectName);
            log.info("解码后的 ObjectName: {}", decodedObjectName);
            String effectiveRequesterId = normalize(requesterId);
            if (effectiveRequesterId == null || effectiveRequesterId.isBlank()) {
                effectiveRequesterId = normalize(requestUserId);
            }
            String effectiveRequesterRole = normalizeRole(requesterRole);
            if ((requesterRole == null || requesterRole.isBlank()) && requestRole != null && !requestRole.isBlank()) {
                effectiveRequesterRole = normalizeRole(requestRole);
            }
            if (!canAccessObject(decodedObjectName, effectiveRequesterId, effectiveRequesterRole)) {
                log.warn("权限不足: {}, userId={}, role={}", decodedObjectName, effectiveRequesterId, effectiveRequesterRole);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("无权限访问该文件");
            }

            String fileName = decodedObjectName.contains("-")
                    ? decodedObjectName.substring(decodedObjectName.lastIndexOf("-") + 1)
                    : decodedObjectName;
            String lower = fileName.toLowerCase();
            if (!(lower.endsWith(".ppt") || lower.endsWith(".pptx"))) {
                log.warn("不支持的文件格式: {}", fileName);
                return ResponseEntity.badRequest().body("仅支持 PPT/PPTX 预览");
            }

            byte[] bytes;
            try (InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(decodedObjectName)
                            .build())) {
                bytes = stream.readAllBytes();
            }
            log.info("从 MinIO 读取文件成功: {}, 字节大小: {}", decodedObjectName, bytes.length);

            int safeMaxSlides = Math.min(Math.max(maxSlides, 1), 20);
            int safeWidth = Math.min(Math.max(width, 480), 1280);
            int totalSlides = countPowerPointSlides(bytes, fileName);
            log.info("PPT 总页数: {}, fileName: {}", totalSlides, fileName);
            
            int availableSlides = Math.min(totalSlides, safeMaxSlides);
            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("fileName", fileName);
            body.put("count", totalSlides);
            body.put("total", totalSlides);
            body.put("initialCount", availableSlides);
            body.put("maxSlides", safeMaxSlides);
            body.put("width", safeWidth);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("幻灯片预览读取失败: {}", objectName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("预览失败: " + e.getMessage());
        }
    }

    @GetMapping("/preview-slide-image")
    public ResponseEntity<?> previewSlideImage(
            @RequestParam(name = "objectName") String objectName,
            @RequestParam(name = "slideIndex") int slideIndex,
            @RequestParam(name = "userId", required = false) String requestUserId,
            @RequestParam(name = "role", required = false) String requestRole,
            @RequestParam(name = "width", required = false, defaultValue = "960") int width,
            @RequestHeader(name = "X-User-Id", required = false) String requesterId,
            @RequestHeader(name = "X-User-Role", required = false) String requesterRole) {
        if (objectName == null || objectName.isBlank()) {
            return ResponseEntity.badRequest().body("缺少 objectName");
        }
        if (slideIndex < 0) {
            return ResponseEntity.badRequest().body("slideIndex 不能小于 0");
        }
        boolean acquired = false;
        try {
            String decodedObjectName = decodeObjectName(objectName);
            String effectiveRequesterId = normalize(requesterId);
            if (effectiveRequesterId == null || effectiveRequesterId.isBlank()) {
                effectiveRequesterId = normalize(requestUserId);
            }
            String effectiveRequesterRole = normalizeRole(requesterRole);
            if ((requesterRole == null || requesterRole.isBlank()) && requestRole != null && !requestRole.isBlank()) {
                effectiveRequesterRole = normalizeRole(requestRole);
            }
            if (!canAccessObject(decodedObjectName, effectiveRequesterId, effectiveRequesterRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("无权限访问该文件");
            }

            String fileName = decodedObjectName.contains("-")
                    ? decodedObjectName.substring(decodedObjectName.lastIndexOf("-") + 1)
                    : decodedObjectName;
            String lower = fileName.toLowerCase();
            if (!(lower.endsWith(".ppt") || lower.endsWith(".pptx"))) {
                return ResponseEntity.badRequest().body("仅支持 PPT/PPTX 预览");
            }

            acquired = slideRenderSemaphore.tryAcquire(30, TimeUnit.SECONDS);
            if (!acquired) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("预览任务繁忙，请稍后重试");
            }

            int safeWidth = Math.min(Math.max(width, 360), 960);
            byte[] bytes;
            try (InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(decodedObjectName)
                            .build())) {
                bytes = stream.readAllBytes();
            }
            byte[] imageBytes = renderPowerPointSlideImage(bytes, fileName, slideIndex, safeWidth);
            if ((imageBytes == null || imageBytes.length == 0) && safeWidth > 640) {
                imageBytes = renderPowerPointSlideImage(bytes, fileName, slideIndex, 640);
            }
            if ((imageBytes == null || imageBytes.length == 0) && safeWidth > 480) {
                imageBytes = renderPowerPointSlideImage(bytes, fileName, slideIndex, 480);
            }
            if (imageBytes == null || imageBytes.length == 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("未找到该页幻灯片");
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                    .body(imageBytes);
        } catch (Throwable e) {
            log.error("幻灯片图片预览读取失败: {}, slideIndex={}", objectName, slideIndex, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("预览失败: " + e.getMessage());
        } finally {
            if (acquired) {
                slideRenderSemaphore.release();
            }
        }
    }

    private String getContentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        return "text/plain";
    }

    /**
     * 上传资产并自动向量化文本内容 (支持角色权限与私有空间)
     */
    @PostMapping("/upload")
    public String uploadAsset(@RequestParam(name = "file") MultipartFile file,
                             @RequestParam(name = "role", defaultValue = "all") String role,
                             @RequestParam(name = "sourceType", defaultValue = "official") String sourceType,
                             @RequestParam(name = "category", defaultValue = "auto") String category,
                             @RequestParam(name = "description", required = false) String description,
                             @RequestParam(name = "userId", required = false) String userId,
                             @RequestHeader(name = "X-User-Id", required = false) String requesterId,
                             @RequestHeader(name = "X-User-Role", required = false) String requesterRole) {
        if (file == null || file.isEmpty()) {
            return "上传失败: 文件为空";
        }

        String effectiveRequesterId = normalize(requesterId);
        String effectiveRequesterRole = normalizeRole(requesterRole);
        String uploadRole = normalizeRole(role);
        String effectiveUserId = (effectiveRequesterId != null && !effectiveRequesterId.isBlank())
            ? effectiveRequesterId
            : normalize(userId);
        if ("private".equalsIgnoreCase(uploadRole) && (effectiveUserId == null || effectiveUserId.isBlank())) {
            return "上传失败: 私有文件缺少用户身份";
        }
        if (!"private".equalsIgnoreCase(uploadRole) && !isManagerRole(effectiveRequesterRole)) {
            return "上传失败: 仅教师或管理员可上传公共文档";
        }

        try {
            return assetService.processAsset(
                    file.getBytes(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    uploadRole,
                    effectiveUserId,
                    category,
                    sourceType,
                    description,
                    null,
                    assetService.new DefaultAssetProcessor()
            );
        } catch (Exception e) {
            log.error("资产处理失败", e);
            return "上传失败: " + e.getMessage();
        }
    }

    @PostMapping("/upload-honor")
    public Map<String, String> uploadHonorAsset(@RequestParam(name = "file") MultipartFile file,
                                                @RequestParam(name = "honorLevel") String honorLevel,
                                                @RequestParam(name = "honorCategory") String honorCategory,
                                                @RequestParam(name = "timestamp", required = false) String timestamp,
                                                @RequestParam(name = "description", required = false) String description,
                                                @RequestParam(name = "role", defaultValue = "all") String role,
                                                @RequestParam(name = "userId", required = false) String userId,
                                                @RequestHeader(name = "X-User-Id", required = false) String requesterId,
                                                @RequestHeader(name = "X-User-Role", required = false) String requesterRole) {
        if (file == null || file.isEmpty()) {
            return Map.of("message", "上传失败: 文件为空");
        }

        String effectiveRequesterId = normalize(requesterId);
        String effectiveRequesterRole = normalizeRole(requesterRole);
        String uploadRole = normalizeRole(role);
        if (!ROLE_ADMIN.equalsIgnoreCase(effectiveRequesterRole)) {
            return Map.of("message", "上传失败: 仅管理员可上传校园荣誉");
        }
        String effectiveUserId = (effectiveRequesterId != null && !effectiveRequesterId.isBlank())
                ? effectiveRequesterId
                : normalize(userId);
        if ("private".equalsIgnoreCase(uploadRole) && (effectiveUserId == null || effectiveUserId.isBlank())) {
            return Map.of("message", "上传失败: 私有荣誉文件缺少用户身份");
        }
        if (!"private".equalsIgnoreCase(uploadRole) && !isManagerRole(effectiveRequesterRole)) {
            return Map.of("message", "上传失败: 仅教师或管理员可上传公共荣誉文件");
        }

        try {
            Map<String, Object> honorMetadata = new java.util.HashMap<>();
            honorMetadata.put("honorLevel", honorLevel);
            honorMetadata.put("honorCategory", honorCategory);
            if (timestamp != null && !timestamp.isBlank()) {
                honorMetadata.put("timestamp", timestamp);
            }

            String result = assetService.processAsset(
                    file.getBytes(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    uploadRole,
                    effectiveUserId,
                    "校园荣誉",
                    "honor",
                    description,
                    honorMetadata,
                    assetService.new DefaultAssetProcessor()
            );
            return Map.of("message", result);
        } catch (Exception e) {
            log.error("荣誉处理失败", e);
            return Map.of("message", "上传失败: " + e.getMessage());
        }
    }

    /**
     * 添加外部链接并向量化
     */
    @PostMapping("/link")
    public String uploadLink(@RequestParam(name = "title") String title,
                            @RequestParam(name = "url") String url,
                            @RequestParam(name = "description", required = false) String description,
                            @RequestParam(name = "role", defaultValue = "all") String role,
                            @RequestParam(name = "category", defaultValue = "外部资源") String category,
                            @RequestHeader(name = "X-User-Id", required = false) String requesterId,
                            @RequestHeader(name = "X-User-Role", required = false) String requesterRole) {
        
        String effectiveRequesterId = normalize(requesterId);
        String effectiveRequesterRole = normalizeRole(requesterRole);
        String uploadRole = normalizeRole(role);

        if (!"private".equalsIgnoreCase(uploadRole) && !isManagerRole(effectiveRequesterRole)) {
            return "上传失败: 仅教师或管理员可发布公共链接";
        }

        try {
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("fileName", title);
            metadata.put("url", url);
            metadata.put("objectName", "link://" + url); // 特殊标识
            metadata.put("role", uploadRole);
            metadata.put("sourceType", "link");
            metadata.put("category", category);
            if (description != null) {
                metadata.put("description", description);
            }
            if ("private".equalsIgnoreCase(uploadRole) && effectiveRequesterId != null) {
                metadata.put("userId", effectiveRequesterId);
            }

            StringBuilder searchableText = new StringBuilder();
            searchableText.append("外部链接: ").append(title).append("\n");
            searchableText.append("URL: ").append(url).append("\n");
            if (description != null && !description.isBlank()) {
                searchableText.append("描述: ").append(description);
            }

            memoryService.addMemory(searchableText.toString(), metadata);
            log.info("链接已向量化: {}, URL: {}", title, url);
            
            return "链接保存成功！";
        } catch (Exception e) {
            log.error("链接保存失败", e);
            return "保存失败: " + e.getMessage();
        }
    }

    /**
     * 同步现有资产到向量库
     * 扫描 MinIO 中的所有文件，重新提取文本并注入向量库
     */
    @PostMapping("/sync")
    public ResponseEntity<String> syncAssets(@RequestHeader(name = "X-User-Role", required = false) String requesterRole) {
        if (!isManagerRole(normalizeRole(requesterRole))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("无权限执行全量同步");
        }
        log.info("开始同步 MinIO 资产到向量库...");
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucketName).build()
            );

            int successCount = 0;
            int totalCount = 0;

            for (Result<Item> result : results) {
                Item item = result.get();
                String objectName = item.objectName();
                totalCount++;

                if (isSupportedAsset(objectName)) {
                    try (InputStream stream = minioClient.getObject(
                            GetObjectArgs.builder().bucket(bucketName).object(objectName).build())) {
                        
                        byte[] bytes = stream.readAllBytes();
                        String fileName = objectName.contains("-") ? objectName.substring(objectName.indexOf("-") + 1) : objectName;
                        String text;
                        if (isImage(fileName)) {
                            text = extractImageText(bytes, fileName);
                        } else {
                            text = extractDocumentTextSafely(bytes, fileName);
                        }

                        if ((text == null || text.trim().isEmpty()) && isMultimedia(fileName)) {
                            text = "多媒体资产: " + fileName
                                    + " (媒体类型: " + resolveMediaLabel(fileName)
                                    + ", 类型: " + getContentType(fileName) + ")";
                        }
                        if ((text == null || text.trim().isEmpty()) && isImage(fileName)) {
                            text = "图片资产: " + fileName;
                        }
                        if (text == null || text.trim().isEmpty()) {
                            text = "文档资产: " + fileName;
                        }

                        if (text != null && !text.trim().isEmpty()) {
                            String resolvedCategory = inferCategory("auto", fileName, null, text);
                            String finalContent = "文件名: " + fileName + "\n分类: " + resolvedCategory + "\n内容: " + text;
                            if (isMultimedia(fileName)) {
                                finalContent = finalContent
                                        + "\n媒体类型: " + resolveMediaLabel(fileName)
                                        + "\n检索标签: " + resolveMediaLabel(fileName) + " 多媒体 校园素材";
                            }
                            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
                            metadata.put("fileName", fileName);
                            metadata.put("objectName", objectName);
                            metadata.put("category", resolvedCategory);

                            String ownerId = extractOwnerId(objectName);
                            if (ownerId != null) {
                                metadata.put("userId", ownerId);
                                metadata.put("role", "private");
                                metadata.put("sourceType", "private");
                            } else {
                                metadata.put("role", "all");
                                metadata.put("sourceType", isMultimedia(fileName) ? "multimedia" : "official");
                            }
                            if (isMultimedia(fileName)) {
                                metadata.put("mediaType", resolveMediaLabel(fileName));
                            }

                            log.info("同步资产: {}, 文本长度: {}, 元数据: {}", objectName, finalContent.length(), metadata);
                            memoryService.addMemory(finalContent, metadata);
                            successCount++;
                        }
                    } catch (Exception e) {
                        log.error("同步资产失败: {}", objectName, e);
                    }
                }
            }
            return ResponseEntity.ok(String.format("同步完成！共扫描 %d 个资产，成功向量化 %d 个资产。", totalCount, successCount));
        } catch (Exception e) {
            log.error("同步资产过程发生错误", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("同步失败: " + e.getMessage());
        }
    }

    private String normalize(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private String normalizeRole(String role) {
        if (role == null) return "student";
        String r = role.trim().toLowerCase();
        return r.isEmpty() ? "student" : r;
    }

    private boolean isManagerRole(String role) {
        return ROLE_TEACHER.equalsIgnoreCase(role) || ROLE_ADMIN.equalsIgnoreCase(role);
    }

    private boolean hasPrivateAccess(String requesterId, String requesterRole, String requestedUserId) {
        return requesterId != null && requesterId.equals(requestedUserId);
    }

    private boolean canAccessObject(String objectName, String requesterId, String requesterRole) {
        if (objectName == null || objectName.isBlank()) return false;
        if (!objectName.startsWith("private/")) return true;
        String ownerId = extractOwnerId(objectName);
        return ownerId != null && ownerId.equals(requesterId);
    }

    private boolean isHonorObject(String objectName) {
        if (objectName == null || objectName.isBlank()) return false;
        return objectName.startsWith("public/honor/") || objectName.contains("/honor/");
    }

    private boolean isObjectNotFoundError(Exception e) {
        if (e instanceof ErrorResponseException errorResponseException) {
            String code = errorResponseException.errorResponse().code();
            return "NoSuchKey".equalsIgnoreCase(code) || "NoSuchObject".equalsIgnoreCase(code);
        }
        String message = e.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("no such key") || lower.contains("does not exist") || lower.contains("not found");
    }

    private String extractOwnerId(String objectName) {
        if (objectName == null || !objectName.startsWith("private/")) return null;
        String[] parts = objectName.split("/");
        if (parts.length < 3) return null;
        return parts[1];
    }

    private String decodeObjectName(String objectName) {
        if (objectName == null) return null;
        if (!objectName.contains("%")) return objectName;
        try {
            return java.net.URLDecoder.decode(objectName, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return objectName;
        }
    }

    private String inferCategory(String requestedCategory, String fileName, String description, String extractedText) {
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

    private boolean isSupportedAsset(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".txt") || lower.endsWith(".doc")
                || lower.endsWith(".ppt") || lower.endsWith(".pptx")
                || isMultimedia(fileName) || isImage(fileName);
    }

    private boolean isMultimedia(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".mp4") || lower.endsWith(".mp3") || lower.endsWith(".wav")
                || lower.endsWith(".avi") || lower.endsWith(".mov");
    }

    private String resolveMediaLabel(String fileName) {
        if (fileName == null) return "多媒体";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp4") || lower.endsWith(".avi") || lower.endsWith(".mov")) return "视频";
        if (lower.endsWith(".mp3") || lower.endsWith(".wav")) return "音频";
        return "多媒体";
    }

    private String extractDocumentTextSafely(byte[] fileBytes, String fileName) {
        String lowerName = fileName == null ? "" : fileName.toLowerCase();
        try {
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

    private String mergeTextBlocks(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        if (left.isEmpty()) return right.isEmpty() ? null : right;
        if (right.isEmpty()) return left;
        if (left.contains(right)) return left;
        if (right.contains(left)) return right;
        return left + "\n\n" + right;
    }

    private int countPowerPointSlides(byte[] fileBytes, String fileName) {
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

    private byte[] renderPowerPointSlideImage(byte[] fileBytes, String fileName, int slideIndex, int targetWidth) {
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

    private boolean isImage(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    private String extractImageText(byte[] bytes, String fileName) {
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
            String finalLanguage = String.join("+", availableLanguages);
            if (!finalLanguage.contains("chi") && containsCjk(fileName)) {
                log.warn("OCR 缺少中文语言包且文件名包含中文，跳过 OCR 以避免乱码: fileName={}, language={}", fileName, finalLanguage);
                return "";
            }
            tesseract.setLanguage(finalLanguage);
            log.info("OCR 使用语言: {}, tessdataPath={}", finalLanguage, resolvedTessdataPath);
            
            tesseract.setTessVariable("user_defined_dpi", "300");
            tesseract.setTessVariable("preserve_interword_spaces", "1");
            tesseract.setTessVariable("textord_tabfind_vertical_text", "0");
            BufferedImage enhanced = preprocessForOcr(image);
            BufferedImage binary = toBinaryImage(enhanced);
            String normalized = pickBestOcrText(tesseract, image, enhanced, binary);
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

    private boolean hasValidTrainedData(String tessdataDir, String language) {
        if (tessdataDir == null || tessdataDir.isBlank() || language == null || language.isBlank()) return false;
        File trainedData = new File(tessdataDir, language + ".traineddata");
        return trainedData.exists() && trainedData.length() >= 256 * 1024;
    }

    private String pickBestOcrText(Tesseract tesseract, BufferedImage original, BufferedImage enhanced, BufferedImage binary) {
        String best = "";
        int bestScore = Integer.MIN_VALUE;
        int[] psmModes = new int[] {6, 11, 3};
        BufferedImage[] candidates = new BufferedImage[] {enhanced, binary, original};
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
        if (best == null || best.isBlank()) return "";
        if (bestScore < 25 || isLikelyGarbledOcr(best)) {
            return "";
        }
        return best;
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
        boolean hasMeaningfulLength = wordChars >= 8 || cjkChars >= 4;
        if (!hasMeaningfulLength) return true;
        if (wordRatio < 0.35) return true;
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
        int penalties = noise * 5 + repeatPenalty * 8;
        int shortPenalty = len < 8 ? 20 : 0;
        return info + len - penalties - shortPenalty;
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
        return gray;
    }

    private BufferedImage toBinaryImage(BufferedImage gray) {
        if (gray == null) return null;
        int width = gray.getWidth();
        int height = gray.getHeight();
        int total = width * height;
        long sum = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = gray.getRaster().getSample(x, y, 0);
                sum += v;
            }
        }
        int threshold = (int) Math.max(80, Math.min(190, sum / Math.max(total, 1)));
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
}
