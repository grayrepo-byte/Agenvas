package dev.agenvas.run.application;

import java.util.List;
import java.util.UUID;

/** Freezes public history only; the caller authorizes and orders terminal Runs under its conversation lock. */
public interface ConversationMemoryReader {

    /** At most ten user/assistant pairs, leaving room for the current Run's twelve model rounds. */
    int MAX_HISTORY_MESSAGES = 20;
    /** Unicode code points, rather than UTF-16 units, so Chinese and supplementary text stay intact. */
    int MAX_HISTORY_CODE_POINTS = 32_000;

    /** IDs belong to this project and conversation, ordered oldest first; this is not a public read API. */
    ConversationMemory read(UUID projectId, List<UUID> authorizedPriorRunIds);

    /** Immutable input snapshot; source history remains in the database when this projection is truncated. */
    record ConversationMemory(List<Entry> entries, boolean truncated, int priorRunCount) {
        public ConversationMemory {
            entries = List.copyOf(entries);
        }
    }

    /** Public text only: no provider metadata, tool arguments, tool-call IDs or protocol continuation state. */
    record Entry(Role role, String content) {}

    enum Role { USER, ASSISTANT }
}
