package dev.agenvas.canvas.application;

import dev.agenvas.canvas.domain.CanvasItem;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 画布空间展示记录的所有者范围持久化边界。 */
public interface CanvasItemRepository {

    /** 按确定的 z 顺序列出已保存布局。 */
    List<CanvasItem> list(UUID ownerId, UUID projectId);

    /** 锁定单个画布项，以执行带预期版本的命令。 */
    Optional<CanvasItem> findForUpdate(UUID ownerId, UUID projectId, UUID itemId);

    /** 插入由客户端指定稳定 ID 的展示项。 */
    boolean create(CanvasItem item);

    /** 预期版本匹配时替换可变展示字段。 */
    boolean update(UUID ownerId, CanvasItem item, long expectedVersion, Instant updatedAt);

    /** 仅删除画布展示关系，不删除其引用的业务对象。 */
    boolean delete(UUID ownerId, UUID projectId, UUID itemId, long expectedVersion);
}
