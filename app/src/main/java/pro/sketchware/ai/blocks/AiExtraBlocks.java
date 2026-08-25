package pro.sketchware.ai.blocks;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Registers the built-in AI blocks into the Sketchware Pro extra-block
 * system. Called once from {@code mod.hilal.saif.blocks.BlocksHandler#builtInBlocks}.
 *
 * <p>The block code templates are fully self-contained: they use only
 * android.jar APIs (HttpURLConnection, org.json) so generated user projects
 * need no external dependencies. Generated projects read their API key from
 * their own {@code sketchware_ai_config} shared preferences file; the last
 * successful response is stored back under the same preference name so the
 * returned-value block can read it.</p>
 *
 * <p>Generated projects require the INTERNET permission (enable it in the
 * project's Permission Manager).</p>
 */
public final class AiExtraBlocks {

    public static final String PREFS_NAME = "sketchware_ai_config";
    public static final String PREF_API_KEY = "api_key";
    public static final String PREF_LAST_RESPONSE = "last_response";

    private static final String COLOR_AI = "#7C4DFF";
    private static final String PALETTE_MY_BLOCK = "0";
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    private AiExtraBlocks() {
    }

    /** Adds all AI blocks to the provided list used by the block loader. */
    public static void register(@NonNull ArrayList<HashMap<String, Object>> arrayList) {
        arrayList.add(generateBlock());
        arrayList.add(responseBlock());
        arrayList.add(chatBlock());
    }

    /**
     * Command block: fires an async Gemini generateContent request and runs
     * the nested stack on the UI thread when a response arrives.
     * %1$s = prompt parameter, %2$s = nested substack.
     */
    @NonNull
    private static HashMap<String, Object> generateBlock() {
        HashMap<String, Object> map = new HashMap<>();
        map.put("name", "aiGeminiGenerate");
        map.put("type", "c");
        map.put("typeName", "");
        map.put("color", COLOR_AI);
        map.put("palette", PALETTE_MY_BLOCK);
        map.put("spec", "AI Gemini generate text prompt %s then");
        map.put("code", """
                new Thread(new Runnable(){public void run(){try{\
                String _k=getSharedPreferences("%PREFS%",0).getString("%KEY%","");\
                if(_k==null||_k.length()<10){throw new IllegalStateException("No API key configured in this app's %PREFS% preferences");}\
                org.json.JSONObject _body=new org.json.JSONObject();\
                org.json.JSONArray _parts=new org.json.JSONArray();\
                org.json.JSONObject _part=new org.json.JSONObject();\
                _part.put("text",%1$s);_parts.put(_part);\
                org.json.JSONObject _content=new org.json.JSONObject();\
                _content.put("parts",_parts);org.json.JSONArray _contents=new org.json.JSONArray();\
                _contents.put(_content);_body.put("contents",_contents);\
                byte[] _b=_body.toString().getBytes("UTF-8");\
                java.net.URL _u=new java.net.URL("%URL%?key="+java.net.URLEncoder.encode(_k,"UTF-8"));\
                java.net.HttpURLConnection _c=(java.net.HttpURLConnection)_u.openConnection();\
                _c.setRequestMethod("POST");_c.setRequestProperty("Content-Type","application/json; charset=utf-8");\
                _c.setConnectTimeout(20000);_c.setReadTimeout(120000);_c.setDoOutput(true);\
                java.io.OutputStream _o=_c.getOutputStream();_o.write(_b);_o.close();\
                int _code=_c.getResponseCode();\
                java.io.BufferedReader _in=new java.io.BufferedReader(new java.io.InputStreamReader(_code<400?_c.getInputStream():_c.getErrorStream(),"UTF-8"));\
                StringBuilder _sb=new StringBuilder();String _ln;\
                while((_ln=_in.readLine())!=null){_sb.append(_ln);}\
                _in.close();final String _txt;\
                if(_code>=400){_txt="AI request failed (HTTP "+_code+")";}\
                else{try{org.json.JSONObject _j=new org.json.JSONObject(_sb.toString());\
                org.json.JSONArray _cands=_j.getJSONArray("candidates");\
                _txt=_cands.getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).optString("text","");}\
                catch(Exception _pe){_txt="Unexpected AI response format";}}\
                getSharedPreferences("%PREFS%",0).edit().putString("%LAST%",_txt).apply();\
                runOnUiThread(new Runnable(){public void run(){%2$s}});\
                }catch(Exception _e){final String _m="AI error: "+_e.getMessage();\
                runOnUiThread(new Runnable(){public void run(){android.widget.Toast.makeText(getApplicationContext(),_m,1).show();}});}}}).start();"""
                .replace("%PREFS%", PREFS_NAME)
                .replace("%KEY%", PREF_API_KEY)
                .replace("%LAST%", PREF_LAST_RESPONSE)
                .replace("%URL%", GEMINI_URL));
        return map;
    }

    /**
     * Returned-value block: reads the last stored AI response.
     */
    @NonNull
    private static HashMap<String, Object> responseBlock() {
        HashMap<String, Object> map = new HashMap<>();
        map.put("name", "aiResponse");
        map.put("type", "s");
        map.put("typeName", "");
        map.put("color", COLOR_AI);
        map.put("palette", PALETTE_MY_BLOCK);
        map.put("spec", "AI last response");
        map.put("code", ("getSharedPreferences(\"" + PREFS_NAME + "\",0)"
                + ".getString(\"" + PREF_LAST_RESPONSE + "\",\"\")"));
        return map;
    }

    /**
     * Command block: sends a full conversation. %1$s = conversation as a
     * JSON array string of {"role":"user"|"model","text":"..."} objects,
     * %2$s = nested substack.
     */
    @NonNull
    private static HashMap<String, Object> chatBlock() {
        HashMap<String, Object> map = new HashMap<>();
        map.put("name", "aiChatSend");
        map.put("type", "c");
        map.put("typeName", "");
        map.put("color", COLOR_AI);
        map.put("palette", PALETTE_MY_BLOCK);
        map.put("spec", "AI chat send conversation %s then");
        map.put("code", """
                new Thread(new Runnable(){public void run(){try{\
                String _k=getSharedPreferences("%PREFS%",0).getString("%KEY%","");\
                if(_k==null||_k.length()<10){throw new IllegalStateException("No API key configured in this app's %PREFS% preferences");}\
                org.json.JSONArray _conv=new org.json.JSONArray(%1$s);\
                org.json.JSONArray _contents=new org.json.JSONArray();\
                for(int _i=0;_i<_conv.length();_i++){\
                org.json.JSONObject _m=_conv.getJSONObject(_i);\
                org.json.JSONObject _content=new org.json.JSONObject();\
                org.json.JSONArray _ps=new org.json.JSONArray();\
                org.json.JSONObject _p=new org.json.JSONObject();\
                _p.put("text",_m.optString("text",""));_ps.put(_p);\
                _content.put("role","model".equals(_m.optString("role"))?"model":"user");\
                _content.put("parts",_ps);_contents.put(_content);}\
                org.json.JSONObject _body=new org.json.JSONObject();\
                _body.put("contents",_contents);\
                byte[] _b=_body.toString().getBytes("UTF-8");\
                java.net.URL _u=new java.net.URL("%URL%?key="+java.net.URLEncoder.encode(_k,"UTF-8"));\
                java.net.HttpURLConnection _c=(java.net.HttpURLConnection)_u.openConnection();\
                _c.setRequestMethod("POST");_c.setRequestProperty("Content-Type","application/json; charset=utf-8");\
                _c.setConnectTimeout(20000);_c.setReadTimeout(120000);_c.setDoOutput(true);\
                java.io.OutputStream _o=_c.getOutputStream();_o.write(_b);_o.close();\
                int _code=_c.getResponseCode();\
                java.io.BufferedReader _in=new java.io.BufferedReader(new java.io.InputStreamReader(_code<400?_c.getInputStream():_c.getErrorStream(),"UTF-8"));\
                StringBuilder _sb=new StringBuilder();String _ln;\
                while((_ln=_in.readLine())!=null){_sb.append(_ln);}\
                _in.close();final String _txt;\
                if(_code>=400){_txt="AI request failed (HTTP "+_code+")";}\
                else{try{org.json.JSONObject _j=new org.json.JSONObject(_sb.toString());\
                _txt=_j.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).optString("text","");}\
                catch(Exception _pe){_txt="Unexpected AI response format";}}\
                getSharedPreferences("%PREFS%",0).edit().putString("%LAST%",_txt).apply();\
                runOnUiThread(new Runnable(){public void run(){%2$s}});\
                }catch(Exception _e){final String _m="AI error: "+_e.getMessage();\
                runOnUiThread(new Runnable(){public void run(){android.widget.Toast.makeText(getApplicationContext(),_m,1).show();}});}}}).start();"""
                .replace("%PREFS%", PREFS_NAME)
                .replace("%KEY%", PREF_API_KEY)
                .replace("%LAST%", PREF_LAST_RESPONSE)
                .replace("%URL%", GEMINI_URL));
        return map;
    }
}
