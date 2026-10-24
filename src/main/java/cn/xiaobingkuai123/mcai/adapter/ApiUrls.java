package cn.xiaobingkuai123.mcai.adapter;

public final class ApiUrls {
    private ApiUrls() {}
    public static String v1(String baseUrl, String path) {
        String base = baseUrl.replaceAll("/+$", "");
        return base.endsWith("/v1") ? base + path : base + "/v1" + path;
    }
}
