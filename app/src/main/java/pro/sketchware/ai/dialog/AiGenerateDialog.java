package pro.sketchware.ai.dialog;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import pro.sketchware.ai.GeminiApiService;
import pro.sketchware.ai.nl.INLToBlocksConverter;

/**
 * "Generate from text" dialog for the logic editor. Asks for a natural
 * language instruction, calls the active AI provider through
 * {@code GeminiNLToBlocksConverter} and hands the resulting plan back to the
 * {@link LogicEditorActivity} for insertion.
 */
public final class AiGenerateDialog {

    public interface OnPlanReady {
        void onPlan(@NonNull INLToBlocksConverter.NLPlan plan);
    }

    private AiGenerateDialog() {
    }

    @SuppressLint("InflateParams")
    public static void show(@NonNull Activity activity,
                            @NonNull String contextHint,
                            @NonNull OnPlanReady onPlanReady) {
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, 0);

        final EditText input = new EditText(activity);
        input.setHint("Describe what the blocks should do…");
        input.setMinLines(3);
        input.setMaxLines(8);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        container.addView(input);

        final TextView status = new TextView(activity);
        status.setVisibility(View.GONE);
        container.addView(status);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle("Generate from text")
                .setView(container)
                .setNegativeButton("Cancel", null);

        Dialog dialog = builder.create();

        dialog.setOnShowListener(d -> {
            try {
                ((Button) dialog.getButton(AlertDialog.BUTTON_POSITIVE))
                        .setVisibility(View.GONE);
                ((Button) dialog.getButton(AlertDialog.BUTTON_NEGATIVE))
                        .setText("Close");
            } catch (Exception ignored) {
            }
            Button run = new Button(activity);
            run.setText("Generate");
            container.addView(run, 0);
            run.setOnClickListener(v -> {
                String instruction = input.getText() == null ? "" : input.getText().toString().trim();
                if (instruction.isEmpty()) {
                    return;
                }
                if (!GeminiApiService.isConfigured(activity)) {
                    status.setVisibility(View.VISIBLE);
                    status.setText("No API key configured. Open Settings > AI Provider first.");
                    return;
                }
                run.setEnabled(false);
                input.setEnabled(false);
                status.setVisibility(View.VISIBLE);
                status.setText("Generating blocks…");

                new GeminiNLToBlocksAdapter(activity).convert(instruction, contextHint,
                        new INLToBlocksConverter.Result() {
                            @Override
                            public void onSuccess(@NonNull INLToBlocksConverter.NLPlan plan) {
                                activity.runOnUiThread(() -> {
                                    dialog.dismiss();
                                    onPlanReady.onPlan(plan);
                                });
                            }

                            @Override
                            public void onError(@NonNull String safeMessage) {
                                activity.runOnUiThread(() -> {
                                    run.setEnabled(true);
                                    input.setEnabled(true);
                                    status.setText(safeMessage);
                                });
                            }
                        });
            });
        });

        dialog.show();
    }

    /** Thin wrapper exposing the converter without leaking its constructor. */
    private static final class GeminiNLToBlocksAdapter extends pro.sketchware.ai.nl.GeminiNLToBlocksConverter {
        GeminiNLToBlocksAdapter(@NonNull Activity activity) {
            super(activity.getApplicationContext());
        }
    }
}
