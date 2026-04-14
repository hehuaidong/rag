package com.example.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SliceSearchResult {

    private Long id;
    private Long documentId;
    private Integer chunkIndex;
    private String content;
    private Double distance;
}
