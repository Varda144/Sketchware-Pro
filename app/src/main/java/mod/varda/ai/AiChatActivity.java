package mod.varda.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public class AiChatActivity extends Activity {

    private TextView historyView, statusView;
    private EditText inputView;
    private Button sendButton, streamButton, mcpButton;
    private ScrollView historyScroll;
    private final List<String[]> history = new ArrayList<>();
    private boolean isStreaming = false;

    private int dp(float v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    private EditText field(String hint, String value, boolean pw) {
        EditText et = new EditText(this);
        et.setHint(hint);
        if (value != null) et.setText(value);
        if (pw) et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setSingleLine(true);
        return et;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(12));

        TextView title = new TextView(this);
        title.setText("AI Provider & MCP");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setPadding(0, dp(4), 0, dp(8));
        root.addView(statusView);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        Button settingsBtn = new Button(this);
        settingsBtn.setText("Settings");
        settingsBtn.setTextSize(12);
        settingsBtn.setOnClickListener(v -> showSettingsDialog());
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.setMarginEnd(dp(4));
        btns.addView(settingsBtn, lp1);
        mcpButton = new Button(this);
        mcpButton.setText("MCP: OFF");
        mcpButton.setTextSize(12);
        mcpButton.setOnClickListener(v -> toggleMcp());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.setMarginEnd(dp(4));
        btns.addView(mcpButton, lp2);
        streamButton = new Button(this);
        streamButton.setText("Stream");
        streamButton.setTextSize(12);
        streamButton.setOnClickListener(v -> { isStreaming = !isStreaming; streamButton.setText(isStreaming ? "Stream ON" : "Stream"); });
        btns.addView(streamButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(btns);

        historyScroll = new ScrollView(this);
        historyScroll.setFillViewport(true);
        historyView = new TextView(this);
        historyView.setTextIsSelectable(true);
        historyView.setMovementMethod(new ScrollingMovementMethod());
        historyView.setPadding(0, dp(8), 0, dp(8));
        historyView.setText("AI Provider & MCP Server\n========================\n\nAI: Build complete apps (frontend + backend), fix errors, add ads.\nMCP: File access, project data, shell, AI chat for agents.\n\nType a message or use Settings to configure.\n");
        historyScroll.addView(historyView);
        root.addView(historyScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        inputView = new EditText(this);
        inputView.setHint("Ask AI to build something...");
        inputView.setMaxLines(4);
        root.addView(inputView);

        sendButton = new Button(this);
        sendButton.setText("Send");
        sendButton.setOnClickListener(v -> sendMessage());
        root.addView(sendButton);

        setContentView(root);
        refreshStatus();
        if (AiProviderConfig.isMcpEnabled(this) && !McpServer.isRunning()) {
            McpServer.start(this);
            refreshStatus();
        }
    }

    private void toggleMcp() {
        if (McpServer.isRunning()) { McpServer.stop(); AiProviderConfig.setMcpEnabled(this, false); }
        else { boolean ok = McpServer.start(this); AiProviderConfig.setMcpEnabled(this, ok); Toast.makeText(this, ok ? "MCP started" : "MCP failed", Toast.LENGTH_SHORT).show(); }
        refreshStatus();
    }

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Model: ").append(AiProviderConfig.getModel(this));
        if (AiProviderConfig.getApiKey(this).isEmpty()) sb.append(" | NO KEY");
        sb.append(" | ").append(isStreaming ? "Stream" : "Sync").append("\n");
        if (McpServer.isRunning()) {
            String tok = AiProviderConfig.getMcpToken(this).isEmpty() ? "open" : "token";
            sb.append("MCP: http://").append(localIp()).append(":").append(AiProviderConfig.getMcpPort(this)).append("/mcp (").append(tok).append(")");
        } else sb.append("MCP: stopped");
        statusView.setText(sb.toString());
    }

    private String localIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            ArrayList<String> found = new ArrayList<>();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a.getAddress().length == 4) found.add(a.getHostAddress());
                }
            }
            Collections.sort(found);
            return found.isEmpty() ? "127.0.0.1" : found.get(found.size() - 1);
        } catch (Exception e) { return "127.0.0.1"; }
    }

    private void sendMessage() {
        String prompt = inputView.getText().toString().trim();
        if (prompt.isEmpty()) return;
        inputView.setText("");
        appendHistory("You", prompt);
        setBusy(true);
        List<String[]> snap = new ArrayList<>(history);
        int start = Math.max(0, snap.size() - AiProviderConfig.getMaxHistory(this));
        List<String[]> sub = snap.subList(start, snap.size());

        if (isStreaming) {
            AiProvider.chatStream(this, sub, prompt, new AiProvider.StreamCallback() {
                private final StringBuilder full = new StringBuilder();
                public void onToken(String t) { full.append(t); historyView.setText(historyView.getText().toString().split("\n__STREAM__")[0] + "\n__STREAM__" + full); }
                public void onDone(String r) { setBusy(false); history.add(new String[]{"assistant", r}); historyView.setText(historyView.getText().toString().split("\n__STREAM__")[0]); appendLine("AI", r); }
                public void onError(String e) { setBusy(false); appendLine("ERROR", e); }
            });
        } else {
            AiProvider.chat(this, sub, prompt, (answer, error) -> {
                setBusy(false);
                if (error != null) appendLine("ERROR", error);
                else appendHistory("AI", answer);
            });
        }
    }

    private void setBusy(boolean b) { inputView.setEnabled(!b); sendButton.setEnabled(!b); sendButton.setText(b ? "Thinking..." : "Send"); }
    private void appendHistory(String who, String t) { historyView.append("\n" + who + ": " + t + "\n"); history.add(new String[]{who.equals("You") ? "user" : "assistant", t}); }
    private void appendLine(String who, String t) { historyView.append(who + ": " + t + "\n\n"); }

    private void showSettingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, 0);
        EditText ep = field("Endpoint URL", AiProviderConfig.getEndpoint(this), false); box.addView(ep);
        EditText ak = field("API key", AiProviderConfig.getApiKey(this), true); box.addView(ak);
        EditText md = field("Model (e.g. gpt-4o-mini)", AiProviderConfig.getModel(this), false); box.addView(md);
        EditText pp = field("MCP port", String.valueOf(AiProviderConfig.getMcpPort(this)), false); pp.setInputType(InputType.TYPE_CLASS_NUMBER); box.addView(pp);
        EditText tk = field("MCP token (empty=open)", AiProviderConfig.getMcpToken(this), false); box.addView(tk);
        EditText sp = field("System prompt", AiProviderConfig.getSystemPrompt(this), false); box.addView(sp);

        new AlertDialog.Builder(this).setTitle("Provider & MCP Settings").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    AiProviderConfig.setEndpoint(this, ep.getText().toString().trim());
                    AiProviderConfig.setApiKey(this, ak.getText().toString());
                    AiProviderConfig.setModel(this, md.getText().toString());
                    AiProviderConfig.setMcpToken(this, tk.getText().toString());
                    AiProviderConfig.setSystemPrompt(this, sp.getText().toString());
                    try { AiProviderConfig.setMcpPort(this, Integer.parseInt(pp.getText().toString().trim())); } catch (NumberFormatException ignored) {}
                    if (McpServer.isRunning()) { McpServer.stop(); McpServer.start(this); }
                    refreshStatus();
                }).setNegativeButton("Cancel", null).show();
    }
}
