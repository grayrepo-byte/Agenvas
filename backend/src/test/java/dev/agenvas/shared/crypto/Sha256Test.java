package dev.agenvas.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class Sha256Test {
    @ParameterizedTest
    @MethodSource("textVectors")
    void retainsExactUtf8TextAndLowercaseHexadecimal(String text, String expected) {
        assertThat(Sha256.hex(text)).isEqualTo(expected);
    }

    static Stream<Arguments> textVectors() {
        return Stream.of(
                Arguments.of("", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                Arguments.of("abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                Arguments.of("创作🎨", "85868ce42a0e6bb3f1e6d0c7bf58486dd4a2a43f89aca9c677c2b684aa0357c0"),
                Arguments.of("a\nb\u0000c", "896144d1d44195e89a7ed32b80e86e5c886c7f993eaf12f946524018d68dbd9c"),
                Arguments.of("{\"a\":1,\"b\":2}", "43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777"),
                Arguments.of("{\"b\":2,\"a\":1}", "3fb75453225c732a76b7899ea2096dda1455189c89817239732182f73fe5a09f"));
    }
}
