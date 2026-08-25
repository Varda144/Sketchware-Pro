package pro.sketchware.mcp;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WorkspaceTest {

    private Path tempDir() throws IOException {
        return Files.createTempDirectory("swmcp");
    }

    @Test
    public void findsProjects() throws IOException {
        Path root = tempDir();
        Files.createDirectories(root.resolve(".sketchware/data/601"));
        Files.writeString(root.resolve(".sketchware/data/601/project_config"), "{}");
        Workspace ws = new Workspace(root, Workspace.Mode.READ_ONLY);
        List<Workspace.ProjectRef> projects = ws.findProjects();
        assertEquals(1, projects.size());
        assertEquals("601", projects.get(0).id());
    }

    @Test
    public void rejectsTraversal() throws IOException {
        Path root = tempDir();
        Workspace ws = new Workspace(root, Workspace.Mode.READ_ONLY);
        assertThrows(IOException.class, () -> ws.readFile("../outside.txt"));
    }

    @Test
    public void readOnlyBlocksWrites() throws IOException {
        Path root = tempDir();
        Workspace ws = new Workspace(root, Workspace.Mode.READ_ONLY);
        assertThrows(IOException.class, () -> ws.writeFile("x.txt", "hi"));
    }

    @Test
    public void atomicWriteCreatesBackup() throws IOException {
        Path root = tempDir();
        Files.createDirectories(root.resolve(".sketchware/data/1"));
        Files.writeString(root.resolve(".sketchware/data/1/project_config"), "{}");
        Workspace ws = new Workspace(root, Workspace.Mode.WRITE);
        String target = ".sketchware/data/1/project_config";
        ws.writeFile(target, "{\"a\":1}");
        ws.writeFile(target, "{\"a\":2}");
        assertTrue(ws.snapshotDir().toString(), Files.list(root.resolve(".ai_snapshots"))
                .count() >= 1);
    }
}
