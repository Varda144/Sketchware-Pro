package pro.sketchware.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;

/** Minimal JSON-RPC 2.0 helpers over Gson. */
final class JsonRpc {

    private JsonRpc() {
    }

    static JsonObject parseObject(String raw) throws IOException {
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new IOException("Invalid JSON: " + e.getMessage());
        }
    }

    /** Builds a JSON-RPC success response. */
    static JsonObject result(Object id, com.google.gson.JsonElement result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", idOr(id));
        o.add("result", result);
        return o;
    }

    /** Builds a JSON-RPC error response. */
    static JsonObject error(Object id, int code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", idOr(id));
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        o.add("error", err);
        return o;
    }

    private static com.google.gson.JsonElement idOr(Object id) {
        if (id == null) return com.google.gson.JsonNull.INSTANCE;
        if (id instanceof com.google.gson.JsonElement el) {
            if (el.isJsonNull()) return com.google.gson.JsonNull.INSTANCE;
            if (el.isJsonPrimitive()) return el.getAsJsonPrimitive();
            return el;
        }
        if (id instanceof String s) return new com.google.gson.JsonPrimitive(s);
        if (id instanceof Number n) return new com.google.gson.JsonPrimitive(n);
        return new com.google.gson.JsonPrimitive(String.valueOf(id));
    }
}
