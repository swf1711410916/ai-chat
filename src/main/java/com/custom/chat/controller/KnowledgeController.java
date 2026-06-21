package com.custom.chat.controller;

import com.custom.chat.service.VectorDocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Path;

@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {

    @Autowired
    private VectorDocumentService vectorDocumentService;

    /**
     * 接口：上传私有业务文档到向量库
     */
    @PostMapping("/upload")
    public String uploadDocument(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return "文件为空！";
        }

        String originalFilename = file.getOriginalFilename();
        java.nio.file.Path tempFilePath = null;

        try {
            // 1. 安全提取后缀
            String suffix = originalFilename.contains(".") ?
                    originalFilename.substring(originalFilename.lastIndexOf(".")) : ".txt";

            // 2. 创建并写入临时文件
            tempFilePath = java.nio.file.Files.createTempFile("rag-", suffix);
            java.nio.file.Files.write(tempFilePath, file.getBytes());

            // 3. 调用业务层解析并洗入 Redis
            vectorDocumentService.importFileToVectorStore(tempFilePath.toAbsolutePath());

            return "文件【" + originalFilename + "】解析并向量化导入 Redis 成功！";

        } catch (Exception e) {
            e.printStackTrace();
            return "导入失败: " + e.getMessage();
        }
    }
}