package dev.packupdater;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalModFilterTest {

    /** The "Bare Bones" shape from a real pack: optional, but enabled by default. */
    private static final String DEFAULT_ON = """
            name = "Bare Bones"
            filename = "Bare Bones 1.21.11.zip"
            side = "both"
            pin = true

            [download]
            url = "https://cdn.modrinth.com/data/rox3U8B6/versions/jQBWn2Q3/Bare%20Bones%201.21.11.zip"
            hash-format = "sha512"
            hash = "268b578d6d34adaa2491572af4cabf65fab94edee00e688f5b4cb4f072c0620c4"

            [option]
            optional = true
            default = true
            description = "Alternative texture pack."
            """;

    private static final String DEFAULT_OFF = """
            name = "Shader Pack"
            filename = "shaders.zip"
            side = "both"

            [download]
            url = "https://example.com/shaders.zip"
            hash-format = "sha256"
            hash = "deadbeef"

            [option]
            optional = true
            default = false
            description = "Shaders, heavy on mobile."
            """;

    private static final String NO_OPTION = """
            name = "Required Mod"
            filename = "required.jar"
            side = "both"

            [download]
            url = "https://example.com/required.jar"
            hash-format = "sha256"
            hash = "cafebabe"
            """;

    private static final String OPTIONAL_NO_DEFAULT = """
            name = "Ambiguous Mod"
            filename = "ambiguous.jar"
            side = "both"

            [download]
            url = "https://example.com/ambiguous.jar"
            hash-format = "sha256"
            hash = "0ddba11"

            [option]
            optional = true
            """;

    @Test
    void optionalModEnabledByDefaultIsKept() {
        assertFalse(OptionalModFilter.isOptedOut(DEFAULT_ON),
                "an optional mod the pack enables by default must survive the fallback");
    }

    @Test
    void optionalModDisabledByDefaultIsDropped() {
        assertTrue(OptionalModFilter.isOptedOut(DEFAULT_OFF),
                "an optional mod the pack disables by default must be skipped");
    }

    @Test
    void requiredModIsKept() {
        assertFalse(OptionalModFilter.isOptedOut(NO_OPTION));
    }

    @Test
    void optionalModWithoutAStatedDefaultIsKept() {
        // The spec leaves this undefined, so the less destructive choice is to install it.
        assertFalse(OptionalModFilter.isOptedOut(OPTIONAL_NO_DEFAULT));
    }

    @Test
    void optionBlockIsNotConfusedWithOtherTables() {
        // [download] must not be mistaken for [option], and a "default" in another table
        // must not be read as the option default.
        String tricky = """
                name = "Tricky"
                filename = "tricky.jar"

                [download]
                url = "https://example.com/tricky.jar"
                default = false

                [option]
                optional = true
                """;
        assertFalse(OptionalModFilter.isOptedOut(tricky),
                "a default under [download] must not count as the option default");
    }

    @Test
    void noOptionBlockMeansKeep() {
        assertFalse(OptionalModFilter.isOptedOut("name = \"x\"\nfilename = \"x.jar\"\n"));
    }

    @Test
    void optionRequiredFalseIsNotTreatedAsOptional() {
        String requiredViaOption = """
                name = "Pinned"
                filename = "pinned.jar"

                [option]
                optional = false
                default = false
                """;
        assertFalse(OptionalModFilter.isOptedOut(requiredViaOption));
    }
}