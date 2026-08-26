package pro.sketchware.ai.nl;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import pro.sketchware.ai.AiModels;
import pro.sketchware.ai.GeminiApiService;
import pro.sketchware.ai.AiProviderRegistry;
import pro.sketchware.ai.secure.AiSecureStore;

/**
 * {@link INLToBlocksConverter} implementation that prompts the active AI
 * provider with a constrained, schema-described prompt and validates the
 * result through {@link NLPlanValidator}.
 */
public final class GeminiNLToBlocksConverter implements INLToBlocksConverter {

    private static final String SYSTEM_PROMPT =
            "You are a code generator embedded in Sketchware Pro. Convert the user's "
                    + "natural-language instruction into a JSON plan. "
                    + "Reply with ONLY a JSON object - no markdown fences, no prose.\n"
                    + "Schema:\n"
                    + "{\n"
                    + "  \"version\": 1,\n"
                    + "  \"events\": [\n"
                    + "    {\n"
                    + "      \"activity\": \"MainActivity\",\n"
                    + "      \"component\": \"button1\" or null for activity events,\n"
                    + "      \"event\": \"onClick\" | \"onCreate\" | ...,\n"
                    + "      \"blocks\": [ { \"op\": ..., \"params\": [...],\n"
                    + "                     \"then\": [blocks], \"elseThen\": [blocks] } ]\n"
                    + "    }\n"
                    + "  ],\n"
                    + "  \"warnings\": [optional strings]\n"
                    + "}\n\n"
                    + "Rules:\n"
                    + "- Use ONLY operations from the provided catalog.\n"
                    + "- Never invent Java code unless the op is add_source_directly.\n"
                    + "- Prefer minimal blocks that satisfy the request.\n"
                    + "- If the request cannot be expressed, return an empty events array"
                    + " and explain via warnings.\n\n"
                    + "Catalog:\n";

    private static final int MAX_ATTEMPTS = 2;

    @NonNull
    private final Context context;
    private final boolean allowExperimental;

    public GeminiNLToBlocksConverter(@NonNull Context context) {
        this(context, false);
    }

    public GeminiNLToBlocksConverter(@NonNull Context context, boolean allowExperimental) {
        this.context = context.getApplicationContext();
        this.allowExperimental = allowExperimental;
    }

    @Override
    public void convert(@NonNull String instruction,
                        @Nullable String contextHint,
                        @NonNull Result callback) {
        if (!GeminiApiService.isConfigured(context)) {
            callback.onError("No API key configured. Set one in Settings > AI Provider.");
            return;
        }
        String userPrompt = buildUserPrompt(instruction, contextHint);
        attempt(userPrompt, 1, callback);
    }

    private void attempt(String userPrompt, int attemptNumber, Result callback) {
        GeminiApiService.generateText(context, userPrompt, new AiModels.Callback() {
            @Override
            public void onSuccess(@NonNull AiModels.GenerateResult result) {
                try {
                    INLToBlocksConverter.NLPlan plan =
                            NLPlanValidator.validate(result.text, allowExperimental);
                    if (plan.events.isEmpty()) {
                        String w = plan.warnings.isEmpty()
                                ? "The AI could not express this as blocks."
                                : String.join("; ", plan.warnings);
                        callback.onError(w);
                        return;
                    }
                    callback.onSuccess(plan);
                } catch (NLPlanValidator.ValidationException e) {
                    if (attemptNumber < MAX_ATTEMPTS) {
                        attempt(userPrompt + "\n\nYour previous reply was rejected: "
                                        + e.getMessage() + "\nReturn corrected JSON only.",
                                attemptNumber + 1, callback);
                    } else {
                        callback.onError("AI output failed validation: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onError(@NonNull AiModels.AiException error) {
                callback.onError(error.getMessage());
            }
        });
    }

    private String buildUserPrompt(String instruction, String contextHint) {
        StringBuilder sb = new StringBuilder();
        String model = AiSecureStore.get(context)
                .getModel(AiProviderRegistry.active(context).id(),
                        AiProviderRegistry.active(context).defaultModel());
        sb.append("Target model: ").append(model).append('\n');
        if (contextHint != null && !contextHint.isEmpty()) {
            sb.append("Current editor context: ").append(contextHint).append('\n');
        }
        sb.append("Instruction: ").append(instruction).append('\n');
        sb.append(BlockCatalog.describe());
        return sb.toString();
    }

    /** Exposed for tests: validates without network. */
    @NonNull
    public static INLToBlocksConverter.NLPlan parseValidated(@NonNull String json,
                                                             boolean allowExperimental)
            throws NLPlanValidator.ValidationException {
        return NLPlanValidator.validate(json, allowExperimental);
    }
}
