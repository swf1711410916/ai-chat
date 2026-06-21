package com.custom.chat.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
// 💡 引入本地量化模型的实现类
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class VectorDocumentService {

    @Autowired
    private EmbeddingStore<TextSegment> redisEmbeddingStore;

    // 🗑️ 删掉了之前报错的 @Autowired private EmbeddingModel embeddingModel;

    @Async
    public void importFileToVectorStore(Path filePath) {
        try {
            String fileName = filePath.getFileName().toString().toLowerCase();
            long fileSize = Files.size(filePath);
            System.out.println("👉👉 [Debug] 异步后台线程接收到的文件: " + fileName + "，大小: " + fileSize + " 字节");

            Document document = null;

            // 1. EPUB 格式
            if (fileName.endsWith(".epub")) {
                System.out.println("检测到 EPUB 格式，正在启动原生 ZIP+Jsoup 提取流...");
                String pureText = parseEpubManually(filePath);
                if (!pureText.trim().isEmpty()) {
                    document = Document.from(pureText);
                }
            }
            // 2. PDF 格式
            else if (fileName.endsWith(".pdf")) {
                System.out.println("检测到 PDF 格式，正在使用 LangChain4j 默认多媒体加载器...");
                document = FileSystemDocumentLoader.loadDocument(filePath);
            }
            // 3. 其他常规格式
            else {
                System.out.println("其他标准格式，使用 Apache Tika 引擎解析...");
                document = FileSystemDocumentLoader.loadDocument(filePath, new ApacheTikaDocumentParser());
            }

            // 安全防御检查
            if (document == null || document.text() == null || document.text().trim().isEmpty()) {
                System.err.println("❌ [Error] 提取失败：该文件解开后未探测到有效的可读文字图层。");
                return;
            }

            System.out.println("✅ 成功加载文档，提取出字符数：" + document.text().length());

            // 4. 切片
            DocumentSplitter splitter = DocumentSplitters.recursive(300, 30);
            List<TextSegment> segments = splitter.split(document);

            System.out.println("正在计算向量并批量写入 Redis (切片数: " + segments.size() + ")...");

            // 🌟 核心修改点：在这里直接手动 new 实例化一个本地高性能嵌入模型
            AllMiniLmL6V2QuantizedEmbeddingModel localEmbeddingModel = new AllMiniLmL6V2QuantizedEmbeddingModel();

            // 写入 Redis 7.x
            redisEmbeddingStore.addAll(localEmbeddingModel.embedAll(segments).content(), segments);
            System.out.println("🎉 后台向量化写入 Redis 成功！");

        } catch (Exception e) {
            System.err.println("❌ 后台解析发生深层异常: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 安全擦除临时文件
            try {
                boolean deleted = Files.deleteIfExists(filePath);
                System.out.println("🗑️ 后台临时文件擦除结果: " + deleted);
            } catch (Exception ignored) {}
        }
    }

    private String parseEpubManually(Path epubPath) {
        StringBuilder textBuilder = new StringBuilder();
        try (InputStream fis = Files.newInputStream(epubPath);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase();
                if (name.endsWith(".html") || name.endsWith(".xhtml") || name.endsWith(".htm")) {
                    StringBuilder htmlContent = new StringBuilder();
                    int bytesRead;
                    while ((bytesRead = zis.read(buffer)) != -1) {
                        htmlContent.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
                    }
                    String pureText = Jsoup.parse(htmlContent.toString()).text();
                    textBuilder.append(pureText).append("\n");
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            System.err.println("手动暴力解包 EPUB 失败: " + e.getMessage());
        }
        return textBuilder.toString();
    }
}