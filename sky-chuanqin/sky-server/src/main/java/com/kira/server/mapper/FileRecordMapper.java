package com.kira.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kira.server.domain.entity.FileRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件存储记录 Mapper
 *
 * @author Kira
 * @create 2026-02-27
 */
@Mapper
public interface FileRecordMapper extends BaseMapper<FileRecord> {

}
