package pro.sketchware.ai.nl;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates raw JSON returned by the model against the v1 schema and
 * {@link BlockCatalog}, producing a typed {@link INLToBlocksConverter.NLPlan}.
 *
 * Validation is strict: unknown ops, wrong arity, missing fields, or
 * non-object structures cause a descriptive validation error instead of
 * partially-usable output.
 */
public final class NLPlanValidator {

    private NLPlanValidator() {
    }

    public static final class ValidationException extends Exception {
        public ValidationException(String message) {
            super(message);
        }
    }

    /** @param allowExperimental permits ops flagged experimental in catalog */
    @NonNull
    public static INLToBlocksConverter.NLPlan validate(@NonNull String json,
                                                       boolean allowExperimental)
            throws ValidationException {
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception e) {
            throw new ValidationException("Response is not valid JSON.");
        }

        // Tolerate models wrapping output in markdown fences or extra text.
        if (!root.has("version") && !root.has("events")) {
            root = extractEmbeddedJson(json);
        }

        int version = root.optInt("version", -1);
        if (version != 1) {
            throw new ValidationException("Unsupported plan version: " + version);
        }
        JSONArray eventsArr = root.optJSONArray("events");
        if (eventsArr == null || eventsArr.length() == 0) {
            throw new ValidationException("Plan contains no events.");
        }

        INLToBlocksConverter.NLPlan plan = new INLToBlocksConverter.NLPlan();
        JSONArray warnings = root.optJSONArray("warnings");
        if (warnings != null) {
            for (int i = 0; i < warnings.length(); i++) {
                plan.warnings.add(warnings.optString(i, ""));
            }
        }

        for (int i = 0; i < eventsArr.length(); i++) {
            JSONObject e;
            try {
                e = eventsArr.getJSONObject(i);
            } catch (Exception ex) {
                throw new ValidationException("Event #" + (i + 1) + " is not an object.");
            }
            INLToBlocksConverter.NLEvent event = new INLToBlocksConverter.NLEvent();
            event.activity = emptyToNull(e.optString("activity", ""));
            event.component = emptyToNull(e.optString("component", ""));
            event.event = emptyToNull(e.optString("event", ""));
            if (event.event == null) {
                throw new ValidationException("Event #" + (i + 1) + " is missing \"event\".");
            }
            if (event.activity == null) {
                throw new ValidationException(
                        "Event #" + (i + 1) + " is missing \"activity\".");
            }
            JSONArray blocksArr = e.optJSONArray("blocks");
            if (blocksArr != null && blocksArr.length() > 0) {
                for (int b = 0; b < blocksArr.length(); b++) {
                    event.blocks.add(parseBlock(blocksArr, b, allowExperimental));
                }
            }
            plan.events.add(event);
        }
        return plan;
    }

    private static INLToBlocksConverter.NLBlock parseBlock(JSONArray arr, int index,
                                                           boolean allowExperimental)
            throws ValidationException {
        JSONObject obj;
        try {
            obj = arr.getJSONObject(index);
        } catch (Exception e) {
            throw new ValidationException("Block #" + (index + 1) + " is not an object.");
        }
        String op = obj.optString("op", "");
        BlockCatalog.Entry entry = BlockCatalog.get(op);
        if (entry == null) {
            throw new ValidationException(
                    "Unsupported block type \"" + op + "\". " + supportedList());
        }
        if (entry.experimental() && !allowExperimental) {
            throw new ValidationException(
                    "Block type \"" + op + "\" is experimental and disabled.");
        }
        JSONArray params = obj.optJSONArray("params");
        List<String> paramList = new ArrayList<>();
        if (params != null) {
            for (int p = 0; p < params.length(); p++) {
                Object v = params.opt(p);
                paramList.add(v == null ? "" : String.valueOf(v));
            }
        }
        int expected = entry.paramKinds().size();
        // Expression-typed entries may receive fewer params; commands must match.
        if (entry.subStackCount() > 0 || entry.type().equals(BlockCatalog.TYPE_COMMAND)
                || entry.type().equals(BlockCatalog.TYPE_IF_ELSE)) {
            if (paramList.size() < expected) {
                throw new ValidationException("Block \"" + op + "\" expects "
                        + expected + " parameter(s) but got " + paramList.size() + ".");
            }
        } else if (paramList.size() > expected) {
            throw new ValidationException("Block \"" + op + "\" accepts at most "
                    + expected + " parameter(s).");
        }
        for (int k = 0; k < Math.min(paramList.size(), expected); k++) {
            String kind = entry.paramKinds().get(k);
            String value = paramList.get(k);
            switch (kind) {
                case "number" -> assertNumber(op, value);
                case "boolean" -> {
                    if (!isExpression(value) && !"true".equals(value) && !"false".equals(value)) {
                        throw new ValidationException("Block \"" + op
                                + "\" parameter " + (k + 1) + " must be true/false or an expression.");
                    }
                }
                case "raw_java" -> {
                    if (value.contains("\"\"\"")) {
                        throw new ValidationException(
                                "Raw Java parameter contains illegal characters.");
                    }
                }
                default -> {
                }
            }
        }
        INLToBlocksConverter.NLBlock block = new INLToBlocksConverter.NLBlock();
        block.op = op;
        block.params = paramList;
        block.then = parseSubStack(obj.optJSONArray("then"), allowExperimental,
                "\"" + op + "\".then");
        block.elseThen = parseSubStack(obj.optJSONArray("elseThen"), allowExperimental,
                "\"" + op + "\".elseThen");
        int need = entry.subStackCount();
        if (need >= 1 && block.then == null) {
            block.then = new ArrayList<>();
        }
        if (need >= 2 && block.elseThen == null) {
            block.elseThen = new ArrayList<>();
        }
        return block;
    }

    private static List<INLToBlocksConverter.NLBlock> parseSubStack(JSONArray arr,
                                                                    boolean allowExperimental,
                                                                    String where)
            throws ValidationException {
        if (arr == null) {
            return null;
        }
        List<INLToBlocksConverter.NLBlock> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            out.add(parseNested(arr, i, allowExperimental, where));
        }
        return out;
    }

    private static INLToBlocksConverter.NLBlock parseNested(JSONArray arr, int index,
                                                            boolean allowExperimental,
                                                            String where)
            throws ValidationException {
        JSONObject obj;
        try {
            obj = arr.getJSONObject(index);
        } catch (Exception e) {
            throw new ValidationException(where + "[" + (index + 1) + "] is not an object.");
        }
        String op = obj.optString("op", "");
        BlockCatalog.Entry entry = BlockCatalog.get(op);
        if (entry == null) {
            throw new ValidationException("Unsupported block type \"" + op + "\" in "
                    + where + ". " + supportedList());
        }
        if (entry.experimental() && !allowExperimental) {
            throw new ValidationException(
                    "Block type \"" + op + "\" is experimental and disabled.");
        }
        JSONArray params = obj.optJSONArray("params");
        List<String> paramList = new ArrayList<>();
        if (params != null) {
            for (int p = 0; p < params.length(); p++) {
                Object v = params.opt(p);
                paramList.add(v == null ? "" : String.valueOf(v));
            }
        }
        INLToBlocksConverter.NLBlock block = new INLToBlocksConverter.NLBlock();
        block.op = op;
        block.params = paramList;
        block.then = parseSubStack(obj.optJSONArray("then"), allowExperimental,
                where + "." + op + ".then");
        block.elseThen = parseSubStack(obj.optJSONArray("elseThen"), allowExperimental,
                where + "." + op + ".elseThen");
        int need = entry.subStackCount();
        if (need >= 1 && block.then == null) {
            block.then = new ArrayList<>();
        }
        if (need >= 2 && block.elseThen == null) {
            block.elseThen = new ArrayList<>();
        }
        return block;
    }

    /**
     * Some models wrap the JSON in ```json fences or prose; find the first
     * {...} block that parses and contains "events".
     */
    private static JSONObject extractEmbeddedJson(String text) throws ValidationException {
        int start = text.indexOf('{');
        while (start >= 0) {
            int end = text.lastIndexOf('}');
            if (end > start) {
                String candidate = text.substring(start, end + 1);
                try {
                    JSONObject o = new JSONObject(candidate);
                    if (o.has("events") || o.has("version")) {
                        return o;
                    }
                } catch (Exception ignored) {
                }
            }
            start = text.indexOf('{', start + 1);
        }
        throw new ValidationException(
                "The AI response did not contain a structured plan.");
    }

    private static boolean isExpression(String s) {
        return !s.isEmpty() && (s.startsWith("@") || s.contains("(")
                || Character.isDigit(s.charAt(0)));
    }

    private static void assertNumber(String op, String value) throws ValidationException {
        try {
            Double.parseDouble(value);
        } catch (Exception e) {
            if (!isExpression(value)) {
                throw new ValidationException("Block \"" + op + "\" expected a number"
                        + " but got \"" + value + "\".");
            }
        }
    }

    private static String supportedList() {
        return "Supported types: " + String.join(", ", BlockCatalog.knownOps()) + ".";
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
