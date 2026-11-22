package cn.xiaobingkuai123.mcai.adapter;

import cn.xiaobingkuai123.mcai.AiStreamClient.ProtocolException;
import com.google.gson.*;
import static cn.xiaobingkuai123.mcai.adapter.ApiAdapter.CompletionStatus.*;

/** Explicit provider terminal states; unknown values remain observable and unsuccessful. */
public final class CompletionReasons {
    private CompletionReasons() {}
    public static ApiAdapter.CompletionStatus chat(String reason) {
        return switch(reason) {
            case "stop" -> COMPLETED; case "length" -> INCOMPLETE; case "content_filter" -> BLOCKED;
            case "tool_calls", "function_call" -> UNSUPPORTED; default -> UNSUPPORTED;
        };
    }
    public static ApiAdapter.CompletionStatus anthropic(String reason) {
        return switch(reason) {
            case "end_turn", "stop_sequence" -> COMPLETED;
            case "max_tokens", "model_context_window_exceeded" -> INCOMPLETE;
            case "refusal" -> REFUSED; case "pause_turn" -> PAUSED; default -> UNSUPPORTED;
        };
    }
    public static ApiAdapter.CompletionStatus gemini(String reason) {
        return switch(reason) {
            case "STOP" -> COMPLETED; case "MAX_TOKENS" -> INCOMPLETE;
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT" -> BLOCKED;
            default -> UNSUPPORTED;
        };
    }
    public static ApiAdapter.CompletionStatus responses(String status) {
        return switch(status) { case "completed" -> COMPLETED; case "incomplete" -> INCOMPLETE; case "failed" -> FAILED; default -> UNSUPPORTED; };
    }
    public static String requiredString(JsonObject o, String key) throws ProtocolException {
        JsonElement e = o == null ? null : o.get(key);
        if(e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().isBlank())
            throw new ProtocolException("Missing or invalid provider field: " + key);
        return e.getAsString();
    }
    public static String optionalString(JsonObject o,String key) throws ProtocolException {
        if(o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        JsonElement e=o.get(key);
        if(!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new ProtocolException("Invalid provider field: " + key);
        return e.getAsString();
    }
    public static JsonObject root(String text) throws ProtocolException {
        try { JsonElement e=JsonParser.parseString(text); if(e.isJsonObject()) return e.getAsJsonObject(); }
        catch(JsonParseException e) { throw new ProtocolException("Invalid provider JSON",e); }
        throw new ProtocolException("Expected provider JSON object");
    }
    public static JsonArray requiredArray(JsonObject o,String key) throws ProtocolException {
        if(o == null || !o.has(key) || !o.get(key).isJsonArray()) throw new ProtocolException("Missing or invalid provider array: "+key);
        return o.getAsJsonArray(key);
    }
    public static JsonObject requiredObject(JsonObject o,String key) throws ProtocolException {
        if(o == null || !o.has(key) || !o.get(key).isJsonObject()) throw new ProtocolException("Missing or invalid provider object: "+key);
        return o.getAsJsonObject(key);
    }
    public static JsonObject first(JsonArray a) throws ProtocolException {
        if(a.isEmpty() || !a.get(0).isJsonObject()) throw new ProtocolException("Missing provider candidate/choice");
        return a.get(0).getAsJsonObject();
    }
    public static ApiAdapter.AdapterResponse chatResponse(String text, boolean deepSeek) throws ProtocolException {
        try {
        JsonObject root=root(text), choice=first(requiredArray(root,"choices")), message=requiredObject(choice,"message");
        String reason=requiredString(choice,"finish_reason"), content=optionalString(message,"content"), refusal=optionalString(message,"refusal");
        var status=chat(reason);
        if(!refusal.isBlank()) { content += refusal; if(status == COMPLETED) status=REFUSED; }
        if(!deepSeek && content.isBlank() && status == COMPLETED) throw new ProtocolException("Chat response contains neither content nor refusal");
        String reasoning=optionalString(message,"reasoning_content");
        return new ApiAdapter.AdapterResponse(content,reasoning.isEmpty()?null:reasoning,CacheUsageParser.parse(root),null,java.util.List.of(),status,reason,TokenUsageParser.parse(root));
        } catch (RuntimeException e) { throw new ProtocolException("Invalid Chat response fields", e); }
    }
}
