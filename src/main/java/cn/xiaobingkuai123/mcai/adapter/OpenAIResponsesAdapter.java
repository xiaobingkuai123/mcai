package cn.xiaobingkuai123.mcai.adapter;
import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;

public class OpenAIResponsesAdapter implements ApiAdapter {
    private final int timeoutSeconds;
    private final boolean summaryEnabled;
    private final Set<String> summaryModels;
    private final String summaryLevel;

    public OpenAIResponsesAdapter(int timeoutSeconds) { this(timeoutSeconds, false, Set.of(), "auto"); }
    public OpenAIResponsesAdapter(int timeoutSeconds, boolean summaryEnabled, Set<String> summaryModels, String summaryLevel) {
        this.timeoutSeconds = timeoutSeconds;
        this.summaryEnabled = summaryEnabled;
        this.summaryModels = Set.copyOf(summaryModels == null ? Set.of() : summaryModels);
        this.summaryLevel = switch (summaryLevel == null ? "" : summaryLevel.toLowerCase(Locale.ROOT)) {
            case "concise", "detailed" -> summaryLevel.toLowerCase(Locale.ROOT);
            default -> "auto";
        };
    }
    @Override public HttpRequest buildRequest(String baseUrl,String key,String model,int max,List<Message> messages,boolean thinking)throws Exception{return buildRequest(baseUrl,key,model,messages,new ApiRequestOptions(.7,1,max,timeoutSeconds,0,false,thinking,4096,"medium"));}
    @Override public HttpRequest buildRequest(String baseUrl,String key,String model,List<Message> messages,ApiRequestOptions o){
        String endpoint=baseUrl.endsWith("/v1")||baseUrl.endsWith("/v1/")?baseUrl.replaceAll("/$","")+"/responses":baseUrl.replaceAll("/$","")+"/v1/responses";
        JsonArray a=new JsonArray(); for(Message m:messages){JsonObject x=new JsonObject();x.addProperty("role",m.role());x.addProperty("content",m.content());a.add(x);}
        JsonObject b=new JsonObject();b.addProperty("model",model);b.add("input",a);b.addProperty("max_output_tokens",o.maxTokens());b.addProperty("stream",o.streaming());
        if(o.thinkingEnabled()){JsonObject r=new JsonObject();r.addProperty("effort",o.reasoningEffort());if(summaryEnabled&&summaryModels.contains(model))r.addProperty("summary",summaryLevel);b.add("reasoning",r);}
        return HttpRequest.newBuilder().uri(URI.create(endpoint)).header("Content-Type","application/json").header("Authorization","Bearer "+key).timeout(Duration.ofSeconds(o.timeoutSeconds())).POST(HttpRequest.BodyPublishers.ofString(b.toString())).build();
    }
    @Override public AdapterResponse parseResponse(String text) throws cn.xiaobingkuai123.mcai.AiStreamClient.ProtocolException {
        try { return parsePayload(text); }
        catch (cn.xiaobingkuai123.mcai.AiStreamClient.ProtocolException e) { throw e; }
        catch (RuntimeException e) { throw new cn.xiaobingkuai123.mcai.AiStreamClient.ProtocolException("Invalid provider response fields", e); }
    }
    private AdapterResponse parsePayload(String text) throws cn.xiaobingkuai123.mcai.AiStreamClient.ProtocolException {
        JsonObject root=CompletionReasons.root(text);
        String status=CompletionReasons.requiredString(root,"status");
        var completion=CompletionReasons.responses(status);
        String reason=status;
        if("incomplete".equals(status)) reason=CompletionReasons.requiredString(CompletionReasons.requiredObject(root,"incomplete_details"),"reason");
        boolean refused=false;
        if(!root.has("output") && completion == CompletionStatus.COMPLETED) throw new cn.xiaobingkuai123.mcai.AiStreamClient.ProtocolException("Responses output missing");
        StringBuilder content=new StringBuilder(), reasoning=new StringBuilder();
        for(JsonElement e:root.has("output") ? CompletionReasons.requiredArray(root,"output") : new JsonArray()){
            JsonObject item=e.getAsJsonObject(); String type=item.has("type")?item.get("type").getAsString():"";
            if("reasoning".equals(type)&&item.has("summary")&&item.get("summary").isJsonArray()) for(JsonElement part:item.getAsJsonArray("summary")){
                JsonObject p=part.getAsJsonObject(); if("summary_text".equals(p.has("type")?p.get("type").getAsString():"")&&p.has("text")) reasoning.append(p.get("text").getAsString());
            }
            if("output_text".equals(type)) content.append(CompletionReasons.optionalString(item,"text"));
            if("refusal".equals(type)) { refused=true; content.append(CompletionReasons.optionalString(item,"refusal")); }
            if(type.endsWith("_call")) completion=CompletionStatus.UNSUPPORTED;
            if("message".equals(type)&&item.has("content")) for(JsonElement q:item.getAsJsonArray("content")){
                JsonObject part=q.getAsJsonObject(); if("refusal".equals(CompletionReasons.optionalString(part,"type"))) { refused=true; content.append(CompletionReasons.optionalString(part,"refusal")); } if("output_text".equals(part.has("type")?part.get("type").getAsString():"")&&part.has("text"))content.append(part.get("text").getAsString());
            }
        }
        if(refused && completion == CompletionStatus.COMPLETED) completion=CompletionStatus.REFUSED;
        return new AdapterResponse(content.toString(),reasoning.isEmpty()?null:reasoning.toString(),CacheUsageParser.parse(root),null,List.of(),completion,reason,TokenUsageParser.parse(root));
    }
}
