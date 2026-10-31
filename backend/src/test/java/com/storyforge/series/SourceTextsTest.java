package com.storyforge.series;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The poem text store: verbatim quotes (scenario 4), allowed translations (scenario 5), excerpts for D1. */
class SourceTextsTest {

    static final Path ROOT = Path.of("target/test-sources-unit");
    SourceTexts sources;

    @BeforeEach
    void setUp() throws Exception {
        SourceFixture.write(ROOT);
        sources = new SourceTexts(ROOT);
    }

    @Test
    void the_repo_source_file_describes_the_poem() {
        SourceTexts repo = new SourceTexts(Path.of("../sources"));
        SourceTexts.Source s = repo.get("lusiadas");
        assertThat(s.title()).isEqualTo("Os Lusíadas");
        assertThat(s.year()).isEqualTo(1572);
        assertThat(s.stanzaCounts().stream().mapToInt(Integer::intValue).sum()).isEqualTo(1102);
        assertThat(s.names()).contains("Adamastor", "Camões", "Vasco da Gama");
        assertThat(repo.ids()).containsExactly("lusiadas");
    }

    @Test
    void s4_a_verse_moment_must_quote_the_stanza_verbatim() {
        assertThat(sources.isVerbatim("lusiadas", 1, 1, String.join("\n", SourceFixture.STANZA_I_1))).isTrue();
        // A run of consecutive lines is a valid quote, and line breaks may differ.
        assertThat(sources.isVerbatim("lusiadas", 1, 1,
                "As armas e os barões assinalados,  Que da ocidental praia Lusitana,")).isTrue();
        // Changed spelling, dropped accent or skipped line: not verbatim.
        assertThat(sources.isVerbatim("lusiadas", 1, 1, "As armas e os barões assinalados,\nQue da occidental praia Lusitana,")).isFalse();
        assertThat(sources.isVerbatim("lusiadas", 1, 1, "As armas e os baroes assinalados,")).isFalse();
        assertThat(sources.isVerbatim("lusiadas", 1, 1,
                "As armas e os barões assinalados,\nPor mares nunca de antes navegados,")).isFalse();
    }

    @Test
    void s5_only_our_own_or_pre_1929_translations_are_allowed() {
        assertThat(sources.translation("lusiadas", "burton-1880")).get()
                .extracting(SourceTexts.Translation::credit).asString().contains("Burton, 1880");
        assertThat(sources.translation("lusiadas", "own")).isPresent();
        assertThat(sources.translation("lusiadas", "white-1997")).isEmpty();
    }

    @Test
    void an_excerpt_returns_its_stanzas_verbatim_in_order() {
        var excerpt = SourceExcerpt.parse("I 1-3; II 5", sources.get("lusiadas"));
        var stanzas = sources.excerpt("lusiadas", excerpt);
        assertThat(stanzas).extracting(SourceTexts.Stanza::canto, SourceTexts.Stanza::number)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 1), org.assertj.core.groups.Tuple.tuple(1, 2),
                        org.assertj.core.groups.Tuple.tuple(1, 3), org.assertj.core.groups.Tuple.tuple(2, 5));
        assertThat(stanzas.getFirst().lines()).isEqualTo(SourceFixture.STANZA_I_1);
    }

    @Test
    void a_canto_file_with_the_wrong_number_of_stanzas_is_refused() throws Exception {
        Path f = ROOT.resolve("lusiadas/canto-07.txt");
        String text = Files.readString(f);
        Files.writeString(f, text.substring(0, text.lastIndexOf("\n87\n") + 1));
        assertThatThrownBy(() -> sources.canto("lusiadas", 7)).hasMessageContaining("has 86 stanzas, expected 87");
    }

    @Test
    void a_missing_text_says_how_to_import_it() throws Exception {
        Files.delete(ROOT.resolve("lusiadas/canto-03.txt"));
        assertThat(sources.hasText("lusiadas")).isFalse();
        assertThatThrownBy(() -> sources.canto("lusiadas", 3)).hasMessageContaining("import_gutenberg.py");
    }

    @Test
    void excerpts_parse_roman_or_arabic_cantos_and_reject_nonsense() {
        var src = sources.get("lusiadas");
        assertThat(SourceExcerpt.parse("Canto V 37-60", src).asText()).isEqualTo("V 37-60");
        assertThat(SourceExcerpt.parse("10:145-156", src).label()).isEqualTo("Canto X, st. 145–156");
        assertThatThrownBy(() -> SourceExcerpt.parse("the storm bit", src)).hasMessageContaining("Can't read");
        assertThatThrownBy(() -> SourceExcerpt.parse("III 50-40", src)).hasMessageContaining("stanzas 1 to 143");
    }
}
