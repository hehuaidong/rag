package com.example.rag.service.chat;

import com.example.rag.agent.*;
import com.example.rag.dto.ChatRequest;
import com.example.rag.dto.ChatResponse;
import com.example.rag.dto.SliceSearchResult;
import com.example.rag.service.rag.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 对话编排服务：整合 ReAct 路由 Agent、RAG 问答、Reflection 校验
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatOrchestratorService {

    private final RoutingAgentService routingAgentService;
    private final ReflectionAgentService reflectionAgentService;
    private final ToolExecutor toolExecutor;
    private final RagChatService ragChatService;
    private final EmbeddingService embeddingService;
    private final RetrievalService retrievalService;
    private final PromptBuilderService promptBuilderService;
    private final ChatHistoryCacheService chatHistoryCacheService;
    private final ChatLanguageModel chatLanguageModel;

    /**
     * 非流式问答主入口（带 Agent 路由 + Reflection 校验）
     */
    public ChatResponse chat(ChatRequest request) {
        String userId = request.getUserId();
        String question = request.getQuestion();
        Intent intent = routingAgentService.route(question);
        log.info("用户={}, 问题={}, 识别意图={}", userId, question, intent);

        switch (intent) {
            case CHAT:
                return ragChatService.chatDirect(userId, question);

            case TOOL_GET_CURRENT_TIME:
                String time = toolExecutor.getCurrentTime();
                String toolHistory = chatHistoryCacheService.formatHistory(userId);
                String toolPrompt = "当前时间是 " + time + "，请用自然语言回答用户问题。\n\n" +
                        "历史对话：\n" + toolHistory + "\n\n" +
                        "用户问题：" + question + "\n\n请回答：";
                Response<AiMessage> toolResponse = chatLanguageModel.generate(new UserMessage(toolPrompt));
                String toolAnswer = toolResponse.content().text();
                ragChatService.saveChatHistory(userId, question, toolAnswer, Collections.emptyList());
                return new ChatResponse(toolAnswer, Collections.emptyList());

            case RAG:
            default:
                return doRagChat(userId, question);
        }
    }

    /**
     * SSE 流式问答主入口（简化版）
     */
    public SseEmitter chatStream(ChatRequest request) {
        // 流式暂不走复杂 Agent 校验，直接走 RAG 流式
        return ragChatService.chatStream(request.getUserId(), request.getQuestion());
    }

    private ChatResponse doRagChat(String userId, String question) {
        // 1. RAG 生成
        ChatResponse ragResponse = ragChatService.chat(userId, question);
        String answer = ragResponse.getAnswer();

        // 2. Reflection 校验（使用与 RAG 生成一致的重写后问题做检索）
        String rewrittenQuestion = ragChatService.rewriteQuestion(userId, question);
        float[] questionVector = embeddingService.embed(rewrittenQuestion);
        List<SliceSearchResult> slices = retrievalService.retrieve(rewrittenQuestion, questionVector);
        String context = slices.stream()
                .map(SliceSearchResult::getContent)
                .collect(Collectors.joining("\n---\n"));

        ReflectionAgentService.ReflectionResult reflection = reflectionAgentService.validate(context, answer);
        if (!reflection.isValid()) {
            String safeAnswer = "根据现有资料无法确认该问题，原因：" + reflection.getReason();
            // 更新历史记录中的答案为安全答案
            ragChatService.updateLastAnswer(userId, safeAnswer);
            return new ChatResponse(safeAnswer, ragResponse.getReferencedSliceIds());
        }

        return ragResponse;
    }
}
