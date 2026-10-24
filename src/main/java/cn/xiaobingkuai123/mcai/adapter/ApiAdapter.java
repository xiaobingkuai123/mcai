package cn.xiaobingkuai123.mcai.adapter;
import java.net.http.HttpRequest;
import java.util.List;

public interface ApiAdapter {
    record Message(String role, String content) {}
    record AdapterResponse(String content, String reasoning) {}
    HttpRequest buildRequest(String baseUrl, String apiKey, String model, int maxTokens,
                             List<Message> messages, boolean isThinking) throws Exception;
    AdapterResponse parseResponse(String responseBody) throws Exception;
}
