package com.wzw.knowledge.service.impl;

import com.wzw.knowledge.common.ResultCode;
import com.wzw.knowledge.config.MinioConfig;
import com.wzw.knowledge.exception.BusinessException;
import com.wzw.knowledge.service.MinioService;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * MinIO对象存储服务实现类
 *
 * @author wzw
 * @version 2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioServiceImpl implements MinioService {
    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    @Override
    public String uploadFile(String objectName, InputStream inputStream, String contentType) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .object(objectName)
                            .stream(inputStream, -1, 10485760) // 10MB part size
                            .contentType(contentType)
                            .build()
            );
            String url = getFileUrl(objectName);
            log.info("文件上传MinIO成功: {}", objectName);
            return url;
        } catch (Exception e) {
            log.error("文件上传MinIO失败: {}", objectName, e);
            throw new BusinessException(ResultCode.MINIO_ERROR, "文件上传失败: " + e.getMessage());
        }
    }

    @Override
    public String uploadBytes(String objectName, byte[] data, String contentType) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .object(objectName)
                            .stream(bais, data.length, -1)
                            .contentType(contentType)
                            .build()
            );
            String url = getFileUrl(objectName);
            log.info("字节数据上传MinIO成功: {}, size={}", objectName, data.length);
            return url;
        } catch (Exception e) {
            log.error("字节数据上传MinIO失败: {}", objectName, e);
            throw new BusinessException(ResultCode.MINIO_ERROR, "文件上传失败: " + e.getMessage());
        }
    }

    @Override
    public boolean deleteFile(String objectName) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .object(objectName)
                            .build()
            );
            log.info("MinIO文件删除成功: {}", objectName);
            return true;
        } catch (Exception e) {
            log.error("MinIO文件删除失败: {}", objectName, e);
            return false;
        }
    }

    @Override
    public String getFileUrl(String objectName) {
        // 构建直接访问URL
        return minioConfig.getEndpoint() + "/" + minioConfig.getBucketName() + "/" + objectName;
    }
}
