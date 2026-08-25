package pro.sketchware.mcp;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BlockPlanCatalogTest {

    @Test
    public void validPlanPasses() throws IOException {
        JsonObject plan = JsonRpc.parseObject("""
                {"version":1,"events":[{"activity":"MainActivity","component":"button1",
                "event":"onClick","blocks":[{"op":"if","params":["true"],"then":[
                {"op":"stringJoin","params":["a","b"]}],"elseThen":[]}]}]}""");
        BlockPlanCatalog.validate(plan);
    }

    @Test
    public void wrongVersionRejected() {
        JsonObject plan = JsonRpc.parseObject("{\"version\":2,\"events\":[]}");
        assertThrows(IOException.class, () -> BlockPlanCatalog.validate(plan));
    }

    @Test
    public void unsupportedOpRejected() {
        JsonObject plan = JsonRpc.parseObject("""
                {"version":1,"events":[{"activity":"A","event":"onClick",
                "blocks":[{"op":"launchMissiles"}]}]}""");
        IOException e = assertThrows(IOException.class,
                () -> BlockPlanCatalog.validate(plan));
        assertTrue(e.getMessage().contains("Unsupported op"));
    }

    @Test
    public void emptyEventsRejected() {
        JsonObject plan = JsonRpc.parseObject("{\"version\":1,\"events\":[]}");
        assertThrows(IOException.class, () -> BlockPlanCatalog.validate(plan));
    }

    @Test
    public void fencesStripped() {
        String raw = "```json\n{\"version\":1,\"events\":[],\"x\":1}\n```";
        assertEquals("{\"version\":1,\"events\":[],\"x\":1}",
                BlockPlanCatalog.stripFences(raw));
    }
}
