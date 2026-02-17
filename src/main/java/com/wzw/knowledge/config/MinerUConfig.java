package com.wzw.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MinerU PDF解析服务配置类
 *
 * @author wzw
 * @version 2.0
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mineru")
public class MinerUConfig {

    /**
     * MinerU API服务地址
     */
    private String apiUrl = "http://localhost:30001";

    /**
     * MinerU VLM服务地址
     */
    private String vlServerUrl = "http://localhost:30000";

    /**
     * 解析超时时间（秒）
     */
    private Integer timeout = 1800;

    /**
     * 后端类型
     */
    private String backend = "vlm-http-client";

    /**
     * 语言列表
     */
    private String langList = "ch";
}
