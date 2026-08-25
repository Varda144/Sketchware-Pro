package pro.sketchware.ai.nl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Interface for converting natural-language instructions into a validated,
 * structured plan that can be mapped onto Sketchware Pro's internal model.
 */
public interface INLToBlocksConverter {

    /**
     * Asynchronously converts an instruction into a {@link NLPlan}.
     *
     * @param instruction natural-language description of wanted behavior
     * @param contextHint optional description of the current activity/event
     * @param callback    invoked exactly once
     */
    void convert(@NonNull String instruction,
                 @Nullable String contextHint,
                 @NonNull Result callback);

    /** Called when conversion finished (success or validation failure). */
    interface Result {
        void onSuccess(@NonNull NLPlan plan);

        void onError(@NonNull String safeMessage);
    }

    // ------------------------------------------------------------------ //
    //  Plan model (JSON schema v1)                                        //
    // ------------------------------------------------------------------ //

    class NLPlan {
        public int version = 1;
        public List<NLEvent> events = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
    }

    class NLEvent {
        @Nullable
        public String activity;
        /** Component id such as "button1"; empty/null for activity events. */
        @Nullable
        public String component;
        /** Event name such as "onClick", "onCreate". */
        @Nullable
        public String event;
        public List<NLBlock> blocks = new ArrayList<>();
    }

    class NLBlock {
        /** Semantic operation name, e.g. "ai_generate", "if", "show_message". */
        @Nullable
        public String op;
        public List<String> params = new ArrayList<>();
        @Nullable
        public List<NLBlock> then;
        @Nullable
        public List<NLBlock> elseThen;
    }
}
