package dev.agenvas.provider.application;

import static dev.agenvas.provider.application.VideoProbeMeasurements.State.INVALID;
import static dev.agenvas.provider.application.VideoProbeMeasurements.State.MEASURED;
import static dev.agenvas.provider.application.VideoProbeMeasurements.State.MISSING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class VideoProbeMeasurementsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesStreamOrderAndFractionalMeasurements() {
        var probe = read("""
                {"streams":[
                  {"codec_type":"audio","codec_name":"aac"},
                  {"codec_type":"video","codec_name":"h264","width":700,"height":600,"avg_frame_rate":"24000/1001"},
                  {"codec_type":"video","codec_name":"hevc","avg_frame_rate":60}
                ],"format":{"format_name":"mov,mp4","duration":"5.0001"}}
                """);
        assertThat(probe.streams()).extracting(VideoProbeMeasurements.Stream::codecType)
                .containsExactly("audio", "video", "video");
        assertThat(probe.streams()).extracting(VideoProbeMeasurements.Stream::codecName)
                .containsExactly("aac", "h264", "hevc");
        assertThat(probe.streams().getFirst().averageFrameRate().state()).isEqualTo(MISSING);
        var video = probe.streams().get(1);
        assertThat(video.averageFrameRate().state()).isEqualTo(MEASURED);
        assertThat(video.averageFrameRate().value()).isCloseTo(23.976023976, within(1e-9));
        assertThat(video.width().value()).isEqualTo(700);
        assertThat(video.height().value()).isEqualTo(600);
        assertThat(probe.streams().getLast().averageFrameRate().value()).isEqualTo(60.0);
        assertThat(probe.formatName()).isEqualTo("mov,mp4");
        assertThat(probe.durationSeconds().value()).isEqualTo(5.0001);
    }

    @Test
    void distinguishesMissingFieldsFromUnsupportedNumericValuesWithoutUsingTheNominalRate() {
        var absent = read("""
                {"streams":[{"codec_type":"video","r_frame_rate":"30/1","height":null}]}
                """);
        var stream = absent.streams().getFirst();
        assertThat(stream.averageFrameRate().state()).isEqualTo(MISSING);
        assertThat(stream.width().state()).isEqualTo(MISSING);
        assertThat(stream.height().state()).isEqualTo(MISSING);
        assertThat(absent.durationSeconds().state()).isEqualTo(MISSING);
        assertThat(read("{}").streams()).isEmpty();

        var malformed = read("""
                {"streams":[{"width":700.5,"height":true}],"format":{"duration":"NaN"}}
                """);
        assertThat(malformed.streams().getFirst().width().state()).isEqualTo(INVALID);
        assertThat(malformed.streams().getFirst().height().state()).isEqualTo(INVALID);
        assertThat(malformed.durationSeconds().state()).isEqualTo(INVALID);
    }

    @Test
    void rejectsZeroDenominatorsNonfiniteComponentsAndMalformedFractions() {
        for (String text : List.of("", "0/0", "24/0", "NaN", "NaN/1", "24/Infinity", "24/1e999",
                "24/", "/1", "24/1/2", "24//", "1e308/1e-308", "0x18p0", "24d")) {
            var probe = mapper.createObjectNode();
            probe.putArray("streams").addObject().put("avg_frame_rate", text);
            assertThat(VideoProbeMeasurements.read(probe).streams().getFirst().averageFrameRate().state())
                    .as("average frame rate %s", text).isEqualTo(INVALID);
        }
    }

    private VideoProbeMeasurements read(String json) {
        return VideoProbeMeasurements.read(mapper.readTree(json));
    }
}
