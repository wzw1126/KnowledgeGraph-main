package com.wzw.knowledge.service.impl;

import cn.hutool.core.util.IdUtil;
import com.wzw.knowledge.config.MinerUConfig;
import com.wzw.knowledge.exception.BusinessException;
import com.wzw.knowledge.common.ResultCode;
import com.wzw.knowledge.service.MinerUService;
import com.wzw.knowledge.service.MinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * MinerU PDF解析服务实现类
 * <p>
 * 通过HTTP调用docker中的MinerU mineru-api服务（/file_parse端点），
 * 将PDF解析为高质量Markdown，并将图片上传到MinIO。
 * </p>
 *
 * @author wzw
 * @version 2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinerUServiceImpl implements MinerUService {
    private final MinerUConfig minerUConfig;
    private final MinioService minioService;

    /** 匹配Markdown中图片引用的正则 */
    private static final Pattern IMG_PATTERN = Pattern.compile("!\\[([^]]*)\\]\\(([^)]+)\\)");

    @Override
    public MinerUParseResult parsePdf(String filePath) {
        long startTime = System.currentTimeMillis();
        File file = new File(filePath);
        if (!file.exists()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "PDF文件不存在: " + filePath);
        }

        log.info("MinerU开始解析PDF: {}", file.getName());

        try {
            // 1. 调用MinerU API
            byte[] zipBytes = callMinerUApi(file);

            // 2. 解压ZIP获取Markdown和图片
            Path tempDir = Files.createTempDirectory("mineru_");
            try {
                extractZip(zipBytes, tempDir);

                // 3. 找到markdown文件
                String markdownContent = findAndReadMarkdown(tempDir);

                // 4. 上传图片到MinIO并替换Markdown中的图片路径
                String documentId = IdUtil.fastSimpleUUID();
                int[] imageCount = {0};
                markdownContent = processImagesInMarkdown(markdownContent, tempDir, documentId, imageCount);

                long elapsed = System.currentTimeMillis() - startTime;
                log.info("MinerU解析完成: {} - {}字符, {}张图片, 耗时{}ms",
                        file.getName(), markdownContent.length(), imageCount[0], elapsed);

                return new MinerUParseResult(markdownContent, imageCount[0], elapsed);

            } finally {
                // 清理临时目录
                deleteDirectory(tempDir);
            }

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("MinerU解析失败: {} ({}ms)", file.getName(), elapsed, e);
            throw new BusinessException(ResultCode.MINERU_ERROR, "MinerU解析失败: " + e.getMessage());
        }
    }

    @Override
    public boolean checkHealth() {
        try {
            RestTemplate restTemplate = createRestTemplate(10);
            String url = minerUConfig.getApiUrl() + "/openapi.json";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("MinerU服务不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 调用MinerU API进行文件解析
     */
    private byte[] callMinerUApi(File file) {
        String parseEndpoint = minerUConfig.getApiUrl() + "/file_parse";

        RestTemplate restTemplate = createRestTemplate(minerUConfig.getTimeout());

        // 构建multipart请求
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", new FileSystemResource(file));
        body.add("lang_list", minerUConfig.getLangList());
        body.add("backend", minerUConfig.getBackend());
        body.add("parse_method", "auto");
        body.add("return_md", "true");
        body.add("response_format_zip", "true");
        body.add("return_images", "true");

        // vlm-http-client后端需要指定vl-server地址
        if ("vlm-http-client".equals(minerUConfig.getBackend())) {
            body.add("server_url", minerUConfig.getVlServerUrl());
        }

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        log.info("调用MinerU API: endpoint={}, backend={}, lang={}",
                parseEndpoint, minerUConfig.getBackend(), minerUConfig.getLangList());

        ResponseEntity<byte[]> response = restTemplate.exchange(
                parseEndpoint, HttpMethod.POST, requestEntity, byte[].class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new BusinessException(ResultCode.MINERU_ERROR,
                    "MinerU API返回异常: HTTP " + response.getStatusCode());
        }

        log.info("MinerU API调用成功，返回数据大小: {}bytes", response.getBody().length);
        return response.getBody();
    }

    /**
     * 解压ZIP到临时目录
     */
    private void extractZip(byte[] zipBytes, Path targetDir) throws IOException {
        Path zipPath = targetDir.resolve("result.zip");
        Files.write(zipPath, zipBytes);

        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                // 防止zip slip攻击
                if (!entryPath.startsWith(targetDir)) {
                    throw new IOException("ZIP entry outside target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (InputStream is = zipFile.getInputStream(entry)) {
                        Files.copy(is, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        // 删除zip文件本身
        Files.deleteIfExists(zipPath);
    }

    /**
     * 从解压目录中找到并读取Markdown文件
     */
    private String findAndReadMarkdown(Path dir) throws IOException {
        // 优先查找 full.md
        try (var stream = Files.walk(dir)) {
            var fullMd = stream.filter(p -> p.getFileName().toString().equals("full.md")).findFirst();
            if (fullMd.isPresent()) {
                return Files.readString(fullMd.get());
            }
        }

        // 其次查找任意.md文件
        try (var stream = Files.walk(dir)) {
            var anyMd = stream.filter(p -> p.toString().endsWith(".md")).findFirst();
            if (anyMd.isPresent()) {
                return Files.readString(anyMd.get());
            }
        }

        throw new BusinessException(ResultCode.MINERU_ERROR, "MinerU返回结果中未找到Markdown文件");
    }

    /**
     * 处理Markdown中的图片引用：上传图片到MinIO并替换路径
     */
    private String processImagesInMarkdown(String markdown, Path baseDir, String documentId, int[] imageCount) {
        StringBuffer result = new StringBuffer();
        Matcher matcher = IMG_PATTERN.matcher(markdown);

        while (matcher.find()) {
            String altText = matcher.group(1);
            String imagePath = matcher.group(2);

            // 跳过已经是URL的图片
            if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
                continue;
            }

            try {
                // 解析本地图片路径
                Path imgFile = resolveImagePath(baseDir, imagePath);
                if (imgFile != null && Files.exists(imgFile)) {
                    // 上传到MinIO
                    byte[] imageBytes = Files.readAllBytes(imgFile);
                    String ext = getFileExtension(imgFile.getFileName().toString());
                    String objectName = "documents/" + documentId + "/images/" + IdUtil.fastSimpleUUID() + "." + ext;
                    String contentType = guessContentType(ext);

                    String minioUrl = minioService.uploadBytes(objectName, imageBytes, contentType);
                    matcher.appendReplacement(result, "![" + altText + "](" + minioUrl + ")");
                    imageCount[0]++;
                    log.debug("图片上传MinIO: {} -> {}", imagePath, minioUrl);
                } else {
                    log.warn("图片文件不存在: {}", imagePath);
                }
            } catch (Exception e) {
                log.warn("图片处理失败: {}, error={}", imagePath, e.getMessage());
            }
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * 解析图片路径（处理相对路径）
     */
    private Path resolveImagePath(Path baseDir, String imagePath) throws IOException {
        // 直接解析
        Path direct = baseDir.resolve(imagePath).normalize();
        if (Files.exists(direct)) {
            return direct;
        }

        // 在子目录中搜索
        String fileName = Path.of(imagePath).getFileName().toString();
        try (var stream = Files.walk(baseDir)) {
            return stream.filter(p -> p.getFileName().toString().equals(fileName)).findFirst().orElse(null);
        }
    }

    private String getFileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1) : "png";
    }

    private String guessContentType(String ext) {
        return switch (ext.toLowerCase()) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "svg" -> "image/svg+xml";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    private RestTemplate createRestTemplate(int timeoutSeconds) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(30));
        factory.setReadTimeout(java.time.Duration.ofSeconds(timeoutSeconds));
        return new RestTemplate(factory);
    }

    private void deleteDirectory(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }
}
