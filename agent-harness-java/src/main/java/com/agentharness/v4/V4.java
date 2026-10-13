package com.agentharness.v4;

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
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * v4: persistent memory. Java equivalent of v4.py.
 * <p>
 * Two things change from v2: a {@code memory.md} file is loaded into the system
 * prompt at startup and a {@code save_memory} tool appends new facts to it, and
 * the conversation ({@code messages}) now persists across turns instead of being
 * rebuilt fresh on every call - {@link #runAgent} mutates the same list the REPL
 * loop owns, rather than building its own. Local tools here are {@code list_files}/
 * {@code read_file} only (no {@code write_file}), matching v4.py.
 */
public class V4 {

    private static final String SYSTEM_PROMPT = """
            You are a helpful personal assistant.

            The user's workspace folder holds their personal files. You have tools to \
            list and read those files - use them whenever asked. Never claim you lack \
            access to the user's files; call the tool instead. When a tool returns a \
            file's contents, share that content with the user directly rather than \
            describing how they could look it up themselves.

            Here is what you remember about the user from previous sessions:
            %s

            When you learn a new lasting fact about the user, save it with save_memory.""";
    private static final String MODEL_NAME = ChatProvider.DEFAULT_MODEL;
    private static final Path WORKSPACE = Path.of("workspace");
    private static final Path MEMORY_FILE = Path.of("memory.md");
    private static final ObjectMapper JSON = new ObjectMapper();

    // --- memory: a markdown file, that's it -------------------------------------

    private static String loadMemory() {
        if (Files.isRegularFile(MEMORY_FILE)) {
            try {
                return Files.readString(MEMORY_FILE);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return "(nothing saved yet)";
    }

    private static String saveMemory(Map<String, Object> args) {
        Object factArg = args.get("fact");
        if (!(factArg instanceof String fact)) {
            return "error: missing required argument 'fact'";
        }
        try {
            Files.writeString(MEMORY_FILE, "- " + fact + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return "saved: " + fact;
    }

    // --- the other tools ----------------------------------------------------------

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
            "list_files", V4::listFiles,
            "read_file", V4::readFile,
            "save_memory", V4::saveMemory
    );

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
                    .name("save_memory")
                    .description("Save one short fact about the user to long-term memory. "
                            + "Use whenever you learn something worth remembering: their "
                            + "name, preferences, projects, recurring tasks.")
                    .parameters(JsonObjectSchema.builder()
                            .addStringProperty("fact")
                            .required("fact")
                            .build())
                    .build()
    );

    public static void main(String[] args) {
        ChatModel model = ChatProvider.get(MODEL_NAME);

        // the running conversation - persists across every turn, unlike v0-v3
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT.formatted(loadMemory())));

        System.out.println("v4 assistant (" + MODEL_NAME + ") - memory loaded - ctrl+c to quit");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("\nbye!")));

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nyou: ");
                if (!scanner.hasNextLine()) {
                    break;
                }
                messages.add(UserMessage.from(scanner.nextLine()));
                System.out.println("\nassistant: " + runAgent(model, messages));
            }
        }
    }

    private static String runAgent(ChatModel model, List<ChatMessage> messages) {
        while (true) {
            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(TOOL_SPECIFICATIONS)
                    .build());
            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                return aiMessage.text() != null ? aiMessage.text() : "";  // done - it answered
            }

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
