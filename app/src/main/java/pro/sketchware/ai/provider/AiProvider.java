package pro.sketchware.ai.provider;

import androidx.annotation.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pro.sketchware.ai.AiModels;

/**
 * Abstraction over AI backends (Gemini, OpenAI-compatible endpoints, ...).
 * Implementations must never block the calling thread: every call is dispatched
 * onto the shared background executor and results are delivered through the
 * supplied {@link AiModels.Callback}.
 */
public interface AiProvider {

    ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "sketchware-ai");
        t.setDaemon(true);
        return t;
    });

    /** Stable identifier persisted in preferences, e.g. "gemini". */
    @NonNull
    String id();

    /** Human readable name shown in settings, e.g. "Google Gemini". */
    @NonNull
    String displayName();

    /** Default model id used when the user has not chosen one. */
    @NonNull
    String defaultModel();

    /**
     * @return true when an API key / credential is configured for this provider.
     * Must not reveal the key itself.
     */
    boolean isConfigured();

    /** Performs the generation asynchronously. Never blocks the caller. */
    void generate(@NonNull AiModels.GenerateRequest request, @NonNull AiModels.Callback callback);
}
