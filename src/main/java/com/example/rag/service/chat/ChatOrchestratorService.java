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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final ExecutorService reflectionExecutor = Executors.newFixedThreadPool(2);

    /**
     * 非流式问答主入口（带 Agent 路由 + Reflection 校验）
     */
    public ChatResponse chat(ChatRequest request) {
        long totalStart = System.currentTimeMillis();
        String userId = request.getUserId();
        String question = request.getQuestion();

        long routeStart = System.currentTimeMillis();
        boolean hasHistory = !chatHistoryCacheService.getHistory(userId).isEmpty();
        Intent intent = routingAgentService.route(question, hasHistory);
        long routeCost = System.currentTimeMillis() - routeStart;
        log.info("[耗时-意图识别] 用户={}, cost={}ms, 意图={}", userId, routeCost, intent);

        ChatResponse response;
        switch (intent) {
            case CHAT:
                response = ragChatService.chatDirect(userId, question);
                break;

            case TOOL_GET_CURRENT_TIME:
                long toolStart = System.currentTimeMillis();
                String time = toolExecutor.getCurrentTime();
                String toolHistory = chatHistoryCacheService.formatHistory(userId);
                String toolPrompt = "当前时间是 " + time + "，请用自然语言回答用户问题。\n\n" +
                        "历史对话：\n" + toolHistory + "\n\n" +
                        "用户问题：" + question + "\n\n请回答：";
                Response<AiMessage> toolResponse = chatLanguageModel.generate(new UserMessage(toolPrompt));
                String toolAnswer = toolResponse.content().text();
                ragChatService.saveChatHistory(userId, question, toolAnswer, Collections.emptyList());
                response = new ChatResponse(toolAnswer, Collections.emptyList());
                log.info("[耗时-工具调用] 用户={}, total={}ms", userId, System.currentTimeMillis() - toolStart);
                break;

            case RAG:
            default:
                response = doRagChat(userId, question);
                break;
        }

        log.info("[耗时-总链路] 用户={}, total={}ms", userId, System.currentTimeMillis() - totalStart);
        return response;
    }

    /**
     * SSE 流式问答主入口（简化版）
     */
    public SseEmitter chatStream(ChatRequest request) {
        // 流式暂不走复杂 Agent 校验，直接走 RAG 流式
        return ragChatService.chatStream(request.getUserId(), request.getQuestion());
    }

    private ChatResponse doRagChat(String userId, String question) {
        long ragStart = System.currentTimeMillis();
        // 1. RAG 生成
        ChatResponse ragResponse = ragChatService.chat(userId, question);
        String answer = ragResponse.getAnswer();
        log.info("[耗时-RAG生成] 用户={}, cost={}ms", userId, System.currentTimeMillis() - ragStart);

        // 2. Reflection 校验改为异步执行，不阻塞主响应
        reflectionExecutor.submit(() -> {
            long reflectionStart = System.currentTimeMillis();
            try {
                String rewrittenQuestion = ragChatService.rewriteQuestion(userId, question);
                float[] questionVector = embeddingService.embed(rewrittenQuestion);
                List<SliceSearchResult> slices = retrievalService.retrieve(rewrittenQuestion, questionVector);
                String context = slices.stream()
                        .map(SliceSearchResult::getContent)
                        .collect(Collectors.joining("\n---\n"));

                ReflectionAgentService.ReflectionResult reflection = reflectionAgentService.validate(context, answer);
                log.info("[耗时-Reflection校验-异步] 用户={}, cost={}ms, isValid={}", userId, System.currentTimeMillis() - reflectionStart, reflection.isValid());
                if (!reflection.isValid()) {
                    String safeAnswer = "根据现有资料无法确认该问题，原因：" + reflection.getReason();
                    ragChatService.updateLastAnswer(userId, safeAnswer);
                    log.warn("[Reflection拦截] 用户={}, 已异步更新安全答案", userId);
                }
            } catch (Exception e) {
                log.error("[Reflection异步异常] 用户={}", userId, e);
            }
        });

        return ragResponse;
    }
}
