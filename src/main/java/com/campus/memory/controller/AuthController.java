package com.campus.memory.controller;

import com.campus.memory.dto.LoginRequest;
import com.campus.memory.dto.LoginResponse;
import com.campus.memory.dto.RegisterRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final JdbcTemplate jdbcTemplate;
    private static final Set<String> ALLOWED_REGISTER_ROLES = Set.of("student", "teacher");

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        String password = request.getPassword() == null ? "" : request.getPassword().trim();
        log.info("收到登录请求: {}", username);

        if (username.isBlank() || password.isBlank()) {
            return LoginResponse.builder()
                .success(false)
                .message("账号和密码不能为空")
                .build();
        }

        String sql = "SELECT username, role FROM users WHERE username = ? AND password = ?";
        List<Map<String, Object>> users = jdbcTemplate.queryForList(sql, username, password);

        if (!users.isEmpty()) {
            Map<String, Object> user = users.get(0);
            String role = (String) user.get("role");
            String account = (String) user.get("username");
            String displayName = resolveDisplayName(role, account);

            log.info("登录成功: {}, 角色: {}", account, role);
            return LoginResponse.builder()
                    .success(true)
                    .message("登录成功")
                    .userId(account)
                    .username(displayName)
                    .displayName(displayName)
                    .role(role)
                    .build();
        } else {
            log.warn("登录失败: 账号或密码错误 - {}", username);
            return LoginResponse.builder()
                    .success(false)
                    .message("账号或密码错误")
                    .build();
        }
    }

    @PostMapping("/register")
    public LoginResponse register(@RequestBody RegisterRequest request) {
        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        String password = request.getPassword() == null ? "" : request.getPassword().trim();
        String role = request.getRole() == null ? "student" : request.getRole().trim().toLowerCase();
        String displayName = username;

        if (username.isBlank() || password.isBlank()) {
            return LoginResponse.builder()
                .success(false)
                .message("账号和密码不能为空")
                .build();
        }
        if (username.length() < 3) {
            return LoginResponse.builder()
                .success(false)
                .message("账号至少 3 位")
                .build();
        }
        if (password.length() < 6) {
            return LoginResponse.builder()
                .success(false)
                .message("密码至少 6 位")
                .build();
        }
        if (!ALLOWED_REGISTER_ROLES.contains(role)) {
            return LoginResponse.builder()
                .success(false)
                .message("注册角色仅支持 student 或 teacher")
                .build();
        }
        if ("student".equals(role)) {
            List<Map<String, Object>> students = jdbcTemplate.queryForList(
                "SELECT name FROM students WHERE student_id = ? LIMIT 1",
                username
            );
            if (students.isEmpty()) {
                return LoginResponse.builder()
                    .success(false)
                    .message("该学号不在学籍库中，无法注册")
                    .build();
            }
            Object studentName = students.get(0).get("name");
            if (studentName != null) {
                displayName = String.valueOf(studentName);
            }
        }

        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?",
                Integer.class,
                username
            );
            if (count != null && count > 0) {
                return LoginResponse.builder()
                    .success(false)
                    .message("账号已存在")
                    .build();
            }

            jdbcTemplate.update(
                "INSERT INTO users (username, password, role) VALUES (?, ?, ?)",
                username,
                password,
                role
            );

            log.info("注册成功: {}, 角色: {}", username, role);
            return LoginResponse.builder()
                .success(true)
                .message("注册成功")
                .userId(username)
                .username(displayName)
                .displayName(displayName)
                .role(role)
                .build();
        } catch (Exception e) {
            log.error("注册失败: {}", username, e);
            return LoginResponse.builder()
                .success(false)
                .message("注册失败，请稍后重试")
                .build();
        }
    }

    private String resolveDisplayName(String role, String account) {
        if (!"student".equalsIgnoreCase(role)) {
            return account;
        }
        try {
            List<String> names = jdbcTemplate.queryForList(
                "SELECT name FROM students WHERE student_id = ? LIMIT 1",
                String.class,
                account
            );
            if (!names.isEmpty() && names.get(0) != null && !names.get(0).isBlank()) {
                return names.get(0);
            }
            return account;
        } catch (Exception e) {
            log.warn("查询学生姓名失败，回退账号展示: {}", account);
            return account;
        }
    }
}
