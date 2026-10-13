package com.agentharness.v3;

import com.agentharness.chat.ChatProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * v3: MCP as a tool source. Java equivalent of v3.py.
 * <p>
 * On top of v2's local tools and loop, this stage launches external MCP servers
 * as subprocesses speaking MCP over stdio - the same mechanism Claude Code itself
 * uses - and merges their advertised tools into the same tool list the model sees.
 * The agent loop neither knows nor cares whether a tool is local or came from an
 * MCP server; {@link #callTool} is the only place that distinction exists.
 * <p>
 * Local tools here are {@code list_files}/{@code read_file} only (no
 * {@code write_file}), matching v3.py.
 */
public class V3 {

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

    // Each server is just a command to launch. Same format Claude Code uses.
    private static final Map<String, List<String>> MCP_SERVERS = Map.of(
            "time", List.of("uvx", "mcp-server-time"),
            "fetch", List.of("uvx", "mcp-server-fetch")
    );

    // --- our own local tools, unchanged from v1 ---------------------------------

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

    private static final Map<String, Function<Map<String, Object>, String>> LOCAL_TOOLS = Map.of(
            "list_files", V3::listFiles,
            "read_file", V3::readFile
    );

    private static final List<ToolSpecification> LOCAL_TOOL_SPECIFICATIONS = List.of(
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

    // --- NEW: the MCP component --------------------------------------------------

    /** Launches every configured MCP server and returns its connected client, keyed by server name. */
    private static Map<String, McpClient> connectMcp() {
        Map<String, McpClient> clients = new HashMap<>();
        for (var entry : MCP_SERVERS.entrySet()) {
            StdioMcpTransport transport = StdioMcpTransport.builder()
                    .command(entry.getValue())
                    // these servers are Python (mcp-server-time, mcp-server-fetch); Python
                    // block-buffers stdout when it isn't a TTY, so its JSON-RPC responses
                    // get computed but never actually flushed down the pipe until the
                    // buffer fills or the process exits - this makes every call hang until
                    // timeout. PYTHONUNBUFFERED forces line-by-line flushing instead.
                    .environment(Map.of("PYTHONUNBUFFERED", "1"))
                    .build();
            McpClient client = DefaultMcpClient.builder()
                    .key(entry.getKey())
                    .transport(transport)
                    // the official Python MCP servers (mcp-server-time, mcp-server-fetch)
                    // only speak the legacy protocol and don't answer the newer auto-detection
                    // probe, which otherwise stalls until it times out - skip detection.
                    .protocolVersion("2025-11-25")
                    .build();
            clients.put(entry.getKey(), client);
        }
        return clients;
    }

    /** The merged tool list the model sees, plus which MCP client owns each remote tool. */
    private record ToolRegistry(List<ToolSpecification> specifications, Map<String, McpClient> mcpOwners) {
    }

    /** Lists each server's tools and merges them into {@link #LOCAL_TOOL_SPECIFICATIONS}. */
    private static ToolRegistry buildToolRegistry(Map<String, McpClient> mcpClients) {
        List<ToolSpecification> specifications = new ArrayList<>(LOCAL_TOOL_SPECIFICATIONS);
        Map<String, McpClient> owners = new HashMap<>();
        for (var entry : mcpClients.entrySet()) {
            McpClient client = entry.getValue();
            List<ToolSpecification> tools = client.listTools();
            for (ToolSpecification tool : tools) {
                owners.put(tool.name(), client);
                specifications.add(tool);
            }
            System.out.println("  [mcp] connected '" + entry.getKey() + "': "
                    + tools.stream().map(ToolSpecification::name).toList());
        }
        return new ToolRegistry(specifications, owners);
    }

    /** Runs a tool - ours directly, or an MCP server's over the protocol. */
    private static String callTool(Map<String, McpClient> mcpOwners, ToolExecutionRequest request) {
        Function<Map<String, Object>, String> localTool = LOCAL_TOOLS.get(request.name());
        if (localTool != null) {
            return localTool.apply(parseArguments(request.arguments()));
        }
        McpClient owner = mcpOwners.get(request.name());
        if (owner == null) {
            return "error: unknown tool " + request.name();
        }
        ToolExecutionResult result = owner.executeTool(request);
        return result.resultContents().stream()
                .filter(TextContent.class::isInstance)
                .map(content -> ((TextContent) content).text())
                .collect(Collectors.joining("\n"));
    }

    // --- the agent loop, unchanged in shape from v2 -------------------------------

    private static String runAgent(ChatModel model, ToolRegistry tools, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(userMessage)));

        while (true) {
            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(tools.specifications())
                    .build());
            AiMessage aiMessage = response.aiMessage();

            if (!aiMessage.hasToolExecutionRequests()) {
                return aiMessage.text() != null ? aiMessage.text() : "";
            }

            messages.add(aiMessage);
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                System.out.println("  [tool] " + request.name() + "(" + request.arguments() + ")");
                String result = callTool(tools.mcpOwners(), request);
                messages.add(ToolExecutionResultMessage.from(request, result));
            }
        }
    }

    public static void main(String[] args) {
        ChatModel model = ChatProvider.get(MODEL_NAME);
        Map<String, McpClient> mcpClients = connectMcp();
        try {
            ToolRegistry tools = buildToolRegistry(mcpClients);

            System.out.println("\nv3 assistant (" + MODEL_NAME + ") with "
                    + tools.specifications().size() + " tools - ctrl+c to quit");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("\nbye!")));

            try (Scanner scanner = new Scanner(System.in)) {
                while (true) {
                    System.out.print("\nyou: ");
                    if (!scanner.hasNextLine()) {
                        break;
                    }
                    String question = scanner.nextLine();
                    System.out.println("\nassistant: " + runAgent(model, tools, question));
                }
            }
        } finally {
            for (McpClient client : mcpClients.values()) {
                try {
                    client.close();
                } catch (Exception e) {
                    System.out.println("  [mcp] error closing client: " + e.getMessage());
                }
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
