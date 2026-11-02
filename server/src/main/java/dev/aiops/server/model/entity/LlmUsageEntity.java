package dev.aiops.server.model.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

@Entity
@Table(name = "llm_usage")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LlmUsageEntity {

    @Id
    private LocalDate day;

    @Column(name = "call_count", nullable = false)
    private int callCount;
}