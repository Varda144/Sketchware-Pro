package pro.sketchware.ai.chat;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.besome.sketch.lib.base.BaseAppCompatActivity;

import java.util.ArrayList;
import java.util.List;

import pro.sketchware.ai.AiModels;
import pro.sketchware.ai.AiProviderRegistry;
import pro.sketchware.ai.GeminiApiService;
import pro.sketchware.ai.provider.AiProvider;
import pro.sketchware.R;
import pro.sketchware.databinding.ActivityAiChatBinding;
import pro.sketchware.databinding.ItemAiChatMessageBinding;

/**
 * Multi-turn AI assistant chat.
 *
 * Opens from the app settings ("AI Chat") without a project, or from inside a
 * project editor with the current project's context attached so the AI can
 * answer questions about that project. Conversations route through the
 * currently-active AI provider and the secure key store.
 */
public final class AiChatActivity extends BaseAppCompatActivity {

    public static final String EXTRA_SC_ID = "sc_id";
    public static final String EXTRA_CONTEXT_HINT = "context_hint";

    private ActivityAiChatBinding binding;
    private final List<AiModels.Message> history = new ArrayList<>();
    private MessageAdapter adapter;
    private boolean sending;

    public static void open(@NonNull Context context, @Nullable String scId,
                            @Nullable String contextHint) {
        Intent intent = new Intent(context, AiChatActivity.class);
        if (scId != null) {
            intent.putExtra(EXTRA_SC_ID, scId);
        }
        if (contextHint != null && !contextHint.isEmpty()) {
            intent.putExtra(EXTRA_CONTEXT_HINT, contextHint);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        enableEdgeToEdgeNoContrast();
        super.onCreate(savedInstanceState);
        binding = ActivityAiChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbar.setNavigationOnClickListener(v -> onBackPressed());

        String scId = getIntent().getStringExtra(EXTRA_SC_ID);
        String contextHint = getIntent().getStringExtra(EXTRA_CONTEXT_HINT);
        if (scId != null) {
            binding.toolbar.setTitle("AI Chat · project");
        }

        seedSystemContext(scId, contextHint);

        adapter = new MessageAdapter(history);
        binding.messageList.setLayoutManager(new LinearLayoutManager(this));
        binding.messageList.setAdapter(adapter);
        adapter.notifyDataSetChanged();
        scrollToBottom();

        binding.btnSend.setOnClickListener(v -> sendCurrentMessage());
        updateStatus();
    }

    private void seedSystemContext(@Nullable String scId, @Nullable String contextHint) {
        AiProvider provider = AiProviderRegistry.active(this);
        String providerName = provider.displayName();

        StringBuilder system = new StringBuilder();
        system.append("You are the AI assistant built into Sketchware Pro. ")
                .append("Help the user with app development in Sketchware Pro: explain concepts, ")
                .append("write block plans, generate code, or answer questions. ")
                .append("Be concise and practical. Current provider: ")
                .append(providerName).append('.');
        if (contextHint != null && !contextHint.isEmpty()) {
            system.append("\n\nCurrent project context: ").append(contextHint);
        }
        history.add(new AiModels.Message(AiModels.Message.Role.SYSTEM, system.toString()));
    }

    private void updateStatus() {
        if (!GeminiApiService.isConfigured(this)) {
            binding.statusText.setVisibility(View.VISIBLE);
            binding.statusText.setText("No AI provider is configured. Open Settings > AI Provider "
                    + "to add an API key for Gemini, byNara, or another provider, then return here.");
        } else {
            binding.statusText.setVisibility(View.GONE);
        }
    }

    private void sendCurrentMessage() {
        String text = binding.inputMessage.getText() == null
                ? "" : binding.inputMessage.getText().toString().trim();
        if (text.isEmpty() || sending) {
            return;
        }
        if (!GeminiApiService.isConfigured(this)) {
            updateStatus();
            return;
        }
        sending = true;
        setInputEnabled(false);

        history.add(new AiModels.Message(AiModels.Message.Role.USER, text));
        binding.inputMessage.getText().clear();
        adapter.notifyItemInserted(history.size() - 1);
        scrollToBottom();
        binding.statusText.setVisibility(View.VISIBLE);
        binding.statusText.setText("Thinking…");

        GeminiApiService.chat(this, history, null, null, new AiModels.Callback() {
            @Override
            public void onSuccess(@NonNull AiModels.GenerateResult result) {
                runOnUiThread(() -> {
                    history.add(new AiModels.Message(
                            AiModels.Message.Role.MODEL, result.text));
                    adapter.notifyItemInserted(history.size() - 1);
                    scrollToBottom();
                    sending = false;
                    setInputEnabled(true);
                    binding.statusText.setVisibility(View.GONE);
                });
            }

            @Override
            public void onError(@NonNull AiModels.AiException error) {
                runOnUiThread(() -> {
                    int removedIndex = history.size() - 1;
                    history.remove(removedIndex);
                    adapter.notifyItemRemoved(removedIndex);
                    sending = false;
                    setInputEnabled(true);
                    binding.statusText.setVisibility(View.VISIBLE);
                    binding.statusText.setText("Error: " + error.getMessage());
                });
            }
        });
    }

    private void setInputEnabled(boolean enabled) {
        binding.inputMessage.setEnabled(enabled);
        binding.btnSend.setEnabled(enabled);
    }

    private void scrollToBottom() {
        binding.messageList.post(() ->
                binding.messageList.scrollToPosition(adapter.getItemCount() - 1));
    }

    @Override
    public void onDestroy() {
        binding = null;
        super.onDestroy();
    }

    static final class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.Holder> {

        private final List<AiModels.Message> messages;

        MessageAdapter(@NonNull List<AiModels.Message> messages) {
            this.messages = messages;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemAiChatMessageBinding binding =
                    ItemAiChatMessageBinding.inflate(LayoutInflater.from(parent.getContext()),
                            parent, false);
            return new Holder(binding);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(messages.get(position));
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            private final ItemAiChatMessageBinding binding;

            Holder(@NonNull ItemAiChatMessageBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }

            void bind(@NonNull AiModels.Message message) {
                if (message.role == AiModels.Message.Role.SYSTEM) {
                    binding.getRoot().setVisibility(View.GONE);
                    return;
                }
                binding.getRoot().setVisibility(View.VISIBLE);
                binding.messageBubble.setText(message.content);

                boolean user = message.role == AiModels.Message.Role.USER;
                binding.messageRow.setGravity(user
                        ? android.view.Gravity.END : android.view.Gravity.START);
                binding.messageBubble.setBackgroundResource(
                        user ? R.drawable.chat_bubble_user : R.drawable.chat_bubble_assistant);
            }
        }
    }
}
