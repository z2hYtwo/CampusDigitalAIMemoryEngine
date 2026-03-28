package com.campus.memory.controller;

import com.campus.memory.service.DataImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
public class DataImportController {

    private final DataImportService dataImportService;

    @PostMapping("/students")
    public ResponseEntity<?> importStudents(@RequestParam("file") MultipartFile file) {
        try {
            dataImportService.importStudents(file);
            return ResponseEntity.ok(Map.of("message", "学籍信息导入成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "导入失败: " + e.getMessage()));
        }
    }

    @PostMapping("/courses")
    public ResponseEntity<?> importCourses(@RequestParam("file") MultipartFile file) {
        try {
            dataImportService.importCourses(file);
            return ResponseEntity.ok(Map.of("message", "课程信息导入成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "导入失败: " + e.getMessage()));
        }
    }

    @PostMapping("/scores")
    public ResponseEntity<?> importScores(@RequestParam("file") MultipartFile file) {
        try {
            dataImportService.importScores(file);
            return ResponseEntity.ok(Map.of("message", "成绩信息导入成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "导入失败: " + e.getMessage()));
        }
    }
}
