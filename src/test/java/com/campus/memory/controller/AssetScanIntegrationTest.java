package com.campus.memory.controller;

import com.campus.memory.service.AssetService;
import com.campus.memory.service.MemoryService;
import com.campus.memory.service.WhisperAsrService;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class AssetScanIntegrationTest {

    @Mock
    private MinioClient minioClient;

    @Mock
    private MemoryService memoryService;

    @Mock
    private WhisperAsrService whisperAsrService;

    private AssetService assetService;

    private AssetController assetController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        assetService = new AssetService(minioClient, memoryService, whisperAsrService);
        
        // 设置 AssetService 的私有字段
        ReflectionTestUtils.setField(assetService, "bucketName", "test-bucket");
        ReflectionTestUtils.setField(assetService, "ocrEnabled", false); // 测试环境禁用真实 OCR

        assetController = new AssetController(minioClient, memoryService, assetService, whisperAsrService);
        ReflectionTestUtils.setField(assetController, "bucketName", "test-bucket");
    }

    @Test
    void testScanCallbackFlow_GeneralAsset() throws Exception {
        // 1. 准备模拟文件
        byte[] content = "这是一份校园历史文档的内容".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "campus_history.txt", "text/plain", content);

        // 2. 模拟 AI 分类结果
        Map<String, Object> aiResult = new HashMap<>();
        aiResult.put("category", "校史");
        aiResult.put("sourceType", "official");
        aiResult.put("isHonor", false);
        aiResult.put("summary", "关于校园历史的文档摘要");
        
        when(memoryService.classifyAsset(anyString(), anyString(), anyString(), isNull())).thenReturn(aiResult);

        // 3. 执行控制器方法
        Map<String, Object> response = assetController.scanCallback(file, "SCANNER-001", null, null, null, null);

        // 4. 验证结果
        assertEquals("success", response.get("status"));
        assertTrue(response.get("message").toString().contains("校史"));

        // 验证 MinIO 上传被调用
        verify(minioClient, times(1)).putObject(any(PutObjectArgs.class));

        // 验证 MemoryService.addMemory 被调用，且元数据包含 AI 识别的信息
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(memoryService, times(1)).addMemory(anyString(), metadataCaptor.capture());
        
        Map<String, Object> capturedMetadata = metadataCaptor.getValue();
        assertEquals("校史", capturedMetadata.get("category"));
        assertEquals("official", capturedMetadata.get("sourceType"));
        assertEquals("关于校园历史的文档摘要", capturedMetadata.get("aiSummary"));
    }

    @Test
    void testScanCallbackFlow_EntitiesAsListFix() throws Exception {
        // 1. 准备模拟文件
        byte[] content = "包含实体列表的文档内容".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "entities_test.txt", "text/plain", content);

        // 2. 模拟 AI 分类结果，包含实体 List
        Map<String, Object> aiResult = new HashMap<>();
        aiResult.put("category", "通用");
        aiResult.put("isHonor", false);
        aiResult.put("extractedEntities", java.util.Arrays.asList("实体A", "实体B"));
        
        when(memoryService.classifyAsset(anyString(), anyString(), anyString(), isNull())).thenReturn(aiResult);

        // 3. 执行控制器方法
        assetController.scanCallback(file, "SCANNER-003", null, null, null, null);

        // 4. 验证 MemoryService.addMemory 被调用
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(memoryService).addMemory(anyString(), metadataCaptor.capture());
        
        // 5. 验证元数据中的 entities 已被 AssetService 预处理为 String
        // (AssetService.java:133-138)
        Map<String, Object> capturedMetadata = metadataCaptor.getValue();
        assertEquals("实体A, 实体B", capturedMetadata.get("entities"));
    }

    @Test
    void testScanCallbackFlow_HonorAsset() throws Exception {
        // 1. 准备模拟图片文件 (假设是奖状扫描件)
        byte[] content = "奖状内容: 授予张三同学一等奖".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "honor_certificate.jpg", "image/jpeg", content);

        // 2. 模拟 AI 分类结果 (识别为荣誉)
        Map<String, Object> aiResult = new HashMap<>();
        aiResult.put("category", "荣誉");
        aiResult.put("sourceType", "honor");
        aiResult.put("isHonor", true);
        aiResult.put("honorLevel", "省级");
        aiResult.put("honorCategory", "学术");
        aiResult.put("honorYear", "2023");
        aiResult.put("summary", "张三获得的省级一等奖");
        
        when(memoryService.classifyAsset(anyString(), anyString(), anyString(), isNull())).thenReturn(aiResult);

        // 3. 执行控制器方法
        Map<String, Object> response = assetController.scanCallback(file, "SCANNER-002", null, null, null, null);

        // 4. 验证结果
        assertEquals("success", response.get("status"));
        assertTrue(response.get("message").toString().contains("荣誉"));

        // 验证 MemoryService.addHonor 被调用
        verify(memoryService, times(1)).addHonor(anyString(), any(Map.class));
        
        // 验证元数据包含荣誉特有字段
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(memoryService).addHonor(anyString(), metadataCaptor.capture());
        
        Map<String, Object> capturedMetadata = metadataCaptor.getValue();
        assertEquals("省级", capturedMetadata.get("honorLevel"));
        assertEquals("学术", capturedMetadata.get("honorCategory"));
        assertEquals("2023", capturedMetadata.get("honorYear"));
    }

    @Test
    void testScanCallbackFlow_VideoAssetUsesWhisperAndStoresMultimediaMetadata() throws Exception {
        byte[] content = "mock-video-content".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "campus_promo.mp4", "video/mp4", content);

        WhisperAsrService.TranscriptionResult transcription =
                new WhisperAsrService.TranscriptionResult("这是校园宣传片解说词", "zh");
        when(whisperAsrService.transcribe(any(byte[].class), eq("campus_promo.mp4"), eq("video/mp4"), isNull()))
                .thenReturn(transcription);

        Map<String, Object> aiResult = new HashMap<>();
        aiResult.put("category", "校史");
        aiResult.put("isHonor", false);
        when(memoryService.classifyAsset(anyString(), anyString(), anyString(), isNull())).thenReturn(aiResult);

        Map<String, Object> response = assetController.scanCallback(file, "SCANNER-004", null, null, null, null);

        assertEquals("success", response.get("status"));
        verify(whisperAsrService, times(1))
                .transcribe(any(byte[].class), eq("campus_promo.mp4"), eq("video/mp4"), isNull());

        ArgumentCaptor<String> searchableTextCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(memoryService, times(1)).addMemory(searchableTextCaptor.capture(), metadataCaptor.capture());

        String searchableText = searchableTextCaptor.getValue();
        Map<String, Object> metadata = metadataCaptor.getValue();

        assertTrue(searchableText.contains("这是校园宣传片解说词"));
        assertTrue(searchableText.contains("媒体类型: 视频"));
        assertEquals("multimedia", metadata.get("sourceType"));
        assertEquals("视频", metadata.get("mediaType"));
        assertEquals("zh", metadata.get("recognizedLanguage"));
    }
}
