package com.kira.server.enums;

/**
 * Redis键枚举类
 * @author Kira
 * @create 2025-08-31 15:57
 */
public enum RedisKeys {
    /**
     * 井号
     */
    WELL_NAME("well_name", -1),

    /**
     * 采集中的井号
     */
    WELL_NAME_HARVEST("well_name_harvest", -1),
    
    /**
     * 区块号
     */
    LOCATION_NAME("location_name", -1),

    /**
     * 采集中的区块号
     */
    LOCATION_NAME_HARVEST("location_name_harvest", -1),
    
    /**
     * 状态：0：油基/1：水基
     */
    STATUS("status", -1);

    /**
     * 键的前缀模板
     */
    private final String keyPrefix;

    /**
     * 默认过期时间（秒）
     * -1 表示永不过期
     */
    private final int defaultTtl;

    /**
     * 构造函数
     */
    RedisKeys(String keyPrefix, int defaultTtl) {
        this.keyPrefix = keyPrefix;
        this.defaultTtl = defaultTtl;
    }

    // ============== 核心方法 ==============

    /**
     * 生成完整的Redis键（无后缀）
     * @return 完整的Redis键
     */
    public String getKey() {
        return this.keyPrefix;
    }

    /**
     * 生成完整的Redis键
     * @param suffix 键的后缀部分
     * @return 完整的Redis键
     */
    public String getKey(String suffix) {
        if (suffix == null || suffix.trim().isEmpty()) {
            return this.keyPrefix;
        }
        return this.keyPrefix + suffix;
    }

    /**
     * 生成完整的Redis键（支持多个后缀）
     * @param suffixes 键的后缀部分（多个）
     * @return 完整的Redis键
     */
    public String getKey(String... suffixes) {
        if (suffixes == null || suffixes.length == 0) {
            return this.keyPrefix;
        }
        return this.keyPrefix + String.join(":", suffixes);
    }

    /**
     * 获取键前缀
     * @return 键前缀
     */
    public String getKeyPrefix() {
        return keyPrefix;
    }

    /**
     * 获取默认过期时间
     * @return 过期时间（秒），-1表示永不过期
     */
    public int getDefaultTtl() {
        return defaultTtl;
    }

    /**
     * 判断是否永不过期
     * @return true表示永不过期，false表示有过期时间
     */
    public boolean isNeverExpire() {
        return defaultTtl == -1;
    }
}
