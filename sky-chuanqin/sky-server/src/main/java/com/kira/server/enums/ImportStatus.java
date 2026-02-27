package com.kira.server.enums;

/**
 * 知识库文档导入状态枚举
 *
 * @author Kira
 * @create 2026-02-26
 */
public enum ImportStatus {
    /**
     * 待处理 - 文档已创建，等待处理
     */
    PENDING("待处理", 0),

    /**
     * 解析中 - Tika 正在解析文档
     */
    PARSING("解析中", 1),

    /**
     * 解析完成 - 内容已提取，等待入队
     */
    PARSED("解析完成", 2),

    /**
     * 已入队 - Redis Stream 消息已发送
     */
    QUEUED("已入队", 3),

    /**
     * 分块中 - Agent 正在进行文档分块
     */
    CHUNKING("分块中", 4),

    /**
     * 向量化中 - 正在生成向量
     */
    EMBEDDING("向量化中", 5),

    /**
     * 完成 - 导入流程全部完成
     */
    COMPLETED("完成", 6),

    /**
     * 失败 - 导入过程中出错
     */
    FAILED("失败", 7);

    /**
     * 状态描述
     */
    private final String description;

    /**
     * 状态码（用于排序和比较）
     */
    private final int code;

    /**
     * 构造函数
     */
    ImportStatus(String description, int code) {
        this.description = description;
        this.code = code;
    }

    /**
     * 获取状态描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取状态码
     */
    public int getCode() {
        return code;
    }

    /**
     * 根据名称获取枚举值
     *
     * @param name 枚举名称
     * @return 枚举值，未找到返回 null
     */
    public static ImportStatus fromName(String name) {
        if (name == null) {
            return null;
        }
        try {
            return ImportStatus.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 判断是否为终态（不再变化的状态）
     *
     * @return true 表示终态，false 表示中间态
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    /**
     * 判断是否为处理中的状态
     *
     * @return true 表示处理中，false 表示非处理中
     */
    public boolean isProcessing() {
        return this == PARSING || this == CHUNKING || this == EMBEDDING;
    }
}
