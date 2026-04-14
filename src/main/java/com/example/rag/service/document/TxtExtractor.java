package com.example.rag.service.document;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class TxtExtractor implements DocumentTextExtractor {

    @Override
    public boolean supports(String fileType) {
        return "txt".equalsIgnoreCase(fileType);
    }

    @Override
    public String extract(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }
}
