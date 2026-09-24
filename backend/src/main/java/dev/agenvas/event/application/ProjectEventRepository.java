package dev.agenvas.event.application;

import dev.agenvas.event.domain.ProjectEvent;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/** 项目本地事件序号与不可变事件行的数据库边界。 */
public interface ProjectEventRepository {

    /** 在调用方事务中锁定所属项目计数行，防止按全局自增 ID 推断提交顺序。 */
    OptionalLong lockCurrentSequence(UUID ownerId, UUID projectId);

    /** 仅计数行仍为预期值时推进一次序号。 */
    boolean advanceSequence(UUID ownerId, UUID projectId, long expectedSequence, long nextSequence);

    /** 在计数推进的同一事务中插入对应事件行。 */
    void insert(ProjectEvent event);

    /** 在独占游标之后按项目序号读取有界补发页。 */
    List<ProjectEvent> listAfter(UUID ownerId, UUID projectId, long afterSequence, int limit);

    /** 读取最新水位和仍保留的最早序号，供游标校验。 */
    CursorBounds cursorBounds(UUID ownerId, UUID projectId);

    /** 最多删除 limit 条过期事件；项目 event_seq 永不回退。 */
    int pruneOlderThan(java.time.Instant cutoff, int limit);

    /** 项目水位和保留日志下界；项目不属于该用户时返回空。
     * @param latestSequence 当前已提交的最大项目事件序号
     * @param oldestRetainedSequence 当前仍可补发的最小序号；没有保留事件时为空
     */
    record CursorBounds(long latestSequence, Long oldestRetainedSequence) {}
}
