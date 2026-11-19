package dev.agenvas.provider.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Measurements from ffprobe, preserving stream order and the distinction between absent and malformed numbers. */
record VideoProbeMeasurements(List<Stream> streams, String formatName, Measurement<Double> durationSeconds) {
    private static final Pattern DECIMAL = Pattern
            .compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    static VideoProbeMeasurements read(JsonNode probe) {
        var streams = new ArrayList<Stream>();
        for (var stream : probe.path("streams")) {
            streams.add(new Stream(stream.path("codec_type").asText(), stream.path("codec_name").asText(),
                    number(stream.path("width"), VideoProbeMeasurements::integer),
                    number(stream.path("height"), VideoProbeMeasurements::integer),
                    number(stream.path("avg_frame_rate"), VideoProbeMeasurements::frameRate)));
        }
        var format = probe.path("format");
        return new VideoProbeMeasurements(List.copyOf(streams), format.path("format_name").asText(),
                number(format.path("duration"), VideoProbeMeasurements::finiteDecimal));
    }

    private static <T> Measurement<T> number(JsonNode node, Function<String, T> parse) {
        if (node.isMissingNode() || node.isNull())
            return new Measurement<>(State.MISSING, null);
        if (node.isNumber() || node.isTextual()) {
            try {
                return new Measurement<>(State.MEASURED, parse.apply(node.asText()));
            } catch (NumberFormatException | ArithmeticException malformed) {
                return new Measurement<>(State.INVALID, null);
            }
        }
        return new Measurement<>(State.INVALID, null);
    }

    private static double frameRate(String value) {
        int separator = value.indexOf('/');
        if (separator < 0)
            return finiteDecimal(value);
        double numerator = finiteDecimal(value.substring(0, separator));
        double denominator = finiteDecimal(value.substring(separator + 1));
        return requireFinite(numerator / denominator);
    }

    private static int integer(String value) {
        return new BigDecimal(decimal(value)).intValueExact();
    }

    private static double finiteDecimal(String value) {
        return requireFinite(Double.parseDouble(decimal(value)));
    }

    private static String decimal(String value) {
        String text = value.strip();
        if (!DECIMAL.matcher(text).matches())
            throw new NumberFormatException();
        return text;
    }

    private static double requireFinite(double value) {
        if (!Double.isFinite(value))
            throw new NumberFormatException();
        return value;
    }

    record Stream(String codecType, String codecName, Measurement<Integer> width, Measurement<Integer> height,
            Measurement<Double> averageFrameRate) {
    }

    enum State {
        MEASURED, MISSING, INVALID
    }

    record Measurement<T>(State state, T value) {
        T orElseThrow(Supplier<? extends RuntimeException> failure) {
            if (state != State.MEASURED)
                throw failure.get();
            return value;
        }
    }
}
