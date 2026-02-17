package com.wzw.knowledge.service;

import java.io.InputStream;

/**
 * MinIO对象存储服务接口
 *
 * @author wzw
 * @version 2.0
 */
public interface MinioService {

    /**
     * 上传文件到MinIO
     *
     * @param objectName  对象名称（路径+文件名）
     * @param inputStream 文件流
     * @param contentType MIME类型
     * @return 文件的访问URL
     */
    String uploadFile(String objectName, InputStream inputStream, String contentType);

    /**
     * 上传字节数组到MinIO
     *
     * @param objectName  对象名称
     * @param data        文件字节数组
     * @param contentType MIME类型
     * @return 文件的访问URL
     */
    String uploadBytes(String objectName, byte[] data, String contentType);

    /**
     * 删除MinIO中的文件
     *
     * @param objectName 对象名称
     * @return 是否成功
     */
    boolean deleteFile(String objectName);

    /**
     * 获取文件的访问URL
     *
     * @param objectName 对象名称
     * @return URL
     */
    String getFileUrl(String objectName);
}