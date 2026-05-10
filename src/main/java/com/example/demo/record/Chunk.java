package com.example.demo.record;

import java.nio.file.Path;

public record Chunk(
        Path file,
        long start,
        long end) {
}
