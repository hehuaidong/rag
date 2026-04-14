package com.example.rag.agent;

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
 * Reflection 回答校验 Agent：检测幻觉与事实偏离
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionAgentService {

    private final ChatLanguageModel chatLanguageModel;
    private final ObjectMapper objectMapper;

    public ReflectionResult validate(String context, String answer) {
        long start = System.currentTimeMillis();
        String prompt = "请判断以下回答是否严格基于参考资料，是否存在幻觉或编造内容。\n\n" +
                "参考资料：\n" + (context == null || context.isEmpty() ? "（无）" : context) + "\n\n" +
                "回答：\n" + answer + "\n\n" +
                "请输出 JSON：{\"isValid\": true/false, \"reason\": \"...\"}";

        try {
            Response<AiMessage> response = chatLanguageModel.generate(new UserMessage(prompt));
            String content = response.content().text();
            String json = extractJson(content);
            JsonNode node = objectMapper.readTree(json);
            boolean isValid = node.path("isValid").asBoolean(true);
            String reason = node.path("reason").asText();
            log.info("[耗时-ReflectionAgent] cost={}ms, isValid={}, reason={}", System.currentTimeMillis() - start, isValid, reason);
            return new ReflectionResult(isValid, reason);
        } catch (Exception e) {
            log.error("Reflection Agent 异常，默认放行", e);
            return new ReflectionResult(true, "校验异常，默认放行");
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

    public static class ReflectionResult {
        private final boolean valid;
        private final String reason;

        public ReflectionResult(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        public boolean isValid() {
            return valid;
        }

        public String getReason() {
            return reason;
        }
    }
}
