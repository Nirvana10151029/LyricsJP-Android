package jp.lyricsjp.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TranslationClient {
    private static final int BATCH_SIZE = 30;
    private final Context context;

    public TranslationClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public void translate(LyricsDocument document) throws Exception {
        String provider = AppState.getProvider(context);
        String apiKey = SecretStore.loadApiKey(context, provider);
        if (apiKey.isEmpty()) throw new Exception("翻訳APIキーを設定してください。");
        List<String> originals = new ArrayList<>();
        for (LyricsDocument.Line line : document.lines) originals.add(line.original);

        List<String> translated = new ArrayList<>();
        for (int start = 0; start < originals.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, originals.size());
            List<String> batch = originals.subList(start, end);
            translated.addAll(translateBatch(batch, apiKey));
        }
        if (translated.size() != document.lines.size()) throw new Exception("翻訳結果の行数が一致しませんでした。");
        for (int i = 0; i < translated.size(); i++) document.lines.get(i).translation = translated.get(i);
    }

    private List<String> translateBatch(List<String> lines, String apiKey) throws Exception {
        String provider = AppState.getProvider(context);
        if (provider.equals("deepl")) return translateDeepL(lines, apiKey);
        if (provider.equals("gemini")) return translateGemini(lines, apiKey);
        return translateGpt(lines, apiKey);
    }

    private List<String> translateGpt(List<String> lines, String apiKey) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", AppState.getModel(context));
        body.put("instructions", "歌詞を自然な日本語に翻訳してください。各入力行と出力行を必ず1対1で対応させ、解説は追加しないでください。");
        body.put("input", prompt(lines));
        body.put("store", false);

        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", new JSONArray().put("translations"));
        schema.put("properties", new JSONObject().put("translations",
                new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string"))));
        JSONObject format = new JSONObject()
                .put("type", "json_schema")
                .put("name", "lyrics_translation")
                .put("strict", true)
                .put("schema", schema);
        body.put("text", new JSONObject().put("format", format));

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        HttpClient.Response response = HttpClient.postJson("https://api.openai.com/v1/responses", body.toString(), headers);
        if (!response.isSuccessful()) throw new Exception(remoteError(response));
        JSONObject json = new JSONObject(response.body);
        StringBuilder outputText = new StringBuilder();
        JSONArray output = json.optJSONArray("output");
        if (output != null) {
            for (int i = 0; i < output.length(); i++) {
                JSONArray content = output.optJSONObject(i) == null ? null : output.optJSONObject(i).optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject item = content.optJSONObject(j);
                    if (item != null && !item.optString("text").isEmpty()) outputText.append(item.optString("text"));
                }
            }
        }
        return parseTranslationJson(outputText.toString(), lines.size());
    }

    private List<String> translateGemini(List<String> lines, String apiKey) throws Exception {
        String model = URLEncoder.encode(AppState.getModel(context), "UTF-8");
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
        JSONObject body = new JSONObject();
        body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(
                new JSONObject().put("text", "歌詞を自然な日本語に翻訳し、入力と同じ行数・順序で返してください。"))));
        body.put("contents", new JSONArray().put(new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", prompt(lines))))));
        body.put("generationConfig", new JSONObject().put("responseMimeType", "application/json"));

        Map<String, String> headers = new HashMap<>();
        headers.put("x-goog-api-key", apiKey);
        HttpClient.Response response = HttpClient.postJson(url, body.toString(), headers);
        if (!response.isSuccessful()) throw new Exception(remoteError(response));
        JSONObject json = new JSONObject(response.body);
        JSONArray candidates = json.optJSONArray("candidates");
        StringBuilder text = new StringBuilder();
        if (candidates != null && candidates.length() > 0) {
            JSONObject content = candidates.optJSONObject(0).optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (parts != null) for (int i = 0; i < parts.length(); i++) text.append(parts.optJSONObject(i).optString("text"));
        }
        return parseTranslationJson(text.toString(), lines.size());
    }

    private List<String> translateDeepL(List<String> lines, String apiKey) throws Exception {
        JSONObject body = new JSONObject();
        JSONArray texts = new JSONArray();
        for (String line : lines) texts.put(line);
        body.put("text", texts);
        body.put("target_lang", "JA");
        body.put("preserve_formatting", true);
        String url = AppState.isDeepLFree(context)
                ? "https://api-free.deepl.com/v2/translate"
                : "https://api.deepl.com/v2/translate";
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "DeepL-Auth-Key " + apiKey);
        HttpClient.Response response = HttpClient.postJson(url, body.toString(), headers);
        if (!response.isSuccessful()) throw new Exception(remoteError(response));
        JSONArray items = new JSONObject(response.body).optJSONArray("translations");
        List<String> output = new ArrayList<>();
        if (items != null) for (int i = 0; i < items.length(); i++) output.add(items.optJSONObject(i).optString("text"));
        if (output.size() != lines.size()) throw new Exception("DeepLの翻訳行数が一致しませんでした。");
        return output;
    }

    private String prompt(List<String> lines) {
        JSONArray input = new JSONArray();
        for (String line : lines) input.put(line);
        return "次のJSON配列の各要素を日本語へ翻訳し、必ず {\"translations\":[\"...\"]} だけを返してください。\n" + input;
    }

    private List<String> parseTranslationJson(String raw, int expected) throws Exception {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstBreak >= 0 && lastFence > firstBreak) text = text.substring(firstBreak + 1, lastFence).trim();
        }
        JSONArray array = new JSONObject(text).optJSONArray("translations");
        if (array == null || array.length() != expected) throw new Exception("AIの翻訳結果が想定形式ではありませんでした。");
        List<String> output = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) output.add(array.optString(i));
        return output;
    }

    private String remoteError(HttpClient.Response response) {
        try {
            JSONObject json = new JSONObject(response.body);
            Object error = json.opt("error");
            if (error instanceof JSONObject) {
                String message = ((JSONObject) error).optString("message");
                if (!message.isEmpty()) return message;
            }
            if (error instanceof String && !((String) error).isEmpty()) return (String) error;
            String message = json.optString("message");
            if (!message.isEmpty()) return message;
        } catch (Exception ignored) {}
        return "翻訳APIへの接続に失敗しました（HTTP " + response.status + "）";
    }
}
