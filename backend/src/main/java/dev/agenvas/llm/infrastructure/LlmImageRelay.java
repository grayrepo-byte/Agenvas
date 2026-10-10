package dev.agenvas.llm.infrastructure;

import dev.agenvas.asset.storage.MediaRelayService;
import java.net.URI;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Component;

/** Translates already-authorized image pixels only at dispatch, for both regular and streaming calls. */
@Component
public class LlmImageRelay {
    private final MediaRelayService relay;
    public LlmImageRelay(MediaRelayService relay) { this.relay = relay; }

    public List<Message> prepare(List<Message> messages) {
        boolean hasImages = messages.stream().anyMatch(message -> message instanceof UserMessage user
                && user.getMedia().stream().anyMatch(LlmImageRelay::inlineImage));
        if (!hasImages) return messages;
        UUID profile = relay.llmProfile();
        if (profile == null) return messages;
        var prepared = new IdentityHashMap<Media, Media>();
        return messages.stream().map(message -> {
            if (!(message instanceof UserMessage user) || user.getMedia().stream().noneMatch(LlmImageRelay::inlineImage)) return message;
            List<Media> media = user.getMedia().stream().map(input -> {
                if (!inlineImage(input)) return input;
                return prepared.computeIfAbsent(input, image -> new Media(image.getMimeType(), URI.create(
                        relay.signedImage(profile, image.getDataAsByteArray(), image.getMimeType().toString()))));
            }).toList();
            return (Message) UserMessage.builder().text(user.getText()).metadata(user.getMetadata()).media(media).build();
        }).toList();
    }

    private static boolean inlineImage(Media media) {
        return "image".equals(media.getMimeType().getType()) && media.getData() instanceof byte[];
    }
}
