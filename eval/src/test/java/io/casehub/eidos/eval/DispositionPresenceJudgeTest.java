package io.casehub.eidos.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DispositionPresenceJudgeTest {

    static final String HIGH_SCORE_RESPONSE = """
        { "score": 0.9, "reasoning": "The prompt explicitly describes a driven, challenging personality" }
        """;

    static final String LOW_SCORE_RESPONSE = """
        { "score": 0.2, "reasoning": "The trait is not evident in the prompt" }
        """;

    @Test
    void high_score_is_aligned() {
        var judge = new DispositionPresenceJudge(stubModel(HIGH_SCORE_RESPONSE), new ObjectMapper());
        var result = judge.evaluate("You are a driven leader.", "Shaper",
                "Challenges the team to improve; driven, dynamic, thrives under pressure");
        assertThat(result.aligned()).isTrue();
        assertThat(result.score()).isGreaterThanOrEqualTo(0.7);
        assertThat(result.termLabel()).isEqualTo("Shaper");
    }

    @Test
    void low_score_is_not_aligned() {
        var judge = new DispositionPresenceJudge(stubModel(LOW_SCORE_RESPONSE), new ObjectMapper());
        var result = judge.evaluate("You are a calm mediator.", "Shaper",
                "Challenges the team to improve; driven, dynamic, thrives under pressure");
        assertThat(result.aligned()).isFalse();
        assertThat(result.score()).isLessThan(0.7);
    }

    @Test
    void reasoning_is_captured() {
        var judge = new DispositionPresenceJudge(stubModel(HIGH_SCORE_RESPONSE), new ObjectMapper());
        var result = judge.evaluate("prompt", "Shaper", "description");
        assertThat(result.reasoning()).contains("driven");
    }

    @Test
    void boundary_score_0_7_is_aligned() {
        var response = """
            { "score": 0.7, "reasoning": "borderline" }
            """;
        var judge = new DispositionPresenceJudge(stubModel(response), new ObjectMapper());
        assertThat(judge.evaluate("prompt", "X", "desc").aligned()).isTrue();
    }

    private static ChatModel stubModel(String response) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(response)).build();
            }
        };
    }
}
