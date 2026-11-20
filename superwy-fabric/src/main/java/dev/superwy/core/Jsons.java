package dev.superwy.core;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class Jsons {
    public static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private Jsons() {}
    public static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            value.getAsJsonObject().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> result.add(e.getKey(), canonical(e.getValue())));
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray(); value.getAsJsonArray().forEach(v -> result.add(canonical(v))); return result;
        }
        return value.deepCopy();
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String hash(JsonElement value) { return hash(canonical(value).toString()); }
}
