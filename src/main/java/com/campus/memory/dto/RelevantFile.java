package com.campus.memory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelevantFile {
    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 在 MinIO 中的对象名称
     */
    private String objectName;

    /**
     * 文件下载/查看的 URL (可选，也可由前端拼接)
     */
    private String url;

    /**
     * 来源类型 (official, private, multimedia, link 等)
     */
    private String sourceType;

    /**
     * 是否是私有资产
     */
    private Boolean isPrivate;
}
