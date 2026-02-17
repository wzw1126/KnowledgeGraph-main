package com.wzw.knowledge.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/**
 * MinIO对象存储配置类
 *
 * @author wzw
 * @version 2.0
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "minio")
public class MinioConfig {

    /**
     * MinIO服务端点
     */
    private String endpoint = "http://localhost:9000";

    /**
     * 访问密钥
     */
    private String accessKey = "minioadmin";

    /**
     * 秘密密钥
     */
    private String secretKey = "minioadmin";

    /**
     * 存储桶名称
     */
    private String bucketName = "knowledge-images";

    @Bean
    public MinioClient minioClient() {
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();

        // 确保bucket存在
        try {
            boolean exists = client.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                client.makeBucket(
                        MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("MinIO bucket '{}' 创建成功", bucketName);
            }
        } catch (Exception e) {
            log.warn("MinIO bucket初始化失败，可能MinIO服务未启动: {}", e.getMessage());
        }

        return client;
    }
}
