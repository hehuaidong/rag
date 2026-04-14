package com.example.rag.controller;

import com.example.rag.common.Result;
import com.example.rag.dto.DocumentUploadResult;
import com.example.rag.entity.Document;
import com.example.rag.service.document.DocumentManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentManagementService documentManagementService;

    @PostMapping("/upload")
    public Result<DocumentUploadResult> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(documentManagementService.uploadDocument(file));
    }

    @GetMapping
    public Result<Page<Document>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return Result.success(documentManagementService.listDocuments(pageable));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        documentManagementService.deleteDocument(id);
        return Result.success();
    }
}
