package com.campus.memory.security;

import com.campus.memory.controller.AssetController;
import com.campus.memory.controller.ScoreController;
import com.campus.memory.dto.SearchResponse;
import com.campus.memory.service.AssetService;
import com.campus.memory.service.MemoryService;
import com.campus.memory.service.PlanningLayer;
import com.campus.memory.service.ScoreService;
import com.campus.memory.service.WhisperAsrService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationIsolationRegressionTest {

    @Test
    void guestQueryingDataShouldReturnNoAccess() {
        EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> embeddingStore = Mockito.mock(EmbeddingStore.class);
        ChatLanguageModel chatLanguageModel = Mockito.mock(ChatLanguageModel.class);
        ScoreService scoreService = Mockito.mock(ScoreService.class);
        PlanningLayer planningLayer = new PlanningLayer();
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);

        MemoryService memoryService = new MemoryService(
            embeddingModel,
            embeddingStore,
            chatLanguageModel,
            scoreService,
            planningLayer,
            jdbcTemplate
        );

        SearchResponse response = memoryService.searchWithAi("查询学生成绩统计", 5, "guest", "session-guest");

        assertNotNull(response);
        assertEquals("无访问权限", response.getAnswer());
        assertNotNull(response.getRelevantFiles());
        assertTrue(response.getRelevantFiles().isEmpty());
    }

    @Test
    void guestQueryClassifierShouldAllowPublicPolicyButBlockStudentPrivacy() throws Exception {
        EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> embeddingStore = Mockito.mock(EmbeddingStore.class);
        ChatLanguageModel chatLanguageModel = Mockito.mock(ChatLanguageModel.class);
        ScoreService scoreService = Mockito.mock(ScoreService.class);
        PlanningLayer planningLayer = new PlanningLayer();
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);

        MemoryService memoryService = new MemoryService(
            embeddingModel,
            embeddingStore,
            chatLanguageModel,
            scoreService,
            planningLayer,
            jdbcTemplate
        );

        boolean policyQuerySensitive = invokeBooleanPrivate(
            memoryService,
            "isStudentSensitiveQuery",
            new Class<?>[]{String.class},
            "学生资助政策有哪些公开内容"
        );
        boolean scoreQuerySensitive = invokeBooleanPrivate(
            memoryService,
            "isStudentSensitiveQuery",
            new Class<?>[]{String.class},
            "查询学生成绩和学号"
        );

        assertFalse(policyQuerySensitive);
        assertTrue(scoreQuerySensitive);
    }

    @Test
    void adminShouldNotListOrReadTeacherPrivateSpace() throws Exception {
        AssetController assetController = new AssetController(
            Mockito.mock(MinioClient.class),
            Mockito.mock(MemoryService.class),
            Mockito.mock(AssetService.class),
            Mockito.mock(WhisperAsrService.class)
        );

        boolean listAccess = invokeBooleanPrivate(
            assetController,
            "hasPrivateAccess",
            new Class<?>[]{String.class, String.class, String.class},
            "admin-001",
            "admin",
            "teacher-001"
        );
        boolean readAccess = invokeBooleanPrivate(
            assetController,
            "canAccessObject",
            new Class<?>[]{String.class, String.class, String.class},
            "private/teacher-001/uuid-file.pdf",
            "admin-001",
            "admin"
        );

        assertFalse(listAccess);
        assertFalse(readAccess);
    }

    @Test
    void studentShouldReadOwnPrivateSpace() throws Exception {
        AssetController assetController = new AssetController(
            Mockito.mock(MinioClient.class),
            Mockito.mock(MemoryService.class),
            Mockito.mock(AssetService.class),
            Mockito.mock(WhisperAsrService.class)
        );

        boolean readAccess = invokeBooleanPrivate(
            assetController,
            "canAccessObject",
            new Class<?>[]{String.class, String.class, String.class},
            "private/student-001/uuid-file.pdf",
            "student-001",
            "student"
        );

        assertTrue(readAccess);
    }

    @Test
    void guestShouldReceiveForbiddenFromScoreEndpoint() {
        ScoreService scoreService = Mockito.mock(ScoreService.class);
        Mockito.when(scoreService.getDashboardStatistics("guest-id", "guest"))
            .thenReturn(Map.of("error", "无访问权限", "message", "无访问权限"));
        ScoreController scoreController = new ScoreController(scoreService);

        ResponseEntity<Map<String, Object>> response = scoreController.getStatistics("guest-id", "guest");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("无访问权限", response.getBody().get("error"));
    }

    @Test
    void adminShouldAccessScoreEndpointNormally() {
        ScoreService scoreService = Mockito.mock(ScoreService.class);
        Mockito.when(scoreService.getDashboardStatistics("admin-id", "admin"))
            .thenReturn(Map.of("totalStudents", 120, "totalScoreRecords", 560));
        ScoreController scoreController = new ScoreController(scoreService);

        ResponseEntity<Map<String, Object>> response = scoreController.getStatistics("admin-id", "admin");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(120, response.getBody().get("totalStudents"));
    }

    private boolean invokeBooleanPrivate(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return (Boolean) method.invoke(target, args);
    }
}
