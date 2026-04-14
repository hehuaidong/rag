package com.example.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DocumentUploadResult {

    private Long id;
    private String fileName;
    private String fileType;
    private Integer totalChunks;
}
