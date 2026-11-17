import type { QueryClient, QueryKey } from "@tanstack/react-query";
import type { Task } from "../../shared/api/client";

function creationOrder(task: Task): string {
  // The server sorts PostgreSQL instants before UUIDs. Preserve sub-millisecond
  // precision instead of treating separate tasks in the same millisecond as ties.
  return `${new Date(task.createdAt).toISOString().slice(0, 19)}.${(task.createdAt.match(/\.(\d+)/)?.[1] ?? "").padEnd(9, "0")}`;
}

/** A delayed read/receipt must not erase accepted work or replace a newer task result. */
export async function acknowledgePromptTask(client: QueryClient, queryKey: QueryKey, task: Task): Promise<void> {
  const known = client.getQueryData<Task[]>(queryKey);
  await client.cancelQueries({ queryKey, exact: true });
  client.setQueryData<Task[]>(queryKey, current => {
    const tasks = new Map<string, Task>();
    for (const entry of [...(known ?? []), ...(current ?? []), task]) {
      const previous = tasks.get(entry.id);
      if (!previous || previous.version <= entry.version) tasks.set(entry.id, entry);
    }
    return [...tasks.values()].sort((left, right) => {
      const a = creationOrder(left),
        b = creationOrder(right);
      return a !== b ? (a < b ? 1 : -1) : left.id === right.id ? 0 : left.id < right.id ? 1 : -1;
    });
  });
}
