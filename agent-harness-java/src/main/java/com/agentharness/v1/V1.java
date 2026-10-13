package com.agentharness.v1;

import com.agentharness.chat.ChatProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * v1: tool calling - a single round. Java equivalent of v1.py.
 * <p>
 * The model is offered tools ({@code list_files}, {@code read_file}). If it asks for one,
 * we run it once and feed the result back for ONE final reply - this is not yet the
 * looping agent (that's v2).
 */
public class V1 {

    private static final String SYSTEM_PROMPT = """
            You are a helpful personal assistant.

            The user's workspace folder holds their personal files. You have tools to \
            list and read those files - use them whenever asked. Never claim you lack \
            access to the user's files; call the tool instead. When a tool returns a \
            file's contents, share that content with the user directly rather than \
            describing how they could look it up themselves.""";
    private static final String MODEL_NAME = ChatProvider.DEFAULT_MODEL;
    private static final Path WORKSPACE = Path.of("workspace");
    private static final ObjectMapper JSON = new ObjectMapper();

    // --- the actual tools: plain Java functions ------------------------------

    private static String listFiles(Map<String, Object> args) {
        try (var files = Files.list(WORKSPACE)) {
            String names = files.map(p -> p.getFileName().toString())
                    .collect(Collectors.joining("\n"));
            return names.isEmpty() ? "(empty)" : names;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readFile(Map<String, Object> args) {
        Object filenameArg = args.get("filename");
        if (!(filenameArg instanceof String filename)) {
            return "error: missing required argument 'filename'";
        }
        Path path = WORKSPACE.resolve(filename);
        if (!Files.isRegularFile(path)) {
            return "error: no file named " + filename;
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final Map<String, Function<Map<String, Object>, String>> TOOLS = Map.of(
            "list_files", V1::listFiles,
            "read_file", V1::readFile
    );

    // --- what the model sees: a schema for each tool --------------------------

    private static final List<ToolSpecification> TOOL_SPECIFICATIONS = List.of(
            ToolSpecification.builder()
                    .name("list_files")
                    .description("List the files in the user's workspace folder.")
                    .parameters(JsonObjectSchema.builder().build())
                    .build(),
            ToolSpecification.builder()
                    .name("read_file")
                    .description("Read one file from the user's workspace folder.")
                    .parameters(JsonObjectSchema.builder()
                            .addStringProperty("filename")
                            .required("filename")
                            .build())
                    .build()
    );

    public static void main(String[] args) {
        ChatModel model = ChatProvider.get(MODEL_NAME);

        System.out.println("v1 assistant (" + MODEL_NAME + ") - ctrl+c to quit");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("\nbye!")));

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nyou: ");
                if (!scanner.hasNextLine()) {
                    break;
                }
                String question = scanner.nextLine();
                System.out.println("\nassistant: " + chat(model, question));
            }
        }
    }

    private static String chat(ChatModel model, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(userMessage)));

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(TOOL_SPECIFICATIONS)
                .build());
        AiMessage aiMessage = response.aiMessage();

        // The model didn't want a tool - just answer.
        if (!aiMessage.hasToolExecutionRequests()) {
            return aiMessage.text() != null ? aiMessage.text() : "";
        }

        // Run the tool(s) it asked for and give it the result(s)... ONCE.
        messages.add(aiMessage);
        for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
            Map<String, Object> toolArgs = parseArguments(request.arguments());
            System.out.println("  [tool] " + request.name() + "(" + toolArgs + ")");
            Function<Map<String, Object>, String> tool = TOOLS.get(request.name());
            String result = tool != null ? tool.apply(toolArgs) : "error: unknown tool " + request.name();
            messages.add(ToolExecutionResultMessage.from(request, result));
        }

        // Final reply. We still offer the same tools here (rather than omitting
        // them) because some models - e.g. Groq's gpt-oss, which keeps reaching
        // for tool calls out of habit - reject a follow-up request that declares
        // "tool_choice: none" right after a tool result with a hard error if they
        // try to call one anyway. Offering the tools sidesteps that; since v1 is
        // deliberately single-round, we just don't execute a second tool call if
        // one comes back (that's what v2's loop is for).
        ChatResponse finalResponse = model.chat(ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(TOOL_SPECIFICATIONS)
                .build());
        AiMessage finalMessage = finalResponse.aiMessage();
        if (finalMessage.text() != null) {
            return finalMessage.text();
        }
        return "(model tried to call another tool instead of answering - v1 only runs one round; v2 adds the loop)";
    }

    private static Map<String, Object> parseArguments(String json) {
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
