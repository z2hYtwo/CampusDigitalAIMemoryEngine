package com.campus.memory.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxSizeException(MaxUploadSizeExceededException e) {
        log.error("文件上传大小超出限制", e);
        Map<String, Object> body = new HashMap<>();
        body.put("status", 400);
        body.put("error", "Payload Too Large");
        body.put("message", "文件上传失败：单个文件大小超出系统限制。");
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParamException(MissingServletRequestParameterException e) {
        log.error("请求缺少必要参数: {}", e.getParameterName());
        Map<String, Object> body = new HashMap<>();
        body.put("status", 400);
        body.put("error", "Bad Request");
        body.put("message", "请求缺少必要参数: " + e.getParameterName());
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.error("非法参数请求", e);
        Map<String, Object> body = new HashMap<>();
        body.put("status", 400);
        body.put("error", "Bad Request");
        body.put("message", e.getMessage());
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("系统发生未捕获异常: {}", e.getMessage(), e);
        Map<String, Object> body = new HashMap<>();
        body.put("status", 500);
        body.put("error", "Internal Server Error");
        body.put("message", "系统繁忙，请稍后再试。详细错误: " + e.getMessage());
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
