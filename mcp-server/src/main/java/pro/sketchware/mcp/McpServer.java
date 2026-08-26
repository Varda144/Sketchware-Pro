package pro.sketchware.mcp;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Lightweight MCP-compatible JSON-RPC server over
 * {@link com.sun.net.httpserver.HttpServer}.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code POST /mcp} — JSON-RPC 2.0: initialize, tools/list, tools/call</li>
 *   <li>{@code GET /health} — plain "ok"</li>
 * </ul>
 *
 * <p>Binds to loopback by default. Remote binding requires --host 0.0.0.0 and
 * is discouraged; when used, a bearer token must be supplied via MCP_TOKEN.</p>
 */
public final class McpServer {

    private final HttpServer server;
    private final ToolRegistry tools;
    private final String token;

    public McpServer(Workspace workspace, String host, int port) throws IOException {
        this.tools = new ToolRegistry(workspace);
        this.token = System.getenv("MCP_TOKEN");
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/mcp", this::handleMcp);
        server.createContext("/health", ex -> send(ex, 200, "ok"));
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleMcp(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "{\"error\":\"POST only\"}");
            return;
        }
        if (token != null && !token.isBlank()) {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals("Bearer " + token)) {
                send(ex, 401, JsonRpc.error(null, -32001, "Unauthorized").toString());
                return;
            }
        }
        Object id = null;
        try {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject request = JsonRpc.parseObject(body);
            id = request.has("id") ? request.get("id") : null;
            String method = request.has("method") ? request.get("method").getAsString() : "";
            JsonObject params = request.has("params") && request.get("params").isJsonObject()
                    ? request.getAsJsonObject("params") : new JsonObject();

            JsonObject result = switch (method) {
                case "initialize" -> initializeResult();
                case "tools/list" -> listToolsResult();
                case "tools/call" -> callToolResult(params);
                default -> throw new IOException("Unknown method: " + method);
            };
            send(ex, 200, JsonRpc.result(id, result).toString());
        } catch (IOException e) {
            send(ex, 200, JsonRpc.error(id, -32000, e.getMessage()).toString());
        } catch (Exception e) {
            send(ex, 200, JsonRpc.error(id, -32603,
                    "Internal error: " + e.getClass().getSimpleName()).toString());
        }
    }

    private static JsonObject initializeResult() {
        JsonObject o = new JsonObject();
        o.addProperty("protocolVersion", "2024-11-05");
        o.addProperty("name", "sketchware-pro-mcp");
        o.addProperty("version", "1.0.0");
        o.addProperty("instructions",
                "Operates on a Sketchware Pro workspace directory. Start read-only;"
                        + " enable writes with --allow-write.");
        return o;
    }

    private JsonObject listToolsResult() {
        JsonObject o = new JsonObject();
        o.add("tools", tools.describe());
        return o;
    }

    private JsonObject callToolResult(JsonObject params) throws IOException {
        if (!params.has("name")) {
            throw new IOException("Missing tool name");
        }
        String name = params.get("name").getAsString();
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        JsonObject result = tools.call(name, args);
        JsonObject wrapped = new JsonObject();
        wrapped.add("content", contentJson(result.toString()));
        wrapped.addProperty("isError", false);
        return wrapped;
    }

    private static com.google.gson.JsonArray contentJson(String text) {
        var arr = new com.google.gson.JsonArray();
        var item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        arr.add(item);
        return arr;
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
