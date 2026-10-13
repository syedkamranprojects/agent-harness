package com.agentharness.chat;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/**
 * One place that knows how to build a {@link ChatModel} for any configured model name.
 * <p>
 * Mirrors the {@code MODELS} registry in the Python harness's {@code ModelConfig} pattern:
 * every entry is just an OpenAI-compatible endpoint (base URL + API key + model name), so
 * adding a model is one line here rather than a change everywhere a model is used. Both
 * Groq and Ollama expose an OpenAI-compatible endpoint, so {@link OpenAiChatModel} pointed
 * at the right base URL is all each entry needs - cloud and local models are configured
 * identically.
 */
public final class ChatProvider {

    public record ModelConfig(String baseUrl, String apiKey, String modelName) {
    }

    private static final String GROQ_BASE_URL = "https://api.groq.com/openai/v1";
    private static final String GROQ_API_KEY = System.getenv("GROQ_API_KEY");

    // Ollama's OpenAI-compatible endpoint is at /v1 (NOT /api, which is Ollama's own,
    // differently-shaped native API). Ollama ignores the key's value but the client
    // still needs a non-empty string to send as a bearer token.
    private static final String OLLAMA_BASE_URL = "http://localhost:11434/v1";
    private static final String OLLAMA_API_KEY = "ollama";

    private static final Map<String, ModelConfig> MODELS = Map.ofEntries(
            entry("groq-llama-70b", new ModelConfig(GROQ_BASE_URL, GROQ_API_KEY, "llama-3.3-70b-versatile")),
            entry("groq-llama-8b", new ModelConfig(GROQ_BASE_URL, GROQ_API_KEY, "llama-3.1-8b-instant")),
            entry("groq-oss-120b", new ModelConfig(GROQ_BASE_URL, GROQ_API_KEY, "openai/gpt-oss-120b")),
            entry("groq-oss-20b", new ModelConfig(GROQ_BASE_URL, GROQ_API_KEY, "openai/gpt-oss-20b")),
            // small and fast, but unreliable at generating correctly-shaped tool calls
            entry("local", new ModelConfig(OLLAMA_BASE_URL, OLLAMA_API_KEY, "llama3.2:1b")),
            // more reliable at tool calling, but CPU-only inference is too slow for
            // interactive use on this machine (confirmed: timed out on a single tool
            // call) - kept available for whenever there's GPU acceleration to run it on
            entry("local-qwen", new ModelConfig(OLLAMA_BASE_URL, OLLAMA_API_KEY, "qwen3:8b"))
    );

    public static final String DEFAULT_MODEL = "groq-oss-20b";

    private ChatProvider() {
    }

    /** Build a fresh {@link ChatModel} for the given registry key, e.g. {@code "local"} or {@code "groq-llama-70b"}. */
    public static ChatModel get(String name) {
        ModelConfig config = MODELS.get(name);
        if (config == null) {
            throw new IllegalArgumentException(
                    "unknown model '" + name + "' - available: " + MODELS.keySet());
        }
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "no API key configured for model '" + name + "' - is GROQ_API_KEY set?");
        }
        return OpenAiChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .build();
    }

    /** The registry keys that {@link #get(String)} will accept. */
    public static Set<String> availableModels() {
        return MODELS.keySet();
    }
}
