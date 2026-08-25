package pro.sketchware.ai.provider;

import androidx.annotation.NonNull;

import java.io.IOException;

import pro.sketchware.ai.AiModels;

/** Shared mapping of transport failures to safe {@link AiModels.AiException}s. */
final class AiHttpErrors {

    private AiHttpErrors() {
    }

    @NonNull
    static AiModels.AiException mapIoException(@NonNull IOException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("timeout")) {
            return new AiModels.AiException(AiModels.ErrorCode.TIMEOUT,
                    "The AI service took too long to respond.", e);
        }
        return new AiModels.AiException(AiModels.ErrorCode.NETWORK_UNAVAILABLE,
                "Network error. Check your internet connection.", e);
    }
}
