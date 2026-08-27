package mod.varda.ai;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Centralized config for the AI Provider & MCP server.
 * Stored in SharedPreferences, editable from AiChatActivity settings dialog.
 */
public class AiProviderConfig {

    private static final String PREFS = "varda_ai_config";

    public static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";
    public static final String DEFAULT_SYSTEM_PROMPT =
            "You are an expert Android developer assistant embedded in Sketchware Pro IDE.\n"
            + "You help users build complete Android apps — frontend (XML layouts, Views, Activities) "
            + "and backend (Java/Kotlin logic, Events, Components, API calls, databases).\n\n"
            + "Sketchware Pro uses a block-based editor where:\n"
            + "- Views are XML layouts with widgets (Button, TextView, EditText, ImageView, ListView, etc.)\n"
            + "- Events are Java code triggered by user actions (onClick, onResume, onCreate, etc.)\n"
            + "- Components are pre-built modules (Intent, SharedPreferences, Firebase, MediaPlayer, "
            + "InterstitialAd, RewardedAd, etc.)\n\n"
            + "When the user asks you to build something:\n"
            + "1. Generate complete, compilable Java code for Events\n"
            + "2. Describe the View hierarchy needed\n"
            + "3. List which Components to add\n"
            + "4. If ads are requested, include AdMob InterstitialAd/RewardedAd setup with test IDs\n"
            + "5. For errors, explain the fix clearly and provide corrected code\n\n"
            + "Always output code in code blocks. Be concise but complete.";

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getEndpoint(Context c) {
        return prefs(c).getString("endpoint", DEFAULT_ENDPOINT);
    }
    public static void setEndpoint(Context c, String v) {
        prefs(c).edit().putString("endpoint", v).apply();
    }

    public static String getApiKey(Context c) {
        return prefs(c).getString("api_key", "");
    }
    public static void setApiKey(Context c, String v) {
        prefs(c).edit().putString("api_key", v == null ? "" : v.trim()).apply();
    }

    public static String getModel(Context c) {
        return prefs(c).getString("model", DEFAULT_MODEL);
    }
    public static void setModel(Context c, String v) {
        prefs(c).edit().putString("model", v == null ? DEFAULT_MODEL : v.trim()).apply();
    }

    public static String getSystemPrompt(Context c) {
        return prefs(c).getString("system_prompt", DEFAULT_SYSTEM_PROMPT);
    }
    public static void setSystemPrompt(Context c, String v) {
        prefs(c).edit().putString("system_prompt", v == null ? "" : v).apply();
    }

    public static boolean isMcpEnabled(Context c) {
        return prefs(c).getBoolean("mcp_enabled", false);
    }
    public static void setMcpEnabled(Context c, boolean v) {
        prefs(c).edit().putBoolean("mcp_enabled", v).apply();
    }

    public static int getMcpPort(Context c) {
        return prefs(c).getInt("mcp_port", 8765);
    }
    public static void setMcpPort(Context c, int v) {
        if (v < 1024 || v > 65535) v = 8765;
        prefs(c).edit().putInt("mcp_port", v).apply();
    }

    public static String getMcpToken(Context c) {
        return prefs(c).getString("mcp_token", "");
    }
    public static void setMcpToken(Context c, String v) {
        prefs(c).edit().putString("mcp_token", v == null ? "" : v.trim()).apply();
    }

    public static int getMaxHistory(Context c) {
        return prefs(c).getInt("max_history", 20);
    }
    public static void setMaxHistory(Context c, int v) {
        prefs(c).edit().putInt("max_history", v).apply();
    }
}
