package com.flowscope.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Optional LLM provider settings for the business-logic view.
 *
 * <p>Deliberately provider-neutral: FlowScope talks to whatever endpoint you
 * point it at over plain HTTP, so no vendor SDK is required and no provider is
 * privileged. Configure via {@code application.properties} or environment
 * variables ({@code FLOWSCOPE_LLM_API_KEY}, {@code FLOWSCOPE_LLM_BASE_URL}, …).
 *
 * <p>When {@link #getApiKey()} is blank the feature stays available — the
 * business-logic view falls back to offline heuristics and says so.
 */
@Component
@ConfigurationProperties(prefix = "flowscope.llm")
public class LlmProperties {

    /**
     * Request/response shape to speak. {@code chat-completions} is the
     * OpenAI-style {@code /chat/completions} contract that most providers and
     * local runtimes (Ollama, vLLM, LM Studio, gateways) implement;
     * {@code messages} is the Anthropic-style {@code /v1/messages} contract.
     */
    public enum WireFormat {
        CHAT_COMPLETIONS,
        MESSAGES
    }

    /** How the API key is presented. */
    public enum AuthScheme {
        /** {@code Authorization: Bearer <key>} — most providers. */
        BEARER,
        /** {@code x-api-key: <key>} — Anthropic-style. */
        API_KEY_HEADER,
        /** {@code api-key: <key>} — Azure-style. */
        AZURE_API_KEY,
        /** No auth header (local runtimes). */
        NONE
    }

    /** Master switch; set false to disable LLM calls even when a key is present. */
    private boolean enabled = true;

    /** Full URL of the completion endpoint. */
    private String baseUrl = "";

    /** API key / token. Blank means "not configured" — heuristics are used. */
    private String apiKey = "";

    /** Model identifier, passed through verbatim. */
    private String model = "";

    private WireFormat wireFormat = WireFormat.CHAT_COMPLETIONS;

    private AuthScheme authScheme = AuthScheme.BEARER;

    /** Upper bound on generated tokens. */
    private int maxTokens = 2000;

    /** Per-request timeout. LLM calls are slow; keep this under the MVC async timeout. */
    private int timeoutSeconds = 90;

    /**
     * Extra header required by some gateways, e.g. {@code anthropic-version} or
     * an Azure deployment header. Format {@code Name: value}; blank to omit.
     */
    private String extraHeader = "";

    /** True when a call could actually be made. */
    public boolean isUsable() {
        return enabled
                && !baseUrl.isBlank()
                && !model.isBlank()
                && (authScheme == AuthScheme.NONE || !apiKey.isBlank());
    }

    /** Provider label for the UI — host of the configured endpoint, never the key. */
    public String describe() {
        if (!isUsable()) {
            return "not configured";
        }
        try {
            return java.net.URI.create(baseUrl).getHost() + " · " + model;
        } catch (IllegalArgumentException e) {
            return model;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model.trim();
    }

    public WireFormat getWireFormat() {
        return wireFormat;
    }

    public void setWireFormat(WireFormat wireFormat) {
        this.wireFormat = wireFormat;
    }

    public AuthScheme getAuthScheme() {
        return authScheme;
    }

    public void setAuthScheme(AuthScheme authScheme) {
        this.authScheme = authScheme;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getExtraHeader() {
        return extraHeader;
    }

    public void setExtraHeader(String extraHeader) {
        this.extraHeader = extraHeader == null ? "" : extraHeader.trim();
    }
}
