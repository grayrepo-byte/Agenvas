package dev.agenvas.canvas.application;

import static dev.agenvas.canvas.application.CanvasEditModels.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Project-authorized presentation history persistence, called under the project event lock. */
public interface CanvasEditRepository {
    List<GroupState> groups(UUID projectId);
    boolean createGroup(Group group, Instant now);
    boolean updateGroup(GroupState group, long expectedVersion, Instant now);
    Optional<ReceiptRow> receipt(UUID projectId, UUID receiptId);
    void insertReceipt(ReceiptRow receipt);
    boolean updateReceipt(ReceiptRow receipt, long expectedRevision);
    Optional<Command> command(UUID projectId, UUID commandId);
    void insertCommand(UUID projectId, UUID commandId, UUID receiptId, String requestHash);
    void prune(UUID projectId);
}
