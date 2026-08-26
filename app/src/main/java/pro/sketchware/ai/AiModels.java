package pro.sketchware.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Transport-agnostic request/response models shared by every AI provider
 * implementation. Providers convert these to their wire formats internally.
 */
public final class AiModels {

    private AiModels() {
    }

    /** A single chat message. */
    public static class Message {
        public enum Role {
            SYSTEM, USER, MODEL
        }

        @NonNull
        public final Role role;
        @NonNull
        public final String content;

        public Message(@NonNull Role role, @NonNull String content) {
            this.role = role;
            this.content = content;
        }
    }

    /** Request for a text generation or chat completion. */
    public static class GenerateRequest {
        @NonNull
        public final List<Message> messages;
        /** 0.0 - 2.0 depending on provider; null = provider default. */
        @Nullable
        public final Float temperature;
        @Nullable
        public final Integer maxOutputTokens;

        public GenerateRequest(@NonNull List<Message> messages) {
            this(messages, null, null);
        }

        public GenerateRequest(@NonNull List<Message> messages,
                               @Nullable Float temperature,
                               @Nullable Integer maxOutputTokens) {
            this.messages = new ArrayList<>(messages);
            this.temperature = temperature;
            this.maxOutputTokens = maxOutputTokens;
        }

        /** Convenience factory for a single-turn prompt. */
        public static GenerateRequest singlePrompt(@Nullable String prompt) {
            List<Message> msgs = new ArrayList<>();
            msgs.add(new Message(Message.Role.USER, prompt == null ? "" : prompt));
            return new GenerateRequest(msgs);
        }
    }

    /** Normalized result of a generation call. */
    public static class GenerateResult {
        @NonNull
        public final String text;
        /** Model-reported token usage when available; -1 otherwise. */
        public final int inputTokens;
        public final int outputTokens;
        @Nullable
        public final String rawJson;

        public GenerateResult(@NonNull String text, int inputTokens, int outputTokens,
                              @Nullable String rawJson) {
            this.text = text;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.rawJson = rawJson;
        }
    }

    /** Error codes surfaced by providers; UI maps these to friendly strings. */
    public enum ErrorCode {
        MISSING_API_KEY,
        INVALID_API_KEY,
        NETWORK_UNAVAILABLE,
        TIMEOUT,
        RATE_LIMITED,
        QUOTA_EXCEEDED,
        SERVER_ERROR,
        BAD_REQUEST,
        MALFORMED_RESPONSE,
        UNKNOWN
    }

    /** Exception carrying a safe, user-presentable message and a machine code. */
    public static class AiException extends Exception {
        @NonNull
        public final ErrorCode code;

        public AiException(@NonNull ErrorCode code, @NonNull String safeMessage) {
            super(safeMessage);
            this.code = code;
        }

        public AiException(@NonNull ErrorCode code, @NonNull String safeMessage, Throwable cause) {
            super(safeMessage, cause);
            this.code = code;
        }
    }

    /** Async callback invoked on the caller-provided executor / main thread. */
    public interface Callback {
        void onSuccess(@NonNull GenerateResult result);

        void onError(@NonNull AiException error);
    }
}
