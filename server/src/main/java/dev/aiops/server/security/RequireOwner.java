package dev.aiops.server.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 조장(OWNER)만 부를 수 있는 컨트롤러 메서드에 붙인다 (AI-17, 규칙 12).
 * 검사는 인터셉터 또는 AOP가 한다 (구현: AI-81). MEMBER면 403.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireOwner {
}