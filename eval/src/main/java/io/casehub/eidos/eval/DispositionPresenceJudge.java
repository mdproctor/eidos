package io.casehub.eidos.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class DispositionPresenceJudge {

    static final String JUDGE_PROMPT = """
        You are evaluating whether an AI agent's system prompt expresses a specific
        personality or behavioral trait.

        The trait: [%s] — %s

        Read the system prompt carefully. Score how strongly this trait is expressed:
        - 1.0: The trait is explicitly and clearly expressed
        - 0.7: The trait is present but not dominant
        - 0.4: Weakly present or only implied
        - 0.0: Not present or contradicted

        Return ONLY raw JSON — no markdown, no code blocks:
        { "score": number, "reasoning": string }
        """;

    private final ChatModel judgeModel;
    private final ObjectMapper mapper;

    @Inject
    public DispositionPresenceJudge(@Any final Instance<ChatModel> models,
                                     final ObjectMapper mapper) {
        if (!models.isResolvable()) throw new IllegalStateException("ChatModel not configured.");
        this.judgeModel = models.get();
        this.mapper = mapper;
    }

    DispositionPresenceJudge(final ChatModel judgeModel, final ObjectMapper mapper) {
        this.judgeModel = judgeModel;
        this.mapper = mapper;
    }

    public DispositionPresenceResult evaluate(final String systemPrompt,
                                               final String termLabel,
                                               final String termDescription) {
        final String judgePrompt = String.format(JUDGE_PROMPT, termLabel, termDescription);
        try {
            final var request = ChatRequest.builder()
                    .messages(
                            SystemMessage.from(judgePrompt),
                            UserMessage.from(systemPrompt))
                    .build();
            final String response = judgeModel.chat(request).aiMessage().text();
            return parse(termLabel, response);
        } catch (final MalformedJudgeResponseException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException("DispositionPresenceJudge LLM call failed", e);
        }
    }

    private DispositionPresenceResult parse(final String termLabel, final String json) {
        try {
            final JsonNode root = mapper.readTree(PromptJudge.extractJson(json));
            final double score = root.has("score") ? root.get("score").asDouble() : 0.0;
            final String reasoning = root.has("reasoning") ? root.get("reasoning").asText() : "";
            return new DispositionPresenceResult(termLabel, score, reasoning, score >= 0.7);
        } catch (final Exception e) {
            throw new MalformedJudgeResponseException(
                    "Failed to parse DispositionPresenceJudge response: " + e.getMessage());
        }
    }
}
