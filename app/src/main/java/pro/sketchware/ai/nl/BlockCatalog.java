package pro.sketchware.ai.nl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog of block operations the NL pipeline is allowed to generate.
 *
 * Every entry maps a stable semantic op name to the real Sketchware Pro
 * opCode/type/spec. Only verified opCodes are listed here; anything else is
 * rejected by {@link NLPlanValidator} so malformed AI output can never reach
 * project data.
 */
public final class BlockCatalog {

    /** Type codes used by the Sketchware block system. */
    public static final String TYPE_COMMAND = "c";
    public static final String TYPE_BOOLEAN = "b";
    public static final String TYPE_STRING = "s";
    public static final String TYPE_NUMBER = "d";
    public static final String TYPE_IF_ELSE = "e";
    public static final String TYPE_RAW = " ";

    private BlockCatalog() {
    }

    /**
     * A catalog entry.
     *
     * @param spec          display/codegen spec; placeholders follow Sketchware
     *                      conventions (%s string, %d number, %b boolean,
     *                      %m.menu)
     * @param paramKinds    expected parameter kinds per placeholder:
     *                      "string", "number", "boolean", "block" (nested
     *                      expression reference), validated loosely
     * @param subStackCount 0, 1 (then) or 2 (then/else) nested stacks
     * @param experimental  when true, generation requires explicit opt-in
     */
    public record Entry(String opCode, String type, String spec, List<String> paramKinds,
                        int subStackCount, boolean experimental) {
    }

    private static final Map<String, Entry> OPS = new LinkedHashMap<>();

    private static void register(Entry e) {
        OPS.put(e.opCode(), e);
    }

    static {
        // --- Verified core control blocks -------------------------------- //
        register(new Entry("if", TYPE_COMMAND, "if %b",
                List.of("boolean"), 1, false));
        register(new Entry("ifElse", TYPE_IF_ELSE, "ifelse %b",
                List.of("boolean"), 2, false));
        register(new Entry("repeat", TYPE_COMMAND, "repeat %d",
                List.of("number"), 1, false));
        register(new Entry("forever", TYPE_COMMAND, "repeat forever",
                List.of(), 1, false));

        // --- Verified expression helpers (used as parameters) ------------- //
        register(new Entry("true", TYPE_BOOLEAN, "true", List.of(), 0, false));
        register(new Entry("false", TYPE_BOOLEAN, "false", List.of(), 0, false));
        register(new Entry("<", TYPE_BOOLEAN, "< %d %d",
                List.of("number", "number"), 0, false));
        register(new Entry("=", TYPE_BOOLEAN, "= %s %s",
                List.of("any", "any"), 0, false));
        register(new Entry(">", TYPE_BOOLEAN, "> %d %d",
                List.of("number", "number"), 0, false));
        register(new Entry("&&", TYPE_BOOLEAN, "&& %b %b",
                List.of("boolean", "boolean"), 0, false));
        register(new Entry("||", TYPE_BOOLEAN, "|| %b %b",
                List.of("boolean", "boolean"), 0, false));
        register(new Entry("not", TYPE_BOOLEAN, "not %b",
                List.of("boolean"), 0, false));
        register(new Entry("+", TYPE_NUMBER, "+ %d %d",
                List.of("number", "number"), 0, false));
        register(new Entry("-", TYPE_NUMBER, "- %d %d",
                List.of("number", "number"), 0, false));
        register(new Entry("*", TYPE_NUMBER, "* %d %d",
                List.of("number", "number"), 0, false));
        register(new Entry("/", TYPE_NUMBER, "/ %d %d",
                List.of("number", "number"), 0, false));
        register(new Entry("stringJoin", TYPE_STRING, "join strings %s %s",
                List.of("string", "string"), 0, false));
        register(new Entry("toString", TYPE_STRING, "convert to string %s",
                List.of("any"), 0, false));
        register(new Entry("toNumber", TYPE_NUMBER, "convert to number %s",
                List.of("string"), 0, false));
        register(new Entry("stringLength", TYPE_NUMBER, "length of string %s",
                List.of("string"), 0, false));
        register(new Entry("trim", TYPE_STRING, "trim %s",
                List.of("string"), 0, false));
        register(new Entry("toUpperCase", TYPE_STRING, "uppercase %s",
                List.of("string"), 0, false));
        register(new Entry("toLowerCase", TYPE_STRING, "lowercase %s",
                List.of("string"), 0, false));

        // --- Raw Java escape hatch ---------------------------------------- //
        register(new Entry("addSourceDirectly", TYPE_RAW, "%s Java code",
                List.of("raw_java"), 0, true));

        // --- AI provider blocks (registered by AiExtraBlocks) ------------- //
        register(new Entry("aiGeminiGenerate", TYPE_COMMAND,
                "AI generate text from prompt %s then",
                List.of("string"), 1, false));
        register(new Entry("aiResponse", TYPE_STRING,
                "AI last response",
                List.of(), 0, false));
        register(new Entry("aiChatSend", TYPE_COMMAND,
                "AI chat send conversation %s then",
                List.of("string"), 1, false));
    }

    @Nullable
    public static Entry get(@NonNull String op) {
        return OPS.get(op);
    }

    @NonNull
    public static List<String> knownOps() {
        return new ArrayList<>(OPS.keySet());
    }

    /** Ops whose params may contain nested expression blocks. */
    public static boolean acceptsExpressions(@NonNull Entry entry) {
        return entry.type().equals(TYPE_BOOLEAN)
                || entry.type().equals(TYPE_STRING)
                || entry.type().equals(TYPE_NUMBER);
    }

    @NonNull
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Supported operations:\n");
        for (Map.Entry<String, Entry> e : OPS.entrySet()) {
            if (!e.getValue().experimental()) {
                sb.append("- ").append(e.getKey())
                        .append(": ").append(e.getValue().spec()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Used by tests and docs to keep prompt/validator in sync. */
    @NonNull
    public static List<Entry> entriesWhere(@NonNull java.util.function.Predicate<Entry> filter) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : OPS.values()) {
            if (filter.test(e)) {
                out.add(e);
            }
        }
        return out;
    }
}
