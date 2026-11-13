package cn.xiaobingkuai123.mcai.adapter;
import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

public class GeminiAdapter implements ApiAdapter {
    private final int timeoutSeconds;
    public GeminiAdapter(int timeoutSeconds){this.timeoutSeconds=timeoutSeconds;}
    @Override public HttpRequest buildRequest(String baseUrl,String key,String model,int max,List<Message> messages,boolean thinking)throws Exception{return buildRequest(baseUrl,key,model,messages,new ApiRequestOptions(.7,1,max,timeoutSeconds,0,false,thinking,4096,"medium"));}
    @Override public HttpRequest buildRequest(String baseUrl,String key,String model,List<Message> messages,ApiRequestOptions o){JsonObject sys=null;JsonArray contents=new JsonArray();for(Message m:messages){if("system".equals(m.role())){JsonObject p=new JsonObject();p.addProperty("text",m.content());JsonArray ps=new JsonArray();ps.add(p);sys=new JsonObject();sys.add("parts",ps);continue;}JsonObject p=new JsonObject();p.addProperty("text",m.content());JsonArray ps=new JsonArray();ps.add(p);JsonObject c=new JsonObject();c.addProperty("role","assistant".equals(m.role())?"model":"user");c.add("parts",ps);contents.add(c);}JsonObject g=new JsonObject();g.addProperty("maxOutputTokens",o.maxTokens());g.addProperty("temperature",o.temperature());g.addProperty("topP",o.topP());JsonObject t=new JsonObject();if(o.thinkingEnabled()){t.addProperty("includeThoughts",true);t.addProperty("thinkingBudget",Math.min(o.maxTokens(),o.thinkingBudget()));}else{t.addProperty("thinkingBudget",0);}g.add("thinkingConfig",t);JsonObject b=new JsonObject();if (o.cachedContent() != null && !o.cachedContent().isBlank()) {
    b.addProperty("cachedContent", o.cachedContent());
    JsonArray current = new JsonArray();
    if (contents.size() > 0) current.add(contents.get(contents.size() - 1));
    contents = current;
}
if(sys!=null && (o.cachedContent() == null || o.cachedContent().isBlank())) b.add("systemInstruction",sys); b.add("contents",contents); b.add("generationConfig",g); String url=baseUrl.replaceAll("/$","")+"/v1beta/models/"+model+":generateContent"; return HttpRequest.newBuilder().uri(URI.create(url)).header("Content-Type","application/json").header("x-goog-api-key",key).timeout(Duration.ofSeconds(o.timeoutSeconds())).POST(HttpRequest.BodyPublishers.ofString(b.toString())).build();}
    @Override public AdapterResponse parseResponse(String text){JsonArray p=JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("candidates").get(0).getAsJsonObject().getAsJsonObject("content").getAsJsonArray("parts");StringBuilder c=new StringBuilder(),r=new StringBuilder();for(JsonElement e:p){JsonObject x=e.getAsJsonObject();if(x.has("text")){if(x.has("thought")&&x.get("thought").getAsBoolean())r.append(x.get("text").getAsString());else c.append(x.get("text").getAsString());}}return new AdapterResponse(c.toString(),r.length()==0?null:r.toString());}
}
