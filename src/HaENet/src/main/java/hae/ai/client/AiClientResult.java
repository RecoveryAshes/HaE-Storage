package hae.ai.client;

public final class AiClientResult {
    private final int statusCode;
    private final String responseBody;

    public AiClientResult(int statusCode, String responseBody) {
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    @Override
    public String toString() {
        return "AiClientResult{" +
                "statusCode=" + statusCode +
                ", responseBodyLength=" + responseBody.length() +
                '}';
    }
}
