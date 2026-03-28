package com.campus.memory.controller;

import com.campus.memory.service.ScoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/score")
@RequiredArgsConstructor
public class ScoreController {

    private final ScoreService scoreService;

    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics(
        @RequestHeader(name = "X-User-Id", required = false) String userId,
        @RequestHeader(name = "X-User-Role", required = false) String role
    ) {
        Map<String, Object> result = scoreService.getDashboardStatistics(userId, role);
        if ("无访问权限".equals(result.get("error"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
        }
        if (result.containsKey("error")) {
            return ResponseEntity.internalServerError().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/major-statistics")
    public ResponseEntity<Map<String, Object>> getMajorStatistics() {
        Map<String, Object> result = scoreService.getMajorStatistics();
        if (result.containsKey("error")) {
            return ResponseEntity.internalServerError().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/student-insights")
    public ResponseEntity<Map<String, Object>> getStudentInsights(
        @RequestHeader(name = "X-User-Id", required = false) String userId,
        @RequestHeader(name = "X-User-Role", required = false) String role,
        @RequestParam(name = "identifier", required = false) String identifier
    ) {
        Map<String, Object> result = scoreService.getStudentComprehensiveInsights(userId, role, identifier);
        if ("无访问权限".equals(result.get("error"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
        }
        if (result.containsKey("error")) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }
}
