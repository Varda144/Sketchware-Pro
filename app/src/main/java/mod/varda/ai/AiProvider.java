package mod.varda.ai;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Multi-provider AI client for Sketchware Pro.
 *
 * Supported providers (see AiProviderConfig.PROVIDER_*):
 *  - openai      : OpenAI Chat Completions (and any OpenAI-compatible proxy:
 *                  OpenRouter, Groq, Together, LM Studio, Ollama, etc.)
 *  - anthropic   : Anthropic Messages API
 *  - gemini      : Google Gemini (generativelanguage API)
 *
 * Features: streaming (OpenAI-compatible), vision/multimodal, system prompt,
 * conversation history, and provider-aware request/response handling.
 */
public class AiProvider {

    public interface Callback {
        void onResult(String answer, String error);
    }

    public interface StreamCallback {
        void onToken(String token);
        void onDone(String fullResponse);
        void onError(String error);
    }

    private static volatile OkHttpClient client;

    private static OkHttpClient http() {
        if (client == null) {
            synchronized (AiProvider.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(20, TimeUnit.SECONDS)
                            .readTimeout(180, TimeUnit.SECONDS)
                            .writeTimeout(60, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return client;
    }

    /**
     * Non-streaming chat (text only).
     */
    public static void chat(Context ctx, List<String[]> history, String prompt, Callback cb) {
        Thread t = new Thread(() -> {
            String result;
            String error = null;
            try {
                result = chatBlocking(ctx, history, prompt);
            } catch (Exception e) {
                result = null;
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final String r = result;
            final String e = error;
            new Handler(Looper.getMainLooper()).post(() -> cb.onResult(r, e));
        }, "varda-ai-chat");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Streaming chat. OpenAI-compatible providers stream token-by-token;
     * Anthropic/Gemini fall back to a single blocking call delivered as one token.
     */
    public static void chatStream(Context ctx, List<String[]> history, String prompt, StreamCallback cb) {
        String provider = AiProviderConfig.getProvider(ctx);
        if (AiProviderConfig.PROVIDER_OPENAI.equals(provider)) {
            streamOpenAi(ctx, history, prompt, cb);
        } else {
            Thread t = new Thread(() -> {
                try {
                    String full = chatBlocking(ctx, history, prompt);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        cb.onToken(full);
                        cb.onDone(full);
                    });
                } catch (Exception e) {
                    final String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    new Handler(Looper.getMainLooper()).post(() -> cb.onError(err));
                }
            }, "varda-ai-stream-fallback");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void streamOpenAi(Context ctx, List<String[]> history, String prompt, StreamCallback cb) {
        Thread t = new Thread(() -> {
            StringBuilder full = new StringBuilder();
            try {
                JsonObject body = buildChatJson(ctx, history, prompt, true, null, null);
                Request.Builder rb = new Request.Builder()
                        .url(resolveEndpoint(ctx))
                        .post(RequestBody.create(body.toString(), MediaType.parse("application/json")));
                addAuth(ctx, rb);

                try (Response resp = http().newCall(rb.build()).execute()) {
                    if (!resp.isSuccessful()) {
                        String err = resp.body() != null ? resp.body().string() : "";
                        cb.onError("HTTP " + resp.code() + ": " + abbreviate(err));
                        return;
                    }
                    BufferedReader br = new BufferedReader(new InputStreamReader(resp.body().byteStream()));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (!line.startsWith("data: ")) continue;
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        try {
                            JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                            JsonArray choices = chunk.getAsJsonArray("choices");
                            if (choices != null && choices.size() > 0) {
                                JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                                if (delta != null && delta.has("content")) {
                                    String token = delta.get("content").getAsString();
                                    full.append(token);
                                    final String tok = token;
                                    new Handler(Looper.getMainLooper()).post(() -> cb.onToken(tok));
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    final String f = full.toString();
                    new Handler(Looper.getMainLooper()).post(() -> cb.onDone(f));
                }
            } catch (Exception e) {
                final String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                new Handler(Looper.getMainLooper()).post(() -> cb.onError(err));
            }
        }, "varda-ai-stream");
        t.setDaemon(true);
        t.start();
    }

    public static String chatBlocking(Context ctx, List<String[]> history, String prompt) throws Exception {
        return complete(ctx, buildChatJson(ctx, history, prompt, false, null, null));
    }

    /**
     * Vision / multimodal chat. Sends an image (base64) alongside the prompt.
     * Uses the vision model if configured, else the default model.
     */
    public static String chatVisionBlocking(Context ctx, List<String[]> history, String prompt,
                                           String imageBase64, String mime) throws Exception {
        String modelOverride = AiProviderConfig.getVisionModel(ctx);
        JsonObject body = buildChatJson(ctx, history, prompt, false, imageBase64, mime);
        if (!modelOverride.isEmpty()) {
            body.addProperty("model", modelOverride);
        }
        return complete(ctx, body);
    }

    private static String complete(Context ctx, JsonObject body) throws Exception {
        Request.Builder rb = new Request.Builder()
                .url(resolveEndpoint(ctx))
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")));
        addAuth(ctx, rb);

        try (Response resp = http().newCall(rb.build()).execute()) {
            String text = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("HTTP " + resp.code() + ": " + abbreviate(text));
            }
            return parseCompletion(AiProviderConfig.getProvider(ctx), text);
        }
    }

    // ---------- Provider-aware request building ----------

    private static JsonObject buildChatJson(Context ctx, List<String[]> history, String prompt,
                                           boolean stream, String imageBase64, String mime) {
        String provider = AiProviderConfig.getProvider(ctx);
        String model = AiProviderConfig.getModel(ctx);
        float temp = AiProviderConfig.getTemperature(ctx);
        String sys = AiProviderConfig.getSystemPrompt(ctx);
        boolean hasSys = sys != null && !sys.trim().isEmpty();

        if (AiProviderConfig.PROVIDER_ANTHROPIC.equals(provider)) {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("max_tokens", 4096);
            body.addProperty("temperature", temp);
            if (hasSys) body.addProperty("system", sys);
            body.add("messages", buildMessages(history, prompt, imageBase64, mime, /*anthropic*/ true));
            return body;
        }

        if (AiProviderConfig.PROVIDER_GEMINI.equals(provider)) {
            JsonObject body = new JsonObject();
            if (hasSys) {
                JsonObject si = new JsonObject();
                si.add("parts", arr(obj("text", sys)));
                body.add("systemInstruction", si);
            }
            body.add("contents", buildGeminiContents(history, prompt, imageBase64, mime));
            JsonObject gen = new JsonObject();
            gen.addProperty("maxOutputTokens", 4096);
            gen.addProperty("temperature", temp);
            body.add("generationConfig", gen);
            return body;
        }

        // OpenAI / compatible
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", stream);
        body.addProperty("max_tokens", 4096);
        body.addProperty("temperature", temp);

        JsonArray messages = new JsonArray();
        if (hasSys) messages.add(obj("role", "system", "content", sys));
        if (history != null) {
            for (String[] item : history) messages.add(obj("role", item[0], "content", item[1]));
        }
        messages.add(userMessageOpenAi(prompt, imageBase64, mime));
        body.add("messages", messages);
        return body;
    }

    private static JsonArray buildMessages(List<String[]> history, String prompt,
                                           String imageBase64, String mime, boolean anthropic) {
        JsonArray messages = new JsonArray();
        if (history != null) {
            for (String[] item : history) {
                messages.add(obj("role", item[0], "content", item[1]));
            }
        }
        if (imageBase64 != null && !imageBase64.isEmpty()) {
            JsonArray content = new JsonArray();
            JsonObject img = new JsonObject();
            img.addProperty("type", "image");
            JsonObject src = new JsonObject();
            src.addProperty("type", "base64");
            src.addProperty("media_type", mime == null ? "image/png" : mime);
            src.addProperty("data", imageBase64);
            img.add("source", src);
            content.add(img);
            content.add(obj("type", "text", "text", prompt));
            JsonObject m = new JsonObject();
            m.addProperty("role", "user");
            m.add("content", content);
            messages.add(m);
        } else {
            messages.add(obj("role", "user", "content", prompt));
        }
        return messages;
    }

    private static JsonArray buildGeminiContents(List<String[]> history, String prompt,
                                                String imageBase64, String mime) {
        JsonArray contents = new JsonArray();
        if (history != null) {
            for (String[] item : history) {
                String role = "assistant".equals(item[0]) ? "model" : "user";
                contents.add(geminiContent(role, item[1], null, null));
            }
        }
        contents.add(geminiContent("user", prompt, imageBase64, mime));
        return contents;
    }

    private static JsonObject geminiContent(String role, String text, String imageBase64, String mime) {
        JsonObject c = new JsonObject();
        c.addProperty("role", role);
        JsonArray parts = new JsonArray();
        if (imageBase64 != null && !imageBase64.isEmpty()) {
            JsonObject img = new JsonObject();
            JsonObject data = new JsonObject();
            data.addProperty("mime_type", mime == null ? "image/png" : mime);
            data.addProperty("data", imageBase64);
            img.add("inline_data", data);
            parts.add(img);
        }
        parts.add(obj("text", text));
        c.add("parts", parts);
        return c;
    }

    private static JsonObject userMessageOpenAi(String prompt, String imageBase64, String mime) {
        if (imageBase64 != null && !imageBase64.isEmpty()) {
            JsonObject m = new JsonObject();
            m.addProperty("role", "user");
            JsonArray content = new JsonArray();
            JsonObject img = new JsonObject();
            img.addProperty("type", "image_url");
            JsonObject url = new JsonObject();
            url.addProperty("url", "data:" + (mime == null ? "image/png" : mime) + ";base64," + imageBase64);
            img.add("image_url", url);
            content.add(img);
            content.add(obj("type", "text", "text", prompt));
            m.add("content", content);
            return m;
        }
        return obj("role", "user", "content", prompt);
    }

    // ---------- Endpoint / auth resolution ----------

    private static String resolveEndpoint(Context ctx) {
        String provider = AiProviderConfig.getProvider(ctx);
        String ep = AiProviderConfig.getEndpoint(ctx);
        String key = AiProviderConfig.getApiKey(ctx);
        String model = AiProviderConfig.getModel(ctx);
        if (AiProviderConfig.PROVIDER_GEMINI.equals(provider)) {
            String base = ep.contains("generativelanguage")
                    ? ep : "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
            return base + (base.contains("?") ? "&" : "?") + "key=" + (key == null ? "" : key);
        }
        if (AiProviderConfig.PROVIDER_ANTHROPIC.equals(provider)) {
            return ep.contains("anthropic.com") ? ep : "https://api.anthropic.com/v1/messages";
        }
        return ep;
    }

    private static void addAuth(Context ctx, Request.Builder rb) {
        String provider = AiProviderConfig.getProvider(ctx);
        String key = AiProviderConfig.getApiKey(ctx);
        rb.header("Content-Type", "application/json");
        if (AiProviderConfig.PROVIDER_ANTHROPIC.equals(provider)) {
            if (key != null && !key.isEmpty()) rb.header("x-api-key", key);
            rb.header("anthropic-version", "2023-06-01");
        } else if (!AiProviderConfig.PROVIDER_GEMINI.equals(provider)) {
            if (key != null && !key.isEmpty()) rb.header("Authorization", "Bearer " + key);
        }
    }

    private static String parseCompletion(String provider, String text) throws Exception {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        if (AiProviderConfig.PROVIDER_ANTHROPIC.equals(provider)) {
            if (root.has("error")) throw new IllegalStateException(root.getAsJsonObject("error").get("message").getAsString());
            JsonArray content = root.getAsJsonArray("content");
            if (content != null && content.size() > 0) {
                JsonObject first = content.get(0).getAsJsonObject();
                if (first.has("text")) return first.get("text").getAsString();
            }
            throw new IllegalStateException("Unexpected Anthropic response: " + abbreviate(text));
        }
        if (AiProviderConfig.PROVIDER_GEMINI.equals(provider)) {
            if (root.has("error")) throw new IllegalStateException(root.getAsJsonObject("error").get("message").getAsString());
            JsonArray cands = root.getAsJsonArray("candidates");
            if (cands != null && cands.size() > 0) {
                JsonObject c = cands.get(0).getAsJsonObject();
                if (c.has("content")) {
                    JsonArray parts = c.getAsJsonObject("content").getAsJsonArray("parts");
                    if (parts != null && parts.size() > 0) return parts.get(0).getAsJsonObject().get("text").getAsString();
                }
            }
            throw new IllegalStateException("Unexpected Gemini response: " + abbreviate(text));
        }
        // OpenAI / compatible
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices != null && choices.size() > 0) {
            JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (msg != null && msg.has("content")) return msg.get("content").getAsString();
        }
        throw new IllegalStateException("Unexpected response: " + abbreviate(text));
    }

    // ---------- small json helpers ----------

    private static JsonObject obj(String k1, String v1, String k2, String v2) {
        JsonObject o = new JsonObject();
        o.addProperty(k1, v1);
        o.addProperty(k2, v2);
        return o;
    }

    private static JsonObject obj(String k, String v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }

    private static JsonArray arr(JsonObject... items) {
        JsonArray a = new JsonArray();
        for (JsonObject o : items) a.add(o);
        return a;
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }

    /**
     * Encode a file as base64 (used for vision).
     */
    public static String fileToBase64(String path) throws Exception {
        File f = new File(path);
        FileInputStream fis = new FileInputStream(f);
        byte[] data = new byte[(int) f.length()];
        int off = 0;
        while (off < data.length) {
            int r = fis.read(data, off, data.length - off);
            if (r < 0) break;
            off += r;
        }
        fis.close();
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    /**
     * Get project context string for AI (reads disk files).
     */
    public static String getProjectContext(String scId) {
        StringBuilder sb = new StringBuilder();
        try {
            File dataDir = new File(Environment.getExternalStorageDirectory(), ".sketchware/data/" + scId);
            File listDir = new File(Environment.getExternalStorageDirectory(), ".sketchware/mysc/list/" + scId);

            sb.append("Project ID: ").append(scId).append("\n");

            File props = new File(listDir, "project.properties");
            if (props.exists()) sb.append("Properties: ").append(readFile(props)).append("\n");

            File resDir = new File(dataDir, "files/resource");
            if (resDir.exists()) {
                File[] resFiles = resDir.listFiles();
                if (resFiles != null) {
                    sb.append("Resources (").append(resFiles.length).append("): ");
                    for (File f : resFiles) sb.append(f.getName()).append(", ");
                    sb.append("\n");
                }
            }

            File compileLog = new File(dataDir, "compile_log");
            if (compileLog.exists()) {
                String log = readFile(compileLog);
                if (!log.trim().isEmpty()) sb.append("Compile log:\n").append(log).append("\n");
            }
        } catch (Exception e) {
            sb.append("Error reading project: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    private static String readFile(File f) {
        try {
            FileInputStream fis = new FileInputStream(f);
            byte[] data = new byte[(int) f.length()];
            fis.read(data);
            fis.close();
            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
