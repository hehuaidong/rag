package com.example.rag.controller;

import com.example.rag.common.Result;
import com.example.rag.dto.ChatRequest;
import com.example.rag.dto.ChatResponse;
import com.example.rag.entity.ChatHistory;
import com.example.rag.service.chat.ChatOrchestratorService;
import com.example.rag.service.rag.RagChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatOrchestratorService chatOrchestratorService;
    private final RagChatService ragChatService;

    @PostMapping
    public Result<ChatResponse> chat(@RequestBody ChatRequest request) {
        return Result.success(chatOrchestratorService.chat(request));
    }

    @PostMapping("/stream")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        return chatOrchestratorService.chatStream(request);
    }

    @PostMapping("/stream/real")
    public SseEmitter chatStreamReal(@RequestBody ChatRequest request) {
        return chatOrchestratorService.chatStreamReal(request);
    }

    @GetMapping("/history")
    public Result<Page<ChatHistory>> history(
            @RequestParam String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return Result.success(ragChatService.getHistory(userId, pageable));
    }

    @DeleteMapping("/history")
    public Result<Void> clearHistory(@RequestParam String userId) {
        ragChatService.clearHistory(userId);
        return Result.success();
    }
}
