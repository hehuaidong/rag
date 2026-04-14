package com.example.rag.dto;

import lombok.Data;

@Data
public class ChatRequest {

    private String userId;
    private String question;
}
