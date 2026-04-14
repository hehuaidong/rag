package com.example.rag.service.document;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class MdExtractor implements DocumentTextExtractor {

    @Override
    public boolean supports(String fileType) {
        return "md".equalsIgnoreCase(fileType);
    }

    @Override
    public String extract(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }
}
