package com.example.rag.service.rag;

import com.example.rag.dto.ChatResponse;
import com.example.rag.dto.SliceSearchResult;
import com.example.rag.entity.ChatHistory;
import com.example.rag.exception.BizException;
import com.example.rag.repository.ChatHistoryRepository;
import com.example.rag.service.chat.ChatHistoryCacheService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 智能问答核心服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatService {

    private final EmbeddingService embeddingService;
    private final RetrievalService retrievalService;
    private final HybridRetrievalService hybridRetrievalService;
    private final PromptBuilderService promptBuilderService;
    private final ChatHistoryCacheService chatHistoryCacheService;
    private final ChatHistoryRepository chatHistoryRepository;
    private final ChatLanguageModel chatLanguageModel;
    private final StreamingChatLanguageModel streamingChatLanguageModel;

    /**
     * 非流式 RAG 问答
     */
    @Transactional
    public ChatResponse chat(String userId, String question) {
        long totalStart = System.currentTimeMillis();
        if (userId == null || userId.trim().isEmpty()) {
            throw new BizException("userId 不能为空");
        }
        if (question == null || question.trim().isEmpty()) {
            throw new BizException("问题不能为空");
        }

        // 0. 查询重写：消解历史对话中的指代，提升向量检索命中率
        long rewriteStart = System.currentTimeMillis();
        String rewrittenQuestion = rewriteQuestion(userId, question);
        log.info("[耗时-查询重写] 用户={}, cost={}ms, 原始={}, 改写后={}", userId, System.currentTimeMillis() - rewriteStart, question, rewrittenQuestion);

        // 1. 问题向量化（基于重写后的问题）
        long embedStart = System.currentTimeMillis();
        float[] questionVector = embeddingService.embed(rewrittenQuestion);
        log.info("[耗时-向量化] 用户={}, cost={}ms", userId, System.currentTimeMillis() - embedStart);

        // 2. 混合检索（基于重写后的问题）
        long retrieveStart = System.currentTimeMillis();
        List<SliceSearchResult> slices = hybridRetrievalService.retrieve(rewrittenQuestion, questionVector);
        log.info("[耗时-混合检索] 用户={}, cost={}ms, hits={}", userId, System.currentTimeMillis() - retrieveStart, slices.size());

        // 3. Prompt 组装
        long promptStart = System.currentTimeMillis();
        String prompt = promptBuilderService.buildRagPrompt(userId, question, slices);
        log.info("[耗时-Prompt组装] 用户={}, cost={}ms", userId, System.currentTimeMillis() - promptStart);

        // 4. 调用大模型
        long llmStart = System.currentTimeMillis();
        Response<AiMessage> response = chatLanguageModel.generate(new UserMessage(prompt));
        String answer = response.content().text();
        log.info("[耗时-大模型生成] 用户={}, cost={}ms, answerLength={}", userId, System.currentTimeMillis() - llmStart, answer.length());

        // 5. 提取引用切片 ID
        List<Long> referencedSliceIds = slices.stream()
                .map(SliceSearchResult::getId)
                .collect(Collectors.toList());

        // 6. 保存对话历史
        long saveStart = System.currentTimeMillis();
        saveChatHistory(userId, question, answer, referencedSliceIds);
        log.info("[耗时-保存历史] 用户={}, cost={}ms", userId, System.currentTimeMillis() - saveStart);

        log.info("[耗时-RAGChat总耗时] 用户={}, total={}ms", userId, System.currentTimeMillis() - totalStart);
        return new ChatResponse(answer, referencedSliceIds);
    }

    /**
     * 通用闲聊（非 RAG）
     */
    @Transactional
    public ChatResponse chatDirect(String userId, String question) {
        String prompt = promptBuilderService.buildChatPrompt(userId, question);
        Response<AiMessage> response = chatLanguageModel.generate(new UserMessage(prompt));
        String answer = response.content().text();
        saveChatHistory(userId, question, answer, Collections.emptyList());
        return new ChatResponse(answer, Collections.emptyList());
    }

    /**
     * SSE 流式 RAG 问答（简化版：先一次性生成再分段推送）
     * 若需要真正的逐 token 流式，需使用 StreamingChatLanguageModel + TokenStream。
     */
    public SseEmitter chatStream(String userId, String question) {
        SseEmitter emitter = new SseEmitter(120_000L);

        new Thread(() -> {
            try {
                String rewrittenQuestion = rewriteQuestion(userId, question);
                float[] questionVector = embeddingService.embed(rewrittenQuestion);
                List<SliceSearchResult> slices = hybridRetrievalService.retrieve(rewrittenQuestion, questionVector);
                String prompt = promptBuilderService.buildRagPrompt(userId, question, slices);

                Response<AiMessage> response = chatLanguageModel.generate(new UserMessage(prompt));
                String answer = response.content().text();

                // 模拟流式：按句子或固定长度分段推送
                int chunkSize = 4;
                for (int i = 0; i < answer.length(); i += chunkSize) {
                    String part = answer.substring(i, Math.min(i + chunkSize, answer.length()));
                    emitter.send(SseEmitter.event().data(part));
                    Thread.sleep(30);
                }

                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();

                List<Long> referencedSliceIds = slices.stream()
                        .map(SliceSearchResult::getId)
                        .collect(Collectors.toList());
                saveChatHistory(userId, question, answer, referencedSliceIds);
            } catch (Exception e) {
                log.error("SSE 流式问答异常", e);
                try {
                    emitter.send(SseEmitter.event().data("流式输出异常：" + e.getMessage()));
                } catch (IOException ignored) {
                }
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }

    /**
     * SSE 真实流式 RAG 问答（逐 token 推送，使用 StreamingChatLanguageModel）
     */
    public SseEmitter chatStreamReal(String userId, String question) {
        SseEmitter emitter = new SseEmitter(120_000L);

        try {
            String rewrittenQuestion = rewriteQuestion(userId, question);
            float[] questionVector = embeddingService.embed(rewrittenQuestion);
            List<SliceSearchResult> slices = hybridRetrievalService.retrieve(rewrittenQuestion, questionVector);
            String prompt = promptBuilderService.buildRagPrompt(userId, question, slices);

            List<Long> referencedSliceIds = slices.stream()
                    .map(SliceSearchResult::getId)
                    .collect(Collectors.toList());

            StringBuilder answerBuilder = new StringBuilder();

            streamingChatLanguageModel.generate(
                    Collections.singletonList(new UserMessage(prompt)),
                    new StreamingResponseHandler<AiMessage>() {
                        @Override
                        public void onNext(String token) {
                            try {
                                answerBuilder.append(token);
                                emitter.send(SseEmitter.event().data(token));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        }

                        @Override
                        public void onComplete(Response<AiMessage> response) {
                            try {
                                emitter.send(SseEmitter.event().data("[DONE]"));
                                emitter.complete();
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                            String finalAnswer = answerBuilder.toString();
                            saveChatHistory(userId, question, finalAnswer, referencedSliceIds);
                        }

                        @Override
                        public void onError(Throwable error) {
                            log.error("[真实流式-异常] 用户={}", userId, error);
                            try {
                                emitter.send(SseEmitter.event().data("流式输出异常：" + error.getMessage()));
                            } catch (IOException ignored) {
                            }
                            emitter.completeWithError(error);
                        }
                    }
            );
        } catch (Exception e) {
            log.error("[真实流式-前置异常] 用户={}", userId, e);
            try {
                emitter.send(SseEmitter.event().data("流式输出异常：" + e.getMessage()));
            } catch (IOException ignored) {
            }
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * 查询重写：结合历史对话消解指代，将当前问题改写为独立明确的问题
     */
    public String rewriteQuestion(String userId, String question) {
        String history = chatHistoryCacheService.formatHistory(userId);
        if (history.isEmpty() || "无".equals(history)) {
            return question;
        }
        // 轻量规则：不含常见指代/省略特征，直接跳过 LLM 重写
        if (!question.matches(".*(它|他|她|这|那|呢|吗|什么|怎么|为什么|多少|哪些|还有|另外|除此之外|前者|后者|这样|那样).*")) {
            log.info("[查询重写-规则跳过] 用户={}, 问题={}, 原因=无指代特征", userId, question);
            return question;
        }
        long start = System.currentTimeMillis();
        String prompt = "请根据以下历史对话，将当前用户问题改写成一个独立、明确的问题。\n" +
                "如果当前问题已经独立明确，则无需改写直接返回原问题。\n\n" +
                "历史对话：\n" + history + "\n\n" +
                "当前问题：" + question + "\n\n" +
                "请仅输出改写后的问题，不要有任何解释。";
        Response<AiMessage> response = chatLanguageModel.generate(new UserMessage(prompt));
        String rewritten = response.content().text().trim();
        log.info("[查询重写-LLM] 用户={}, cost={}ms, 原始={}, 改写后={}", userId, System.currentTimeMillis() - start, question, rewritten);
        return rewritten;
    }

    public void saveChatHistory(String userId, String question, String answer, List<Long> referencedSliceIds) {
        long dbStart = System.currentTimeMillis();
        chatHistoryCacheService.addMessage(userId, "User", question);
        chatHistoryCacheService.addMessage(userId, "AI", answer);

        ChatHistory history = new ChatHistory();
        history.setUserId(userId);
        history.setQuestion(question);
        history.setAnswer(answer);
        history.setReferencedSliceIds(referencedSliceIds.isEmpty() ? null : referencedSliceIds.toString());
        chatHistoryRepository.save(history);
        log.info("[耗时-DB保存历史] userId={}, cost={}ms", userId, System.currentTimeMillis() - dbStart);
    }

    @Transactional
    public void updateLastAnswer(String userId, String newAnswer) {
        chatHistoryCacheService.clearHistory(userId);
        // 重新加载最近历史到 Redis（排除最后一条 AI 回复，用新答案替代）
        List<ChatHistory> histories = chatHistoryRepository.findTop10ByUserIdOrderByCreatedAtDesc(userId);
        if (!histories.isEmpty()) {
            ChatHistory last = histories.get(0);
            last.setAnswer(newAnswer);
            chatHistoryRepository.save(last);

            // 按时间正序重新灌入 Redis
            for (int i = histories.size() - 1; i >= 0; i--) {
                ChatHistory h = histories.get(i);
                chatHistoryCacheService.addMessage(userId, "User", h.getQuestion());
                String aiAnswer = (i == 0) ? newAnswer : h.getAnswer();
                chatHistoryCacheService.addMessage(userId, "AI", aiAnswer);
            }
        }
    }

    public org.springframework.data.domain.Page<ChatHistory> getHistory(String userId, org.springframework.data.domain.Pageable pageable) {
        return chatHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional
    public void clearHistory(String userId) {
        chatHistoryCacheService.clearHistory(userId);
        chatHistoryRepository.deleteByUserId(userId);
    }
}
