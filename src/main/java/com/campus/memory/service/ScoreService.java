package com.campus.memory.service;

import com.campus.memory.context.OrchestrationContext;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class ScoreService {

    private final JdbcTemplate jdbcTemplate;
    private static final Pattern STUDENT_ID_PATTERN = Pattern.compile("\\d{6,20}");
    private static final Pattern CHINESE_NAME_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5·]{2,4}");
    private static final Map<String, Object> NO_ACCESS = Map.of(
        "error", "无访问权限",
        "message", "无访问权限"
    );

    /**
     * 获取所有有成绩的学生名单
     */
    @Tool("获取系统中所有已录入成绩的学生姓名列表。如果人数较多，仅返回前100名，请提示用户缩小查询范围或提供具体姓名。")
    public Map<String, Object> getAllStudentNames() {
        OrchestrationContext.recordToolInvoke("DATA");
        if (!canAccessDataTools()) {
            return NO_ACCESS;
        }
        if (isStudentRole(OrchestrationContext.getUserRole())) {
            return NO_ACCESS;
        }
        try {
            String sql = "SELECT DISTINCT name FROM students LIMIT 101";
            List<String> students = jdbcTemplate.queryForList(sql, String.class);
            boolean hasMore = students.size() > 100;
            List<String> resultList = hasMore ? students.subList(0, 100) : students;
            
            return Map.of(
                "tool", "getAllStudentNames",
                "count", resultList.size(),
                "hasMore", hasMore,
                "students", resultList,
                "note", hasMore ? "系统内学生较多，仅显示前100名，建议通过具体姓名进行查询。" : "已列出所有匹配的学生姓名。"
            );
        } catch (Exception e) {
            log.error("获取学生名单失败", e);
            return Map.of(
                "tool", "getAllStudentNames",
                "error", "获取学生名单失败"
            );
        }
    }

    /**
     * 根据学号或姓名查询学生成绩
     * @param identifier 学号或姓名
     */
    @Tool("根据学号（如2023050125）或学生姓名查询其详细的考试成绩和科目分数")
    public Map<String, Object> getStudentScoreDetail(String identifier) {
        log.info("Tool: 正在查询学生成绩: {}", identifier);
        OrchestrationContext.recordToolInvoke("DATA");
        if (!canAccessDataTools()) {
            return NO_ACCESS;
        }
        try {
            StudentIdentity student = resolveStudentIdentity(identifier);
            if (student == null) {
                return Map.of(
                    "tool", "getStudentScoreDetail",
                    "identifier", identifier,
                    "found", false,
                    "message", "未找到该学生的成绩记录"
                );
            }
            if (!canAccessStudentScope(student.studentId())) {
                return NO_ACCESS;
            }
            String sql = "SELECT s.student_id, s.name, c.course_name, sc.score, sc.credits, sc.gpa " +
                         "FROM students s " +
                         "JOIN scores sc ON s.student_id = sc.student_id " +
                         "JOIN courses c ON sc.course_id = c.course_id " +
                         "WHERE s.student_id = ? " +
                         "ORDER BY sc.score DESC, c.course_name ASC";
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, student.studentId());
            if (!results.isEmpty()) {
                StringBuilder summary = new StringBuilder();
                for (Map<String, Object> row : results) {
                    summary.append(String.format("《%s》：%s分 (学分:%s, 绩点:%s)；", 
                        row.get("course_name"), row.get("score"), row.get("credits"), row.get("gpa")));
                }
                String studentName = student.name();
                String studentId = student.studentId();
                String markdownTable = buildScoreMarkdownTable(results);
                Map<String, Object> chartConfig = buildScoreChartConfig(studentName, results);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("tool", "getStudentScoreDetail");
                response.put("identifier", identifier);
                response.put("studentName", studentName);
                response.put("studentId", studentId);
                response.put("found", true);
                response.put("scores", results);
                response.put("scoreDetails", summary.toString());
                response.put("markdownTable", markdownTable);
                response.put("chartConfig", chartConfig);
                return response;
            }
            return Map.of(
                "tool", "getStudentScoreDetail",
                "identifier", identifier,
                "found", false,
                "message", "未找到该学生的成绩记录"
            );
        } catch (Exception e) {
            log.error("数据库查询失败", e);
            return Map.of(
                "tool", "getStudentScoreDetail",
                "identifier", identifier,
                "error", "查询异常，请稍后再试"
            );
        }
    }

    /**
     * 获取所有课程列表
     */
    @Tool("获取系统中所有已录入的课程名称及基本信息。如果课程较多，仅返回前50门。")
    public Map<String, Object> getAllCourses() {
        OrchestrationContext.recordToolInvoke("DATA");
        if (!canAccessDataTools()) {
            return NO_ACCESS;
        }
        try {
            String sql = "SELECT course_id, course_name, credits, total_hours FROM courses LIMIT 51";
            List<Map<String, Object>> courses = jdbcTemplate.queryForList(sql);
            boolean hasMore = courses.size() > 50;
            List<Map<String, Object>> resultList = hasMore ? courses.subList(0, 50) : courses;
            
            return Map.of(
                "tool", "getAllCourses",
                "count", resultList.size(),
                "hasMore", hasMore,
                "courses", resultList
            );
        } catch (Exception e) {
            log.error("获取课程列表失败", e);
            return Map.of(
                "tool", "getAllCourses",
                "error", "获取课程列表失败"
            );
        }
    }

    /**
     * 查询指定课程的详细信息
     * @param courseName 课程名称
     */
    @Tool("根据课程名称查询其学分、学时等详细课程元数据")
    public Map<String, Object> getCourseDetail(String courseName) {
        log.info("Tool: 正在查询课程详情: {}", courseName);
        OrchestrationContext.recordToolInvoke("DATA");
        if (!canAccessDataTools()) {
            return NO_ACCESS;
        }
        try {
            String sql = "SELECT * FROM courses WHERE course_name LIKE ?";
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, "%" + courseName + "%");
            if (!results.isEmpty()) {
                return Map.of(
                    "tool", "getCourseDetail",
                    "courseName", courseName,
                    "found", true,
                    "courses", results
                );
            }
            return Map.of(
                "tool", "getCourseDetail",
                "courseName", courseName,
                "found", false,
                "message", "未找到该课程的详细信息"
            );
        } catch (Exception e) {
            log.error("课程查询失败", e);
            return Map.of(
                "tool", "getCourseDetail",
                "courseName", courseName,
                "error", "查询异常"
            );
        }
    }

    /**
     * 根据学号或姓名查询学生个人资料
     * @param identifier 学号或姓名
     */
    @Tool("根据学号（如2023050125）或学生姓名查询其学籍基本信息（如学院、专业、性别等）")
    public Map<String, Object> getStudentProfile(String identifier) {
        log.info("Tool: 正在查询学生学籍资料: {}", identifier);
        OrchestrationContext.recordToolInvoke("DATA");
        if (!canAccessDataTools()) {
            return NO_ACCESS;
        }
        try {
            StudentIdentity student = resolveStudentIdentity(identifier);
            if (student == null) {
                return Map.of(
                    "tool", "getStudentProfile",
                    "identifier", identifier,
                    "found", false,
                    "message", "未找到该学生的学籍资料，请确认学号或姓名是否正确"
                );
            }
            if (!canAccessStudentScope(student.studentId())) {
                return NO_ACCESS;
            }
            String sql = "SELECT student_id, name, gender, department, major, birthday, ethnicity, political_status " +
                         "FROM students WHERE student_id = ?";
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, student.studentId());
            if (!results.isEmpty()) {
                return Map.of(
                    "tool", "getStudentProfile",
                    "identifier", identifier,
                    "found", true,
                    "profile", results.get(0)
                );
            }
            return Map.of(
                "tool", "getStudentProfile",
                "identifier", identifier,
                "found", false,
                "message", "未找到该学生的学籍资料，请确认学号或姓名是否正确"
            );
        } catch (Exception e) {
            log.error("学籍查询失败", e);
            return Map.of(
                "tool", "getStudentProfile",
                "identifier", identifier,
                "error", "查询异常"
            );
        }
    }

    /**
     * 获取成绩统计信息
     */
    @Tool("获取当前系统中学生成绩的整体统计信息，如总人数、总成绩记录数等")
    public Map<String, Object> getScoreStatistics() {
        OrchestrationContext.recordToolInvoke("DATA");
        if (!canAccessDataTools()) {
            return NO_ACCESS;
        }
        if (isStudentRole(OrchestrationContext.getUserRole())) {
            return NO_ACCESS;
        }
        try {
            String studentCountSql = "SELECT COUNT(*) FROM students";
            Integer studentCount = jdbcTemplate.queryForObject(studentCountSql, Integer.class);
            
            String scoreCountSql = "SELECT COUNT(*) FROM scores";
            Integer scoreCount = jdbcTemplate.queryForObject(scoreCountSql, Integer.class);
            
            return Map.of(
                "tool", "getScoreStatistics",
                "totalStudents", studentCount != null ? studentCount : 0,
                "totalScoreRecords", scoreCount != null ? scoreCount : 0
            );
        } catch (Exception e) {
            log.error("统计失败", e);
            return Map.of(
                "tool", "getScoreStatistics",
                "error", "统计服务暂时不可用"
            );
        }
    }

    public Map<String, Object> getDashboardStatistics() {
        return getDashboardStatistics(null, null);
    }

    public Map<String, Object> getDashboardStatistics(String userId, String role) {
        if (!canAccessDashboard(role)) {
            return NO_ACCESS;
        }
        try {
            String normalizedRole = role == null ? "" : role.trim().toLowerCase();
            String normalizedUserId = userId == null ? "" : userId.trim();
            if ("student".equals(normalizedRole) && normalizedUserId.isBlank()) {
                return NO_ACCESS;
            }
            boolean studentScope = "student".equals(normalizedRole) && !normalizedUserId.isBlank();

            Integer totalStudents = studentScope
                ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM students WHERE student_id = ?", Integer.class, normalizedUserId)
                : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM students", Integer.class);

            Integer totalScoreRecords = studentScope
                ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM scores WHERE student_id = ?", Integer.class, normalizedUserId)
                : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM scores", Integer.class);

            Double excellentRate = studentScope
                ? jdbcTemplate.queryForObject(
                    "SELECT IFNULL(AVG(CASE WHEN score >= 90 THEN 1 ELSE 0 END) * 100, 0) FROM scores WHERE student_id = ?",
                    Double.class,
                    normalizedUserId
                )
                : jdbcTemplate.queryForObject(
                    "SELECT IFNULL(AVG(CASE WHEN score >= 90 THEN 1 ELSE 0 END) * 100, 0) FROM scores",
                    Double.class
                );

            Integer warningStudents = studentScope
                ? jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT student_id) FROM scores WHERE score < 60 AND student_id = ?",
                    Integer.class,
                    normalizedUserId
                )
                : jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT student_id) FROM scores WHERE score < 60",
                    Integer.class
                );

            Double averageScore = studentScope
                ? jdbcTemplate.queryForObject(
                    "SELECT IFNULL(AVG(score), 0) FROM scores WHERE student_id = ?",
                    Double.class,
                    normalizedUserId
                )
                : jdbcTemplate.queryForObject(
                    "SELECT IFNULL(AVG(score), 0) FROM scores",
                    Double.class
                );

            List<String> trendLabels = List.of("近六次统计");
            List<Double> trendValues = List.of(formatScore(averageScore));

            List<Map<String, Object>> distributionRows = studentScope
                ? jdbcTemplate.queryForList(
                    "SELECT " +
                        "CASE " +
                        "WHEN score >= 90 THEN '优秀' " +
                        "WHEN score >= 80 THEN '良好' " +
                        "WHEN score >= 60 THEN '中等' " +
                        "ELSE '及格' END AS level, " +
                        "COUNT(*) AS cnt " +
                    "FROM scores " +
                    "WHERE student_id = ? " +
                    "GROUP BY level",
                    normalizedUserId
                )
                : jdbcTemplate.queryForList(
                    "SELECT " +
                        "CASE " +
                        "WHEN score >= 90 THEN '优秀' " +
                        "WHEN score >= 80 THEN '良好' " +
                        "WHEN score >= 60 THEN '中等' " +
                        "ELSE '及格' END AS level, " +
                        "COUNT(*) AS cnt " +
                    "FROM scores " +
                    "GROUP BY level"
                );

            Map<String, Double> distribution = new LinkedHashMap<>();
            distribution.put("优秀", 0.0);
            distribution.put("良好", 0.0);
            distribution.put("中等", 0.0);
            distribution.put("及格", 0.0);
            double total = (totalScoreRecords == null || totalScoreRecords == 0) ? 1.0 : totalScoreRecords;
            for (Map<String, Object> row : distributionRows) {
                String level = String.valueOf(row.get("level"));
                double cnt = row.get("cnt") == null ? 0.0 : Double.parseDouble(row.get("cnt").toString());
                distribution.put(level, Math.round((cnt / total) * 1000.0) / 10.0);
            }

            Map<String, Object> lineChartConfig = Map.of(
                "type", "line",
                "title", "当前平均分",
                "labels", trendLabels,
                "datasets", List.of(Map.of(
                    "label", "平均分",
                    "data", trendValues
                ))
            );

            Map<String, Object> pieChartConfig = Map.of(
                "type", "pie",
                "title", "成绩等级分布",
                "labels", distribution.keySet(),
                "datasets", List.of(Map.of(
                    "label", "占比",
                    "data", distribution.values()
                ))
            );

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalStudents", totalStudents == null ? 0 : totalStudents);
            result.put("totalScoreRecords", totalScoreRecords == null ? 0 : totalScoreRecords);
            result.put("excellentRate", formatScore(excellentRate));
            result.put("warningStudents", warningStudents == null ? 0 : warningStudents);
            result.put("lineChartConfig", lineChartConfig);
            result.put("pieChartConfig", pieChartConfig);
            result.put("scope", studentScope ? "student" : "global");
            if (studentScope) {
                result.put("studentId", normalizedUserId);
            }
            return result;
        } catch (Exception e) {
            log.error("获取成绩看板统计失败", e);
            return Map.of(
                "error", "成绩统计服务暂时不可用"
            );
        }
    }

    public Map<String, Object> getStudentComprehensiveInsights(String userId, String role) {
        return getStudentComprehensiveInsights(userId, role, null);
    }

    public Map<String, Object> getStudentComprehensiveInsights(String userId, String role, String identifier) {
        if (!canAccessDashboard(role)) {
            return NO_ACCESS;
        }
        String normalizedRole = role == null ? "" : role.trim().toLowerCase();
        String normalizedUserId = userId == null ? "" : userId.trim();
        String normalizedIdentifier = identifier == null ? "" : identifier.trim();
        boolean isStudentRole = "student".equals(normalizedRole);
        boolean isTeacherOrAdmin = "teacher".equals(normalizedRole) || "admin".equals(normalizedRole);
        if (!isStudentRole && !isTeacherOrAdmin) {
            return Map.of(
                "error", "当前角色不支持该成绩洞察功能"
            );
        }
        try {
            StudentIdentity student;
            String effectiveStudentId;
            if (isStudentRole) {
                if (normalizedUserId.isBlank()) {
                    return Map.of(
                        "error", "学生身份缺少学号信息"
                    );
                }
                student = findStudentByExactIdentifier(normalizedUserId);
                effectiveStudentId = normalizedUserId;
            } else {
                if (normalizedIdentifier.isBlank()) {
                    return Map.of(
                        "error", "请输入学号或姓名后再查询"
                    );
                }
                student = resolveStudentIdentity(normalizedIdentifier);
                effectiveStudentId = student == null ? "" : student.studentId();
            }
            if (student == null) {
                return Map.of(
                    "error", "未找到对应学生，请检查学号或姓名"
                );
            }

            Map<String, Object> dashboard = getDashboardStatistics(effectiveStudentId, "student");
            if (dashboard.containsKey("error")) {
                return dashboard;
            }
            Map<String, Object> radarResult = buildStudentRadarSnapshot(student.studentId(), student.name());
            Map<String, Object> radarChartConfig = new LinkedHashMap<>();
            Map<String, Double> radarData = new LinkedHashMap<>();
            Object chart = radarResult.get("chartConfig");
            if (chart instanceof Map<?, ?> chartMap) {
                radarChartConfig.putAll((Map<String, Object>) chartMap);
            }
            Object radar = radarResult.get("radarData");
            if (radar instanceof Map<?, ?> radarMap) {
                for (Map.Entry<?, ?> entry : radarMap.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    radarData.put(key, formatScore(entry.getValue()));
                }
            }

            List<Map<String, Object>> scoreRows = jdbcTemplate.queryForList(
                "SELECT c.course_name, sc.score, sc.credits, sc.gpa " +
                    "FROM scores sc " +
                    "JOIN courses c ON sc.course_id = c.course_id " +
                    "WHERE sc.student_id = ? " +
                    "ORDER BY sc.score DESC, c.course_name ASC",
                effectiveStudentId
            );
            if (scoreRows.isEmpty()) {
                return Map.of(
                    "error", "该学生暂无成绩数据"
                );
            }

            List<Map<String, Object>> weakCourses = jdbcTemplate.queryForList(
                "SELECT c.course_name, sc.score, sc.credits, sc.gpa " +
                    "FROM scores sc " +
                    "JOIN courses c ON sc.course_id = c.course_id " +
                    "WHERE sc.student_id = ? " +
                    "ORDER BY sc.score ASC, c.course_name ASC " +
                    "LIMIT 5",
                effectiveStudentId
            );
            List<Map<String, Object>> topCourses = jdbcTemplate.queryForList(
                "SELECT c.course_name, sc.score, sc.credits, sc.gpa " +
                    "FROM scores sc " +
                    "JOIN courses c ON sc.course_id = c.course_id " +
                    "WHERE sc.student_id = ? " +
                    "ORDER BY sc.score DESC, c.course_name ASC " +
                    "LIMIT 5",
                effectiveStudentId
            );

            List<String> trendLabels = new ArrayList<>();
            List<Double> trendData = new ArrayList<>();
            List<Map<String, Object>> trendRows;
            try {
                trendRows = jdbcTemplate.queryForList(
                    "SELECT COALESCE(semester, '当前学期') AS semester, IFNULL(AVG(score), 0) AS avg_score " +
                        "FROM scores WHERE student_id = ? GROUP BY COALESCE(semester, '当前学期') ORDER BY semester ASC",
                    effectiveStudentId
                );
            } catch (Exception ex) {
                trendRows = jdbcTemplate.queryForList(
                    "SELECT '当前统计' AS semester, IFNULL(AVG(score), 0) AS avg_score FROM scores WHERE student_id = ?",
                    effectiveStudentId
                );
            }
            for (Map<String, Object> row : trendRows) {
                trendLabels.add(String.valueOf(row.get("semester")));
                trendData.add(formatScore(row.get("avg_score")));
            }
            if (trendLabels.isEmpty()) {
                trendLabels.add("当前");
                trendData.add(0.0);
            }

            Map<String, Object> trendChartConfig = Map.of(
                "type", "line",
                "title", "个人学期平均分趋势",
                "labels", trendLabels,
                "datasets", List.of(Map.of(
                    "label", "学期平均分",
                    "data", trendData
                ))
            );

            List<String> weakLabels = new ArrayList<>();
            List<Double> weakScores = new ArrayList<>();
            for (Map<String, Object> row : weakCourses) {
                weakLabels.add(String.valueOf(row.get("course_name")));
                weakScores.add(formatScore(row.get("score")));
            }
            Map<String, Object> weakCourseChartConfig = Map.of(
                "type", "bar",
                "title", "个人待提升课程（低分优先）",
                "labels", weakLabels,
                "datasets", List.of(Map.of(
                    "label", "课程分数",
                    "data", weakScores
                ))
            );

            List<String> aiSuggestions = buildAiSuggestions(dashboard, radarData, weakCourses, topCourses);
            String primaryFocus = resolvePrimaryFocus(radarData, weakCourses);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scope", "student");
            result.put("studentId", effectiveStudentId);
            result.put("studentName", student.name());
            result.put("requestedByRole", normalizedRole);
            if (!isStudentRole) {
                result.put("queryKeyword", normalizedIdentifier);
            }
            result.put("overview", dashboard);
            result.put("radarData", radarData);
            result.put("radarChartConfig", radarChartConfig);
            result.put("trendChartConfig", trendChartConfig);
            result.put("weakCourseChartConfig", weakCourseChartConfig);
            result.put("weakCourses", weakCourses);
            result.put("topCourses", topCourses);
            result.put("aiSuggestions", aiSuggestions);
            result.put("primaryFocus", primaryFocus);
            return result;
        } catch (Exception e) {
            log.error("获取学生综合分析失败", e);
            return Map.of(
                "error", "学生综合分析服务暂时不可用"
            );
        }
    }

    public Map<String, Object> getMajorStatistics() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT major, department FROM students WHERE major IS NOT NULL AND TRIM(major) <> ''"
            );

            Set<String> majorSet = new LinkedHashSet<>();
            Set<String> departmentSet = new LinkedHashSet<>();
            Set<String> directionSet = new LinkedHashSet<>();

            for (Map<String, Object> row : rows) {
                String majorRaw = row.get("major") == null ? "" : String.valueOf(row.get("major"));
                String departmentRaw = row.get("department") == null ? "" : String.valueOf(row.get("department"));

                String majorKey = normalizeMajorName(majorRaw);
                if (!majorKey.isBlank()) {
                    majorSet.add(majorKey);
                    directionSet.addAll(extractDirectionTokens(majorRaw));
                }

                String departmentKey = normalizeLabel(departmentRaw);
                if (!departmentKey.isBlank()) {
                    departmentSet.add(departmentKey);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("uniqueMajorCount", majorSet.size());
            result.put("departmentCount", departmentSet.size());
            result.put("directionCount", directionSet.size());
            result.put("studentRowsWithMajor", rows.size());
            return result;
        } catch (Exception e) {
            log.error("获取专业统计失败", e);
            return Map.of(
                "error", "专业统计服务暂时不可用"
            );
        }
    }

    /**
     * 获取指定学生的学业雷达图数据（五个能力维度得分）
     * @param identifier 学号或姓名
     */
    @Tool("获取指定学生的学业雷达图数据，包括数理逻辑、工程实践、人文感悟、持续耐力、极限爆发五个维度的能力得分。")
    public Map<String, Object> getStudentRadarChart(String identifier) {
        log.info("Tool: 正在生成学生学业雷达图: {}", identifier);
        OrchestrationContext.recordToolInvoke("DATA");
        if (!canAccessDataTools()) {
            return NO_ACCESS;
        }
        try {
            StudentIdentity student = resolveStudentIdentity(identifier);
            if (student == null) {
                return Map.of("found", false, "message", "未找到该学生信息");
            }
            String studentId = student.studentId();
            if (!canAccessStudentScope(studentId)) {
                return NO_ACCESS;
            }
            String studentName = student.name();
            Map<String, Object> radarSnapshot = buildStudentRadarSnapshot(studentId, studentName);

            return Map.of(
                "tool", "getStudentRadarChart",
                "studentName", studentName,
                "studentId", studentId,
                "found", true,
                "radarData", radarSnapshot.get("radarData"),
                "chartConfig", radarSnapshot.get("chartConfig"),
                "description", String.format("已成功提取学生 %s 的五维能力画像。数值越高代表在该领域能力越强。", studentName)
            );
        } catch (Exception e) {
            log.error("雷达图数据提取失败", e);
            return Map.of("found", false, "error", "数据提取异常");
        }
    }

    private Map<String, Object> buildStudentRadarSnapshot(String studentId, String studentName) {
        String radarSql =
            "SELECT " +
                "  AVG(CASE WHEN c.course_name REGEXP '数学|物理|代数|微积分|离散|概率|统计|几何' THEN sc.score ELSE NULL END) as logical, " +
                "  AVG(CASE WHEN c.course_name REGEXP '程序|设计|实验|电路|开发|系统|工程|实训|实践' THEN sc.score ELSE NULL END) as engineering, " +
                "  AVG(CASE WHEN c.course_name REGEXP '英语|语文|艺术|历史|政治|哲学|心理|社会|欣赏|体育' THEN sc.score ELSE NULL END) as humanities, " +
                "  (100 - IFNULL(STDDEV(sc.score), 0) * 2) as stamina " +
            "FROM scores sc " +
            "JOIN courses c ON sc.course_id = c.course_id " +
            "WHERE sc.student_id = ?";
        Map<String, Object> baseRadar = jdbcTemplate.queryForMap(radarSql, studentId);

        String peakSql =
            "SELECT AVG(score) FROM (" +
                "  SELECT score FROM scores WHERE student_id = ? ORDER BY credits DESC LIMIT 3" +
            ") as top_scores";
        Double peakScore = jdbcTemplate.queryForObject(peakSql, Double.class, studentId);

        Map<String, Double> radarData = new LinkedHashMap<>();
        radarData.put("数理逻辑", formatScore(baseRadar.get("logical")));
        radarData.put("工程实践", formatScore(baseRadar.get("engineering")));
        radarData.put("人文感悟", formatScore(baseRadar.get("humanities")));
        radarData.put("持续耐力", formatScore(baseRadar.get("stamina")));
        radarData.put("极限爆发", formatScore(peakScore));

        Map<String, Object> chartConfig = Map.of(
            "type", "radar",
            "title", String.format("%s 的五维能力雷达图", studentName),
            "labels", radarData.keySet(),
            "datasets", List.of(Map.of(
                "label", "能力评分",
                "data", radarData.values()
            ))
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("radarData", radarData);
        result.put("chartConfig", chartConfig);
        return result;
    }

    /**
     * 获取指定学生的学分分布占比
     * @param identifier 学号或姓名
     */
    @Tool("获取指定学生的学分分布占比，按数理逻辑、工程实践、人文感悟、其他四个维度统计。")
    public Map<String, Object> getStudentCreditDistribution(String identifier) {
        log.info("Tool: 正在查询学生学分分布: {}", identifier);
        OrchestrationContext.recordToolInvoke("DATA");
        if (!canAccessDataTools()) {
            return NO_ACCESS;
        }
        try {
            StudentIdentity student = resolveStudentIdentity(identifier);
            if (student == null) {
                return Map.of("found", false, "message", "未找到该学生信息");
            }
            String studentId = student.studentId();
            if (!canAccessStudentScope(studentId)) {
                return NO_ACCESS;
            }
            String studentName = student.name();

            String sql = 
                "SELECT " +
                "  SUM(CASE WHEN c.course_name REGEXP '数学|物理|代数|微积分|离散|概率|统计|几何' THEN c.credits ELSE 0 END) as logical, " +
                "  SUM(CASE WHEN c.course_name REGEXP '程序|设计|实验|电路|开发|系统|工程|实训|实践' THEN c.credits ELSE 0 END) as engineering, " +
                "  SUM(CASE WHEN c.course_name REGEXP '英语|语文|艺术|历史|政治|哲学|心理|社会|欣赏|体育' THEN c.credits ELSE 0 END) as humanities, " +
                "  SUM(CASE WHEN c.course_name NOT REGEXP '数学|物理|代数|微积分|离散|概率|统计|几何|程序|设计|实验|电路|开发|系统|工程|实训|实践|英语|语文|艺术|历史|政治|哲学|心理|社会|欣赏|体育' THEN c.credits ELSE 0 END) as others " +
                "FROM scores sc " +
                "JOIN courses c ON sc.course_id = c.course_id " +
                "WHERE sc.student_id = ?";
            
            Map<String, Object> distribution = jdbcTemplate.queryForMap(sql, studentId);
            
            // 2. 统一图形化 JSON 协议 (ChartConfig)
            Map<String, Double> chartData = new LinkedHashMap<>();
            chartData.put("数理逻辑", formatScore(distribution.get("logical")));
            chartData.put("工程实践", formatScore(distribution.get("engineering")));
            chartData.put("人文感悟", formatScore(distribution.get("humanities")));
            chartData.put("其他", formatScore(distribution.get("others")));

            Map<String, Object> chartConfig = Map.of(
                "type", "pie",
                "title", String.format("%s 的学分分布占比", studentName),
                "labels", chartData.keySet(),
                "datasets", List.of(Map.of(
                    "label", "学分占比",
                    "data", chartData.values()
                ))
            );

            return Map.of(
                "tool", "getStudentCreditDistribution",
                "studentName", studentName,
                "found", true,
                "distribution", chartData,
                "chartConfig", chartConfig,
                "totalCredits", chartData.values().stream().mapToDouble(Double::doubleValue).sum()
            );
        } catch (Exception e) {
            log.error("雷达图数据提取失败", e);
            return Map.of("found", false, "error", "数据提取异常");
        }
    }

    private double formatScore(Object score) {
        if (score == null) return 0.0;
        try {
            double val = Double.parseDouble(score.toString());
            return Math.round(val * 10.0) / 10.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String buildScoreMarkdownTable(List<Map<String, Object>> results) {
        StringBuilder table = new StringBuilder();
        table.append("\n\n"); // 头部空行
        table.append("| 课程名称 | 成绩 | 学分 | 绩点 |\n");
        table.append("|---|---:|---:|---:|\n");
        for (Map<String, Object> row : results) {
            table.append("| ")
                .append(String.valueOf(row.get("course_name")))
                .append(" | ")
                .append(formatScore(row.get("score")))
                .append(" | ")
                .append(formatScore(row.get("credits")))
                .append(" | ")
                .append(formatScore(row.get("gpa")))
                .append(" |\n");
        }
        table.append("\n"); // 尾部空行
        return table.toString();
    }

    private Map<String, Object> buildScoreChartConfig(String studentName, List<Map<String, Object>> results) {
        List<String> labels = new ArrayList<>();
        List<Double> data = new ArrayList<>();
        for (Map<String, Object> row : results) {
            labels.add(String.valueOf(row.get("course_name")));
            data.add(formatScore(row.get("score")));
        }
        return Map.of(
            "type", "bar",
            "title", String.format("%s 的课程成绩分布", studentName),
            "labels", labels,
            "datasets", List.of(Map.of(
                "label", "课程成绩",
                "data", data
            ))
        );
    }

    private List<String> buildAiSuggestions(
        Map<String, Object> dashboard,
        Map<String, Double> radarData,
        List<Map<String, Object>> weakCourses,
        List<Map<String, Object>> topCourses
    ) {
        List<String> suggestions = new ArrayList<>();
        double average = formatScore(((Map<?, ?>) dashboard).get("excellentRate"));
        if (average >= 60) {
            suggestions.add("建议把优势课程的学习方法固化为每周复盘模板，保持稳定输出。");
        } else {
            suggestions.add("建议先建立固定学习节奏：每周至少2次错题回顾+1次章节总结，优先稳住基础分。");
        }

        if (!radarData.isEmpty()) {
            String weakDimension = "";
            double weakScore = Double.MAX_VALUE;
            for (Map.Entry<String, Double> entry : radarData.entrySet()) {
                if (entry.getValue() < weakScore) {
                    weakScore = entry.getValue();
                    weakDimension = entry.getKey();
                }
            }
            if (!weakDimension.isBlank()) {
                suggestions.add(String.format("当前能力短板集中在「%s」（%.1f分），建议围绕该维度每周补充2门对应课程训练。", weakDimension, weakScore));
            }
        }

        if (!weakCourses.isEmpty()) {
            Map<String, Object> course = weakCourses.get(0);
            String weakCourse = String.valueOf(course.get("course_name"));
            double weakScore = formatScore(course.get("score"));
            suggestions.add(String.format("优先提升《%s》（%.1f分）：先做近三次考试题型归因，再按题型建立专项练习清单。", weakCourse, weakScore));
        }

        if (!topCourses.isEmpty()) {
            Map<String, Object> bestCourse = topCourses.get(0);
            String bestCourseName = String.valueOf(bestCourse.get("course_name"));
            double bestScore = formatScore(bestCourse.get("score"));
            suggestions.add(String.format("你的优势科目是《%s》（%.1f分），建议把该科目的方法迁移到弱项课程。", bestCourseName, bestScore));
        }

        if (suggestions.size() < 4) {
            suggestions.add("建议每次考试后在48小时内完成复盘，重点记录失分原因与可执行改进动作。");
        }
        return suggestions;
    }

    private String resolvePrimaryFocus(Map<String, Double> radarData, List<Map<String, Object>> weakCourses) {
        if (!radarData.isEmpty()) {
            String weakDimension = "";
            double weakScore = Double.MAX_VALUE;
            for (Map.Entry<String, Double> entry : radarData.entrySet()) {
                if (entry.getValue() < weakScore) {
                    weakScore = entry.getValue();
                    weakDimension = entry.getKey();
                }
            }
            if (!weakDimension.isBlank()) {
                return weakDimension;
            }
        }
        if (!weakCourses.isEmpty()) {
            return String.valueOf(weakCourses.get(0).get("course_name"));
        }
        return "基础能力";
    }

    private StudentIdentity resolveStudentIdentity(String rawIdentifier) {
        String normalized = rawIdentifier == null ? "" : rawIdentifier.trim();
        if (normalized.isBlank()) return null;

        StudentIdentity exact = findStudentByExactIdentifier(normalized);
        if (exact != null) return exact;

        String studentIdCandidate = extractStudentId(normalized);
        if (!studentIdCandidate.equals(normalized)) {
            StudentIdentity byId = findStudentByExactIdentifier(studentIdCandidate);
            if (byId != null) return byId;
        }

        String nameCandidate = extractChineseName(normalized);
        if (!nameCandidate.equals(normalized)) {
            StudentIdentity byExtractedName = findStudentByExactIdentifier(nameCandidate);
            if (byExtractedName != null) return byExtractedName;
        }

        StudentIdentity fuzzy = findStudentByFuzzyName(nameCandidate);
        if (fuzzy != null) return fuzzy;

        return findStudentByFuzzyName(normalized);
    }

    private StudentIdentity findStudentByExactIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) return null;
        String sql = "SELECT student_id, name FROM students WHERE student_id = ? OR name = ? LIMIT 1";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, identifier.trim(), identifier.trim());
        if (rows.isEmpty()) return null;
        return new StudentIdentity(
            String.valueOf(rows.get(0).get("student_id")),
            String.valueOf(rows.get(0).get("name"))
        );
    }

    private StudentIdentity findStudentByFuzzyName(String name) {
        if (name == null || name.isBlank()) return null;
        String sql = "SELECT student_id, name FROM students WHERE name LIKE ? LIMIT 2";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, "%" + name.trim() + "%");
        if (rows.size() != 1) return null;
        return new StudentIdentity(
            String.valueOf(rows.get(0).get("student_id")),
            String.valueOf(rows.get(0).get("name"))
        );
    }

    private String extractStudentId(String text) {
        if (text == null || text.isBlank()) return "";
        Matcher matcher = STUDENT_ID_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return text.trim();
    }

    private String extractChineseName(String text) {
        if (text == null || text.isBlank()) return "";
        Matcher matcher = CHINESE_NAME_PATTERN.matcher(text.trim());
        if (matcher.find()) {
            return matcher.group();
        }
        return text.trim();
    }

    /**
     * 旧版搜索接口 (保持兼容，但内部逻辑可以简化)
     */
    public String getScoreData(String query) {
        log.info("ScoreService: 正在尝试从业务系统查询成绩数据: {}", query);
        if (!canAccessDataTools()) {
            return "无访问权限";
        }
        
        // 1. 获取所有学生姓名
        List<String> potentialNames;
        try {
            potentialNames = jdbcTemplate.queryForList("SELECT name FROM students", String.class);
        } catch (Exception e) {
            log.error("获取所有学生姓名失败", e);
            potentialNames = java.util.Arrays.asList("张三", "李四", "王五"); // 兜底
        }

        java.util.List<String> targetNames = new java.util.ArrayList<>();
        for (String name : potentialNames) {
            if (query.contains(name)) {
                targetNames.add(name);
            }
        }

        // 2. 特殊逻辑：如果用户要求“罗列所有人”、“全部”、“所有学生”或提及“三个人”等
        boolean listAll = query.contains("所有人") || query.contains("全部") || query.contains("所有学生") 
            || query.contains("所有") || query.contains("罗列出来");
        if (targetNames.isEmpty() && listAll) {
            targetNames.addAll(potentialNames);
        }

        // 3. 执行数据库查询并拼接结果
        if (!targetNames.isEmpty()) {
            StringBuilder resultBuilder = new StringBuilder("【实时教务数据】找到以下学生成绩记录：\n");
            boolean foundAny = false;
            for (String name : targetNames) {
                try {
                    String sql = "SELECT c.course_name, sc.score, sc.credits, sc.gpa " +
                                 "FROM students s " +
                                 "JOIN scores sc ON s.student_id = sc.student_id " +
                                 "JOIN courses c ON sc.course_id = c.course_id " +
                                 "WHERE s.name = ?";
                    List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, name);
                    if (!results.isEmpty()) {
                        resultBuilder.append("- ").append(name).append(": ");
                        for (int i = 0; i < results.size(); i++) {
                            Map<String, Object> row = results.get(i);
                            resultBuilder.append(String.format("《%s》%s分 (绩点:%s)", 
                                row.get("course_name"), row.get("score"), row.get("gpa")));
                            if (i < results.size() - 1) resultBuilder.append("；");
                        }
                        resultBuilder.append("\n");
                        foundAny = true;
                    }
                } catch (Exception e) {
                    log.error("查询学生 {} 失败", name, e);
                }
            }
            if (foundAny) return resultBuilder.toString().trim();
        }

        // 4. 通用查询统计
        if (query.contains("成绩") || query.contains("分数") || query.contains("统计") 
            || query.contains("学生") || query.contains("数据") || query.contains("名单")) {
            try {
                String countSql = "SELECT COUNT(*) FROM students";
                Integer count = jdbcTemplate.queryForObject(countSql, Integer.class);
                String listSql = "SELECT name FROM students";
                List<String> names = jdbcTemplate.queryForList(listSql, String.class);
                return String.format("【实时教务数据】当前系统内共有 %d 名学生的成绩记录可用（%s）。", 
                    (count != null ? count : 0), String.join("、", names));
            } catch (Exception e) {
                log.error("统计查询失败", e);
            }
        }

        return null;
    }

    private boolean canAccessDataTools() {
        return !isGuestRole(OrchestrationContext.getUserRole());
    }

    private boolean canAccessStudentScope(String targetStudentId) {
        if (targetStudentId == null || targetStudentId.isBlank()) return false;
        String role = normalizeRole(OrchestrationContext.getUserRole());
        if (isGuestRole(role)) return false;
        if ("student".equals(role)) {
            String requesterStudentId = normalizeSessionId(OrchestrationContext.getSessionId());
            return requesterStudentId != null && requesterStudentId.equals(targetStudentId);
        }
        return "teacher".equals(role) || "admin".equals(role);
    }

    private boolean isStudentRole(String role) {
        return "student".equals(normalizeRole(role));
    }

    private String normalizeRole(String role) {
        if (role == null) return "";
        return role.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null) return null;
        String normalized = sessionId.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeMajorName(String text) {
        String normalized = normalizeLabel(text);
        while (normalized.endsWith("专业")) {
            normalized = normalized.substring(0, normalized.length() - 2).trim();
        }
        return normalized;
    }

    private String normalizeLabel(String text) {
        if (text == null) return "";
        String normalized = text
            .replace('\u3000', ' ')
            .trim()
            .replaceAll("\\s+", " ");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private List<String> extractDirectionTokens(String majorRaw) {
        List<String> tokens = new ArrayList<>();
        if (majorRaw == null || majorRaw.isBlank()) return tokens;
        String normalized = majorRaw.replace('\u3000', ' ').trim();
        String[] parts = normalized.split("[、,，/\\\\|;；]");
        for (String part : parts) {
            String p = part == null ? "" : part.trim();
            if (!p.isBlank() && p.contains("方向")) {
                tokens.add(normalizeLabel(p));
            }
        }
        Matcher bracketMatcher = Pattern.compile("[（(]([^）)]*方向[^）)]*)[）)]").matcher(normalized);
        while (bracketMatcher.find()) {
            String token = normalizeLabel(bracketMatcher.group(1));
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private boolean canAccessDashboard(String role) {
        return !isGuestRole(role);
    }

    private boolean isGuestRole(String role) {
        if (role == null) return true;
        String normalized = role.trim().toLowerCase();
        return normalized.isEmpty()
            || "guest".equals(normalized)
            || "anonymous".equals(normalized)
            || "visitor".equals(normalized)
            || "all".equals(normalized);
    }

    private record StudentIdentity(String studentId, String name) {}
}
