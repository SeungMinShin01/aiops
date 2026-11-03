package dev.aiops.server.security;

// 키가 없거나, 틀렸거나, 폐기됐을 때 던지는 예외
public class InvalidApiKeyException extends RuntimeException{
    public InvalidApiKeyException(String message){
        super(message);
    }
}
