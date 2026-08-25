package pro.sketchware.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.besome.sketch.beans.BlockBean;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import pro.sketchware.ai.nl.INLToBlocksConverter;
import pro.sketchware.ai.nl.NLPlanToBlocks;

public class NLPlanToBlocksTest {

    @Test
    public void mapsChainWithSubstack() {
        List<INLToBlocksConverter.NLBlock> blocks = new ArrayList<>();

        INLToBlocksConverter.NLBlock gen = new INLToBlocksConverter.NLBlock();
        gen.op = "aiGeminiGenerate";
        gen.params.add("\"Say hi\"");
        INLToBlocksConverter.NLBlock inner = new INLToBlocksConverter.NLBlock();
        inner.op = "if";
        inner.params.add("true");
        INLToBlocksConverter.NLBlock expr = new INLToBlocksConverter.NLBlock();
        expr.op = "toString";
        expr.params.add("\"1\"");
        inner.then = new ArrayList<>();
        inner.then.add(expr);
        gen.then = new ArrayList<>();
        gen.then.add(inner);
        blocks.add(gen);

        NLPlanToBlocks.Mapped mapped = NLPlanToBlocks.map(blocks, 100);

        assertEquals(3, mapped.beans.size());
        Map<String, Integer> counts = NLPlanToBlocks.debugCounts(mapped.beans);
        assertEquals(1, counts.get("aiGeminiGenerate").intValue());
        assertEquals(1, counts.get("if").intValue());
        assertEquals(1, counts.get("toString").intValue());

        BlockBean root = find(mapped.beans, "aiGeminiGenerate");
        assertTrue(root.subStack1 >= 0);
        BlockBean ifBean = find(mapped.beans, "if");
        assertEquals(root.subStack1, Integer.parseInt(ifBean.id));
        assertEquals(-1, ifBean.nextBlock);
    }

    private BlockBean find(ArrayList<BlockBean> beans, String opCode) {
        for (BlockBean b : beans) {
            if (b.opCode.equals(opCode)) {
                return b;
            }
        }
        throw new AssertionError("missing " + opCode);
    }
}
