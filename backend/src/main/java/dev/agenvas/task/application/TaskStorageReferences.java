package dev.agenvas.task.application;

import java.util.UUID;

/** Storage management must retain destinations frozen in task inputs, including historical attempts. */
public interface TaskStorageReferences {
    boolean referencesStorageProfile(UUID profileId);
}
