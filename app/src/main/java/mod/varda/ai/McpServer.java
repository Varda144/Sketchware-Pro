package mod.varda.ai;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import pro.sketchware.utility.FileUtil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Embedded MCP (Model Context Protocol) server for Sketchware Pro.
 * Transport: Streamable HTTP (POST /mcp), JSON-RPC 2.0, stateless.
 *
 * FULL ACCESS tools:
 * - Project management (list, read, write, delete)
 * - View/Event/Component data (read/write)
 * - Resource management (images, sounds, fonts)
 * - Permission management
 * - AndroidManifest editing
 * - Library management
 * - Custom code (Java/Kotlin)
 * - AdMob integration
 * - Logcat reader
 * - Shell commands
 * - AI chat (proxy to provider)
 */
public class McpServer {

    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static volatile McpServer instance;

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private Thread acceptThread;
    private Context appContext;

    // ---------- Lifecycle ----------

    public static synchronized boolean start(Context ctx) {
        if (instance != null && instance.isRunning()) return true;
        try {
            instance = new McpServer();
            instance.appContext = ctx.getApplicationContext();
            int port = AiProviderConfig.getMcpPort(instance.appContext);
            String host = AiProviderConfig.getMcpHost(instance.appContext);
            instance.serverSocket = new ServerSocket(port, 64, InetAddress.getByName(host));
            instance.pool = Executors.newCachedThreadPool();
            instance.acceptThread = new Thread(instance::acceptLoop, "varda-mcp-accept");
            instance.acceptThread.setDaemon(true);
            instance.acceptThread.start();
            return true;
        } catch (Exception e) {
            instance = null;
            return false;
        }
    }

    public static synchronized void stop() {
        McpServer s = instance;
        if (s == null) return;
        try { if (s.serverSocket != null) s.serverSocket.close(); } catch (Exception ignored) {}
        if (s.pool != null) s.pool.shutdownNow();
        instance = null;
    }

    public static boolean isRunning() {
        McpServer s = instance;
        return s != null && s.serverSocket != null && !s.serverSocket.isClosed();
    }

    public static int getPort(Context ctx) {
        return AiProviderConfig.getMcpPort(ctx);
    }

    private boolean serverIsRunning() {
        return serverSocket != null && !serverSocket.isClosed();
    }

    private void acceptLoop() {
        while (serverIsRunning()) {
            try {
                Socket sock = serverSocket.accept();
                sock.setSoTimeout(30000);
                ExecutorService p = pool;
                if (p != null) p.submit(() -> handleConnection(sock));
            } catch (Exception e) {
                return;
            }
        }
    }

    // ---------- HTTP layer ----------

    private void handleConnection(Socket sock) {
        try {
            String reqLine = readLine(sock.getInputStream());
            if (reqLine == null || reqLine.isEmpty()) return;
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String pathFull = parts[1];
            String path = pathFull;
            String query = "";
            int q = pathFull.indexOf('?');
            if (q >= 0) { path = pathFull.substring(0, q); query = pathFull.substring(q + 1); }

            java.util.Map<String, String> headers = new java.util.HashMap<>();
            String line;
            while ((line = readLine(sock.getInputStream())) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
            }
            int len = 0;
            try { len = Integer.parseInt(headers.getOrDefault("content-length", "0")); } catch (NumberFormatException ignored) {}
            if (len > 8 * 1024 * 1024) {
                respond(sock, 413, "{\"error\":\"payload too large (max 8MB)\"}", "application/json");
                return;
            }
            byte[] bodyBytes = new byte[0];
            if (len > 0) {
                bodyBytes = new byte[len];
                int off = 0;
                while (off < len) { int r = sock.getInputStream().read(bodyBytes, off, len - off); if (r < 0) break; off += r; }
            }
            String body = new String(bodyBytes, StandardCharsets.UTF_8);

            if ("OPTIONS".equals(method)) { respond(sock, 204, "", "text/plain"); return; }

            if (!authorized(headers, query)) {
                respond(sock, 401, "{\"error\":\"unauthorized: set X-MCP-Token header\"}", "application/json");
                return;
            }

            if ("/health".equals(path)) {
                JsonObject o = new JsonObject();
                o.addProperty("status", "ok");
                o.addProperty("server", "sketchware-pro-mcp");
                o.addProperty("mcp_running", isRunning());
                o.addProperty("tools_count", buildToolList().size());
                respond(sock, 200, o.toString(), "application/json");
                return;
            }

            if ("/mcp".equals(path)) {
                if (!"POST".equals(method)) {
                    JsonObject info = new JsonObject();
                    info.addProperty("transport", "streamable-http");
                    info.addProperty("endpoint", "/mcp");
                    respond(sock, 405, info.toString(), "application/json");
                    return;
                }
                respond(sock, 200, handleRpc(body), "application/json");
                return;
            }

            respond(sock, 404, "{\"error\":\"not found\"}", "application/json");
        } catch (Exception e) {
            try { respond(sock, 500, "{\"error\":" + gsonStr(String.valueOf(e.getMessage())) + "}", "application/json"); } catch (Exception ignored) {}
        } finally {
            try { sock.close(); } catch (Exception ignored) {}
        }
    }

    private boolean authorized(java.util.Map<String, String> headers, String query) {
        String token = AiProviderConfig.getMcpToken(appContext);
        String host = AiProviderConfig.getMcpHost(appContext);
        boolean networkExposed = "0.0.0.0".equals(host);
        if (token.isEmpty()) {
            // No token configured: only allow when bound to localhost.
            // A network-exposed (0.0.0.0) server with no token would be LAN-wide RCE.
            return !networkExposed;
        }
        String given = headers.get("x-mcp-token");
        if (given != null && token.equals(given)) return true;
        if (given == null && query != null) {
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && "token".equals(kv.substring(0, eq))) {
                    try { if (token.equals(URLDecoder.decode(kv.substring(eq + 1), "UTF-8"))) return true; } catch (Exception ignored) {}
                }
            }
        }
        return false;
    }

    private void respond(Socket sock, int code, String payload, String contentType) throws Exception {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        OutputStream out = sock.getOutputStream();
        String reason = code == 200 ? "OK" : code == 202 ? "Accepted" : code == 204 ? "No Content"
                : code == 401 ? "Unauthorized" : code == 404 ? "Not Found" : code == 405 ? "Method Not Allowed" : "Error";
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(" ").append(reason).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Content-Length: ").append(data.length).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n");
        sb.append("Access-Control-Allow-Headers: Content-Type, X-MCP-Token, Accept\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        if (data.length > 0) out.write(data);
        out.flush();
    }

    private static String readLine(java.io.InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b < 0) break;
            if (prev == '\r' && b == '\n') return sb.toString();
            if (b != '\r' && b != '\n') sb.append((char) b);
            prev = b;
            if (sb.length() > 16384) return sb.toString();
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // ---------- JSON-RPC / MCP ----------

    private String handleRpc(String body) {
        try {
            JsonObject req = JsonParser.parseString(body).getAsJsonObject();
            String method = req.has("method") ? req.get("method").getAsString() : "";
            boolean isNotification = !req.has("id") || req.get("id").isJsonNull();

            if (method.startsWith("notifications/")) return "";
            if (isNotification) return "";

            String id = req.has("id") ? req.get("id").toString() : "null";
            JsonObject result = new JsonObject();

            switch (method) {
                case "initialize": {
                    result.addProperty("protocolVersion", PROTOCOL_VERSION);
                    JsonObject caps = new JsonObject();
                    caps.add("tools", new JsonObject());
                    caps.add("resources", new JsonObject());
                    caps.add("prompts", new JsonObject());
                    result.add("capabilities", caps);
                    JsonObject info = new JsonObject();
                    info.addProperty("name", "sketchware-pro-mcp");
                    info.addProperty("version", "2.0.0");
                    result.add("serverInfo", info);
                    result.addProperty("instructions",
                            "Full-access MCP bridge for Sketchware Pro IDE on Android. "
                            + "Tools: project management, views, events, components, resources, "
                            + "permissions, AndroidManifest, libraries, custom code, ads, logcat, "
                            + "shell commands, and AI chat.");
                    break;
                }
                case "ping": break;
                case "tools/list": result.add("tools", buildToolList()); break;
                case "resources/list": result.add("resources", buildResourceList()); break;
                case "resources/read": {
                    JsonObject params = req.has("params") ? req.getAsJsonObject("params") : new JsonObject();
                    String uri = params.has("uri") ? params.get("uri").getAsString() : "";
                    result.add("contents", readResource(uri));
                    break;
                }
                case "prompts/list": result.add("prompts", buildPromptList()); break;
                case "prompts/get": {
                    JsonObject params = req.has("params") ? req.getAsJsonObject("params") : new JsonObject();
                    result.add("messages", getPromptMessages(params));
                    break;
                }
                case "tools/call": {
                    JsonObject params = req.has("params") ? req.getAsJsonObject("params") : new JsonObject();
                    String name = params.has("name") ? params.get("name").getAsString() : "";
                    JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                            ? params.getAsJsonObject("arguments") : new JsonObject();
                    return toolCallResponse(id, name, args);
                }
                default: return rpcError(id, -32601, "Unknown method: " + method);
            }

            JsonObject resp = new JsonObject();
            resp.addProperty("jsonrpc", "2.0");
            resp.add("id", JsonParser.parseString(id));
            resp.add("result", result);
            return resp.toString();
        } catch (Exception e) {
            return rpcError("null", -32700, "Parse error: " + e.getMessage());
        }
    }

    private String toolCallResponse(String id, String name, JsonObject args) {
        String text;
        boolean isError = false;
        try { text = dispatch(name, args); }
        catch (Throwable t) { isError = true; text = "ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage(); }
        if (text == null) text = "";

        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        JsonArray arr = new JsonArray();
        arr.add(content);
        JsonObject result = new JsonObject();
        result.add("content", arr);
        result.addProperty("isError", isError);

        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", JsonParser.parseString(id));
        resp.add("result", result);
        return resp.toString();
    }

    private String rpcError(String id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", JsonParser.parseString(id));
        resp.add("error", err);
        return resp.toString();
    }

    // ---------- Tools ----------

    private JsonArray buildToolList() {
        JsonArray tools = new JsonArray();
        tools.add(tool("device_info", "Get device model, Android version, screen size, and MCP status", null));
        tools.add(tool("list_projects", "List all Sketchware Pro projects", null));
        tools.add(tool("get_project_context", "Get project metadata, resources, and compile errors",
                "{\"sc_id\":{\"type\":\"string\",\"description\":\"Project sc_id\"}}"));
        tools.add(tool("read_file", "Read any file as text (FULL ACCESS)",
                "{\"path\":{\"type\":\"string\",\"description\":\"Absolute file path\"}}"));
        tools.add(tool("write_file", "Create or overwrite any file (FULL ACCESS)",
                "{\"path\":{\"type\":\"string\",\"description\":\"Absolute file path\"},\"content\":{\"type\":\"string\",\"description\":\"File content\"}}"));
        tools.add(tool("delete_path", "Delete a file or directory (FULL ACCESS)",
                "{\"path\":{\"type\":\"string\",\"description\":\"Absolute path to delete\"}}"));
        tools.add(tool("list_files", "List directory contents with file sizes",
                "{\"path\":{\"type\":\"string\",\"description\":\"Directory path\"}}"));
        tools.add(tool("copy_file", "Copy a file from source to destination",
                "{\"source\":{\"type\":\"string\"},\"destination\":{\"type\":\"string\"}}"));
        tools.add(tool("move_file", "Move/rename a file",
                "{\"source\":{\"type\":\"string\"},\"destination\":{\"type\":\"string\"}}"));
        tools.add(tool("file_exists", "Check if a file or directory exists",
                "{\"path\":{\"type\":\"string\"}}"));
        tools.add(tool("get_file_size", "Get file size in bytes",
                "{\"path\":{\"type\":\"string\"}}"));
        tools.add(tool("read_project_resources", "List all resource files (images, sounds, fonts) in a project",
                "{\"sc_id\":{\"type\":\"string\",\"description\":\"Project sc_id\"}}"));
        tools.add(tool("read_project_permissions", "Read AndroidManifest permissions for a project",
                "{\"sc_id\":{\"type\":\"string\",\"description\":\"Project sc_id\"}}"));
        tools.add(tool("write_project_permissions", "Write AndroidManifest permissions for a project",
                "{\"sc_id\":{\"type\":\"string\",\"description\":\"Project sc_id\"},\"permissions\":{\"type\":\"string\",\"description\":\"Newline-separated permission list\"}}"));
        tools.add(tool("read_custom_code", "Read custom Java/Kotlin code added to a project",
                "{\"sc_id\":{\"type\":\"string\",\"description\":\"Project sc_id\"},\"language\":{\"type\":\"string\",\"description\":\"java or kotlin\"}}"));
        tools.add(tool("write_custom_code", "Write custom Java/Kotlin code to a project",
                "{\"sc_id\":{\"type\":\"string\",\"description\":\"Project sc_id\"},\"language\":{\"type\":\"string\",\"description\":\"java or kotlin\"},\"code\":{\"type\":\"string\",\"description\":\"Code to write\"}}"));
        tools.add(tool("read_compile_log", "Read the last compilation log for a project",
                "{\"sc_id\":{\"type\":\"string\",\"description\":\"Project sc_id\"}}"));
        tools.add(tool("read_library_config", "Read library configuration for a project",
                "{\"sc_id\":{\"type\":\"string\",\"description\":\"Project sc_id\"}}"));
        tools.add(tool("write_library_config", "Write library configuration for a project",
                "{\"sc_id\":{\"type\":\"string\",\"description\":\"Project sc_id\"},\"config\":{\"type\":\"string\",\"description\":\"JSON config\"}}"));
        tools.add(tool("read_proguard_config", "Read ProGuard/R8 configuration",
                "{\"sc_id\":{\"type\":\"string\",\"description\":\"Project sc_id\"}}"));
        tools.add(tool("write_proguard_config", "Write ProGuard/R8 rules",
                "{\"sc_id\":{\"type\":\"string\",\"description\":\"Project sc_id\"},\"rules\":{\"type\":\"string\",\"description\":\"ProGuard rules\"}}"));
        tools.add(tool("shell", "Run a shell command and get output (FULL ACCESS)",
                "{\"command\":{\"type\":\"string\",\"description\":\"Shell command to execute\"}}"));
        tools.add(tool("ai_chat", "Send a prompt to the AI provider and get a response",
                "{\"prompt\":{\"type\":\"string\",\"description\":\"User prompt\"}}"));
        tools.add(tool("generate_view_xml", "Generate Android XML layout for a given UI specification",
                "{\"spec\":{\"type\":\"string\",\"description\":\"Description of the UI layout to generate\"},\"sc_id\":{\"type\":\"string\",\"description\":\"Project sc_id for context\"}}"));
        tools.add(tool("generate_event_code", "Generate Java code for a Sketchware Pro event handler",
                "{\"event_type\":{\"type\":\"string\",\"description\":\"Event type: activity, view, component\"},\"event_name\":{\"type\":\"string\",\"description\":\"Event name like onClick, onCreate\"},\"target_id\":{\"type\":\"string\",\"description\":\"Target widget/component ID\"},\"description\":{\"type\":\"string\",\"description\":\"What the event should do\"}}"));
        tools.add(tool("generate_component_code", "Generate code to use a Sketchware Pro component",
                "{\"component_type\":{\"type\":\"string\",\"description\":\"Component type: intent, sharedpref, firebase, mediaplayer, interstitial_ad, rewarded_ad, etc.\"},\"description\":{\"type\":\"string\",\"description\":\"What the component should do\"}}"));
        tools.add(tool("generate_ad_integration", "Generate AdMob integration code (InterstitialAd, RewardedAd, Banner)",
                "{\"ad_type\":{\"type\":\"string\",\"description\":\"Ad type: interstitial, rewarded, banner\"},\"ad_unit_id\":{\"type\":\"string\",\"description\":\"Ad unit ID (use test ID if empty)\"}}"));
        tools.add(tool("diagnose_error", "Diagnose a compilation or runtime error and suggest fixes",
                "{\"error_message\":{\"type\":\"string\",\"description\":\"The error message or stack trace\"},\"sc_id\":{\"type\":\"string\",\"description\":\"Project sc_id for context\"}}"));
        tools.add(tool("capture_screen", "Capture a screenshot of the device (saved to storage root as mcp_screen.png)",
                null));
        tools.add(tool("ai_vision", "Send a screenshot/image to a vision-capable model and ask about it",
                "{\"prompt\":{\"type\":\"string\",\"description\":\"What to ask about the image\"},\"image_path\":{\"type\":\"string\",\"description\":\"Optional image path; if omitted, a fresh screenshot is captured\"}}"));
        tools.add(tool("apply_custom_code", "Write/append custom Java/Kotlin code into a project so it actually compiles into the app",
                "{\"sc_id\":{\"type\":\"string\"},\"language\":{\"type\":\"string\",\"description\":\"java or kotlin\"},\"code\":{\"type\":\"string\",\"description\":\"Code to write\"},\"append\":{\"type\":\"boolean\",\"description\":\"Append instead of overwrite (default false)\"}}"));
        tools.add(tool("create_project", "Create a new Sketchware Pro project skeleton (folders + project.properties)",
                "{\"sc_id\":{\"type\":\"string\"},\"name\":{\"type\":\"string\",\"description\":\"Project display name\"}}"));
        tools.add(tool("search_project", "Search project data files for a substring (grep)",
                "{\"sc_id\":{\"type\":\"string\"},\"query\":{\"type\":\"string\"}}"));
        return tools;
    }

    private JsonObject tool(String name, String desc, String propsJson) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", desc);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        if (propsJson != null) schema.add("properties", JsonParser.parseString(propsJson).getAsJsonObject());
        else schema.add("properties", new JsonObject());
        t.add("inputSchema", schema);
        return t;
    }

    private String dispatch(String name, JsonObject args) throws Exception {
        switch (name) {
            case "device_info": return deviceInfo();
            case "list_projects": return listProjects();
            case "get_project_context": return projectContext(args);
            case "read_file": return readFile(args);
            case "write_file": return writeFile(args);
            case "delete_path": return deletePath(args);
            case "list_files": return listFiles(args);
            case "copy_file": return copyFile(args);
            case "move_file": return moveFile(args);
            case "file_exists": return fileExists(args);
            case "get_file_size": return getFileSize(args);
            case "read_project_resources": return readProjectResources(args);
            case "read_project_permissions": return readProjectPermissions(args);
            case "write_project_permissions": return writeProjectPermissions(args);
            case "read_custom_code": return readCustomCode(args);
            case "write_custom_code": return writeCustomCode(args);
            case "read_compile_log": return readCompileLog(args);
            case "read_library_config": return readLibraryConfig(args);
            case "write_library_config": return writeLibraryConfig(args);
            case "read_proguard_config": return readProguardConfig(args);
            case "write_proguard_config": return writeProguardConfig(args);
            case "shell": return shell(args);
            case "ai_chat": return aiChat(args);
            case "generate_view_xml": return generateViewXml(args);
            case "generate_event_code": return generateEventCode(args);
            case "generate_component_code": return generateComponentCode(args);
            case "generate_ad_integration": return generateAdIntegration(args);
            case "diagnose_error": return diagnoseError(args);
            case "capture_screen": return captureScreen();
            case "ai_vision": return aiVision(args);
            case "apply_custom_code": return applyCustomCode(args);
            case "create_project": return createProject(args);
            case "search_project": return searchProject(args);
            default: throw new IllegalStateException("Unknown tool: " + name);
        }
    }

    // ---------- Tool implementations ----------

    private String deviceInfo() {
        JsonObject o = new JsonObject();
        o.addProperty("manufacturer", Build.MANUFACTURER);
        o.addProperty("model", Build.MODEL);
        o.addProperty("android_version", Build.VERSION.RELEASE);
        o.addProperty("sdk_int", Build.VERSION.SDK_INT);
        o.addProperty("storage_root", Environment.getExternalStorageDirectory().getAbsolutePath());
        o.addProperty("mcp_running", isRunning());
        o.addProperty("mcp_port", AiProviderConfig.getMcpPort(appContext));
        o.addProperty("ai_model", AiProviderConfig.getModel(appContext));
        return o.toString();
    }

    private String listProjects() {
        File root = new File(Environment.getExternalStorageDirectory(), ".sketchware/mysc/list");
        File[] dirs = root.listFiles();
        if (dirs == null) return "[]";
        JsonArray arr = new JsonArray();
        Arrays.sort(dirs, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            JsonObject proj = new JsonObject();
            proj.addProperty("sc_id", d.getName());
            proj.addProperty("name", d.getName());
            // Try to read project name
            File props = new File(d, "project.properties");
            if (props.exists()) {
                String content = readTextFile(props);
                if (!content.isEmpty()) proj.addProperty("properties", content);
            }
            // Check data dir
            File dataDir = new File(Environment.getExternalStorageDirectory(), ".sketchware/data/" + d.getName());
            if (dataDir.exists()) {
                File viewFile = new File(dataDir, "view");
                if (viewFile.exists()) proj.addProperty("has_view_data", viewFile.length() > 0);
                File logFile = new File(dataDir, "compile_log");
                if (logFile.exists()) proj.addProperty("has_compile_log", logFile.length() > 0);
            }
            arr.add(proj);
        }
        return arr.toString();
    }

    private String projectContext(JsonObject args) {
        String scId = reqStr(args, "sc_id");
        return AiProvider.getProjectContext(scId);
    }

    private String readFile(JsonObject args) {
        String path = reqStr(args, "path");
        File f = new File(path);
        if (!f.exists()) throw new IllegalStateException("Not found: " + path);
        if (f.length() > 1048576) return "File too large (" + f.length() + " bytes). Use shell for binary files.";
        return readTextFile(f);
    }

    private String writeFile(JsonObject args) {
        String path = reqStr(args, "path");
        String content = reqStr(args, "content");
        File f = new File(path);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try {
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.close();
            return "WROTE " + f.getAbsolutePath() + " (" + f.length() + " bytes)";
        } catch (Exception e) {
            return "WRITE FAILED: " + e.getMessage();
        }
    }

    private String deletePath(JsonObject args) {
        String path = reqStr(args, "path");
        File f = new File(path);
        if (!f.exists()) throw new IllegalStateException("Not found: " + path);
        boolean ok = deleteRecursive(f);
        return ok ? "DELETED " + f.getAbsolutePath() : "DELETE FAILED: " + f.getAbsolutePath();
    }

    private String listFiles(JsonObject args) {
        String path = reqStr(args, "path");
        File dir = new File(path);
        if (!dir.exists()) throw new IllegalStateException("Not found: " + path);
        File[] files = dir.listFiles();
        StringBuilder sb = new StringBuilder();
        sb.append(dir.getAbsolutePath()).append("\n");
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File f : files) {
                sb.append(f.isDirectory() ? "d " : "- ").append(f.getName());
                if (f.isFile()) sb.append(" (").append(f.length()).append(" bytes)");
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String copyFile(JsonObject args) {
        String src = reqStr(args, "source");
        String dst = reqStr(args, "destination");
        try {
            FileUtil.copyFile(src, dst);
            return "COPIED " + src + " -> " + dst;
        } catch (Exception e) {
            return "COPY FAILED: " + e.getMessage();
        }
    }

    private String moveFile(JsonObject args) {
        String src = reqStr(args, "source");
        String dst = reqStr(args, "destination");
        try {
            FileUtil.moveFile(src, dst);
            return "MOVED " + src + " -> " + dst;
        } catch (Exception e) {
            return "MOVE FAILED: " + e.getMessage();
        }
    }

    private String fileExists(JsonObject args) {
        String path = reqStr(args, "path");
        return new File(path).exists() ? "EXISTS" : "NOT_FOUND";
    }

    private String getFileSize(JsonObject args) {
        String path = reqStr(args, "path");
        File f = new File(path);
        return f.exists() ? String.valueOf(f.length()) : "NOT_FOUND";
    }

    private String readProjectResources(JsonObject args) {
        String scId = reqStr(args, "sc_id");
        File resDir = new File(Environment.getExternalStorageDirectory(),
                ".sketchware/data/" + scId + "/files/resource");
        if (!resDir.exists()) return "No resources found for project " + scId;
        File[] files = resDir.listFiles();
        if (files == null || files.length == 0) return "No resources found for project " + scId;
        StringBuilder sb = new StringBuilder();
        sb.append("Resources for project ").append(scId).append(":\n");
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            sb.append("  ").append(f.getName()).append(" (").append(f.length()).append(" bytes)\n");
        }
        return sb.toString();
    }

    private String readProjectPermissions(JsonObject args) {
        String scId = reqStr(args, "sc_id");
        File permFile = new File(Environment.getExternalStorageDirectory(),
                ".sketchware/data/" + scId + "/permission");
        if (!permFile.exists()) return "No permissions file found";
        return readTextFile(permFile);
    }

    private String writeProjectPermissions(JsonObject args) {
        String scId = reqStr(args, "sc_id");
        String perms = reqStr(args, "permissions");
        File permFile = new File(Environment.getExternalStorageDirectory(),
                ".sketchware/data/" + scId + "/permission");
        try {
            FileOutputStream fos = new FileOutputStream(permFile);
            fos.write(perms.getBytes(StandardCharsets.UTF_8));
            fos.close();
            return "Permissions updated for project " + scId;
        } catch (Exception e) {
            return "FAILED: " + e.getMessage();
        }
    }

    private String readCustomCode(JsonObject args) {
        String scId = reqStr(args, "sc_id");
        String lang = reqStr(args, "language");
        File codeFile = new File(Environment.getExternalStorageDirectory(),
                ".sketchware/data/" + scId + "/" + lang);
        if (!codeFile.exists()) return "No custom " + lang + " code found";
        return readTextFile(codeFile);
    }

    private String writeCustomCode(JsonObject args) {
        String scId = reqStr(args, "sc_id");
        String lang = reqStr(args, "language");
        String code = reqStr(args, "code");
        File codeFile = new File(Environment.getExternalStorageDirectory(),
                ".sketchware/data/" + scId + "/" + lang);
        try {
            FileOutputStream fos = new FileOutputStream(codeFile);
            fos.write(code.getBytes(StandardCharsets.UTF_8));
            fos.close();
            return "Custom " + lang + " code written for project " + scId;
        } catch (Exception e) {
            return "FAILED: " + e.getMessage();
        }
    }

    private String readCompileLog(JsonObject args) {
        String scId = reqStr(args, "sc_id");
        File logFile = new File(Environment.getExternalStorageDirectory(),
                ".sketchware/data/" + scId + "/compile_log");
        if (!logFile.exists()) return "No compile log found";
        String log = readTextFile(logFile);
        return log.isEmpty() ? "Compile log is empty (no errors)" : log;
    }

    private String readLibraryConfig(JsonObject args) {
        String scId = reqStr(args, "sc_id");
        File libFile = new File(Environment.getExternalStorageDirectory(),
                ".sketchware/data/" + scId + "/library");
        if (!libFile.exists()) return "No library config found";
        return readTextFile(libFile);
    }

    private String writeLibraryConfig(JsonObject args) {
        String scId = reqStr(args, "sc_id");
        String config = reqStr(args, "config");
        File libFile = new File(Environment.getExternalStorageDirectory(),
                ".sketchware/data/" + scId + "/library");
        try {
            FileOutputStream fos = new FileOutputStream(libFile);
            fos.write(config.getBytes(StandardCharsets.UTF_8));
            fos.close();
            return "Library config updated for project " + scId;
        } catch (Exception e) {
            return "FAILED: " + e.getMessage();
        }
    }

    private String readProguardConfig(JsonObject args) {
        String scId = reqStr(args, "sc_id");
        File pgFile = new File(Environment.getExternalStorageDirectory(),
                ".sketchware/data/" + scId + "/proguard-rules.pro");
        if (!pgFile.exists()) return "No ProGuard config found";
        return readTextFile(pgFile);
    }

    private String writeProguardConfig(JsonObject args) {
        String scId = reqStr(args, "sc_id");
        String rules = reqStr(args, "rules");
        File pgFile = new File(Environment.getExternalStorageDirectory(),
                ".sketchware/data/" + scId + "/proguard-rules.pro");
        try {
            FileOutputStream fos = new FileOutputStream(pgFile);
            fos.write(rules.getBytes(StandardCharsets.UTF_8));
            fos.close();
            return "ProGuard rules updated for project " + scId;
        } catch (Exception e) {
            return "FAILED: " + e.getMessage();
        }
    }

    private String shell(JsonObject args) {
        String cmd = reqStr(args, "command");
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            br.close();
            boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
            if (!finished) { proc.destroyForcibly(); sb.append("\n[timeout 60s]"); }
            else sb.append("\n[exit=").append(proc.exitValue()).append("]");
            return sb.toString();
        } catch (Exception e) {
            return "SHELL ERROR: " + e.getMessage();
        }
    }

    private String aiChat(JsonObject args) {
        String prompt = reqStr(args, "prompt");
        try {
            return AiProvider.chatBlocking(appContext, null, prompt);
        } catch (Exception e) {
            return "AI ERROR: " + e.getMessage();
        }
    }

    private String generateViewXml(JsonObject args) {
        String spec = reqStr(args, "spec");
        String scId = args.has("sc_id") ? reqStr(args, "sc_id") : "";
        String prompt = "Generate Android XML layout code for a Sketchware Pro project.\n"
                + "Specification: " + spec + "\n"
                + "Output ONLY the XML code. Use standard Android widgets (LinearLayout, RelativeLayout, "
                + "Button, TextView, EditText, ImageView, ListView, ScrollView, etc.).\n"
                + "Include proper layout_width, layout_height, id, padding, margin attributes.\n"
                + "Use android:id=\"@+id/widgetXX\" naming convention (widget1, widget2, etc.).";
        try { return AiProvider.chatBlocking(appContext, null, prompt); }
        catch (Exception e) { return "AI ERROR: " + e.getMessage(); }
    }

    private String generateEventCode(JsonObject args) {
        String eventType = reqStr(args, "event_type");
        String eventName = reqStr(args, "event_name");
        String targetId = args.has("target_id") ? reqStr(args, "target_id") : "";
        String desc = reqStr(args, "description");
        String prompt = "Generate Java code for a Sketchware Pro event handler.\n"
                + "Event type: " + eventType + "\n"
                + "Event name: " + eventName + "\n"
                + "Target: " + targetId + "\n"
                + "What it should do: " + desc + "\n\n"
                + "Sketchware Pro event handler format:\n"
                + "public void " + eventName + "(" + (targetId.isEmpty() ? "" : "View v") + ") {\n"
                + "    // code here\n"
                + "}\n\n"
                + "Available APIs: Intent, SharedPreferences (using component), Firebase (using component), "
                + "MediaPlayer, Dialog, Toast, Log, Calendar, etc.\n"
                + "Output ONLY the Java method body.";
        try { return AiProvider.chatBlocking(appContext, null, prompt); }
        catch (Exception e) { return "AI ERROR: " + e.getMessage(); }
    }

    private String generateComponentCode(JsonObject args) {
        String compType = reqStr(args, "component_type");
        String desc = reqStr(args, "description");
        String prompt = "Generate Sketchware Pro component usage code.\n"
                + "Component type: " + compType + "\n"
                + "What it should do: " + desc + "\n\n"
                + "Output the Java code that uses this component in Sketchware Pro.\n"
                + "Include proper initialization and usage patterns.";
        try { return AiProvider.chatBlocking(appContext, null, prompt); }
        catch (Exception e) { return "AI ERROR: " + e.getMessage(); }
    }

    private String generateAdIntegration(JsonObject args) {
        String adType = reqStr(args, "ad_type");
        String adUnitId = args.has("ad_unit_id") ? reqStr(args, "ad_unit_id") : "";
        if (adUnitId.isEmpty()) adUnitId = "ca-app-pub-3940256099942544/1033173712";
        String prompt = "Generate AdMob integration code for Sketchware Pro.\n"
                + "Ad type: " + adType + "\n"
                + "Ad unit ID: " + adUnitId + "\n\n"
                + "For Sketchware Pro, the component types are:\n"
                + "- InterstitialAd (type 13)\n"
                + "- RewardedAd (type 22)\n\n"
                + "Output the Java code for loading and showing the ad.\n"
                + "Include: initialization, load, show, and callback handlers.";
        try { return AiProvider.chatBlocking(appContext, null, prompt); }
        catch (Exception e) { return "AI ERROR: " + e.getMessage(); }
    }

    private String diagnoseError(JsonObject args) {
        String errorMsg = reqStr(args, "error_message");
        String scId = args.has("sc_id") ? reqStr(args, "sc_id") : "";
        String context = "";
        if (!scId.isEmpty()) {
            context = AiProvider.getProjectContext(scId);
        }
        String prompt = "Diagnose and fix this Sketchware Pro compilation/runtime error:\n\n"
                + "Error: " + errorMsg + "\n\n"
                + (context.isEmpty() ? "" : "Project context:\n" + context + "\n")
                + "Provide:\n"
                + "1. Root cause explanation\n"
                + "2. The fix (corrected code)\n"
                + "3. Prevention tips for future";
        try { return AiProvider.chatBlocking(appContext, null, prompt); }
        catch (Exception e) { return "AI ERROR: " + e.getMessage(); }
    }

    // ---------- Agent / vision / project tools ----------

    private String exec(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            br.close();
            p.waitFor(60, TimeUnit.SECONDS);
            return sb.toString();
        } catch (Exception e) {
            return "EXEC ERROR: " + e.getMessage();
        }
    }

    private String captureScreen() {
        String out = Environment.getExternalStorageDirectory().getAbsolutePath() + "/mcp_screen.png";
        String r = exec("screencap -p " + out);
        return (r.isEmpty() ? "" : r + "\n") + "Saved screenshot: " + out;
    }

    private String aiVision(JsonObject args) throws Exception {
        String prompt = reqStr(args, "prompt");
        String imgPath = args.has("image_path") ? reqStr(args, "image_path") : "";
        if (imgPath.isEmpty()) {
            imgPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/mcp_screen.png";
            exec("screencap -p " + imgPath);
        }
        String b64 = AiProvider.fileToBase64(imgPath);
        return AiProvider.chatVisionBlocking(appContext, null, prompt, b64, "image/png");
    }

    private String applyCustomCode(JsonObject args) throws Exception {
        String scId = reqStr(args, "sc_id");
        String lang = reqStr(args, "language");
        String code = reqStr(args, "code");
        boolean append = args.has("append") && args.get("append").getAsBoolean();
        File f = new File(Environment.getExternalStorageDirectory(), ".sketchware/data/" + scId + "/" + lang);
        if (append && f.exists()) {
            code = readTextFile(f) + "\n\n" + code;
        }
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(code.getBytes(StandardCharsets.UTF_8));
        fos.close();
        return "Applied " + lang + " custom code to project " + scId + (append ? " (appended)" : " (overwritten)");
    }

    private String createProject(JsonObject args) throws Exception {
        String scId = reqStr(args, "sc_id");
        String name = args.has("name") ? reqStr(args, "name") : scId;
        File listDir = new File(Environment.getExternalStorageDirectory(), ".sketchware/mysc/list/" + scId);
        File dataDir = new File(Environment.getExternalStorageDirectory(), ".sketchware/data/" + scId);
        if (!listDir.mkdirs()) return "Failed to create list directory for " + scId;
        dataDir.mkdirs();
        File props = new File(listDir, "project.properties");
        FileOutputStream fos = new FileOutputStream(props);
        fos.write(("name=" + name + "\nsc_id=" + scId + "\n").getBytes(StandardCharsets.UTF_8));
        fos.close();
        for (String sub : new String[]{"view", "events", "components", "files/resource", "files/assets", "files/native_libs", "java", "kotlin"}) {
            new File(dataDir, sub).mkdirs();
        }
        return "Created project skeleton: " + scId + " (" + name + ")";
    }

    private String searchProject(JsonObject args) {
        String scId = reqStr(args, "sc_id");
        String q = reqStr(args, "query");
        File dir = new File(Environment.getExternalStorageDirectory(), ".sketchware/data/" + scId);
        StringBuilder sb = new StringBuilder();
        searchRec(dir, q, sb, 0);
        return sb.length() == 0 ? "No matches for: " + q : sb.toString();
    }

    private void searchRec(File dir, String q, StringBuilder sb, int depth) {
        if (depth > 8 || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) searchRec(f, q, sb, depth + 1);
            else if (f.length() < 2_000_000 && readTextFile(f).contains(q)) sb.append(f.getAbsolutePath()).append("\n");
        }
    }

    // ---------- MCP resources ----------

    private JsonArray buildResourceList() {
        JsonArray arr = new JsonArray();
        File root = new File(Environment.getExternalStorageDirectory(), ".sketchware/mysc/list");
        File[] dirs = root.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                JsonObject r = new JsonObject();
                r.addProperty("uri", "swp://project/" + d.getName());
                r.addProperty("name", "Project: " + d.getName());
                r.addProperty("description", "Sketchware Pro project context (resources, compile log)");
                r.addProperty("mimeType", "text/plain");
                arr.add(r);
            }
        }
        return arr;
    }

    private JsonArray readResource(String uri) {
        JsonArray arr = new JsonArray();
        JsonObject c = new JsonObject();
        c.addProperty("uri", uri);
        c.addProperty("mimeType", "text/plain");
        String text;
        if (uri.startsWith("swp://project/")) {
            text = AiProvider.getProjectContext(uri.substring("swp://project/".length()));
        } else if (uri.startsWith("swp://file/")) {
            text = readTextFile(new File(uri.substring("swp://file/".length())));
        } else {
            text = "Unknown resource URI: " + uri;
        }
        c.addProperty("text", text);
        arr.add(c);
        return arr;
    }

    // ---------- MCP prompts ----------

    private JsonArray buildPromptList() {
        JsonArray arr = new JsonArray();
        arr.add(promptMeta("build_app", "Scaffold an app feature",
                new String[][]{{"sc_id", "Project id", "true"}, {"feature", "Feature description", "true"}}));
        arr.add(promptMeta("fix_error", "Diagnose & fix an error",
                new String[][]{{"sc_id", "Project id", "true"}, {"error", "Error message or stack trace", "true"}}));
        arr.add(promptMeta("explain_code", "Explain project code",
                new String[][]{{"sc_id", "Project id", "true"}}));
        return arr;
    }

    private JsonObject promptMeta(String name, String desc, String[][] args) {
        JsonObject p = new JsonObject();
        p.addProperty("name", name);
        p.addProperty("description", desc);
        JsonArray a = new JsonArray();
        for (String[] arg : args) {
            JsonObject o = new JsonObject();
            o.addProperty("name", arg[0]);
            o.addProperty("description", arg[1]);
            o.addProperty("required", "true".equals(arg[2]));
            a.add(o);
        }
        p.add("arguments", a);
        return p;
    }

    private JsonArray getPromptMessages(JsonObject params) {
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        String text;
        switch (name) {
            case "build_app":
                text = "Build this feature in Sketchware Pro project " + argStr(args, "sc_id") + ": "
                        + argStr(args, "feature")
                        + ". Provide the view XML layout, the event handler code, required components, "
                        + "and any custom Java/Kotlin code.";
                break;
            case "fix_error":
                text = "Fix this error in Sketchware Pro project " + argStr(args, "sc_id") + ":\n"
                        + argStr(args, "error") + "\nProvide the corrected, compilable code.";
                break;
            case "explain_code":
                text = "Explain the structure and code of Sketchware Pro project " + argStr(args, "sc_id") + ".";
                break;
            default:
                text = "Unknown prompt: " + name;
        }
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.add("content", content);
        JsonArray m = new JsonArray();
        m.add(msg);
        return m;
    }

    private static String argStr(JsonObject args, String key) {
        return args.has(key) ? args.get(key).getAsString() : "";
    }

    // ---------- Helpers ----------

    private String reqStr(JsonObject args, String key) {
        if (!args.has(key)) throw new IllegalArgumentException("Missing argument: " + key);
        return args.get(key).getAsString();
    }

    private static String readTextFile(File f) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        return f.delete();
    }

    private static String gsonStr(String s) {
        return new com.google.gson.Gson().toJson(s == null ? "" : s);
    }
}
