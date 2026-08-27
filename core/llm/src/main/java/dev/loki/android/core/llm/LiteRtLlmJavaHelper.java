package dev.loki.android.core.llm;

import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.MessageCallback;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.Content;
import java.util.Collections;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;

public class LiteRtLlmJavaHelper {
    private Engine engine;

    public boolean initialize(String modelPath) {
        try {
            EngineConfig config = new EngineConfig(modelPath, new Backend.GPU());
            engine = new Engine(config);
            engine.initialize();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean validateModel(String modelPath) {
        try {
            EngineConfig config = new EngineConfig(modelPath, new Backend.GPU());
            try (Engine testEngine = new Engine(config)) {
                testEngine.initialize();
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public CompletableFuture<String> generate(String prompt, Consumer<String> onToken) {
        if (engine == null) {
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Engine not initialized"));
            return future;
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        StringBuilder fullResponse = new StringBuilder();

        try {
            Conversation conversation = engine.createConversation();
            conversation.sendMessageAsync(prompt, new MessageCallback() {
                @Override
                public void onMessage(Message message) {
                    for (Content content : message.getContents().getContents()) {
                        if (content instanceof Content.Text) {
                            String text = ((Content.Text) content).getText();
                            if (onToken != null) {
                                onToken.accept(text);
                            }
                            fullResponse.append(text);
                        }
                    }
                }

                @Override
                public void onDone() {
                    conversation.close();
                    future.complete(fullResponse.toString());
                }

                @Override
                public void onError(Throwable throwable) {
                    conversation.close();
                    future.completeExceptionally(throwable);
                }
            }, Collections.emptyMap());
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public void release() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }
}
