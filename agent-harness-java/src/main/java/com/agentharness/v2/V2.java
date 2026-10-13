package com.agentharness.v2;

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
 * v2: the agentic loop. Java equivalent of v2.py.
 * <p>
 * v1's single round becomes a real loop: keep calling the model and executing
 * whatever tools it requests, feeding the results back, until it replies with
 * no more tool requests. Adds {@code write_file} and {@code rename_file} on top
 * of v1's tools ({@code rename_file} has no Python equivalent - it's an extra).
 */
public class V2 {

    private static final String SYSTEM_PROMPT = """
            You are a helpful personal assistant.

            The user's workspace folder holds their personal files. You have tools to \
            list, read, write, and rename those files - use them whenever asked. Never \
            claim you lack access to the user's files; call the tool instead. When a \
            tool returns a file's contents, share that content with the user directly \
            rather than describing how they could look it up themselves.""";
    private static final String MODEL_NAME = ChatProvider.DEFAULT_MODEL;
    private static final Path WORKSPACE = Path.of("workspace");
    private static final ObjectMapper JSON = new ObjectMapper();

    // --- the tools ------------------------------------------------------------

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

    private static String writeFile(Map<String, Object> args) {
        Object filenameArg = args.get("filename");
        Object contentArg = args.get("content");
        if (!(filenameArg instanceof String filename)) {
            return "error: missing required argument 'filename'";
        }
        if (!(contentArg instanceof String content)) {
            return "error: missing required argument 'content'";
        }
        try {
            Files.writeString(WORKSPACE.resolve(filename), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return "wrote " + filename;
    }

    private static String renameFile(Map<String, Object> args) {
        Object filenameArg = args.get("filename");
        Object newFilenameArg = args.get("new_filename");
        if (!(filenameArg instanceof String filename)) {
            return "error: missing required argument 'filename'";
        }
        if (!(newFilenameArg instanceof String newFilename)) {
            return "error: missing required argument 'new_filename'";
        }
        Path source = WORKSPACE.resolve(filename);
        if (!Files.isRegularFile(source)) {
            return "error: no file named " + filename;
        }
        Path target = WORKSPACE.resolve(newFilename);
        if (Files.exists(target)) {
            return "error: " + newFilename + " already exists";
        }
        try {
            Files.move(source, target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return "renamed " + filename + " to " + newFilename;
    }

    private static final Map<String, Function<Map<String, Object>, String>> TOOLS = Map.of(
            "list_files", V2::listFiles,
            "read_file", V2::readFile,
            "write_file", V2::writeFile,
            "rename_file", V2::renameFile
    );

    // --- what the model sees: a schema for each tool ---------------------------

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
                    .build(),
            ToolSpecification.builder()
                    .name("write_file")
                    .description("Write (or overwrite) a file in the user's workspace folder.")
                    .parameters(JsonObjectSchema.builder()
                            .addStringProperty("filename")
                            .addStringProperty("content")
                            .required("filename", "content")
                            .build())
                    .build(),
            ToolSpecification.builder()
                    .name("rename_file")
                    .description("Rename a file in the user's workspace folder.")
                    .parameters(JsonObjectSchema.builder()
                            .addStringProperty("filename", "the file's current name")
                            .addStringProperty("new_filename", "the name to rename it to")
                            .required("filename", "new_filename")
                            .build())
                    .build()
    );

    public static void main(String[] args) {
        ChatModel model = ChatProvider.get(MODEL_NAME);

        System.out.println("v2 agent (" + MODEL_NAME + ") - ctrl+c to quit");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("\nbye!")));

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nyou: ");
                if (!scanner.hasNextLine()) {
                    break;
                }
                String question = scanner.nextLine();
                System.out.println("\nassistant: " + runAgent(model, question));
            }
        }
    }

    private static String runAgent(ChatModel model, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(userMessage)));

        // THE agent loop. This is the whole trick.
        while (true) {
            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(TOOL_SPECIFICATIONS)
                    .build());
            AiMessage aiMessage = response.aiMessage();

            if (!aiMessage.hasToolExecutionRequests()) {
                return aiMessage.text() != null ? aiMessage.text() : "";  // done - it answered
            }

            messages.add(aiMessage);
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                Map<String, Object> toolArgs = parseArguments(request.arguments());
                System.out.println("  [tool] " + request.name() + "(" + toolArgs + ")");
                Function<Map<String, Object>, String> tool = TOOLS.get(request.name());
                String result = tool != null ? tool.apply(toolArgs) : "error: unknown tool " + request.name();
                messages.add(ToolExecutionResultMessage.from(request, result));
            }
        }
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
