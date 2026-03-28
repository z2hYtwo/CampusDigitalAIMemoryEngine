-- 用户权限数据
INSERT IGNORE INTO users (username, password, role) VALUES ('student', 'student123', 'student');
INSERT IGNORE INTO users (username, password, role) VALUES ('teacher', 'teacher123', 'teacher');
INSERT IGNORE INTO users (username, password, role) VALUES ('admin', 'admin123', 'admin');
