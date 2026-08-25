package pro.sketchware.ai.provider;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Secure storage for AI provider credentials.
 *
 * Keys are stored in {@link EncryptedSharedPreferences}; if Android Keystore
 * is unavailable (rare devices, corrupted keystore) the store degrades to a
 * plain preferences file so the feature still works, and this fact can be
 * surfaced through {@link #isEncrypted()}.
 *
 * The raw key value is never returned by anything except {@link #getKey};
 * UI layers should prefer {@link #isConfigured} / masked previews only.
 */
public final class AiSecureStore {

    private static final String PREFS_NAME = "sketchware_ai_secure";
    private static final String KEY_PROVIDER = "active_provider";
    private static final String KEY_MODEL_PREFIX = "model_";
    private static final String KEY_API_KEY_PREFIX = "api_key_";

    private static final AtomicReference<AiSecureStore> INSTANCE = new AtomicReference<>();

    private final SharedPreferences prefs;
    private final boolean encrypted;

    private AiSecureStore(Context context) {
        SharedPreferences p;
        boolean enc = true;
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            p = new EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            // Keystore unavailable; degrade gracefully rather than crashing.
            p = context.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE);
            enc = false;
        }
        prefs = p;
        encrypted = enc;
    }

    @NonNull
    public static AiSecureStore get(@NonNull Context context) {
        AiSecureStore store = INSTANCE.get();
        if (store == null) {
            synchronized (AiSecureStore.class) {
                store = INSTANCE.get();
                if (store == null) {
                    store = new AiSecureStore(context.getApplicationContext());
                    INSTANCE.set(store);
                }
            }
        }
        return store;
    }

    /** @return true when values are protected by the Android Keystore. */
    public boolean isEncrypted() {
        return encrypted;
    }

    public void setActiveProvider(@NonNull String providerId) {
        prefs.edit().putString(KEY_PROVIDER, providerId).apply();
    }

    @Nullable
    public String getActiveProvider() {
        return prefs.getString(KEY_PROVIDER, null);
    }

    public void setModel(@NonNull String providerId, @NonNull String model) {
        prefs.edit().putString(KEY_MODEL_PREFIX + providerId, model).apply();
    }

    @Nullable
    public String getModel(@NonNull String providerId, @NonNull String fallback) {
        return prefs.getString(KEY_MODEL_PREFIX + providerId, fallback);
    }

    /**
     * Stores an API key. Empty or null input clears the entry.
     */
    public void setApiKey(@NonNull String providerId, @Nullable String apiKey) {
        String key = KEY_API_KEY_PREFIX + providerId;
        if (apiKey == null || apiKey.trim().isEmpty()) {
            prefs.edit().remove(key).apply();
        } else {
            prefs.edit().putString(key, apiKey.trim()).apply();
        }
    }

    /** Raw key access for provider implementations only. */
    @Nullable
    public String getApiKey(@NonNull String providerId) {
        return prefs.getString(KEY_API_KEY_PREFIX + providerId, null);
    }

    /** @return true when a non-empty key exists; never reveals the value. */
    public boolean isConfigured(@NonNull String providerId) {
        String k = getApiKey(providerId);
        return k != null && !k.isEmpty();
    }

    /**
     * Masked preview such as {@code AIza••••••3f9a} for settings UI.
     * Returns empty string when no key is set.
     */
    @NonNull
    public String getMaskedKey(@NonNull String providerId) {
        String k = getApiKey(providerId);
        if (k == null || k.isEmpty()) {
            return "";
        }
        String head = k.length() <= 8 ? "" : k.substring(0, 4);
        String tail = k.length() <= 8 ? "••••" : k.substring(k.length() - 4);
        int dots = Math.max(4, Math.min(10, k.length() - 8));
        StringBuilder sb = new StringBuilder(head);
        for (int i = 0; i < dots; i++) {
            sb.append('•');
        }
        sb.append(tail);
        return sb.toString();
    }
}
