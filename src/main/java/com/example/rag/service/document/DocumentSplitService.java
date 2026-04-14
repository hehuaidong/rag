package com.example.rag.service.document;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

import static dev.langchain4j.data.document.splitter.DocumentSplitters.recursive;

/**
 * 文档分块与向量化服务
 */
@Service
@RequiredArgsConstructor
public class DocumentSplitService {

    private final EmbeddingModel embeddingModel;

    @Value("${rag.document.chunk-size:500}")
    private int chunkSize;

    @Value("${rag.document.chunk-overlap:50}")
    private int chunkOverlap;

    /**
     * 对文本进行分块
     */
    public List<String> split(String text) {
        Document document = Document.from(text, new Metadata());
        List<TextSegment> segments = recursive(chunkSize, chunkOverlap).split(document);
        return segments.stream()
                .map(TextSegment::text)
                .collect(Collectors.toList());
    }
}
