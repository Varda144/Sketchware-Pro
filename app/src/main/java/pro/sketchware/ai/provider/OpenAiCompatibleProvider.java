package pro.sketchware.ai.provider;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import pro.sketchware.ai.AiModels;
import pro.sketchware.ai.secure.AiSecureStore;

/**
 * Provider for any OpenAI-compatible chat-completions endpoint
 * (OpenAI, OpenRouter, LM Studio, llama.cpp server, ...).
 *
 * Endpoint: POST {base}/chat/completions with "Authorization: Bearer KEY".
 */
public class OpenAiCompatibleProvider implements AiProvider {

    public static final String ID = "openai_compatible";
    private static final String DEFAULT_BASE = "https://api.openai.com/v1";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Context context;
    private final OkHttpClient client;
    private final String baseUrl;

    public OpenAiCompatibleProvider(@NonNull Context context) {
        this(context, DEFAULT_BASE);
    }

    /** Visible for testing. */
    public OpenAiCompatibleProvider(@NonNull Context context, @NonNull String baseUrl) {
        this.context = context.getApplicationContext();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @NonNull
    @Override
    public String id() {
        return ID;
    }

    @NonNull
    @Override
    public String displayName() {
        return "OpenAI-compatible endpoint";
    }

    @NonNull
    @Override
    public String defaultModel() {
        return "gpt-4o-mini";
    }

    @Override
    public boolean isConfigured() {
        return AiSecureStore.get(context).isConfigured(ID);
    }

    @Override
    public void generate(@NonNull AiModels.GenerateRequest request, @NonNull AiModels.Callback callback) {
        AiSecureStore store = AiSecureStore.get(context);
        String apiKey = store.getApiKey(ID);
        if (apiKey == null || apiKey.isEmpty()) {
            callback.onError(new AiModels.AiException(
                    AiModels.ErrorCode.MISSING_API_KEY,
                    "No API key configured for the AI provider. Add one in Settings > AI Provider."));
            return;
        }
        String model = store.getModel(ID, defaultModel());
        String base = store.getBaseUrl(ID, baseUrl);
        JSONArray messages = new JSONArray();
        for (AiModels.Message message : request.messages) {
            try {
                JSONObject m = new JSONObject();
                m.put("role", switch (message.role) {
                    case SYSTEM -> "system";
                    case USER -> "user";
                    case MODEL -> "assistant";
                });
                m.put("content", message.content);
                messages.put(m);
            } catch (Exception ignored) {
            }
        }
        JSONObject body = new JSONObject();
        try {
            body.put("model", model);
            body.put("messages", messages);
            if (request.temperature != null) {
                body.put("temperature", request.temperature);
            }
            if (request.maxOutputTokens != null) {
                body.put("max_tokens", request.maxOutputTokens.intValue());
            }
        } catch (Exception ignored) {
        }
        Request httpRequest = new Request.Builder()
                .url(base + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        client.newCall(httpRequest).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError(AiHttpErrors.mapIoException(e));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        callback.onError(mapHttpError(response.code(), responseBody));
                        return;
                    }
                    callback.onSuccess(parseResponse(responseBody));
                } catch (AiModels.AiException e) {
                    callback.onError(e);
                } catch (Exception e) {
                    callback.onError(new AiModels.AiException(
                            AiModels.ErrorCode.MALFORMED_RESPONSE,
                            "Could not understand the AI response.", e));
                }
            }
        });
    }

    private static AiModels.GenerateResult parseResponse(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new AiModels.AiException(AiModels.ErrorCode.MALFORMED_RESPONSE,
                    "The AI service returned no choices.");
        }
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        String text = message == null ? "" : message.optString("content", "");
        int inTokens = -1;
        int outTokens = -1;
        JSONObject usage = root.optJSONObject("usage");
        if (usage != null) {
            inTokens = usage.optInt("prompt_tokens", -1);
            outTokens = usage.optInt("completion_tokens", -1);
        }
        return new AiModels.GenerateResult(text, inTokens, outTokens, raw);
    }

    private static AiModels.AiException mapHttpError(int code, String body) {
        return switch (code) {
            case 400 -> new AiModels.AiException(AiModels.ErrorCode.BAD_REQUEST,
                    "The request was rejected by the service.");
            case 401, 403 -> new AiModels.AiException(AiModels.ErrorCode.INVALID_API_KEY,
                    "Access denied. Check that your API key is valid.");
            case 429 -> new AiModels.AiException(AiModels.ErrorCode.RATE_LIMITED,
                    "Rate limited or quota exceeded. Try again later.");
            default -> code >= 500
                    ? new AiModels.AiException(AiModels.ErrorCode.SERVER_ERROR,
                    "The AI service had a temporary problem (" + code + ").")
                    : new AiModels.AiException(AiModels.ErrorCode.UNKNOWN,
                    "Unexpected response from the AI service (" + code + ").");
        };
    }
}
