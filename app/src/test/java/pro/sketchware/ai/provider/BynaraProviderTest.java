package pro.sketchware.ai.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class BynaraProviderTest {

    @Test
    public void presetId() {
        assertEquals("bynara", BynaraProvider.ID);
    }

    @Test
    public void presetOpenAiCompatibleBase() {
        // byNara exposes a standard OpenAI-compatible v1 chat-completions API.
        assertEquals("https://bynara.id/v1", BynaraProvider.PRESET_BASE);
    }

    @Test
    public void defaultModelConfigured() {
        assertFalse(BynaraProvider.DEFAULT_MODEL.isEmpty());
    }

    @Test
    public void extendsOpenAiCompatibleProvider() {
        assertEquals(OpenAiCompatibleProvider.class,
                BynaraProvider.class.getSuperclass());
    }
}
