package com.example.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ChatResponse {

    private String answer;
    private List<Long> referencedSliceIds;
}
