package pro.sketchware.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Server-side mirror of the app's block plan catalog (schema v1).
 *
 * Keep in sync with
 * {@code app/src/main/java/pro/sketchware/ai/nl/BlockCatalog.java}.
 */
final class BlockPlanCatalog {

    private static final Set<String> OPS = Set.of(
            "if", "ifElse", "repeat", "forever",
            "true", "false", "<", "=", ">", "&&", "||", "not",
            "+", "-", "*", "/",
            "stringJoin", "toString", "toNumber", "stringLength",
            "trim", "toUpperCase", "toLowerCase",
            "addSourceDirectly",
            "aiGeminiGenerate", "aiResponse", "aiChatSend");

    private BlockPlanCatalog() {
    }

    static String describe() {
        return "Allowed ops: " + String.join(", ", OPS);
    }

    static String stripFences(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            t = t.substring(start, end + 1);
        }
        return t.trim();
    }

    /** Validates version + events structure; throws IOException on problems. */
    static void validate(JsonObject plan) throws IOException {
        if (!plan.has("version") || plan.get("version").getAsInt() != 1) {
            throw new IOException("Plan must have \"version\": 1");
        }
        if (!plan.has("events") || !plan.get("events").isJsonArray()) {
            throw new IOException("Plan must contain an events array");
        }
        JsonArray events = plan.getAsJsonArray("events");
        if (events.size() == 0) {
            throw new IOException("Plan contains no events");
        }
        for (int i = 0; i < events.size(); i++) {
            JsonObject e = events.get(i).getAsJsonObject();
            if (!e.has("event") || !e.has("activity")) {
                throw new IOException("Event #" + (i + 1) + " missing activity/event");
            }
            if (e.has("blocks") && e.get("blocks").isJsonArray()) {
                for (var bl : e.getAsJsonArray("blocks")) {
                    JsonObject b = bl.getAsJsonObject();
                    String op = b.has("op") ? b.get("op").getAsString() : "";
                    if (!OPS.contains(op)) {
                        throw new IOException("Unsupported op: \"" + op + "\". " + describe());
                    }
                }
            }
        }
    }
}
