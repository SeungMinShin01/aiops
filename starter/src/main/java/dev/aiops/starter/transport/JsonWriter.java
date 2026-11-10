package dev.aiops.starter.transport;

import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * protocol record를 JSON 문자열로 만든다 (D-085: 외부 JSON 라이브러리 없이, 의존성 0).
 * record는 리플렉션으로 필드를 돌기 때문에 protocol에 필드가 추가돼도 여기는 고치지 않는다.
 * 지원 타입: null, String, Number, Boolean, Enum, Collection, record. 그 외는 IllegalArgumentException.
 */
public final class JsonWriter {

    /** record 클래스별 필드 목록. 매번 리플렉션하지 않도록 한 번만 읽어 둔다 */
    private static final Map<Class<?>, RecordComponent[]> COMPONENTS = new ConcurrentHashMap<>();
    private static final char[] HEX = "0123456789bcdef".toCharArray();

    private JsonWriter() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder(256);
        writeValue(out, value);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(out, s);
        } else if (value instanceof Boolean b) {
            out.append(b.booleanValue());
        } else if (value instanceof Number n) {
            writeNumber(out, n);
        } else if (value instanceof Enum<?> e) {
            writeString(out, e.name());
        } else if (value instanceof Collection<?> c) {
            writeArray(out, c);
        } else if (value.getClass().isRecord()) {
            writeRecord(out, value);
        } else {
            throw new IllegalArgumentException("JsonWriter가 지원하지 않는 타입: " + value.getClass().getName());
        }
    }

    private static void writeNumber(StringBuilder out, Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                out.append("null"); // JSON에는 NaN·Infinity가 없다
                return;
            }
        }
        out.append(n);
    }

    private static void writeArray(StringBuilder out, Collection<?> items) {
        out.append('[');
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                out.append(',');
            }
            writeValue(out, item);
            first = false;
        }
        out.append(']');
    }

    private static void writeRecord(StringBuilder out, Object record) {
        RecordComponent[] components = COMPONENTS.computeIfAbsent(record.getClass(), Class::getRecordComponents);
        out.append('{');
        for (int i = 0; i < components.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            writeString(out, components[i].getName());
            out.append(':');
            writeValue(out, read(components[i], record));
        }
        out.append('}');
    }

    private static Object read(RecordComponent component, Object record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "record 필드를 읽지 못함: " + record.getClass().getName() + "." + component.getName(), e);
        }
    }

    /** 따옴표·백슬래시·제어문자만 이스케이프. 한글·이모지는 그대로 (본문은 UTF-8) */
    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 0xF]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
