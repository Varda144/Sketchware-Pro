package pro.sketchware.ai.provider;

import android.content.Context;

import androidx.annotation.NonNull;

/**
 * Preset for the byNara (bynara.id) hosted service, which exposes a standard
 * OpenAI-compatible chat-completions API.
 *
 * Users may override the base URL, model and API key from the AI settings.
 * The default base URL points at the byNara v1 endpoint and a sensible default
 * model is provided ({@link #defaultModel()}).
 */
public final class BynaraProvider extends OpenAiCompatibleProvider {

    public static final String ID = "bynara";
    public static final String PRESET_BASE = "https://bynara.id/v1";
    public static final String DEFAULT_MODEL = "agnes-2.5-flash";

    public BynaraProvider(@NonNull Context context) {
        super(context, PRESET_BASE);
    }

    @NonNull
    @Override
    public String id() {
        return ID;
    }

    @NonNull
    @Override
    public String displayName() {
        return "byNara (bynara.id)";
    }

    @NonNull
    @Override
    public String defaultModel() {
        return DEFAULT_MODEL;
    }
}
