package com.kira.server.service.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kira.server.config.OssProperties;
import com.kira.server.controller.knowledge.dto.FileRecordDTO;
import com.kira.server.domain.entity.FileRecord;
import com.kira.server.exception.DuplicateFileException;
import com.kira.server.mapper.FileRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FileStorageService 单元测试
 *
 * @author Kira
 * @create 2026-02-27
 */
@ExtendWith(MockitoExtension.class)
class FileStorageServiceTest {

    @Mock
    private FileRecordMapper fileRecordMapper;

    @Mock
    private OssProperties ossProperties;

    @InjectMocks
    private FileStorageService fileStorageService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(fileStorageService, "ossProperties", ossProperties);
        when(ossProperties.getBucketName()).thenReturn("test-bucket");
    }

    @Test
    void testUploadNewFile_Success() {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "test content".getBytes(StandardCharsets.UTF_8)
        );

        when(fileRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        // Act
        FileRecordDTO result = fileStorageService.uploadAndCheckDuplicate(file, "技术文档", null);

        // Assert
        assertNotNull(result);
        assertEquals("test.pdf", result.getOriginalFilename());
        assertEquals("技术文档", result.getCategory());
        verify(fileRecordMapper, times(1)).insert(any(FileRecord.class));
    }

    @Test
    void testUploadDuplicateFile_ThrowsException() {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "test content".getBytes(StandardCharsets.UTF_8)
        );

        FileRecord existingRecord = FileRecord.builder()
                .id(1L)
                .originalFilename("test.pdf")
                .category("技术文档")
                .build();

        when(fileRecordMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(existingRecord);

        // Act & Assert
        DuplicateFileException exception = assertThrows(
                DuplicateFileException.class,
                () -> fileStorageService.uploadAndCheckDuplicate(file, "技术文档", null)
        );

        assertTrue(exception.getMessage().contains("文件已存在"));
        assertEquals("test.pdf", exception.getFilename());
        assertEquals("技术文档", exception.getCategory());
    }

    @Test
    void testUploadSameFilenameDifferentCategory_Success() {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "test content".getBytes(StandardCharsets.UTF_8)
        );

        // 第一次上传 - 技术文档分类
        when(fileRecordMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(null);

        FileRecordDTO result1 = fileStorageService.uploadAndCheckDuplicate(file, "技术文档", null);
        assertNotNull(result1);

        // 第二次上传 - 用户手册分类（返回 null 表示不存在）
        when(fileRecordMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(null);

        FileRecordDTO result2 = fileStorageService.uploadAndCheckDuplicate(file, "用户手册", null);
        assertNotNull(result2);
        assertNotEquals(result1.getId(), result2.getId());
    }
}
