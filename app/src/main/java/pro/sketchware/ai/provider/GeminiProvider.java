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
 * Google Gemini provider using the generativelanguage REST API.
 *
 * Endpoint: POST {base}/models/{model}:generateContent?key=API_KEY
 */
public final class GeminiProvider implements AiProvider {

    public static final String ID = "gemini";
    private static final String DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Context context;
    private final OkHttpClient client;
    private final String baseUrl;

    public GeminiProvider(@NonNull Context context) {
        this(context, DEFAULT_BASE);
    }

    /** Visible for testing: allows a fake base URL. */
    public GeminiProvider(@NonNull Context context, @NonNull String baseUrl) {
        this.context = context.getApplicationContext();
        this.baseUrl = baseUrl;
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
        return "Google Gemini";
    }

    @NonNull
    @Override
    public String defaultModel() {
        return "gemini-2.0-flash";
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
                    "No API key configured for Gemini. Add one in Settings > AI Provider."));
            return;
        }
        String model = store.getModel(ID, defaultModel());
        JSONObject body = buildBody(request);
        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/models/" + model + ":generateContent?key=" + apiKey)
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

    // ------------------------------------------------------------------ //
    //  Request building                                                   //
    // ------------------------------------------------------------------ //

    private static JSONObject buildBody(AiModels.GenerateRequest request) {
        JSONArray contents = new JSONArray();
        StringBuilder systemText = new StringBuilder();
        for (AiModels.Message message : request.messages) {
            if (message.role == AiModels.Message.Role.SYSTEM) {
                if (systemText.length() > 0) {
                    systemText.append("\n\n");
                }
                systemText.append(message.content);
                continue;
            }
            try {
                JSONObject content = new JSONObject();
                content.put("role", message.role == AiModels.Message.Role.MODEL ? "model" : "user");
                JSONObject textPart = new JSONObject();
                textPart.put("text", message.content);
                JSONArray parts = new JSONArray();
                parts.put(textPart);
                content.put("parts", parts);
                contents.put(content);
            } catch (Exception ignored) {
            }
        }
        JSONObject body = new JSONObject();
        try {
            body.put("contents", contents);
            if (systemText.length() > 0) {
                JSONObject sysInstruction = new JSONObject();
                JSONArray sysParts = new JSONArray();
                sysParts.put(new JSONObject().put("text", systemText.toString()));
                sysInstruction.put("parts", sysParts);
                body.put("systemInstruction", sysInstruction);
            }
            JSONObject generationConfig = new JSONObject();
            if (request.temperature != null) {
                generationConfig.put("temperature", request.temperature);
            }
            if (request.maxOutputTokens != null) {
                generationConfig.put("maxOutputTokens", request.maxOutputTokens.intValue());
            }
            if (generationConfig.length() > 0) {
                body.put("generationConfig", generationConfig);
            }
        } catch (Exception ignored) {
        }
        return body;
    }

    // ------------------------------------------------------------------ //
    //  Response parsing / error mapping                                   //
    // ------------------------------------------------------------------ //

    private static AiModels.GenerateResult parseResponse(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        if (root.has("promptFeedback")) {
            JSONObject feedback = root.optJSONObject("promptFeedback");
            if (feedback != null && feedback.has("blockReason")) {
                throw new AiModels.AiException(
                        AiModels.ErrorCode.BAD_REQUEST,
                        "The prompt was blocked by the provider's safety filter.");
            }
        }
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new AiModels.AiException(
                    AiModels.ErrorCode.MALFORMED_RESPONSE,
                    "The AI service returned no candidates.");
        }
        JSONObject candidate = candidates.getJSONObject(0);
        String finishReason = candidate.optString("finishReason", "");
        JSONObject content = candidate.optJSONObject("content");
        StringBuilder text = new StringBuilder();
        if (content != null) {
            JSONArray parts = content.optJSONArray("parts");
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.getJSONObject(i);
                    String t = part.optString("text", "");
                    if (!t.isEmpty()) {
                        if (text.length() > 0) {
                            text.append('\n');
                        }
                        text.append(t);
                    }
                }
            }
        }
        if (text.length() == 0 && !"STOP".equals(finishReason)) {
            throw new AiModels.AiException(
                    AiModels.ErrorCode.MALFORMED_RESPONSE,
                    "The AI returned an empty response"
                            + (finishReason.isEmpty() ? "." : " (" + finishReason + ")."));
        }
        int inTokens = -1;
        int outTokens = -1;
        JSONObject usage = root.optJSONObject("usageMetadata");
        if (usage != null) {
            inTokens = usage.optInt("promptTokenCount", -1);
            outTokens = usage.optInt("candidatesTokenCount", -1);
        }
        return new AiModels.GenerateResult(text.toString(), inTokens, outTokens, raw);
    }

    private static AiModels.AiException mapHttpError(int code, String body) {
        String detail = extractErrorMessage(body);
        return switch (code) {
            case 400 -> detail != null && detail.toLowerCase().contains("api key")
                    ? new AiModels.AiException(AiModels.ErrorCode.INVALID_API_KEY,
                    "The API key was rejected by the service.")
                    : new AiModels.AiException(AiModels.ErrorCode.BAD_REQUEST,
                    "The request was rejected" + suffix(detail));
            case 401, 403 -> new AiModels.AiException(AiModels.ErrorCode.INVALID_API_KEY,
                    "Access denied. Check that your API key is valid and enabled.");
            case 404 -> new AiModels.AiException(AiModels.ErrorCode.BAD_REQUEST,
                    "Model not found. Pick another model in AI settings.");
            case 429 -> detail != null && detail.toLowerCase().contains("quota")
                    ? new AiModels.AiException(AiModels.ErrorCode.QUOTA_EXCEEDED,
                    "Quota exceeded for this API key. Try again later.")
                    : new AiModels.AiException(AiModels.ErrorCode.RATE_LIMITED,
                    "Rate limited. Wait a moment and retry.");
            default -> code >= 500
                    ? new AiModels.AiException(AiModels.ErrorCode.SERVER_ERROR,
                    "The AI service had a temporary problem (" + code + ").")
                    : new AiModels.AiException(AiModels.ErrorCode.UNKNOWN,
                    "Unexpected response from the AI service (" + code + ").");
        };
    }

    private static String extractErrorMessage(String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                return error.optString("message", null);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String suffix(String detail) {
        return detail == null ? "." : ": " + detail;
    }
}
