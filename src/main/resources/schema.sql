-- 基础表：学生信息 (学籍.xlsx)
CREATE TABLE IF NOT EXISTS students (
    id INT AUTO_INCREMENT PRIMARY KEY,
    student_id VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(50) NOT NULL,
    gender VARCHAR(10),
    department VARCHAR(100),
    major VARCHAR(100),
    birthday VARCHAR(50),
    ethnicity VARCHAR(50),
    political_status VARCHAR(50)
);

-- 基础表：课程信息 (课程.xlsx)
CREATE TABLE IF NOT EXISTS courses (
    id INT AUTO_INCREMENT PRIMARY KEY,
    course_id VARCHAR(20) NOT NULL UNIQUE,
    course_name VARCHAR(100) NOT NULL,
    credits DECIMAL(3, 1),
    total_hours INT
);

-- 关联表：成绩信息 (成绩.xlsx)
CREATE TABLE IF NOT EXISTS scores (
    id INT AUTO_INCREMENT PRIMARY KEY,
    student_id VARCHAR(20) NOT NULL,
    course_id VARCHAR(20) NOT NULL,
    score DECIMAL(5, 2),
    credits DECIMAL(3, 1),
    gpa DECIMAL(3, 2),
    UNIQUE KEY uk_student_course (student_id, course_id),
    FOREIGN KEY (student_id) REFERENCES students(student_id),
    FOREIGN KEY (course_id) REFERENCES courses(course_id)
);

-- 兼容旧代码：学生摘要成绩表
CREATE TABLE IF NOT EXISTS student_scores (
    id INT AUTO_INCREMENT PRIMARY KEY,
    student_name VARCHAR(50) NOT NULL,
    score_details TEXT NOT NULL
);

-- 用户权限表
CREATE TABLE IF NOT EXISTS users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL
);