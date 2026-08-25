package pro.sketchware.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import pro.sketchware.ai.nl.BlockCatalog;

public class BlockCatalogTest {

    @Test
    public void coreOpsRegistered() {
        assertNotNull(BlockCatalog.get("if"));
        assertNotNull(BlockCatalog.get("ifElse"));
        assertNotNull(BlockCatalog.get("aiGeminiGenerate"));
        assertNotNull(BlockCatalog.get("aiResponse"));
    }

    @Test
    public void unknownOpNull() {
        assertNull(BlockCatalog.get("doesNotExist"));
    }

    @Test
    public void substackCounts() {
        assertEquals(1, BlockCatalog.get("aiGeminiGenerate").subStackCount());
        assertEquals(2, BlockCatalog.get("ifElse").subStackCount());
        assertEquals(0, BlockCatalog.get("aiResponse").subStackCount());
    }
}
