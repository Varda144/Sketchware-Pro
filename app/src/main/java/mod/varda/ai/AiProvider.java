package mod.varda.ai;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * OpenAI-compatible chat completions client.
 * Works with OpenAI, OpenRouter, Groq, Together, LM Studio, ollama, etc.
 * Supports streaming for real-time response display.
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
     * Non-streaming chat. history items: {role, content}.
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
     * Streaming chat — tokens arrive via onToken(), full text via onDone().
     */
    public static void chatStream(Context ctx, List<String[]> history, String prompt, StreamCallback cb) {
        Thread t = new Thread(() -> {
            StringBuilder full = new StringBuilder();
            try {
                JsonObject body = buildBody(ctx, history, prompt, true);
                Request.Builder rb = new Request.Builder()
                        .url(AiProviderConfig.getEndpoint(ctx))
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
        JsonObject body = buildBody(ctx, history, prompt, false);
        Request.Builder rb = new Request.Builder()
                .url(AiProviderConfig.getEndpoint(ctx))
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")));
        addAuth(ctx, rb);

        try (Response resp = http().newCall(rb.build()).execute()) {
            String text = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("HTTP " + resp.code() + ": " + abbreviate(text));
            }
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                if (msg != null && msg.has("content")) return msg.get("content").getAsString();
            }
            throw new IllegalStateException("Unexpected response: " + abbreviate(text));
        }
    }

    private static JsonObject buildBody(Context ctx, List<String[]> history, String prompt, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", AiProviderConfig.getModel(ctx));
        body.addProperty("stream", stream);
        body.addProperty("max_tokens", 4096);

        JsonArray messages = new JsonArray();
        String sys = AiProviderConfig.getSystemPrompt(ctx);
        if (sys != null && !sys.trim().isEmpty()) {
            JsonObject m = new JsonObject();
            m.addProperty("role", "system");
            m.addProperty("content", sys);
            messages.add(m);
        }
        if (history != null) {
            for (String[] item : history) {
                JsonObject m = new JsonObject();
                m.addProperty("role", item[0]);
                m.addProperty("content", item[1]);
                messages.add(m);
            }
        }
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", prompt);
        messages.add(user);
        body.add("messages", messages);
        return body;
    }

    private static void addAuth(Context ctx, Request.Builder rb) {
        String key = AiProviderConfig.getApiKey(ctx);
        if (!key.isEmpty()) rb.header("Authorization", "Bearer " + key);
        rb.header("Content-Type", "application/json");
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }

    /**
     * Get project context string for AI (reads disk files).
     */
    public static String getProjectContext(String scId) {
        StringBuilder sb = new StringBuilder();
        try {
            File dataDir = new File(Environment.getExternalStorageDirectory(),
                    ".sketchware/data/" + scId);
            File listDir = new File(Environment.getExternalStorageDirectory(),
                    ".sketchware/mysc/list/" + scId);

            sb.append("Project ID: ").append(scId).append("\n");

            // Read project name from project.properties
            File props = new File(listDir, "project.properties");
            if (props.exists()) {
                String propsContent = readFile(props);
                sb.append("Properties: ").append(propsContent).append("\n");
            }

            // List resources
            File resDir = new File(dataDir, "files/resource");
            if (resDir.exists()) {
                File[] resFiles = resDir.listFiles();
                if (resFiles != null) {
                    sb.append("Resources (").append(resFiles.length).append("): ");
                    for (File f : resFiles) sb.append(f.getName()).append(", ");
                    sb.append("\n");
                }
            }

            // Compile log (last errors)
            File compileLog = new File(dataDir, "compile_log");
            if (compileLog.exists()) {
                String log = readFile(compileLog);
                if (!log.trim().isEmpty()) {
                    sb.append("Compile log:\n").append(log).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("Error reading project: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    private static String readFile(File f) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] data = new byte[(int) f.length()];
            fis.read(data);
            fis.close();
            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
