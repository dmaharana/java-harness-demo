package com.example.demo.memory;

import com.example.demo.config.HarnessProperties;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final HarnessProperties properties;
    private final Tracer tracer;

    public MemoryService(HarnessProperties properties, Tracer tracer) {
        this.properties = properties;
        this.tracer = tracer;
    }

    public String loadMemory() {
        if (!properties.getMemory().isEnabled()) {
            return "";
        }

        Span span = tracer.spanBuilder("harness.memory_read")
                .setAttribute("memory.file_path", properties.getMemory().getFilePath())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            Path path = Paths.get(properties.getMemory().getFilePath());
            if (!Files.exists(path)) {
                log.info("Memory file {} does not exist, returning empty memory.", path.toAbsolutePath());
                return "";
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            span.setAttribute("memory.size_bytes", content.length());
            log.info("Loaded AGENTS.md memory content ({} bytes)", content.length());
            return content;
        } catch (IOException e) {
            span.recordException(e);
            log.error("Failed to read memory file {}: {}", properties.getMemory().getFilePath(), e.getMessage());
            return "";
        } finally {
            span.end();
        }
    }

    public synchronized void appendLesson(String lesson) {
        if (!properties.getMemory().isEnabled() || lesson == null || lesson.isBlank()) {
            return;
        }

        Span span = tracer.spanBuilder("harness.memory_write")
                .setAttribute("memory.file_path", properties.getMemory().getFilePath())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            Path path = Paths.get(properties.getMemory().getFilePath());

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String entry = String.format("%n- [%s] %s", timestamp, lesson.trim());

            if (!Files.exists(path)) {
                String header = "# AGENTS.md - Harness Memory & Project Rules\n\n## 🧠 Learned Memory & Lessons\n";
                Files.writeString(path, header + entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            } else {
                Files.writeString(path, entry, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }

            span.setAttribute("memory.entry_added", lesson);
            log.info("Appended new lesson to AGENTS.md: {}", lesson);
        } catch (IOException e) {
            span.recordException(e);
            log.error("Failed to append lesson to memory file {}: {}", properties.getMemory().getFilePath(), e.getMessage());
        } finally {
            span.end();
        }
    }
}
