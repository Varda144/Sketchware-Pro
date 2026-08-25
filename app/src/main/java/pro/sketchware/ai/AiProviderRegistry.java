package pro.sketchware.ai;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import pro.sketchware.ai.provider.AiProvider;
import pro.sketchware.ai.provider.GeminiProvider;
import pro.sketchware.ai.provider.OpenAiCompatibleProvider;
import pro.sketchware.ai.secure.AiSecureStore;

/**
 * Registry of available {@link AiProvider}s and resolution of the
 * currently-active provider from secure preferences.
 */
public final class AiProviderRegistry {

    private static final Object LOCK = new Object();
    private static Map<String, AiProvider> providers;

    private AiProviderRegistry() {
    }

    @NonNull
    public static List<AiProvider> all(@NonNull Context context) {
        ensure(context);
        return new ArrayList<>(providers.values());
    }

    @Nullable
    public static AiProvider byId(@NonNull Context context, @NonNull String id) {
        ensure(context);
        return providers.get(id);
    }

    /**
     * @return the active provider, falling back to Gemini, then to the first
     * registered provider. Never null.
     */
    @NonNull
    public static AiProvider active(@NonNull Context context) {
        ensure(context);
        String saved = AiSecureStore.get(context).getActiveProvider();
        if (saved != null) {
            AiProvider p = providers.get(saved);
            if (p != null) {
                return p;
            }
        }
        AiProvider gemini = providers.get(GeminiProvider.ID);
        if (gemini != null) {
            return gemini;
        }
        return providers.values().iterator().next();
    }

    public static void setActive(@NonNull Context context, @NonNull String providerId) {
        ensure(context);
        if (!providers.containsKey(providerId)) {
            throw new IllegalArgumentException("Unknown AI provider: " + providerId);
        }
        AiSecureStore.get(context).setActiveProvider(providerId);
    }

    private static void ensure(Context context) {
        synchronized (LOCK) {
            if (providers == null) {
                Context app = context.getApplicationContext();
                providers = new LinkedHashMap<>();
                register(new GeminiProvider(app));
                register(new OpenAiCompatibleProvider(app));
            }
        }
    }

    private static void register(AiProvider p) {
        providers.put(p.id(), p);
    }
}
