import { mediaAdapters } from "./mediaAdapterCatalog";

/** Synthetic server response for settings tests; deliberately not the protocols' production defaults. */
export const syntheticHttpTimeouts = { connectSeconds: 7, writeSeconds: 61, readSeconds: 83, callSeconds: 101 };
export const syntheticHttpTimeoutDefaults = Object.fromEntries(
  Object.keys(mediaAdapters)
    .filter(id => !id.startsWith("LOCAL_") && !id.startsWith("MOCK_"))
    .map(id => [id, syntheticHttpTimeouts]),
);
