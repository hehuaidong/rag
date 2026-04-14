package com.example.rag.service.rag;

import com.example.rag.dto.SliceSearchResult;
import com.example.rag.service.chat.ChatHistoryCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Prompt 组装服务
 */
@Service
@RequiredArgsConstructor
public class PromptBuilderService {

    private final ChatHistoryCacheService chatHistoryCacheService;

    /**
     * 组装 RAG Prompt
     */
    public String buildRagPrompt(String userId, String question, List<SliceSearchResult> slices) {
        String context = slices.stream()
                .map(SliceSearchResult::getContent)
                .collect(Collectors.joining("\n---\n"));

        if (context.isEmpty()) {
            context = "（未检索到相关资料）";
        }

        String history = chatHistoryCacheService.formatHistory(userId);

        return "你是一个智能问答助手。请结合历史对话理解用户问题的上下文和指代关系，并严格基于以下参考资料回答问题。\n" +
                "历史对话仅用于理解上下文，回答的事实依据必须来自参考资料。\n" +
                "如果参考资料中没有相关信息，请明确告知\"根据现有资料无法回答\"，不要编造。\n\n" +
                "参考资料：\n" + context + "\n\n" +
                "历史对话：\n" + history + "\n\n" +
                "用户问题：" + question + "\n\n" +
                "请回答：";
    }

    /**
     * 组装通用对话 Prompt（非 RAG 闲聊场景）
     */
    public String buildChatPrompt(String userId, String question) {
        String history = chatHistoryCacheService.formatHistory(userId);
        return "你是一个友善的 AI 助手。\n\n" +
                "历史对话：\n" + history + "\n\n" +
                "用户问题：" + question + "\n\n" +
                "请回答：";
    }
}
