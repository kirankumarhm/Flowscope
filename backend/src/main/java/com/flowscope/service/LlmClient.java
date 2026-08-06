package com.flowscope.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowscope.config.LlmProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Sends a single prompt to whatever LLM endpoint the operator configured and
 * returns the assistant's text.
 *
 * <p>Provider-neutral by design: the two dominant request shapes are built by
 * hand over {@link HttpClient}, so FlowScope depends on no vendor SDK and any
 * endpoint speaking either contract works — hosted APIs, gateways, or a local
 * runtime. Nothing here is specific to one vendor beyond the wire format the
 * operator selects.
 */
@Service
public class LlmClient {

    private final LlmProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmClient(LlmProperties props) {
        this.props = props;
    }

    /** True when {@link #complete} could reach a provider. */
    public boolean available() {
        return props.isUsable();
    }

    /**
     * Runs one completion. Returns the assistant's text, or throws
     * {@link LlmUnavailableException} when the provider is unreachable, rejects
     * the request, or answers in a shape we can't read — callers fall back to
     * heuristics rather than failing the request.
     */
    public String complete(String systemPrompt, String userPrompt) {
        if (!available()) {
            throw new LlmUnavailableException("no LLM provider configured");
        }
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(20, props.getTimeoutSeconds())))
                .build();

        HttpRequest request = buildRequest(systemPrompt, userPrompt);
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new LlmUnavailableException(
                        "provider returned HTTP " + response.statusCode() + ": " + brief(response.body()));
            }
            return extractText(response.body());
        } catch (IOException e) {
            // Connection failures often carry a null message; fall back to the
            // exception type so the caveat shown to the user isn't "null".
            String cause = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            throw new LlmUnavailableException("could not reach provider: " + cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("LLM call interrupted");
        }
    }

    // --- request building ---------------------------------------------------

    private HttpRequest buildRequest(String systemPrompt, String userPrompt) {
        String body = props.getWireFormat() == LlmProperties.WireFormat.MESSAGES
                ? messagesBody(systemPrompt, userPrompt)
                : chatCompletionsBody(systemPrompt, userPrompt);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl()))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        switch (props.getAuthScheme()) {
            case BEARER -> builder.header("authorization", "Bearer " + props.getApiKey());
            case API_KEY_HEADER -> builder.header("x-api-key", props.getApiKey());
            case AZURE_API_KEY -> builder.header("api-key", props.getApiKey());
            case NONE -> { }
        }

        String extra = props.getExtraHeader();
        int colon = extra.indexOf(':');
        if (colon > 0) {
            builder.header(extra.substring(0, colon).trim(), extra.substring(colon + 1).trim());
        }
        return builder.build();
    }

    /** OpenAI-style: system and user are both entries in {@code messages}. */
    private String chatCompletionsBody(String systemPrompt, String userPrompt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", props.getModel());
        root.put("max_tokens", props.getMaxTokens());
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        return root.toString();
    }

    /** Anthropic-style: {@code system} is a top-level field, not a message. */
    private String messagesBody(String systemPrompt, String userPrompt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", props.getModel());
        root.put("max_tokens", props.getMaxTokens());
        root.put("system", systemPrompt);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "user").put("content", userPrompt);
        return root.toString();
    }

    // --- response reading ---------------------------------------------------

    /**
     * Pulls the assistant text out of either response shape. Both are walked
     * defensively — a gateway that wraps or renames fields shouldn't produce a
     * 500, just a fallback to heuristics.
     */
    private String extractText(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            throw new LlmUnavailableException("provider returned non-JSON: " + brief(body));
        }

        // chat-completions: choices[0].message.content
        JsonNode choice = root.path("choices").path(0).path("message").path("content");
        if (choice.isTextual() && !choice.asText().isBlank()) {
            return choice.asText();
        }

        // messages: content[] of typed blocks; concatenate the text ones
        JsonNode content = root.path("content");
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                JsonNode text = block.path("text");
                if (text.isTextual()) {
                    sb.append(text.asText());
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
        }

        // Some gateways answer with a bare string field.
        for (String field : new String[] {"output_text", "text", "response"}) {
            JsonNode node = root.path(field);
            if (node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
        }

        JsonNode error = root.path("error");
        if (!error.isMissingNode()) {
            String message = error.path("message").asText(error.toString());
            throw new LlmUnavailableException("provider error: " + brief(message));
        }
        throw new LlmUnavailableException("could not find assistant text in provider response");
    }

    private String brief(String text) {
        if (text == null) {
            return "(empty)";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > 200 ? flat.substring(0, 199) + "…" : flat;
    }

    /**
     * Thrown when the configured provider can't answer. Callers treat this as
     * "fall back to heuristics", not as a request failure.
     */
    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message) {
            super(message);
        }
    }
}
