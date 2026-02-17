package com.wzw.knowledge.service;

/**
 * MinerU PDF解析服务接口
 * <p>
 * 通过HTTP调用MinerU的mineru-api服务，将PDF解析为高质量Markdown
 * </p>
 *
 * @author wzw
 * @version 2.0
 */
public interface MinerUService {
    /**
     * 解析PDF文件为Markdown
     *
     * @param filePath 本地PDF文件路径
     * @return 解析结果，包含Markdown文本和图片信息
     */
    MinerUParseResult parsePdf(String filePath);

    /**
     * 检查MinerU服务健康状态
     *
     * @return 是否可用
     */
    boolean checkHealth();

    /**
     * MinerU解析结果封装
     */
    record MinerUParseResult(
            /** Markdown文本（图片路径已替换为MinIO URL） */
            String markdownContent,
            /** 图片数量 */
            int imageCount,
            /** 处理耗时(毫秒) */
            long processingTimeMs
    ) {}
}
