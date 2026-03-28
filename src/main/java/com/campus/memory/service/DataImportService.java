package com.campus.memory.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.campus.memory.dto.CourseExcelDTO;
import com.campus.memory.dto.ScoreExcelDTO;
import com.campus.memory.dto.StudentExcelDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class DataImportService {

    private final JdbcTemplate jdbcTemplate;
    private static final int BATCH_SIZE = 1000;

    /**
     * 导入学籍信息
     */
    public void importStudents(MultipartFile file) throws IOException {
        EasyExcel.read(file.getInputStream(), StudentExcelDTO.class, new ReadListener<StudentExcelDTO>() {
            private List<StudentExcelDTO> cachedDataList = new ArrayList<>(BATCH_SIZE);

            @Override
            public void invoke(StudentExcelDTO data, AnalysisContext context) {
                if (data.getStudentId() == null || data.getStudentId().isBlank()) {
                    log.warn("跳过学号为空的行");
                    return;
                }
                cachedDataList.add(data);
                if (cachedDataList.size() >= BATCH_SIZE) {
                    saveStudents();
                }
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                saveStudents();
            }

            private void saveStudents() {
                if (cachedDataList.isEmpty()) return;
                log.info("正在批量保存 {} 条学籍数据...", cachedDataList.size());
                try {
                    String sql = "INSERT IGNORE INTO students (student_id, name, gender, department, major, birthday, ethnicity, political_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                    jdbcTemplate.batchUpdate(sql, cachedDataList, cachedDataList.size(), (ps, student) -> {
                        ps.setObject(1, student.getStudentId());
                        ps.setObject(2, student.getName());
                        ps.setObject(3, student.getGender());
                        ps.setObject(4, student.getDepartment());
                        ps.setObject(5, student.getMajor());
                        ps.setObject(6, student.getBirthday());
                        ps.setObject(7, student.getEthnicity());
                        ps.setObject(8, student.getPoliticalStatus());
                    });
                } catch (Exception e) {
                    log.error("批量保存学籍数据失败: {}", e.getMessage());
                    throw e;
                } finally {
                    cachedDataList.clear();
                }
            }
        }).sheet().doRead();
    }

    /**
     * 导入课程信息
     */
    public void importCourses(MultipartFile file) throws IOException {
        EasyExcel.read(file.getInputStream(), CourseExcelDTO.class, new ReadListener<CourseExcelDTO>() {
            private List<CourseExcelDTO> cachedDataList = new ArrayList<>(BATCH_SIZE);

            @Override
            public void invoke(CourseExcelDTO data, AnalysisContext context) {
                if (data.getCourseId() == null || data.getCourseId().isBlank()) {
                    log.warn("跳过课程号为空的行");
                    return;
                }
                cachedDataList.add(data);
                if (cachedDataList.size() >= BATCH_SIZE) {
                    saveCourses();
                }
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                saveCourses();
            }

            private void saveCourses() {
                if (cachedDataList.isEmpty()) return;
                log.info("正在批量保存 {} 条课程数据...", cachedDataList.size());
                try {
                    String sql = "INSERT IGNORE INTO courses (course_id, course_name, credits, total_hours) VALUES (?, ?, ?, ?)";
                    jdbcTemplate.batchUpdate(sql, cachedDataList, cachedDataList.size(), (ps, course) -> {
                        ps.setObject(1, course.getCourseId());
                        ps.setObject(2, course.getCourseName());
                        ps.setObject(3, course.getCredits());
                        ps.setObject(4, course.getTotalHours());
                    });
                } catch (Exception e) {
                    log.error("批量保存课程数据失败: {}", e.getMessage());
                    throw e;
                } finally {
                    cachedDataList.clear();
                }
            }
        }).sheet().doRead();
    }

    /**
     * 导入成绩信息
     */
    public void importScores(MultipartFile file) throws IOException {
        EasyExcel.read(file.getInputStream(), ScoreExcelDTO.class, new ReadListener<ScoreExcelDTO>() {
            private List<ScoreExcelDTO> cachedDataList = new ArrayList<>(BATCH_SIZE);

            @Override
            public void invoke(ScoreExcelDTO data, AnalysisContext context) {
                if (data.getStudentId() == null || data.getStudentId().isBlank() || 
                    data.getCourseId() == null || data.getCourseId().isBlank()) {
                    log.warn("跳过学号或课程号为空的成绩行");
                    return;
                }
                cachedDataList.add(data);
                if (cachedDataList.size() >= BATCH_SIZE) {
                    saveScores();
                }
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                saveScores();
            }

            private void saveScores() {
                if (cachedDataList.isEmpty()) return;
                log.info("正在批量保存 {} 条成绩数据...", cachedDataList.size());
                try {
                    String sql = "INSERT IGNORE INTO scores (student_id, course_id, score, credits, gpa) VALUES (?, ?, ?, ?, ?)";
                    jdbcTemplate.batchUpdate(sql, cachedDataList, cachedDataList.size(), (ps, score) -> {
                        ps.setObject(1, score.getStudentId());
                        ps.setObject(2, score.getCourseId());
                        ps.setObject(3, score.getScore());
                        ps.setObject(4, score.getCredits());
                        ps.setObject(5, score.getGpa());
                    });
                } catch (Exception e) {
                    log.error("批量保存成绩数据失败: {}", e.getMessage());
                    throw e;
                } finally {
                    cachedDataList.clear();
                }
            }
        }).sheet().doRead();
    }
}
