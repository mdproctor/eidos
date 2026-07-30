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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class MbtiAlignmentJudge {

    static final String SYSTEM_PROMPT = """
        You are an MBTI personality assessor. You will be given a system prompt that
        defines an AI agent's personality. Based ONLY on what is in the system prompt,
        answer the following personality questions.

        For each question, choose option A or B. Answer with ONLY the letter.
        Return your answers as a JSON object with question numbers as keys.

        Return ONLY raw JSON — no markdown, no code blocks, no preamble.
        """;

    static final List<MbtiQuestion> QUESTIONNAIRE = List.of(
            new MbtiQuestion(1, "EI", "I", "Does this agent prefer to work through problems internally before sharing conclusions (A) or prefer to think out loud and collaborate while processing (B)?"),
            new MbtiQuestion(2, "EI", "I", "When facing a complex task, would this agent prefer focused solo analysis (A) or brainstorming with the team (B)?"),
            new MbtiQuestion(3, "EI", "E", "Does this agent actively seek input from others before deciding (A) or prefer to form judgments independently first (B)?"),
            new MbtiQuestion(4, "SN", "S", "Does this agent focus on concrete, established methods and facts (A) or explore abstract patterns and novel possibilities (B)?"),
            new MbtiQuestion(5, "SN", "S", "When given a problem, does this agent look at the specific details and data first (A) or jump to the big-picture implications (B)?"),
            new MbtiQuestion(6, "SN", "S", "Does this agent prefer proven approaches with track records (A) or innovative solutions that challenge conventions (B)?"),
            new MbtiQuestion(7, "TF", "T", "When evaluating options, does this agent prioritize logical consistency and efficiency (A) or consider the impact on people and values (B)?"),
            new MbtiQuestion(8, "TF", "T", "In conflict, does this agent focus on finding the objectively correct solution (A) or on maintaining harmony and understanding (B)?"),
            new MbtiQuestion(9, "TF", "F", "Does this agent prioritize group cohesion and empathy (A) or analytical rigor and objectivity (B)?"),
            new MbtiQuestion(10, "JP", "J", "Does this agent prefer structured plans and clear timelines (A) or flexible, adaptive approaches (B)?"),
            new MbtiQuestion(11, "JP", "P", "Would this agent rather keep options open and explore alternatives (A) or commit to a decision and execute systematically (B)?"),
            new MbtiQuestion(12, "JP", "J", "Does this agent prefer to complete work thoroughly before moving on (A) or juggle multiple streams with quick pivots (B)?")
    );

    private final ChatModel judgeModel;
    private final ObjectMapper mapper;

    @Inject
    public MbtiAlignmentJudge(@Any final Instance<ChatModel> models, final ObjectMapper mapper) {
        if (!models.isResolvable()) throw new IllegalStateException("ChatModel not configured.");
        this.judgeModel = models.get();
        this.mapper = mapper;
    }

    MbtiAlignmentJudge(final ChatModel judgeModel, final ObjectMapper mapper) {
        this.judgeModel = judgeModel;
        this.mapper = mapper;
    }

    public MbtiAlignmentResult evaluate(final String agentSystemPrompt, final String expectedType) {
        final var questionBlock = new StringBuilder();
        for (final var q : QUESTIONNAIRE) {
            questionBlock.append(q.number()).append(". ").append(q.question()).append("\n");
        }

        try {
            final var request = ChatRequest.builder()
                    .messages(
                            SystemMessage.from(agentSystemPrompt),
                            UserMessage.from(SYSTEM_PROMPT + "\n\nQuestions:\n" + questionBlock
                                    + "\nReturn JSON: {\"1\": \"A\" or \"B\", \"2\": \"A\" or \"B\", ...}"))
                    .build();
            final String response = judgeModel.chat(request).aiMessage().text();
            return parse(expectedType, response);
        } catch (final MalformedJudgeResponseException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException("MbtiAlignmentJudge LLM call failed", e);
        }
    }

    private MbtiAlignmentResult parse(final String expectedType, final String json) {
        try {
            final JsonNode root = mapper.readTree(PromptJudge.extractJson(json));
            final Map<String, DimensionResult> dimensions = new LinkedHashMap<>();
            for (final String dim : List.of("EI", "SN", "TF", "JP")) {
                dimensions.put(dim, scoreDimension(root, dim, expectedType));
            }
            final boolean aligned = dimensions.values().stream()
                    .allMatch(d -> d.accuracy() > 0.5);
            return new MbtiAlignmentResult(expectedType, dimensions, aligned);
        } catch (final Exception e) {
            throw new MalformedJudgeResponseException("Failed to parse MBTI alignment response: " + e.getMessage());
        }
    }

    private DimensionResult scoreDimension(final JsonNode root, final String dimension,
                                            final String expectedType) {
        final String expectedPole = switch (dimension) {
            case "EI" -> String.valueOf(expectedType.charAt(0));
            case "SN" -> String.valueOf(expectedType.charAt(1));
            case "TF" -> String.valueOf(expectedType.charAt(2));
            case "JP" -> String.valueOf(expectedType.charAt(3));
            default -> "?";
        };

        int correct = 0;
        int total = 0;
        for (final var q : QUESTIONNAIRE) {
            if (!q.dimension().equals(dimension)) continue;
            total++;
            final JsonNode answer = root.get(String.valueOf(q.number()));
            if (answer == null) continue;
            final String chosen = answer.asText().trim().toUpperCase();
            final boolean chosenIsA = "A".equals(chosen);
            final boolean expectedIsA = q.aIsPole().equals(expectedPole);
            if (chosenIsA == expectedIsA) correct++;
        }
        return new DimensionResult(expectedPole, total > 0 ? (double) correct / total : 0.0);
    }

    public record MbtiQuestion(int number, String dimension, String aIsPole, String question) {}

    public record DimensionResult(String expected, double accuracy) {}

    public record MbtiAlignmentResult(
            String expectedType,
            Map<String, DimensionResult> dimensions,
            boolean overallAligned) {}
}
