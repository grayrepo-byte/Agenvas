package dev.agenvas.llm.application;

/** Marks failures at the model gateway boundary, excluding context, checkpoint and tool errors. */
final class ModelCallFailure extends RuntimeException {
    ModelCallFailure(RuntimeException cause) { super("Model request failed", cause); }
}
