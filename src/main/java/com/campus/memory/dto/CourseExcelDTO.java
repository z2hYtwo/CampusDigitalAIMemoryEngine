package com.campus.memory.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class CourseExcelDTO {
    @ExcelProperty("课程号")
    private String courseId;
    @ExcelProperty("课程名")
    private String courseName;
    @ExcelProperty("学分")
    private Double credits;
    @ExcelProperty("总学时")
    private Integer totalHours;
}
