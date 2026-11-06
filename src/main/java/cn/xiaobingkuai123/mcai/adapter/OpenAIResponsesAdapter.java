package cn.xiaobingkuai123.mcai.adapter;
import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

public class OpenAIResponsesAdapter implements ApiAdapter {
    private final int timeoutSeconds; public OpenAIResponsesAdapter(int timeoutSeconds){this.timeoutSeconds=timeoutSeconds;}
    @Override public HttpRequest buildRequest(String baseUrl,String key,String model,int max,List<Message> messages,boolean thinking)throws Exception{return buildRequest(baseUrl,key,model,messages,new ApiRequestOptions(.7,1,max,timeoutSeconds,0,false,thinking,4096,"medium"));}
    @Override public HttpRequest buildRequest(String baseUrl,String key,String model,List<Message> messages,ApiRequestOptions o){String endpoint=baseUrl.endsWith("/v1")||baseUrl.endsWith("/v1/")?baseUrl.replaceAll("/$","")+"/responses":baseUrl.replaceAll("/$","")+"/v1/responses";JsonArray a=new JsonArray();for(Message m:messages){JsonObject x=new JsonObject();x.addProperty("role",m.role());x.addProperty("content",m.content());a.add(x);}JsonObject b=new JsonObject();b.addProperty("model",model);b.add("input",a);b.addProperty("max_output_tokens",o.maxTokens());b.addProperty("stream",o.streaming());if(o.thinkingEnabled()){JsonObject r=new JsonObject();r.addProperty("effort",o.reasoningEffort());r.addProperty("summary","auto");b.add("reasoning",r);}return HttpRequest.newBuilder().uri(URI.create(endpoint)).header("Content-Type","application/json").header("Authorization","Bearer "+key).timeout(Duration.ofSeconds(o.timeoutSeconds())).POST(HttpRequest.BodyPublishers.ofString(b.toString())).build();}
    @Override public AdapterResponse parseResponse(String text)throws Exception{JsonObject root=new JsonParser().parse(text).getAsJsonObject();if(!root.has("output"))return new AdapterResponse("",null);String c="",r=null;for(JsonElement e:root.getAsJsonArray("output")){JsonObject x=e.getAsJsonObject();if("reasoning".equals(x.get("type").getAsString())&&x.has("summary")&&x.getAsJsonArray("summary").size()>0)r=x.getAsJsonArray("summary").get(0).getAsJsonObject().get("text").getAsString();if("message".equals(x.get("type").getAsString())&&x.has("content"))for(JsonElement q:x.getAsJsonArray("content")){JsonObject z=q.getAsJsonObject();if("output_text".equals(z.get("type").getAsString()))c+=z.get("text").getAsString();}}return new AdapterResponse(c,r);}
}
