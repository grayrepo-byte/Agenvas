package dev.agenvas.llm.application;

import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Compiled behaviors selected by the stored Task before reading a model response. */
interface DirectTextPurpose {
    static DirectTextPurpose forTask(Task task, ImagePrompt imagePrompt) {
        if (task.isImagePrompt())
            return imagePrompt;
        if (task.isVideoPromptOptimization())
            return VideoPrompt.INSTANCE;
        return TextCard.INSTANCE;
    }

    List<Message> messages(Task task);

    /** Dispatch-only material is never included in the durable request. */
    default List<Message> hydrate(Task task, List<Message> messages) {
        return messages;
    }

    default String rejectionCode(Throwable cause) {
        return null;
    }

    void publish(TaskService tasks, Task task, String workerId, JsonNode checkpoint, ObjectNode content);

    private static ObjectNode promptOutput(Task task, JsonNode checkpoint, ObjectNode content) {
        ObjectNode output = (ObjectNode) checkpoint.deepCopy();
        output.putObject("result").put("prompt", content.path("text").asText())
                .put("mock", "mock".equals(task.input().path("modelConfigSource").asText()));
        return output;
    }

    enum TextCard implements DirectTextPurpose {
        INSTANCE;

        @Override
        public List<Message> messages(Task task) {
            return List.of(new SystemMessage(frozenSystemPrompt(task)),
                    new UserMessage("Instruction:\n" + task.input().path("prompt").asText()
                            + "\n\nCurrent card content:\n" + task.input().path("currentText").asText("")));
        }

        @Override
        public void publish(TaskService tasks, Task task, String workerId, JsonNode checkpoint, ObjectNode content) {
            tasks.succeedWithTextArtifact(task, workerId, content);
        }

        private static final int LEGACY_INPUT_SCHEMA_VERSION = 1;
        private static final int FROZEN_PROMPT_INPUT_SCHEMA_VERSION = 2;

        /** Legacy schema 1 tasks retain the original prompt; new tasks never read live settings. */
        private String frozenSystemPrompt(Task lease) {
            if (lease.input().path("schemaVersion").asInt() == LEGACY_INPUT_SCHEMA_VERSION)
                return "You write the finished content of one text card. Follow the user instruction using the current card content as context. Return only the finished card text, without commentary, tool calls, XML wrappers, or Markdown code fences. Keep the response under 20000 characters.";
            String content = lease.input().path("systemPrompt").asText("");
            if (lease.input().path("schemaVersion").asInt() != FROZEN_PROMPT_INPUT_SCHEMA_VERSION || content.isBlank())
                throw new IllegalArgumentException("Text Task lacks its frozen system prompt");
            return content;
        }

    }

    enum VideoPrompt implements DirectTextPurpose {
        INSTANCE;

        @Override
        public List<Message> messages(Task task) {
            return VideoPromptOptimization.messages(task);
        }

        @Override
        public void publish(TaskService tasks, Task task, String workerId, JsonNode checkpoint, ObjectNode content) {
            VideoPromptOptimization.validate(task, content.path("text").asText());
            tasks.succeed(task, workerId, promptOutput(task, checkpoint, content));
        }
    }

    record ImagePrompt(ImagePromptInputService images) implements DirectTextPurpose {
        private static final Logger LOGGER = LoggerFactory.getLogger(ImagePrompt.class);

        @Override
        public List<Message> messages(Task task) {
            return images.messages(task);
        }

        @Override
        public List<Message> hydrate(Task task, List<Message> messages) {
            return images.hydrate(task, messages);
        }

        @Override
        public String rejectionCode(Throwable cause) {
            if (cause instanceof com.openai.errors.OpenAIServiceException service
                    && Set.of(400, 413, 415, 422).contains(service.statusCode()))
                return ImagePromptInputService.MODEL_REJECTED;
            return null;
        }

        @Override
        public void publish(TaskService tasks, Task task, String workerId, JsonNode checkpoint, ObjectNode content) {
            ObjectNode output = promptOutput(task, checkpoint, content);
            output.withObject("result").put("sourceVersionId", task.input().path("sourceVersionId").asText());
            if (!task.isImagePromptCard()) {
                tasks.succeed(task, workerId, output);
                return;
            }
            try {
                tasks.succeedWithImagePromptCard(task, workerId, output, content);
            } catch (RuntimeException publicationFailure) {
                // A placement or transaction failure retries publication from the settled response.
                LOGGER.warn(
                        "Image-prompt task {} could not publish its text card; checkpoint retained for lease recovery",
                        task.id());
            }
        }
    }
}
