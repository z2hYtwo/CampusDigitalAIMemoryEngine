package com.campus.memory.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class StudentExcelDTO {
    @ExcelProperty("学号")
    private String studentId;
    @ExcelProperty("姓名")
    private String name;
    @ExcelProperty("性别")
    private String gender;
    @ExcelProperty("学院")
    private String department;
    @ExcelProperty("专业")
    private String major;
    @ExcelProperty("生日")
    private String birthday;
    @ExcelProperty("民族")
    private String ethnicity;
    @ExcelProperty("政治面貌")
    private String politicalStatus;
}
