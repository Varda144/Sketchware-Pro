package pro.sketchware.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal Gemini REST client for the desktop MCP server (Java 11 HttpClient). */
final class GeminiClient {

    static final String PROMPT_PREFIX =
            "You are a code generator embedded in Sketchware Pro. Convert the instruction"
                    + " into a JSON block plan. Reply with ONLY a JSON object.\n"
                    + "Schema: {\"version\":1,\"events\":[{\"activity\":\"MainActivity\","
                    + "\"component\":\"button1\"|null,\"event\":\"onClick\",\"blocks\":"
                    + "[{\"op\":...,\"params\":[...],\"then\":[],\"elseThen\":[]}]}],\"warnings\":[]}\n";

    private GeminiClient() {
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    static String generate(String apiKey, String model, String prompt) throws IOException {
        // Build JSON safely with Gson instead of string concatenation:
        var gson = new com.google.gson.Gson();
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("contents", new Object[]{
                java.util.Map.of("role", "user",
                        "parts", new Object[]{java.util.Map.of("text", prompt)})
        });
        String json = gson.toJson(payload);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                            + model + ":generateContent?key="
                            + java.net.URLEncoder.encode(apiKey, java.nio.charset.StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
        } catch (Exception e) {
            throw new IOException("Bad request URL: " + e.getMessage());
        }
        try {
            HttpResponse<String> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Gemini API error HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            var root = com.google.gson.JsonParser.parseString(response.body())
                    .getAsJsonObject();
            return root.getAsJsonArray("candidates").get(0).getAsJsonObject()
                    .getAsJsonObject("content").getAsJsonArray("parts").get(0)
                    .getAsJsonObject().get("text").getAsString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }
}
