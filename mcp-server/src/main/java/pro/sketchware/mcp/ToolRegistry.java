package pro.sketchware.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Registry and implementations of MCP tools. Each tool performs a real
 * operation against Sketchware Pro project files inside the workspace.
 *
 * Tool naming follows {@code group.action} (project.inspect, source.read, ...).
 */
public final class ToolRegistry {

    /** A tool implementation. */
    @FunctionalInterface
    public interface Handler {
        JsonObject call(Workspace ws, JsonObject args) throws IOException;
    }

    public record Tool(String name, String description, String inputSchema, Handler handler) {
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Workspace workspace;

    public ToolRegistry(Workspace workspace) {
        this.workspace = workspace;
        registerAll();
    }

    public JsonArray describe() {
        JsonArray arr = new JsonArray();
        for (Tool t : tools.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t.name());
            o.addProperty("description", t.description());
            o.addProperty("inputSchema", t.inputSchema());
            arr.add(o);
        }
        return arr;
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public JsonObject call(String name, JsonObject args) throws IOException {
        Tool t = tools.get(name);
        if (t == null) {
            throw new IOException("Unknown tool: " + name);
        }
        return t.handler().call(workspace, args == null ? new JsonObject() : args);
    }

    // ------------------------------------------------------------------ //

    private void registerAll() {
        register("project.list",
                "List discovered Sketchware projects in the workspace.",
                "{\"directory\":\"optional subdir to scan\"}",
                (ws, a) -> {
                    JsonArray projects = new JsonArray();
                    for (Workspace.ProjectRef p : ws.findProjects()) {
                        JsonObject o = new JsonObject();
                        o.addProperty("id", p.id());
                        o.addProperty("config", p.configPath().toString());
                        projects.add(o);
                    }
                    return ok("projects", projects);
                });

        register("project.metadata",
                "Read the project_config JSON map of a project (app name, package, SDKs...).",
                "{\"projectId\":\"<sc_id>\"}",
                (ws, a) -> {
                    Path0 p = projectConfig(ws, a);
                    JsonObject obj = JsonRpc.parseObject(Files.readString(p.value));
                    return ok("config", obj);
                });

        register("project.inspect",
                "Unified overview: settings, activities/logic files, custom blocks,"
                        + " resources, assets, fonts, sounds of one project.",
                "{\"projectId\":\"<sc_id>\"}",
                ToolRegistry::inspectProject);

        register("view.list",
                "List activity/view folders of a project (.sketchware/data/<id>/file/*).",
                "{\"projectId\":\"<sc_id>\"}",
                (ws, a) -> {
                    String id = require(a, "projectId");
                    return ok("views", array(ws.listDir(".sketchware/data/" + id + "/file")));
                });

        register("component.tree",
                "List component/layout files of an activity directory.",
                "{\"projectId\":\"<sc_id>\",\"activity\":\"MainActivity\"}",
                (ws, a) -> {
                    String base = ".sketchware/data/" + require(a, "projectId")
                            + "/file/" + require(a, "activity");
                    return ok("files", ws.walk(base, null));
                });

        register("component.update_property",
                "Update a property in a view XML file (atomic write with backup).",
                "{\"file\":\"relative path\",\"property\":\"text\",\"value\":\"Login\","
                        + "\"oldValue\":\"optional current value for safety\"}",
                (ws, a) -> {
                    String file = require(a, "file");
                    String prop = require(a, "property");
                    String value = a.get("value").isJsonPrimitive()
                            ? a.get("value").getAsString() : "";
                    String content = ws.readFile(file);
                    if (!content.contains(prop)) {
                        throw new IOException("Property \"" + prop + "\" not present in " + file
                                + "; refusing blind append.");
                    }
                    String updated = XmlOps.updateAttribute(content, prop, value,
                            a.has("oldValue") && a.get("oldValue").isJsonPrimitive()
                                    ? a.get("oldValue").getAsString() : null);
                    ws.writeFile(file, updated);
                    return okStatus("updated " + prop + " in " + file);
                });

        register("source.list",
                "List files under a directory (java/xml/resources), optionally filtered by extension.",
                "{\"directory\":\".sketchware/data/<id>/files/java\",\"extension\":\".java optional\"}",
                (ws, a) -> {
                    String dir = require(a, "directory");
                    String ext = a.has("extension") ? a.get("extension").getAsString() : null;
                    return ok("files", ws.walk(dir, ext));
                });

        register("source.read",
                "Read any file inside the workspace boundary.",
                "{\"file\":\"relative path\"}",
                (ws, a) -> ok("content", ws.readFile(require(a, "file"))));

        register("source.write",
                "Write a text file (atomic, automatic backup).",
                "{\"file\":\"relative path\",\"content\":\"new content\"}",
                (ws, a) -> {
                    ws.writeFile(require(a, "file"), string(a, "content"));
                    return okStatus("written");
                });

        register("source.delete",
                "Delete a file. Destructive: requires --allow-destructive.",
                "{\"file\":\"relative path\"}",
                (ws, a) -> {
                    ws.deleteFile(require(a, "file"));
                    return okStatus("deleted");
                });

        register("resource.search",
                "Search all workspace files for a substring; returns matching paths+lines.",
                "{\"query\":\"text\",\"extension\":\".xml optional filter\"}",
                ToolRegistry::search);

        register("manifest.add_permission",
                "Insert <uses-permission android:name=\"...\"/> into a manifest file"
                        + " if not already present.",
                "{\"manifest\":\"path to AndroidManifest.xml\",\"permission\":\"android.permission.INTERNET\"}",
                ToolRegistry::addPermission);

        register("snapshot.create",
                "Snapshot a project folder into <workspace>/.ai_snapshots/.",
                "{\"directory\":\"folder to snapshot\",\"name\":\"snapshot name\"}",
                ToolRegistry::createSnapshot);

        register("snapshot.restore",
                "Restore the newest snapshot of a folder over it. Requires --allow-destructive.",
                "{\"name\":\"snapshot name\",\"target\":\"folder to restore into\"}",
                ToolRegistry::restoreSnapshot);

        register("generate_block_code",
                "Generate a validated Sketchware block plan (schema v1 JSON) from natural"
                + " language using Gemini. Reads GEMINI_API_KEY from the environment;"
                + " never persists or logs it.",
                "{\"description\":\"what should happen\",\"model\":\"optional model id\"}",
                ToolRegistry::generateBlockCode);

        register("logcat.read",
                "Read scoped log files kept inside the workspace (e.g. exported logcat)."
                + " Device-wide logs are never accessible through this server.",
                "{\"file\":\"relative path to log file\"}",
                (ws, a) -> ok("content", ws.readFile(require(a, "file"))));

        register("build.run",
                "Not supported on desktop: Sketchware Pro builds run on-device."
                + " Returns guidance instead of executing anything.",
                "{}",
                (ws, a) -> {
                    JsonObject o = okStatus("unsupported");
                    o.addProperty("note", "Builds must be triggered on-device or via the app.");
                    return o;
                });
    }

    // ------------------------------------------------------------------ //
    //  Implementations                                                    //
    // ------------------------------------------------------------------ //

    private static JsonObject inspectProject(Workspace ws, JsonObject a) throws IOException {
        String id = require(a, "projectId");
        JsonObject out = new JsonObject();
        out.addProperty("id", id);
        String configRel = ".sketchware/data/" + id + "/project_config";
        if (!Files.exists(ws.resolve(configRel))) {
            throw new IOException("No project_config for " + id);
        }
        out.add("config", JsonRpc.parseObject(ws.readFile(configRel)));
        out.add("activities", array(ws.listDir(".sketchware/data/" + id + "/file")));
        out.add("customBlocks", ws.walk(".sketchware/data/" + id + "/custom_blocks", null));
        out.add("injection", ws.walk(".sketchware/data/" + id + "/injection", null));
        out.add("resources", ws.walk(".sketchware/resources", null));
        out.add("assets", ws.walk(".sketchware/assets", null));
        out.add("fonts", ws.walk(".sketchware/fonts", null));
        out.add("sounds", ws.walk(".sketchware/sounds", null));
        return okWrapped(out);
    }

    private static JsonObject search(Workspace ws, JsonObject a) throws IOException {
        String q = require(a, "query");
        String ext = a.has("extension") ? a.get("extension").getAsString() : null;
        JsonArray hits = new JsonArray();
        var files = ws.walk(".", ext);
        for (var el : files) {
            String path = el.getAsJsonObject().get("path").getAsString();
            try {
                List<String> lines = java.nio.file.Files.readAllLines(ws.resolve(path));
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(q)) {
                        JsonObject h = new JsonObject();
                        h.addProperty("path", path);
                        h.addProperty("line", i + 1);
                        h.addProperty("text", lines.get(i).trim());
                        hits.add(h);
                        if (hits.size() >= 200) {
                            return ok("matches", hits);
                        }
                    }
                }
            } catch (IOException ignored) {
                // binary/unreadable file - skip
            }
        }
        return ok("matches", hits);
    }

    private static JsonObject addPermission(Workspace ws, JsonObject a) throws IOException {
        String manifest = require(a, "manifest");
        String permission = require(a, "permission");
        if (!permission.matches("[A-Za-z0-9_.]+")) {
            throw new IOException("Invalid permission name: " + permission);
        }
        String xml = ws.readFile(manifest);
        if (xml.contains(permission)) {
            return okStatus("permission already present");
        }
        String updated = XmlOps.insertBefore(xml, "</manifest>",
                "    <uses-permission android:name=\"" + permission + "\" />\n");
        ws.writeFile(manifest, updated);
        return okStatus("added " + permission);
    }

    private static JsonObject createSnapshot(Workspace ws, JsonObject a) throws IOException {
        ws.requireWrite();
        Path0 dir = new Path0(ws.resolve(require(a, "directory")));
        String name = a.has("name") ? string(a, "name") : ("snap-" + System.currentTimeMillis());
        Path0 target = new Path0(ws.snapshotDir().resolve(name).toAbsolutePath().normalize());
        if (!target.value.startsWith(ws.snapshotDir.toAbsolutePath().normalize().toString())) {
            throw new IOException("Bad snapshot name");
        }
        try (var walk = Files.walk(dir.value)) {
            walk.forEach(src -> {
                try {
                    Path0 rel = new Path0(dir.value.relativize(src));
                    Path0 dst = new Path0(target.value.resolve(rel.value));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst.value);
                    } else {
                        Files.createDirectories(dst.value.getParent());
                        Files.copy(src, dst.value, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return okStatus("snapshot stored at .ai_snapshots/" + name);
    }

    private static JsonObject restoreSnapshot(Workspace ws, JsonObject a) throws IOException {
        ws.requireDestructive();
        String name = require(a, "name");
        Path0 snap = new Path0(ws.snapshotDir().resolve(name).toAbsolutePath().normalize());
        if (!Files.exists(snap.value)) {
            throw new IOException("No such snapshot: " + name);
        }
        Path0 targetDir = new Path0(ws.resolve(require(a, "target")));
        try (var walk = Files.walk(snap.value)) {
            walk.filter(Files::isRegularFile).forEach(src -> {
                try {
                    Path0 rel = new Path0(snap.value.relativize(src));
                    Path0 dst = new Path0(targetDir.value.resolve(rel.value));
                    Files.createDirectories(dst.value.getParent());
                    Files.copy(src, dst.value, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return okStatus("restored " + name + " over " + targetDir.value.getFileName());
    }

    private static JsonObject generateBlockCode(Workspace ws, JsonObject a) throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("GEMINI_API_KEY environment variable is not set.");
        }
        String model = a.has("model") ? string(a, "model") : "gemini-2.0-flash";
        String prompt = GeminiClient.PROMPT_PREFIX + require(a, "description")
                + "\n\n" + BlockPlanCatalog.describe();
        String response = GeminiClient.generate(apiKey, model, prompt);
        JsonObject plan = JsonRpc.parseObject(BlockPlanCatalog.stripFences(response));
        BlockPlanCatalog.validate(plan);
        return ok("plan", plan);
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                            //
    // ------------------------------------------------------------------ //

    private static final class Path0 {
        final java.nio.file.Path value;

        Path0(java.nio.file.Path v) {
            value = v;
        }
    }

    private static Path0 projectConfig(Workspace ws, JsonObject a) throws IOException {
        return new Path0(ws.resolve(".sketchware/data/" + require(a, "projectId")
                + "/project_config"));
    }

    private static String require(JsonObject a, String key) throws IOException {
        if (a == null || !a.has(key) || !a.get(key).isJsonPrimitive()) {
            throw new IOException("Missing required argument: " + key);
        }
        return a.get(key).getAsString();
    }

    private static String string(JsonObject a, String key) {
        return a.has(key) && a.get(key).isJsonPrimitive() ? a.get(key).getAsString() : "";
    }

    private static JsonObject ok(String key, com.google.gson.JsonElement value) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.add(key, value);
        return o;
    }

    private static JsonObject okStatus(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("status", message);
        return o;
    }

    private static JsonObject okWrapped(JsonObject payload) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.add("result", payload);
        return o;
    }

    private static JsonArray array(Iterable<String> items) {
        JsonArray arr = new JsonArray();
        for (String s : items) {
            arr.add(s);
        }
        return arr;
    }

    private void register(String name, String desc, String schema, Handler h) {
        tools.put(name, new Tool(name, desc, schema, h));
    }
}
