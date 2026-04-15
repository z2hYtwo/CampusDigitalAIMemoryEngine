package com.campus.memory.controller;

import com.campus.memory.dto.SearchResponse;
import com.campus.memory.service.MemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
@Slf4j
public class MemoryController {

    private final MemoryService memoryService;

    /**
     * 存入校史记忆片段
     * @param payload 包含文本内容和元数据的 JSON 对象
     * @return 状态信息
     */
    @PostMapping("/add")
    public String addMemory(@RequestBody java.util.Map<String, Object> payload,
                            @RequestHeader(name = "X-User-Id", required = false) String requesterId,
                            @RequestHeader(name = "X-User-Role", required = false) String requesterRole) {
        String text = (String) payload.get("text");
        java.util.Map<String, Object> metadata = (java.util.Map<String, Object>) payload.get("metadata");
        if (metadata != null && "true".equals(String.valueOf(metadata.get("isHonor")))) {
            String role = metadata.get("role") == null ? "" : String.valueOf(metadata.get("role")).trim().toLowerCase();
            String ownerId = metadata.get("userId") == null ? "" : String.valueOf(metadata.get("userId")).trim();
            String effectiveRequesterId = requesterId == null ? "" : requesterId.trim();
            String effectiveRequesterRole = requesterRole == null ? "" : requesterRole.trim().toLowerCase();
            boolean privateHonor = "private".equals(role);
            if (privateHonor) {
                boolean allowedRole = "student".equals(effectiveRequesterRole)
                    || "teacher".equals(effectiveRequesterRole)
                    || "admin".equals(effectiveRequesterRole);
                if (!allowedRole) {
                    return "无权限：当前角色不允许操作私人荣誉";
                }
                if (effectiveRequesterId.isBlank() || ownerId.isBlank() || !effectiveRequesterId.equals(ownerId)) {
                    return "无权限：仅允许操作自己的私人荣誉";
                }
            } else if (!"admin".equals(effectiveRequesterRole)) {
                return "无权限：仅管理员可操作校园荣誉";
            }
            return memoryService.addHonor(text, metadata);
        }
        return memoryService.addMemory(text, metadata);
    }

    /**
     * 语义搜索校史记忆 (GET 方式)
     */
    @GetMapping("/search")
    public List<String> searchMemory(@RequestParam(name = "query") String query, 
                                    @RequestParam(name = "maxResults", defaultValue = "3") int maxResults) {
        return memoryService.search(query, maxResults);
    }

    /**
     * 增强版语义搜索 (POST 方式)
     * 支持角色权限隔离 (student/teacher/admin)
     */
    @PostMapping("/search")
    public SearchResponse searchMemoryPost(@RequestBody String query,
                                         @RequestParam(name = "maxResults", defaultValue = "3") int maxResults,
                                         @RequestParam(name = "role", defaultValue = "guest") String role,
                                         @RequestHeader(name = "X-Session-Id", required = false) String sessionId,
                                         @RequestHeader(name = "X-User-Id", required = false) String userId,
                                         @RequestHeader(name = "X-User-Role", required = false) String headerRole) {
        String effectiveRole = (headerRole != null && !headerRole.isBlank()) ? headerRole : role;
        String effectiveSessionId = (userId != null && !userId.isBlank()) ? userId : sessionId;
        return memoryService.searchWithAi(query, maxResults, effectiveRole, effectiveSessionId);
    }

    /**
     * 获取校园荣誉生长树数据
     */
    @GetMapping("/honor-tree")
    public List<java.util.Map<String, Object>> getHonorTree(
            @RequestHeader(name = "X-User-Role", required = false) String requesterRole) {
        return memoryService.getHonorTreeData();
    }

    @GetMapping("/honor-tree/personal")
    public List<java.util.Map<String, Object>> getPersonalHonorTree(
            @RequestParam(name = "userId", required = false) String userId,
            @RequestHeader(name = "X-User-Id", required = false) String requesterId,
            @RequestHeader(name = "X-User-Role", required = false) String requesterRole) {
        String effectiveRequesterId = requesterId == null ? "" : requesterId.trim();
        String effectiveRequesterRole = requesterRole == null ? "" : requesterRole.trim().toLowerCase();
        String requestedUserId = userId == null ? "" : userId.trim();
        if ("admin".equals(effectiveRequesterRole)) {
            return List.of();
        }
        String targetUserId = !effectiveRequesterId.isBlank() ? effectiveRequesterId : requestedUserId;
        if (targetUserId.isBlank()) {
            return List.of();
        }
        if (!requestedUserId.isBlank() && !requestedUserId.equals(targetUserId) && !"admin".equals(effectiveRequesterRole)) {
            return List.of();
        }
        return memoryService.getPersonalHonorTreeData(targetUserId);
    }

    @PostMapping("/honor-narrative")
    public Map<String, String> getHonorNarrative(@RequestBody Map<String, String> payload) {
        String text = payload.getOrDefault("text", "");
        String level = payload.getOrDefault("level", "");
        String category = payload.getOrDefault("category", "");
        String answer = memoryService.buildHonorNarrative(text, level, category);
        return Map.of("answer", answer);
    }
}
