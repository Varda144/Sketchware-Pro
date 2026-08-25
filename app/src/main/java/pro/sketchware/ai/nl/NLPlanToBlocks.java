package pro.sketchware.ai.nl;

import androidx.annotation.NonNull;

import com.besome.sketch.beans.BlockBean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a validated {@link INLToBlocksConverter.NLPlan} onto real
 * {@link BlockBean} instances using the actual Sketchware Pro block model
 * (numeric string ids, linked nextBlock/subStack1/subStack2 indices).
 *
 * <p>Only catalog-registered ops are mapped; validation has already run, but
 * this class re-checks defensively so malformed data can never corrupt a
 * project.</p>
 */
public final class NLPlanToBlocks {

    private NLPlanToBlocks() {
    }

    /** Result of mapping: beans plus the highest assigned id. */
    public static final class Mapped {
        @NonNull
        public final ArrayList<BlockBean> beans;
        public final int lastId;

        Mapped(@NonNull ArrayList<BlockBean> beans, int lastId) {
            this.beans = beans;
            this.lastId = lastId;
        }
    }

    /**
     * Converts a flat list of NL blocks into a linked BlockBean chain.
     *
     * @param blocks   source blocks (top level)
     * @param startId  first id to assign; caller passes pane counter (e.g. o.g+1)
     * @return mapped beans; empty when input is empty or unmappable
     */
    @NonNull
    public static Mapped map(@NonNull List<INLToBlocksConverter.NLBlock> blocks, int startId) {
        IdGenerator gen = new IdGenerator(startId);
        ArrayList<BlockBean> out = new ArrayList<>();
        List<BlockBean> topLevel = mapList(blocks, gen, out);
        if (!topLevel.isEmpty()) {
            // Link the top-level chain through nextBlock.
            for (int i = 0; i < topLevel.size(); i++) {
                BlockBean cur = topLevel.get(i);
                cur.nextBlock = i + 1 < topLevel.size()
                        ? Integer.parseInt(topLevel.get(i + 1).id)
                        : -1;
            }
        }
        return new Mapped(out, gen.current());
    }

    private static List<BlockBean> mapList(List<INLToBlocksConverter.NLBlock> src,
                                           IdGenerator gen,
                                           ArrayList<BlockBean> sink) {
        List<BlockBean> chain = new ArrayList<>();
        if (src == null) {
            return chain;
        }
        for (INLToBlocksConverter.NLBlock nb : src) {
            BlockCatalog.Entry entry = BlockCatalog.get(nb.op == null ? "" : nb.op);
            if (entry == null) {
                continue;
            }
            BlockBean bean = new BlockBean(String.valueOf(gen.next()),
                    entry.spec(), entry.type(), entry.opCode());
            bean.parameters = new ArrayList<>();
            for (int i = 0; i < Math.min(entry.paramKinds().size(), nb.params.size()); i++) {
                String raw = nb.params.get(i);
                if (raw != null && !raw.isEmpty()) {
                    bean.parameters.add(quoteIfNeeded(entry.paramKinds().get(i), raw));
                } else {
                    bean.parameters.add("");
                }
            }
            sink.add(bean);
            chain.add(bean);
            if (nb.then != null && !nb.then.isEmpty()) {
                List<BlockBean> thenChain = mapList(nb.then, gen, sink);
                linkSubStack(bean, thenChain, 1);
            }
            if (nb.elseThen != null && !nb.elseThen.isEmpty()) {
                List<BlockBean> elseChain = mapList(nb.elseThen, gen, sink);
                linkSubStack(bean, elseChain, 2);
            }
        }
        return chain;
    }

    private static void linkSubStack(BlockBean parent, List<BlockBean> chain, int stackIndex) {
        if (chain.isEmpty()) {
            return;
        }
        for (int i = 0; i < chain.size(); i++) {
            BlockBean cur = chain.get(i);
            cur.nextBlock = i + 1 < chain.size()
                    ? Integer.parseInt(chain.get(i + 1).id)
                    : -1;
        }
        if (stackIndex == 1) {
            parent.subStack1 = Integer.parseInt(chain.get(0).id);
        } else {
            parent.subStack2 = Integer.parseInt(chain.get(0).id);
        }
    }

    private static String quoteIfNeeded(String kind, String value) {
        if ("number".equals(kind)) {
            return value;
        }
        if ("boolean".equals(kind)) {
            return value;
        }
        if ("raw_java".equals(kind)) {
            return value;
        }
        // Strings are stored quoted in Sketchware parameters.
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final class IdGenerator {
        private int id;

        IdGenerator(int start) {
            this.id = start;
        }

        int next() {
            return id++;
        }

        int current() {
            return id - 1;
        }
    }

    /** Convenience used by tests. */
    @NonNull
    public static Map<String, Integer> debugCounts(@NonNull ArrayList<BlockBean> beans) {
        Map<String, Integer> counts = new HashMap<>();
        for (BlockBean b : beans) {
            counts.merge(b.opCode, 1, Integer::sum);
        }
        return counts;
    }
}
