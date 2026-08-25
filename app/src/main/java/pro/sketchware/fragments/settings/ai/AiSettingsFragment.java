package pro.sketchware.fragments.settings.ai;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import pro.sketchware.ai.AiModels;
import pro.sketchware.ai.AiProviderRegistry;
import pro.sketchware.ai.GeminiApiService;
import pro.sketchware.ai.provider.AiProvider;
import pro.sketchware.ai.secure.AiSecureStore;
import pro.sketchware.databinding.FragmentSettingsAiBinding;
import a.a.a.qA;
import pro.sketchware.utility.SketchwareUtil;

public class AiSettingsFragment extends qA {

    private FragmentSettingsAiBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsAiBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupToolbar();
        setupInsets();
        loadState();
        setupClickListeners();
    }

    private void setupToolbar() {
        binding.toolbar.setNavigationOnClickListener(v -> {
            if (requireActivity().getSupportFragmentManager().getBackStackEntryCount() > 0) {
                requireActivity().getSupportFragmentManager().popBackStack();
            } else {
                requireActivity().onBackPressed();
            }
        });
    }

    private void setupInsets() {
        View content = binding.content;
        int left = content.getPaddingLeft();
        int top = content.getPaddingTop();
        int right = content.getPaddingRight();
        int bottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, i) -> {
            Insets insets = i.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(left + insets.left, top + insets.top,
                    right + insets.right, bottom + insets.bottom);
            return v;
        });
        View appBar = binding.appBarLayout;
        int aL = appBar.getPaddingLeft();
        int aT = appBar.getPaddingTop();
        int aR = appBar.getPaddingRight();
        int aB = appBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(appBar, (v, i) -> {
            Insets insets = i.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(aL + insets.left, aT + insets.top, aR + insets.right, aB);
            return v;
        });
    }

    private AiProvider selectedProvider() {
        return AiProviderRegistry.byId(requireContext(),
                binding.providerOpenaiCompatible.isChecked()
                        ? "openai_compatible"
                        : "gemini");
    }

    private void loadState() {
        AiSecureStore store = AiSecureStore.get(requireContext());
        AiProvider active = AiProviderRegistry.active(requireContext());
        if ("openai_compatible".equals(active.id())) {
            binding.providerOpenaiCompatible.setChecked(true);
        } else {
            binding.providerGemini.setChecked(true);
        }
        binding.inputModel.setText(store.getModel(active.id(), ""));
        updateKeyStatus();
        if (!store.isEncrypted()) {
            binding.keyStatus.append("\nWarning: encrypted storage unavailable on this device;"
                    + " the key is stored unencrypted.");
        }
    }

    private void updateKeyStatus() {
        AiSecureStore store = AiSecureStore.get(requireContext());
        AiProvider provider = selectedProvider();
        String masked = store.getMaskedKey(provider.id());
        binding.keyStatus.setText(masked.isEmpty()
                ? "No API key configured for " + provider.displayName() + "."
                : "Stored key: " + masked);
    }

    private void setupClickListeners() {
        binding.providerGroup.setOnCheckedChangeListener((group, checkedId) -> {
            AiProvider provider = selectedProvider();
            AiSecureStore store = AiSecureStore.get(requireContext());
            binding.inputModel.setText(store.getModel(provider.id(), ""));
            binding.inputApiKey.setText("");
            updateKeyStatus();
        });

        binding.btnSave.setOnClickListener(v -> save());

        binding.btnClearKey.setOnClickListener(v -> {
            AiSecureStore store = AiSecureStore.get(requireContext());
            store.setApiKey(selectedProvider().id(), null);
            binding.inputApiKey.setText("");
            updateKeyStatus();
            SketchwareUtil.toast("API key cleared.");
        });

        binding.btnTest.setOnClickListener(v -> testConnection());
    }

    private void save() {
        AiProvider provider = selectedProvider();
        AiSecureStore store = AiSecureStore.get(requireContext());
        String key = binding.inputApiKey.getText() == null
                ? "" : binding.inputApiKey.getText().toString().trim();
        if (!key.isEmpty()) {
            if (key.length() < 12) {
                SketchwareUtil.toastError("That API key looks too short to be valid.");
                return;
            }
            store.setApiKey(provider.id(), key);
        }
        store.setModel(provider.id(),
                binding.inputModel.getText() == null ? ""
                        : binding.inputModel.getText().toString().trim());
        AiProviderRegistry.setActive(requireContext(), provider.id());
        binding.inputApiKey.setText("");
        updateKeyStatus();
        SketchwareUtil.toast("Saved. Active provider: " + provider.displayName());
    }

    private void testConnection() {
        AiProvider provider = selectedProvider();
        if (!provider.isConfigured()) {
            binding.testResult.setText("No API key saved for this provider yet.");
            return;
        }
        binding.testResult.setText("Testing…");
        provider.generate(
                AiModels.GenerateRequest.singlePrompt("Reply with exactly: OK"),
                new AiModels.Callback() {
                    @Override
                    public void onSuccess(@NonNull AiModels.GenerateResult result) {
                        requireActivity().runOnUiThread(() ->
                                binding.testResult.setText("Success! Model replied: "
                                        + result.text));
                    }

                    @Override
                    public void onError(@NonNull AiModels.AiException error) {
                        requireActivity().runOnUiThread(() ->
                                binding.testResult.setText(error.getMessage()));
                    }
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
