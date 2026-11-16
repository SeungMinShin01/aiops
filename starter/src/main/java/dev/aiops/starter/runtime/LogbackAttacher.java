package dev.aiops.starter.runtime;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import dev.aiops.starter.capture.AiopsLogbackAppender;
import dev.aiops.starter.capture.EventMapper;
import dev.aiops.starter.mask.Masker;
import dev.aiops.starter.transport.EventBuffer;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Logback에 직접 닿는 코드를 이 클래스에만 둔다.
 * Logback이 없는 앱(Log4j2 등)에서 문제가 생겨도 여기서만 터지고, 부르는 쪽이 Throwable로 잡는다.
 */
final class LogbackAttacher {

    private LogbackAttacher() {
    }

    /** 루트 로거에 AIOPS가 없거나 멈춰 있으면 새로 붙인다. 이미 살아 있으면 아무것도 안 한다 */
    static void attachIfMissing(EventMapper mapper, Masker masker, EventBuffer buffer) {
        LoggerContext context = currentContext();
        if (context == null) {
            return; // Logback이 아님
        }
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Appender<ILoggingEvent> existing = root.getAppender(AiopsLogbackAppender.NAME);
        if (existing != null && existing.isStarted()) {
            return; // 중복 장착 금지
        }
        if (existing != null) {
            root.detachAppender(existing); // 멈춘 것은 떼고 새로 붙인다
        }
        AiopsLogbackAppender appender = new AiopsLogbackAppender(mapper, masker, buffer);
        appender.setContext(context);
        appender.start(); // start 전에는 Logback이 append를 부르지 않는다
        root.addAppender(appender);
    }

    /** 비활성 처리용: 붙어 있으면 떼고 멈춘다 */
    static void detach() {
        LoggerContext context = currentContext();
        if (context == null) {
            return;
        }
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Appender<ILoggingEvent> existing = root.getAppender(AiopsLogbackAppender.NAME);
        if (existing != null) {
            root.detachAppender(existing);
            existing.stop();
        }
    }

    private static LoggerContext currentContext() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            return context;
        }
        return null;
    }
}