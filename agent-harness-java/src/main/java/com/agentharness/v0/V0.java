package com.agentharness.v0;

import com.agentharness.chat.ChatProvider;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.Scanner;

/**
 * v0: the bare LLM call. One request in, one response out - no tools, no loop.
 * Java equivalent of the Python project's v0.py, talking to Groq instead of Ollama.
 */
public class V0 {

    private static final String SYSTEM_PROMPT = "You are a helpful personal assistant.";
    private static final String MODEL_NAME = ChatProvider.DEFAULT_MODEL;

    public static void main(String[] args) {
        ChatModel model = ChatProvider.get(MODEL_NAME);

        System.out.println("v0 assistant (" + MODEL_NAME + ") - ctrl+c to quit");
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
        ChatResponse response = model.chat(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(userMessage));
        AiMessage aiMessage = response.aiMessage();
        return aiMessage.text() != null ? aiMessage.text() : "";
    }
}
