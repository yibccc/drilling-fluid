package com.kira.server.exception;

import lombok.Getter;

/**
 * 文件重复异常
 * 当上传的文件在相同分类下已存在时抛出
 *
 * @author Kira
 * @create 2026-02-27
 */
@Getter
public class DuplicateFileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 文件名
     */
    private final String filename;

    /**
     * 分类
     */
    private final String category;

    /**
     * 构造函数
     *
     * @param message  异常消息
     * @param filename 文件名
     * @param category 分类
     */
    public DuplicateFileException(String message, String filename, String category) {
        super(message);
        this.filename = filename;
        this.category = category;
    }

    /**
     * 构造函数（仅消息）
     *
     * @param message 异常消息
     */
    public DuplicateFileException(String message) {
        super(message);
        this.filename = null;
        this.category = null;
    }
}
