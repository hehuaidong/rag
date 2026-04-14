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

    public Intent route(String question) {
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
            log.info("路由 Agent 识别意图：{}，原因：{}", intentStr, node.path("reason").asText());

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
