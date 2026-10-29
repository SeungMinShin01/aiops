package main.java.dev.aiops.protocol;

/** Starter와 서버가 같이 쓰는 상수. 바꾸면 양쪽 다 바꿔야 하므로 여기 한 곳에만 */
public final class ProtocolConstants {

    public static final String HEADER_API_KEY = "X-AIOPS-KEY";

    public static final String PATH_EVENTS = "/v1/events";
    public static final String PATH_HELLO = "/v1/hello";

    public static final String ENV_LOCAL = "local";
    public static final String ENV_PROD = "prod";

    /** 한 묶음에 담는 최대 이벤트 수. 서버는 초과하면 400 */
    public static final int MAX_BATCH_SIZE = 100;

    /** 바깥 예외의 스택 프레임 최대 개수 */
    public static final int MAX_STACK_FRAMES = 30;

    /** 마스킹 후 메시지 최대 길이 */
    public static final int MAX_MESSAGE_LENGTH = 2000;

    private ProtocolConstants() {
    }
}