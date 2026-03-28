package com.campus.memory.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class ScoreExcelDTO {
    @ExcelProperty("学号")
    private String studentId;
    @ExcelProperty("课程号")
    private String courseId;
    @ExcelProperty("成绩")
    private Double score;
    @ExcelProperty("学分")
    private Double credits;
    @ExcelProperty("绩点")
    private Double gpa;
}
