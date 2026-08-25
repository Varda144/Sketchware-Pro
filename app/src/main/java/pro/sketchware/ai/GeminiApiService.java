package pro.sketchware.ai;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import pro.sketchware.ai.provider.AiProvider;

/**
 * Facade over the provider registry exposing simple async text operations
 * suitable for UI, custom blocks and the natural-language-to-blocks pipeline.
 *
 * All methods are asynchronous; callbacks are invoked on a background thread
 * (post to the main looper yourself when touching views).
 */
public final class GeminiApiService {

    private GeminiApiService() {
    }

    /** One-shot prompt -> generated text. */
    public static void generateText(@NonNull Context context,
                                    @Nullable String prompt,
                                    @NonNull AiModels.Callback callback) {
        active(context).generate(AiModels.GenerateRequest.singlePrompt(prompt), callback);
    }

    /**
     * Chat with conversation history. {@code history} may contain prior
     * USER/MODEL messages; the new user message is appended automatically.
     */
    public static void chat(@NonNull Context context,
                            @Nullable List<AiModels.Message> history,
                            @Nullable String newUserMessage,
                            @Nullable String systemInstruction,
                            @NonNull AiModels.Callback callback) {
        List<AiModels.Message> messages = new ArrayList<>();
        if (systemInstruction != null && !systemInstruction.isEmpty()) {
            messages.add(new AiModels.Message(AiModels.Message.Role.SYSTEM, systemInstruction));
        }
        if (history != null) {
            messages.addAll(history);
        }
        if (newUserMessage != null) {
            messages.add(new AiModels.Message(AiModels.Message.Role.USER, newUserMessage));
        }
        active(context).generate(new AiModels.GenerateRequest(messages), callback);
    }

    /** @return true when the active provider has an API key configured. */
    public static boolean isConfigured(@NonNull Context context) {
        return active(context).isConfigured();
    }

    private static AiProvider active(Context context) {
        return AiProviderRegistry.active(context);
    }
}
