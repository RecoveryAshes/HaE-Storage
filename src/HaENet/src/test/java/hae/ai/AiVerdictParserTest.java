package hae.ai;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hae.ai.parser.AiVerdictParseResult;
import hae.ai.parser.AiVerdictParser;
import org.junit.jupiter.api.Test;

class AiVerdictParserTest {
    @Test
    void fencedJsonParsesAndSecretsRedacted() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signaturevalue";
        String json = "```json\n" +
                "{\n" +
                "  \"overall_verdict\": \"sensitive_exposure\",\n" +
                "  \"overall_severity\": \"high\",\n" +
                "  \"confidence\": 87,\n" +
                "  \"summary\": \"Authorization token " + jwt + " was exposed\",\n" +
                "  \"items_truncated\": true,\n" +
                "  \"omitted_item_count\": 3,\n" +
                "  \"items\": [\n" +
                "    {\n" +
                "      \"rule_group\": \"Sensitive Information\",\n" +
                "      \"rule_name\": \"JWT\",\n" +
                "      \"rule_hash\": \"hash-1\",\n" +
                "      \"matched_value_redacted\": \"token[redacted]\",\n" +
                "      \"match_location\": \"response body\",\n" +
                "      \"verdict\": \"possible_sensitive\",\n" +
                "      \"is_sensitive\": true,\n" +
                "      \"is_exposed\": true,\n" +
                "      \"confidence\": 0.42,\n" +
                "      \"severity\": \"medium\",\n" +
                "      \"reason\": \"detail includes api_key=abcdef1234567890abcdef1234567890\",\n" +
                "      \"recommended_actions\": [\"Rotate Bearer abcdef1234567890abcdef1234567890\"]\n" +
                "    }\n" +
                "  ]\n" +
                "}\n```";

        AiVerdictParseResult result = new AiVerdictParser().parse(json);
        AiTriageResponse response = result.getResponse();

        assertAll(
                () -> assertTrue(result.isParsed()),
                () -> assertNotNull(response),
                () -> assertEquals(AiTriageVerdict.SENSITIVE_EXPOSURE, response.getOverallVerdict()),
                () -> assertEquals(AiTriageRiskLevel.HIGH, response.getOverallRiskLevel()),
                () -> assertEquals(0.87, response.getConfidence()),
                () -> assertTrue(response.getSummary().contains("[redacted]")),
                () -> assertFalse(response.getSummary().contains(jwt)),
                () -> assertTrue(response.isItemsTruncated()),
                () -> assertEquals(3, response.getOmittedItemCount()),
                () -> assertEquals(1, response.getItems().size()),
                () -> assertEquals(AiTriageVerdict.POSSIBLE_SENSITIVE, response.getItems().get(0).getVerdict()),
                () -> assertEquals(AiTriageRiskLevel.MEDIUM, response.getItems().get(0).getRiskLevel()),
                () -> assertEquals(0.42, response.getItems().get(0).getConfidence()),
                () -> assertTrue(response.getItems().get(0).getReason().contains("[redacted]")),
                () -> assertFalse(response.getItems().get(0).getReason().contains("abcdef1234567890abcdef1234567890")),
                () -> assertTrue(response.getItems().get(0).getRecommendedActions().get(0).contains("Bearer [redacted]")),
                () -> assertFalse(response.getItems().get(0).getRecommendedActions().get(0).contains("abcdef1234567890abcdef1234567890"))
        );
    }

    @Test
    void invalidJsonDoesNotCrash() {
        AiVerdictParseResult result = new AiVerdictParser().parse("{not-json");

        assertAll(
                () -> assertFalse(result.isParsed()),
                () -> assertNull(result.getResponse()),
                () -> assertFalse(result.getErrorMessage().isBlank())
        );
    }

    @Test
    void unknownEnumMapsToUnknownAndConfidenceClamps() {
        String json = "{\"overall_verdict\":\"definitely_bad\",\"overall_severity\":\"urgent\",\"confidence\":120," +
                "\"summary\":\"ok\",\"items\":[{" +
                "\"rule_group\":\"g\",\"rule_name\":\"r\",\"rule_hash\":\"h\",\"matched_value_redacted\":\"m\"," +
                "\"match_location\":\"response body\",\"verdict\":\"new_enum\",\"severity\":\"urgent\",\"confidence\":-5" +
                "}]}";

        AiVerdictParseResult result = new AiVerdictParser().parse(json);
        AiTriageResponse response = result.getResponse();

        assertAll(
                () -> assertTrue(result.isParsed()),
                () -> assertEquals(AiTriageVerdict.UNKNOWN, response.getOverallVerdict()),
                () -> assertEquals(AiTriageRiskLevel.UNKNOWN, response.getOverallRiskLevel()),
                () -> assertEquals(1.0, response.getConfidence()),
                () -> assertEquals(AiTriageVerdict.UNKNOWN, response.getItems().get(0).getVerdict()),
                () -> assertEquals(AiTriageRiskLevel.UNKNOWN, response.getItems().get(0).getRiskLevel()),
                () -> assertEquals(0.0, response.getItems().get(0).getConfidence())
        );
    }

    @Test
    void percentAndFractionalConfidenceNormalizeConsistently() {
        String json = "{\"overall_verdict\":\"possible_sensitive\",\"overall_severity\":\"medium\",\"confidence\":75," +
                "\"summary\":\"review needed\",\"items\":[{" +
                "\"rule_group\":\"Sensitive Information\",\"rule_name\":\"JWT\",\"rule_hash\":\"hash-1\"," +
                "\"matched_value_redacted\":\"token[redacted]\",\"match_location\":\"request header\"," +
                "\"verdict\":\"sensitive_exposure\",\"severity\":\"critical\",\"confidence\":\"0.25\"" +
                "}]}";

        AiVerdictParseResult result = new AiVerdictParser().parse(json);
        AiTriageResponse response = result.getResponse();

        assertAll(
                () -> assertTrue(result.isParsed()),
                () -> assertEquals(0.75, response.getConfidence()),
                () -> assertEquals(0.25, response.getItems().get(0).getConfidence()),
                () -> assertEquals(AiTriageVerdict.SENSITIVE_EXPOSURE, response.getItems().get(0).getVerdict()),
                () -> assertEquals(AiTriageRiskLevel.CRITICAL, response.getItems().get(0).getRiskLevel())
        );
    }

    @Test
    void strictJsonParsesWithoutFence() {
        AiVerdictParseResult result = new AiVerdictParser().parse(
                "{\"overall_verdict\":\"not_sensitive\",\"overall_severity\":\"info\",\"confidence\":\"0.5\",\"summary\":\"No exposure\",\"items\":[]}"
        );

        assertAll(
                () -> assertTrue(result.isParsed()),
                () -> assertEquals(AiTriageVerdict.NOT_SENSITIVE, result.getResponse().getOverallVerdict()),
                () -> assertEquals(AiTriageRiskLevel.INFO, result.getResponse().getOverallRiskLevel()),
                () -> assertEquals(0.5, result.getResponse().getConfidence()),
                () -> assertEquals("No exposure", result.getResponse().getSummary())
        );
    }

    @Test
    void emptyUnknownResponseIsInvalid() {
        AiVerdictParseResult result = new AiVerdictParser().parse(
                "{\"overall_verdict\":\"unknown\",\"overall_severity\":\"unknown\",\"confidence\":0.0,\"summary\":\"\",\"items\":[]}"
        );

        assertAll(
                () -> assertFalse(result.isParsed()),
                () -> assertNull(result.getResponse()),
                () -> assertTrue(result.getErrorMessage().contains(AiTriageResultQuality.EMPTY_ADVISORY))
        );
    }

    @Test
    void lowQualityNonEmptyFalsePositiveResponseIsInvalid() {
        AiVerdictParseResult result = new AiVerdictParser().parse(
                "{\"overall_verdict\":\"false_positive\",\"overall_severity\":\"unknown\",\"confidence\":0.0,\"summary\":\"\"," +
                        "\"items\":[{\"rule_group\":\"敏感信息\",\"rule_name\":\"密码\",\"rule_hash\":\"hash-1\"," +
                        "\"matched_value_redacted\":\"accSetPwd:\\\"/[redacted]\\\"\",\"match_location\":\"match context pending\"," +
                        "\"verdict\":\"false_positive\",\"is_sensitive\":false,\"is_exposed\":false," +
                        "\"confidence\":0.0,\"severity\":\"unknown\",\"reason\":\"static asset\",\"recommended_actions\":[]}] }"
        );

        assertAll(
                () -> assertFalse(result.isParsed()),
                () -> assertNull(result.getResponse()),
                () -> assertTrue(result.getErrorMessage().contains(AiTriageResultQuality.LOW_QUALITY_ADVISORY))
        );
    }

    @Test
    void strongItemSynthesizesMissingOverallJudgment() {
        AiVerdictParseResult result = new AiVerdictParser().parse(
                "{\"overall_verdict\":\"unknown\",\"overall_severity\":\"unknown\",\"confidence\":0.0,\"summary\":\"\"," +
                        "\"items_truncated\":false,\"omitted_item_count\":0," +
                        "\"items\":[{\"rule_group\":\"敏感信息\",\"rule_name\":\"用户名\",\"rule_hash\":\"hash-1\"," +
                        "\"matched_value_redacted\":\"\",\"match_location\":\"\",\"verdict\":\"false_positive\"," +
                        "\"is_sensitive\":false,\"is_exposed\":false,\"confidence\":0.98,\"severity\":\"info\"," +
                        "\"reason\":\"Static JavaScript route name, no account identifier present\",\"recommended_actions\":[]}] }"
        );

        assertAll(
                () -> assertTrue(result.isParsed()),
                () -> assertEquals(AiTriageVerdict.FALSE_POSITIVE, result.getResponse().getOverallVerdict()),
                () -> assertEquals(AiTriageRiskLevel.INFO, result.getResponse().getOverallRiskLevel()),
                () -> assertEquals(0.98, result.getResponse().getConfidence()),
                () -> assertEquals("Static JavaScript route name, no account identifier present", result.getResponse().getSummary())
        );
    }

    @Test
    void falsePositiveWithSummaryAndConfidenceParses() {
        AiVerdictParseResult result = new AiVerdictParser().parse(
                "{\"overall_verdict\":\"false_positive\",\"overall_severity\":\"unknown\",\"confidence\":0.86," +
                        "\"summary\":\"Only static route names were observed.\"," +
                        "\"items\":[{\"rule_group\":\"敏感信息\",\"rule_name\":\"密码\",\"rule_hash\":\"hash-1\"," +
                        "\"matched_value_redacted\":\"accSetPwd:\\\"/[redacted]\\\"\",\"match_location\":\"response match_context_excerpt\"," +
                        "\"verdict\":\"false_positive\",\"is_sensitive\":false,\"is_exposed\":false," +
                        "\"confidence\":0.84,\"severity\":\"info\",\"reason\":\"static route name\",\"recommended_actions\":[]}] }"
        );

        assertAll(
                () -> assertTrue(result.isParsed()),
                () -> assertEquals(AiTriageVerdict.FALSE_POSITIVE, result.getResponse().getOverallVerdict()),
                () -> assertEquals(AiTriageRiskLevel.INFO, result.getResponse().getOverallRiskLevel()),
                () -> assertEquals(0.86, result.getResponse().getConfidence())
        );
    }

    @Test
    void promptEvidenceEchoIsRedactedFromParsedFields() {
        String json = "{\"overall_verdict\":\"possible_sensitive\",\"overall_severity\":\"medium\",\"confidence\":0.5," +
                "\"summary\":\"-----BEGIN UNTRUSTED HTTP EVIDENCE----- GET /api/token HTTP/1.1 Host: example.test\"," +
                "\"items\":[{" +
                "\"rule_group\":\"Sensitive Information\",\"rule_name\":\"JWT\",\"rule_hash\":\"hash-1\"," +
                "\"matched_value_redacted\":\"HTTP/1.1 200 OK token\",\"match_location\":\"response body\"," +
                "\"verdict\":\"sensitive_exposure\",\"severity\":\"high\",\"confidence\":0.8," +
                "\"reason\":\"RESPONSE: HTTP/1.1 200 OK token body\"," +
                "\"recommended_actions\":[\"Do not repeat REQUEST: GET /api/token HTTP/1.1\"]" +
                "}]}";

        AiVerdictParseResult result = new AiVerdictParser().parse(json);
        AiTriageResponse response = result.getResponse();

        assertAll(
                () -> assertTrue(result.isParsed()),
                () -> assertEquals("[redacted prompt evidence]", response.getSummary()),
                () -> assertEquals("[redacted prompt evidence]", response.getItems().get(0).getMatchedValueRedacted()),
                () -> assertEquals("[redacted prompt evidence]", response.getItems().get(0).getReason()),
                () -> assertEquals("[redacted prompt evidence]", response.getItems().get(0).getRecommendedActions().get(0))
        );
    }
}
