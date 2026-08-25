package pro.sketchware.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import pro.sketchware.ai.nl.BlockCatalog;
import pro.sketchware.ai.nl.GeminiNLToBlocksConverter;
import pro.sketchware.ai.nl.NLPlanToBlocks;
import pro.sketchware.ai.nl.NLPlanValidator;

import com.besome.sketch.beans.BlockBean;

import org.junit.Test;

import java.util.List;
import java.util.Map;

public class NLPlanValidatorTest {

    private static final String VALID = """
            {"version":1,
             "events":[{"activity":"MainActivity","component":"button1","event":"onClick",
               "blocks":[
                 {"op":"aiGeminiGenerate","params":["\\"Hello\\""],"then":[
                    {"op":"if","params":["true"],"then":[{"op":"toString","params":["1"]}],"elseThen":[]}
                 ]}
               ]}]}""";

    @Test
    public void validPlanParses() throws Exception {
        var plan = GeminiNLToBlocksConverter.parseValidated(VALID, false);
        assertEquals(1, plan.events.size());
        assertEquals("MainActivity", plan.events.get(0).activity);
        assertEquals(1, plan.events.get(0).blocks.size());
        assertNotNull(plan.events.get(0).blocks.get(0).then);
    }

    @Test
    public void malformedJsonRejected() {
        assertThrows(NLPlanValidator.ValidationException.class,
                () -> GeminiNLToBlocksConverter.parseValidated("{not json", false));
    }

    @Test
    public void unsupportedBlockRejected() {
        String bad = """
                {"version":1,"events":[{"activity":"A","event":"onClick",
                  "blocks":[{"op":"launchNukes"}]}]}""";
        var e = assertThrows(NLPlanValidator.ValidationException.class,
                () -> GeminiNLToBlocksConverter.parseValidated(bad, false));
        assertTrue(e.getMessage().contains("Unsupported block type"));
    }

    @Test
    public void missingRequiredFieldRejected() {
        String bad = """
                {"version":1,"events":[{"activity":"A",
                  "blocks":[]}]}""";
        assertThrows(NLPlanValidator.ValidationException.class,
                () -> GeminiNLToBlocksConverter.parseValidated(bad, false));
    }

    @Test
    public void wrongVersionRejected() {
        String bad = "{\"version\":3,\"events\":[]}";
        assertThrows(NLPlanValidator.ValidationException.class,
                () -> GeminiNLToBlocksConverter.parseValidated(bad, false));
    }

    @Test
    public void fencedJsonExtracted() throws Exception {
        String fenced = "```json\n" + VALID + "\n```";
        var plan = GeminiNLToBlocksConverter.parseValidated(fenced, false);
        assertEquals(1, plan.events.size());
    }

    @Test
    public void experimentalBlockedByDefault() {
        String bad = """
                {"version":1,"events":[{"activity":"A","event":"onClick",
                  "blocks":[{"op":"addSourceDirectly","params":["int x=1;"]}]}]}""";
        assertThrows(NLPlanValidator.ValidationException.class,
                () -> GeminiNLToBlocksConverter.parseValidated(bad, false));
        // allowed with opt-in
        assertDoesNotThrow(bad, true);
    }

    private void assertDoesNotThrow(String bad, boolean allow) {
        try {
            GeminiNLToBlocksConverter.parseValidated(bad, allow);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
