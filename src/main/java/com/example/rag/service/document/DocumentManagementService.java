package com.example.rag.service.document;

import com.example.rag.dto.DocumentUploadResult;
import com.example.rag.entity.Document;
import com.example.rag.entity.DocumentSlice;
import com.example.rag.exception.BizException;
import com.example.rag.repository.DocumentRepository;
import com.example.rag.repository.DocumentSliceRepository;
import com.example.rag.service.rag.EmbeddingService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档管理服务
 */
@Service
@RequiredArgsConstructor
public class DocumentManagementService {

    private final DocumentRepository documentRepository;
    private final DocumentSliceRepository documentSliceRepository;
    private final List<DocumentTextExtractor> extractors;
    private final DocumentSplitService documentSplitService;
    private final EmbeddingService embeddingService;

    @Value("${rag.document.supported-types:txt,md,pdf}")
    private String supportedTypes;

    /**
     * 上传文档：提取文本 → 分块 → 向量化 → 持久化
     */
    @Transactional
    public DocumentUploadResult uploadDocument(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BizException("上传文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.contains(".")) {
            throw new BizException("文件名格式不正确");
        }

        String fileType = originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase();
        List<String> supported = Arrays.asList(supportedTypes.split(","));
        if (!supported.contains(fileType)) {
            throw new BizException("不支持的文件类型：" + fileType + "，仅支持：" + supportedTypes);
        }

        String text;
        try {
            text = extractText(file.getBytes(), fileType);
        } catch (IOException e) {
            throw new BizException("文件读取失败：" + e.getMessage());
        }

        if (text == null || text.trim().isEmpty()) {
            throw new BizException("文档内容为空，无法处理");
        }

        List<String> chunks = documentSplitService.split(text);
        if (chunks.isEmpty()) {
            throw new BizException("文档分块结果为空");
        }

        Document document = new Document();
        document.setFileName(originalFilename);
        document.setFileType(fileType);
        document.setFileSize(file.getSize());
        document.setTotalChunks(chunks.size());
        documentRepository.save(document);

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            float[] vector = embeddingService.embed(chunk);

            DocumentSlice slice = new DocumentSlice();
            slice.setDocumentId(document.getId());
            slice.setChunkIndex(i);
            slice.setContent(chunk);
            slice.setEmbedding(vector);
            documentSliceRepository.save(slice);
        }

        return new DocumentUploadResult(document.getId(), document.getFileName(),
                document.getFileType(), document.getTotalChunks());
    }

    /**
     * 分页查询文档列表
     */
    public Page<Document> listDocuments(Pageable pageable) {
        return documentRepository.findAll(pageable);
    }

    /**
     * 删除文档及关联切片
     */
    @Transactional
    public void deleteDocument(Long id) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new BizException("文档不存在：" + id));
        documentSliceRepository.deleteByDocumentId(document.getId());
        documentRepository.delete(document);
    }

    private String extractText(byte[] content, String fileType) {
        for (DocumentTextExtractor extractor : extractors) {
            if (extractor.supports(fileType)) {
                return extractor.extract(content);
            }
        }
        throw new BizException("未找到对应类型的文本提取器：" + fileType);
    }
}
