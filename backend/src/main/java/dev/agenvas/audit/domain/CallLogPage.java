package dev.agenvas.audit.domain;

import java.util.List;

public record CallLogPage(List<CallLog> items, int page, int size,
        long totalElements, long totalPages) {}
