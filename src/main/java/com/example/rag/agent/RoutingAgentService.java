package com.example.rag.agent;

import com.example.rag.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ReAct 风格路由 Agent：对用户提问做意图识别与路由决策
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingAgentService {

    private final ChatLanguageModel chatLanguageModel;
    private final ObjectMapper objectMapper;

    public Intent route(String question, boolean hasHistory) {
        String q = question == null ? "" : question.trim();

        // 1. 规则快速路由（零成本，覆盖常见高频意图）
        String lower = q.toLowerCase();
        if (lower.matches(".*(现在几点|当前时间|几点了|今天.*几号|星期几).*")) {
            log.info("路由 Agent 规则命中：TOOL_GET_CURRENT_TIME");
            return Intent.TOOL_GET_CURRENT_TIME;
        }
        if (lower.matches("^(你好|您好|哈喽|嗨|hello|hi|hey).*") && q.length() < 20) {
            log.info("路由 Agent 规则命中：CHAT");
            return Intent.CHAT;
        }
        if (lower.contains("根据资料") || lower.contains("根据文档") || lower.contains("参考")) {
            log.info("路由 Agent 规则命中：RAG（显式资料关键词）");
            return Intent.RAG;
        }
        // 有历史对话且含指代/追问特征，大概率是 RAG 追问
        if (hasHistory && q.matches(".*(它|他|她|这|那|呢|吗|什么|怎么|为什么|多少|哪些|还有|另外|除此之外).*")) {
            log.info("路由 Agent 规则命中：RAG（追问/指代特征）");
            return Intent.RAG;
        }

        // 2. 规则未命中，走大模型兜底
        long start = System.currentTimeMillis();
        String prompt = "请分析用户意图，从以下选项中选择一个返回 JSON：\n" +
                "- \"RAG\"：需要基于资料库回答\n" +
                "- \"CHAT\"：闲聊或通用知识，不需要查资料\n" +
                "- \"TOOL:get_current_time\"：需要获取当前时间\n\n" +
                "用户问题：" + question + "\n\n" +
                "仅输出 JSON：{\"intent\": \"...\", \"reason\": \"...\"}";

        try {
            Response<AiMessage> response = chatLanguageModel.generate(new UserMessage(prompt));
            String content = response.content().text();
            // 提取 JSON 部分
            String json = extractJson(content);
            JsonNode node = objectMapper.readTree(json);
            String intentStr = node.path("intent").asText("RAG").trim();
            log.info("路由 Agent LLM识别意图：{}，原因：{}，cost={}ms", intentStr, node.path("reason").asText(), System.currentTimeMillis() - start);

            if (intentStr.contains("TOOL:get_current_time")) {
                return Intent.TOOL_GET_CURRENT_TIME;
            } else if (intentStr.contains("CHAT")) {
                return Intent.CHAT;
            }
            return Intent.RAG;
        } catch (Exception e) {
            log.error("路由 Agent 异常，默认走 RAG", e);
            return Intent.RAG;
        }
    }

    private String extractJson(String content) {
        int start = content.indexOf("{");
        int end = content.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }
}
