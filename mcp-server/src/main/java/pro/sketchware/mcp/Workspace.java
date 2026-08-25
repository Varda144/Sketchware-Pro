package pro.sketchware.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Controlled project/workspace boundary for all MCP tools.
 *
 * <p>Every path a tool touches is resolved against this workspace root and
 * must stay inside it. Write access requires the server to be started with
 * {@code --allow-write}; destructive operations additionally require
 * {@code --allow-destructive}.</p>
 */
public final class Workspace {

    public enum Mode {
        READ_ONLY, WRITE, DESTRUCTIVE
    }

    private final Path root;
    private final Mode mode;
    private final Path snapshotDir;

    public Workspace(Path root, Mode mode) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.mode = mode;
        Files.createDirectories(this.root);
        this.snapshotDir = this.root.resolve(".ai_snapshots");
    }

    public Path root() {
        return root;
    }

    public Path snapshotDir() {
        return snapshotDir;
    }

    public Mode mode() {
        return mode;
    }

    /** Resolves a workspace-relative path safely; rejects traversal. */
    public Path resolve(String relative) throws IOException {
        if (relative == null || relative.isEmpty()) {
            throw new IOException("Empty path given");
        }
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) {
            throw new IOException("Path escapes the workspace boundary: " + relative);
        }
        return p;
    }

    public void requireWrite() throws IOException {
        if (mode == Mode.READ_ONLY) {
            throw new IOException(
                    "Server runs read-only. Restart with --allow-write to enable writes.");
        }
    }

    public void requireDestructive() throws IOException {
        if (mode != Mode.DESTRUCTIVE) {
            throw new IOException(
                    "Operation is destructive. Restart with --allow-destructive and"
                            + " confirm explicitly.");
        }
    }

    /**
     * Atomic write with backup: writes tmp file then moves over target after
     * copying the original to .ai_snapshots/backup-<ts>-<name>.
     */
    public void atomicWrite(Path target, byte[] content) throws IOException {
        requireWrite();
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            Files.createDirectories(snapshotDir);
            String ts = Long.toString(Instant.now().toEpochMilli());
            Path backup = snapshotDir.resolve("backup-" + ts + "-" + target.getFileName());
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-mcp");
        Files.write(tmp, content);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    // ------------------------------------------------------------------ //
    //  Project discovery                                                  //
    // ------------------------------------------------------------------ //

    /** A discovered Sketchware project inside the workspace. */
    public record ProjectRef(String id, Path configPath) {
    }

    /**
     * Finds projects: directories under data/ containing project_config
     * (the on-device layout), or any directory containing project_config /
     * project_settings directly (exported layouts).
     */
    public List<ProjectRef> findProjects() throws IOException {
        List<ProjectRef> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, 4)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("project_config"))
                    .forEach(p -> {
                        Path dir = p.getParent();
                        if (dir == null || !dir.startsWith(root)) return;
                        String id = dir.getFileName().toString();
                        out.add(new ProjectRef(id, root.relativize(p)));
                    });
        }
        return out;
    }

    /** Lists immediate child files of a directory, non-recursive. */
    public List<String> listDir(String relative) throws IOException {
        Path dir = resolve(relative);
        if (!Files.isDirectory(dir)) {
            throw new IOException("Not a directory: " + relative);
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> names.add(root.relativize(p).toString()));
        }
        return names;
    }

    /** Recursive file listing with optional extension filter. */
    public JsonArray walk(String relative, String extension) throws IOException {
        Path start = resolve(relative);
        JsonArray arr = new JsonArray();
        if (!Files.exists(start)) {
            return arr;
        }
        try (Stream<Path> s = Files.walk(start)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> extension == null
                            || p.toString().toLowerCase().endsWith(extension.toLowerCase()))
                    .sorted()
                    .forEach(p -> {
                        JsonObject o = new JsonObject();
                        o.addProperty("path", root.relativize(p).toString());
                        try {
                            o.addProperty("size", Files.size(p));
                        } catch (IOException ignored) {
                        }
                        arr.add(o);
                    });
        }
        return arr;
    }

    public String readFile(String relative) throws IOException {
        Path p = resolve(relative);
        return Files.readString(p);
    }

    public void writeFile(String relative, String content) throws IOException {
        atomicWrite(resolve(relative), content.getBytes());
    }

    public void deleteFile(String relative) throws IOException {
        requireDestructive();
        Files.deleteIfExists(resolve(relative));
    }
}
