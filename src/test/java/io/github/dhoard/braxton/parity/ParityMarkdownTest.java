/*
 * Copyright (c) 2026-present Douglas Hoard
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.dhoard.braxton.parity;

import static org.assertj.core.api.Assertions.assertThat;

import examples.parity.MarkdownDemo;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.Markdown;
import io.github.dhoard.braxton.widget.MarkdownConfig;
import io.github.dhoard.braxton.widget.Table;
import io.github.dhoard.braxton.widget.TextViewport;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Phase 15 acceptance: the markdown dialect, its styling, its bounds, its cache and the demo pager.
 *
 * <p>Every assertion is against public behavior: the rows a projection publishes, the styles and
 * hyperlink targets its spans carry, the typed diagnostics a bound answers with, the instance a
 * repeated projection returns, and what a routed key does to the demo scene. The renderer a test
 * drives is the shipped {@link Markdown}, the pane it pages with is the shipped
 * {@link TextViewport}, and the scene a verifier runs is the demo's own.</p>
 */
class ParityMarkdownTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    private static final int SCREEN_ROWS = 24;

    private static final int SCREEN_COLS = 80;

    /**
     * The cells one CJK glyph occupies under the pinned width policy.
     */
    private static final String WIDE = "\u63d0\u706f";

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * A renderer at one width with the default theme, highlighter and bounds.
     */
    private static Markdown renderer(int width) {
        return Markdown.of(MarkdownConfig.defaults().withWidth(width));
    }

    private static Markdown.Document render(String source, int width) {
        return renderer(width).document(source);
    }

    /**
     * The projected rows as plain text.
     */
    private static List<String> rows(Markdown.Document document) {
        return document.lines().stream().map(StyledText::plainText).toList();
    }

    /**
     * Every span of a projection, in paint order.
     */
    private static List<StyledText.Span> spans(Markdown.Document document) {
        List<StyledText.Span> spans = new ArrayList<>();
        for (StyledText line : document.lines()) {
            spans.addAll(line.spans());
        }
        return spans;
    }

    /**
     * Every hyperlink target a projection published, in paint order.
     */
    private static List<String> links(Markdown.Document document) {
        return spans(document).stream()
                .map(StyledText.Span::link)
                .filter(link -> link != null)
                .toList();
    }

    /**
     * The style of the span whose text is {@code text}, or of the first one holding it.
     */
    private static Style styleOf(Markdown.Document document, String text) {
        List<StyledText.Span> spans = spans(document);
        return spans.stream()
                .filter(span -> span.text().equals(text))
                .map(StyledText.Span::style)
                .findFirst()
                .or(() -> spans.stream()
                        .filter(span -> span.text().contains(text))
                        .map(StyledText.Span::style)
                        .findFirst())
                .orElseThrow(() -> new AssertionError("no span holding " + text + " in " + rows(document)));
    }

    /**
     * The columns a projection's rows occupy, as the pane would charge them.
     */
    private static List<Integer> columns(Markdown.Document document) {
        return document.lines().stream()
                .map(line -> TextMeasure.columns(line.plainText()))
                .toList();
    }

    private static InputEvent.KeyInput key(String spec) {
        return InputEvent.KeyInput.of(Key.parse(spec));
    }

    private static InputEvent.KeyInput typed(String text) {
        return InputEvent.KeyInput.of(Key.parse(text), text);
    }

    // ── R-01: the dialect ────────────────────────────────────────────────────

    @Test
    void everyHeadingLevelPaintsItsOwnStyle() {
        Markdown.Document document =
                render("# one\n\n## two\n\n### three\n\n#### four\n\n##### five\n\n###### six", 40);

        assertThat(rows(document)).containsExactly("one", "", "two", "", "three", "", "four", "", "five", "", "six");
        Markdown.Theme theme = Markdown.Theme.light();
        for (int level = 1; level <= 6; level++) {
            String text = rows(document).get((level - 1) * 2);
            assertThat(styleOf(document, text)).isEqualTo(theme.heading(level));
        }
        assertThat(Markdown.Theme.light().heading(1))
                .isNotEqualTo(Markdown.Theme.light().heading(2));
    }

    @Test
    void emphasisStrongStrikethroughAndCodeCarryTheirOwnStyles() {
        Markdown.Document document = render("some *em*, **strong**, ~~struck~~ and `code`.", 60);
        Markdown.Theme theme = Markdown.Theme.light();

        assertThat(rows(document)).containsExactly("some em, strong, struck and code.");
        assertThat(styleOf(document, "em")).isEqualTo(theme.emphasis());
        assertThat(styleOf(document, "strong")).isEqualTo(theme.strong());
        assertThat(styleOf(document, "struck")).isEqualTo(theme.strikethrough());
        assertThat(styleOf(document, "code")).isEqualTo(theme.code());
        assertThat(rows(document).get(0)).doesNotContain("*", "~", "`");
    }

    @Test
    void escapedMarkersStayLiteral() {
        Markdown.Document document = render("\\*not emphasis\\* and \\[not a link\\]", 60);

        assertThat(rows(document)).containsExactly("*not emphasis* and [not a link]");
        assertThat(links(document)).isEmpty();
    }

    @Test
    void aLinkKeepsItsTargetOnlyForAnAllowedScheme() {
        Markdown.Document document = render(
                "[ok](https://example.invalid/a) [mail](mailto:a@example.invalid) "
                        + "[rel](./local.md) [js](javascript:alert(1)) [file](file:///etc/passwd)",
                80);

        assertThat(rows(document)).containsExactly("ok mail rel js file");
        assertThat(links(document)).containsExactly("https://example.invalid/a", "mailto:a@example.invalid");
    }

    @Test
    void aBareUrlAndAnAngleBracketAutolinkAreLinks() {
        Markdown.Document document = render("see https://bare.example and <https://auto.example>", 80);

        assertThat(links(document)).containsExactly("https://bare.example", "https://auto.example");
        assertThat(rows(document)).containsExactly("see https://bare.example and https://auto.example");
    }

    @Test
    void anImagePaintsItsAltTextAndNoTarget() {
        Markdown.Document document = render("![a paper lantern](lantern.png)", 60);

        assertThat(rows(document)).containsExactly("a paper lantern");
        assertThat(links(document)).isEmpty();
    }

    @Test
    void rawHtmlPaintsAsTheTextItIs() {
        Markdown.Document document = render(
                "before <b>bold</b> after\n\n<div class=\"stall\">\n  <p>kept</p>\n</div>\n\n<script>alert(1)</script>",
                60);

        List<String> rows = rows(document);
        assertThat(rows).contains("before <b>bold</b> after");
        assertThat(String.join("\n", rows))
                .contains("<div class=\"stall\">", "<p>kept</p>", "<script>alert(1)</script>");
        assertThat(rows).allSatisfy(row -> assertThat(row).doesNotContain("\u001b"));
        assertThat(styleOf(document, "<b>")).isEqualTo(Markdown.Theme.light().muted());
    }

    @Test
    void aTaskListPaintsItsCheckboxes() {
        Markdown.Document document = render("- [x] done\n- [ ] open", 40);

        assertThat(rows(document)).containsExactly("[x] done", "[ ] open");
        assertThat(styleOf(document, "[x]")).isEqualTo(Markdown.Theme.light().marker());
    }

    @Test
    void nestedListsIndentByTheirOwnMarkers() {
        Markdown.Document document = render("- alpha\n  - beta\n\n1. one\n   1. inner", 40);

        assertThat(rows(document)).containsExactly("\u2022 alpha", "  \u2022 beta", "", "1. one", "   1. inner");
    }

    @Test
    void aQuotePaintsABarAndNests() {
        Markdown.Document document = render("> quoted\n>\n> > deeper", 40);

        assertThat(rows(document)).containsExactly("\u2502 quoted", "\u2502 ", "\u2502 \u2502 deeper");
        assertThat(styleOf(document, "quoted")).isEqualTo(Markdown.Theme.light().quote());
    }

    @Test
    void aThematicBreakPaintsTheWholeWidth() {
        Markdown.Document document = render("alpha\n\n---\n\nbeta", 12);

        assertThat(rows(document)).containsExactly("alpha", "", "\u2500".repeat(12), "", "beta");
        assertThat(styleOf(document, "\u2500\u2500\u2500"))
                .isEqualTo(Markdown.Theme.light().rule());
    }

    @Test
    void aHardBreakStartsARowAndASoftBreakJoinsOne() {
        Markdown.Document document = render("hard  \nbreak\n\nsoft\nbreak", 40);

        assertThat(rows(document)).containsExactly("hard", "break", "", "soft break");
    }

    @Test
    void fencedAndIndentedCodeKeepTheirText() {
        Markdown.Document document = render("```\nx = 1\n```\n\n    y = 2\n", 40);

        assertThat(rows(document)).containsExactly("x = 1", "", "y = 2");
    }

    @Test
    void aTablePaintsAFrameWithItsDeclaredAlignments() {
        Markdown.Document document =
                render("| part | count | notes |\n| :--- | ----: | :---: |\n| a | 2 | x |\n| bbbb | 12 | y |", 40);

        // A framed grid whose columns keep their widths: the count column is right-aligned, so its
        // one- and two-digit cells share an edge, and the notes column is centered with its header.
        assertThat(rows(document))
                .containsExactly(
                        "\u250c" + "\u2500".repeat(20) + "\u2510",
                        "\u2502 part  count  notes \u2502",
                        "\u2502 a         2    x   \u2502",
                        "\u2502 bbbb     12    y   \u2502",
                        "\u2514" + "\u2500".repeat(20) + "\u2518");
        assertThat(styleOf(document, "part"))
                .isEqualTo(Markdown.Theme.light()
                        .text()
                        .merge(Markdown.Theme.light().header()));
    }

    @Test
    void malformedMarkdownStillPaintsSafeText() {
        for (String source : List.of("```\nunclosed fence", "**unclosed", "| a | b\n|---", "<div", "> ")) {
            Markdown.Document document = render(source, 40);
            assertThat(document.failed()).as(source).isFalse();
            assertThat(document.lineCount()).as(source).isPositive();
            assertThat(rows(document)).allSatisfy(row -> assertThat(row).doesNotContain("\u001b"));
        }
    }

    @Test
    void anEmptyDocumentPaintsNothing() {
        Markdown.Document empty = render("", 40);
        assertThat(empty.lineCount()).isZero();
        assertThat(empty.content().lineCount()).isZero();
        assertThat(empty.failed()).isFalse();
        assertThat(empty.text().isEmpty()).isTrue();
        assertThat(render(null, 40).lineCount()).isZero();

        TextViewport pane = new TextViewport(TextViewport.Config.defaults());
        TextViewport.Model state = TextViewport.Model.of(empty.content());
        assertThat(TextViewport.visible(state, pane.config())).isEmpty();
    }

    @Test
    void wideGlyphsAreChargedTheColumnsTheyPaint() {
        String source = WIDE.repeat(5);
        Markdown.Document document = render(source, 9);

        assertThat(columns(document)).allSatisfy(columns -> assertThat(columns).isLessThanOrEqualTo(9));
        assertThat(String.join("", rows(document))).isEqualTo(source);
        assertThat(rows(document)).hasSizeGreaterThan(1);
    }

    @Test
    void aProjectionPaintsItsWholeValueAndItsRows() {
        Markdown.Document document = render("# Title\n\nbody **text**\n", 40);

        assertThat(document.text().plainText()).isEqualTo("Title\n\nbody text");
        assertThat(document.text()).isSameAs(document.text());
        // A one-element view of the projection paints the same value a text view would.
        assertThat(ElementScreen.render(Elements.text(document.text()), 6, 20).lines())
                .containsExactly("Title", "", "body text", "", "", "");
    }

    @Test
    void aProjectionPublishesSingleLineRows() {
        Markdown.Document document = render(MarkdownDemo.SOURCE, 78);

        assertThat(document.failed()).isFalse();
        assertThat(document.lines())
                .allSatisfy(line -> assertThat(line.plainText()).doesNotContain("\n"));
        assertThat(document.content().lineCount()).isEqualTo(document.lineCount());
        for (int index = 0; index < document.lineCount(); index++) {
            assertThat(document.content().line(index))
                    .isEqualTo(document.lines().get(index));
        }
    }

    // ── R-02: styling, themes and highlighting ──────────────────────────────

    @Test
    void aNestedDocumentProjectsTheGoldenRows() {
        Markdown.Document document =
                render("# Title\n\nThe *quick* brown fox jumps over the lazy dog.\n\n- one\n  - two\n\n> quoted\n", 24);

        assertThat(rows(document))
                .containsExactly(
                        "Title",
                        "",
                        "The quick brown fox",
                        "jumps over the lazy dog.",
                        "",
                        "\u2022 one",
                        "  \u2022 two",
                        "",
                        "\u2502 quoted");
    }

    @Test
    void theBundledHighlightersStyleJavaJsonAndShell() {
        Markdown.Document java = render("```java\npublic int a = 42; // note\nString s = \"hi\";\n```", 60);
        Style keyword = styleOf(java, "public");
        assertThat(keyword).isNotEqualTo(Style.NONE);
        assertThat(styleOf(java, "42")).isNotEqualTo(keyword);
        assertThat(styleOf(java, "// note")).isNotEqualTo(keyword);
        assertThat(styleOf(java, "\"hi\"")).isNotEqualTo(keyword);
        // The theme's code-block style is merged over every token.
        Markdown.Theme theme = Markdown.Theme.light();
        assertThat(styleOf(java, "public").background())
                .isEqualTo(theme.codeBlock().background());

        Markdown.Document json = render("```json\n{\"a\": 1, \"ok\": true}\n```", 60);
        assertThat(styleOf(json, "\"ok\"")).isNotEqualTo(Style.NONE);
        assertThat(styleOf(json, "true")).isNotEqualTo(Style.NONE);

        Markdown.Document shell = render("```sh\nif [ -x \"$hook\" ]; then\n    echo hi # note\nfi\n```", 60);
        assertThat(styleOf(shell, "if")).isNotEqualTo(Style.NONE);
        assertThat(styleOf(shell, "# note")).isNotEqualTo(Style.NONE);
    }

    @Test
    void anUnknownLanguageAndThePlainHighlighterPaintUnchangedText() {
        Markdown.Document unknown = render("```\nkept as written\n```", 40);
        assertThat(rows(unknown)).containsExactly("kept as written");
        assertThat(unknown.lines().getFirst().spans())
                .allSatisfy(span -> assertThat(span.style())
                        .isEqualTo(Style.bg(Markdown.Theme.light().codeBlock().background())));

        Markdown plain =
                Markdown.of(MarkdownConfig.defaults().withWidth(40).withHighlighter(Markdown.Highlighter.plain()));
        Markdown.Document document = plain.document("```java\npublic int a = 1;\n```");
        assertThat(document.lines().getFirst().spans())
                .allSatisfy(span -> assertThat(span.style().foreground()).isNull());
    }

    @Test
    void lightAndDarkThemesPaintDifferentStyles() {
        String source = "# Title\n\nbody *emphasis*\n";
        Markdown.Document light = render(source, 40);
        Markdown.Document dark =
                Markdown.of(MarkdownConfig.dark().withWidth(40)).document(source);

        assertThat(rows(light)).isEqualTo(rows(dark));
        assertThat(styleOf(light, "Title")).isNotEqualTo(styleOf(dark, "Title"));
        assertThat(styleOf(dark, "Title")).isEqualTo(Markdown.Theme.dark().heading(1));
        assertThat(MarkdownConfig.dark().highlighter()).isEqualTo(Markdown.Highlighter.dark());
    }

    @Test
    void aCustomThemeIsAppliedToEveryBlockKind() {
        Style distinctive = Style.fg(io.github.dhoard.braxton.style.Color.parseOr("#010203", null))
                .withBold();
        Markdown.Theme theme = Markdown.Theme.light()
                .withMarker(distinctive)
                .withRule(distinctive)
                .withDiagnostic(distinctive);
        Markdown.Document document = Markdown.of(
                        MarkdownConfig.defaults().withTheme(theme).withWidth(40))
                .document("- bullet\n\n---\n");

        assertThat(styleOf(document, "\u2022")).isEqualTo(distinctive);
        assertThat(styleOf(document, "\u2500")).isEqualTo(distinctive);
    }

    @Test
    void aProjectionHoldsNoControlCharacters() {
        Markdown.Document document = render(MarkdownDemo.SOURCE, 78);

        for (StyledText line : document.lines()) {
            String text = line.plainText();
            assertThat(text.chars().anyMatch(value -> value < 0x20 || (value >= 0x7F && value <= 0x9F)))
                    .as(text)
                    .isFalse();
        }
    }

    // ── R-03: bounds, diagnostics and the cache ─────────────────────────────

    @Test
    void anInputOverTheByteLimitIsATypedFailure() {
        Markdown tiny =
                Markdown.of(MarkdownConfig.defaults().withWidth(40).withLimits(new Markdown.Limits(16, 1000, 8, 1000)));
        Markdown.Document document = tiny.document("A source string longer than sixteen bytes.");

        assertThat(document.failed()).isTrue();
        assertThat(document.diagnostics())
                .containsExactly(new Markdown.Diagnostic.InputTooLarge(
                        "A source string longer than sixteen bytes."
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                .length,
                        16));
        assertThat(rows(document))
                .containsExactly(
                        "markdown: " + document.diagnostics().getFirst().message());
        assertThat(styleOf(document, "markdown:"))
                .isEqualTo(Markdown.Theme.light().diagnostic());
    }

    @Test
    void aNodeBudgetIsATypedFailure() {
        Markdown bounded = Markdown.of(MarkdownConfig.defaults()
                .withWidth(40)
                .withLimits(new Markdown.Limits(Markdown.DEFAULT_MAX_INPUT_BYTES, 4, 8, 1000)));
        Markdown.Document document = bounded.document("one\n\ntwo\n\nthree\n");

        assertThat(document.failed()).isTrue();
        assertThat(document.diagnostics()).hasSize(1);
        assertThat(document.diagnostics().getFirst()).isInstanceOf(Markdown.Diagnostic.TooManyNodes.class);
    }

    @Test
    void aDepthBudgetIsATypedFailure() {
        Markdown bounded = Markdown.of(MarkdownConfig.defaults()
                .withWidth(40)
                .withLimits(new Markdown.Limits(Markdown.DEFAULT_MAX_INPUT_BYTES, 1000, 2, 1000)));
        Markdown.Document document = bounded.document("> > > deep\n");

        assertThat(document.failed()).isTrue();
        assertThat(document.diagnostics().getFirst()).isInstanceOf(Markdown.Diagnostic.NestingTooDeep.class);
        assertThat(rows(document).getFirst()).startsWith("markdown: nesting too deep");
    }

    @Test
    void aCellBudgetIsATypedFailure() {
        Markdown bounded = Markdown.of(MarkdownConfig.defaults()
                .withWidth(40)
                .withLimits(new Markdown.Limits(Markdown.DEFAULT_MAX_INPUT_BYTES, 1000, 8, 10)));
        Markdown.Document document = bounded.document("one two three four five six seven eight nine ten\n");

        assertThat(document.failed()).isTrue();
        assertThat(document.diagnostics().getFirst()).isInstanceOf(Markdown.Diagnostic.TooManyCells.class);
        assertThat(rows(document)).containsExactly("markdown: too many cells: more than 10");
    }

    @Test
    void limitsCannotBeRaisedAboveTheDocumentedCeilings() {
        Markdown.Limits raised =
                new Markdown.Limits(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE);
        assertThat(raised).isEqualTo(Markdown.Limits.defaults());

        Markdown.Limits floored = new Markdown.Limits(0, -1, 0, -1);
        assertThat(floored.inputBytes()).isEqualTo(1);
        assertThat(floored.nodes()).isEqualTo(1);
        assertThat(floored.depth()).isEqualTo(1);
        assertThat(floored.cells()).isEqualTo(1);
    }

    @Test
    void aFailedProjectionDoesNotPoisonTheNextOne() {
        Markdown renderer = renderer(40);
        Markdown.Document strict = Markdown.of(MarkdownConfig.defaults()
                        .withWidth(40)
                        .withLimits(new Markdown.Limits(Markdown.DEFAULT_MAX_INPUT_BYTES, 1000, 8, 4)))
                .document("# Title\n\nbody\n");
        assertThat(strict.failed()).isTrue();

        Markdown.Document document = renderer.document("# Title\n\nbody\n");
        assertThat(document.failed()).isFalse();
        assertThat(rows(document)).containsExactly("Title", "", "body");
    }

    @Test
    void aRepeatedProjectionIsTheSameInstance() {
        Markdown renderer = renderer(40);
        String source = "# Title\n\nbody\n";

        Markdown.Document first = renderer.document(source);
        assertThat(renderer.document(new String(source)))
                .as("the cache is keyed by the source's value")
                .isSameAs(first);
        assertThat(first.diagnostics()).isEmpty();
        assertThat(first.content()).isSameAs(renderer.document(source).content());
    }

    @Test
    void aDifferentWidthOrThemeIsADifferentProjectionAndTheCacheKeepsBoth() {
        Markdown renderer = renderer(40);
        String source = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi";
        Markdown.Document narrow = renderer.document(source);
        Markdown.Document wide =
                Markdown.of(MarkdownConfig.defaults().withWidth(80)).document(source);

        assertThat(narrow).isNotSameAs(wide);
        assertThat(rows(narrow).size()).isGreaterThan(rows(wide).size());

        Markdown.Document theme =
                Markdown.of(MarkdownConfig.dark().withWidth(40)).document(source);
        assertThat(theme).isNotSameAs(narrow);
        assertThat(theme.diagnostics()).isEmpty();

        // Returning to a definition the cache still holds answers the projection it already made.
        assertThat(renderer.document(source)).isSameAs(narrow);
        renderer.clearCache();
        assertThat(renderer.document(source)).isNotSameAs(narrow);
    }

    @Test
    void zeroAndOneCellWidthsStayBoundedAndValid() {
        for (int width : new int[] {0, 1}) {
            Markdown.Document document = render("alpha beta", width);
            assertThat(document.failed()).as("width " + width).isFalse();
            assertThat(columns(document))
                    .allSatisfy(columns -> assertThat(columns).isLessThanOrEqualTo(2));
            assertThat(String.join("", rows(document)).replace(" ", "")).isEqualTo("alphabeta");
        }
    }

    // ── R-04: the document, the demo and the pager ──────────────────────────

    @Test
    void theCheckedInDocumentCoversEverySupportedConstruct() {
        String source = MarkdownDemo.SOURCE;
        assertThat(source).contains("# A field guide", "## ", "### ", "#### ", "##### ", "###### ");
        assertThat(source).contains("*", "**", "~~", "`", "]", "![", "\\*", "  \n");
        assertThat(source).contains("| part   | count | notes", "| :----- | ----: | :---");
        assertThat(source).contains("- [x]", "- [ ]", "   1. ", "> > ", "```java", "```json", "```sh", "```\n");
        assertThat(source).contains("<span", "<div", "\n---\n");
        assertThat(source).contains(WIDE);
        assertThat(source).contains(MarkdownDemo.KEYWORD);

        Markdown.Document document = render(source, 78);
        List<String> rows = rows(document);
        assertThat(document.failed()).isFalse();
        assertThat(rows).contains("A field guide to the paper lantern");
        assertThat(rows).contains("A note on the weather", "A note on the knot", "A note on the market");
        assertThat(rows).anySatisfy(row -> assertThat(row).contains("public final class Candle"));
        assertThat(rows).anySatisfy(row -> assertThat(row).contains("\u250c"));
        assertThat(rows).contains("[x] Paper bought at the market", "[ ] Lantern hung");
        assertThat(rows).anySatisfy(row -> assertThat(row).startsWith("\u2502 A lantern is a lamp"));
        assertThat(rows).anySatisfy(row -> assertThat(row).contains("<div class=\"stall\">"));
        assertThat(rows).anySatisfy(row -> assertThat(row).contains("\u63d0\u706f"));
        assertThat(rows).anySatisfy(row -> assertThat(row).contains("https://example.invalid/lanterns"));
        // Every destination the document publishes is one the default policy allows.
        assertThat(links(document))
                .isNotEmpty()
                .allSatisfy(link -> assertThat(link).matches("(?i)^(https?|mailto):.*"));
        assertThat(columns(document)).allSatisfy(columns -> assertThat(columns).isLessThanOrEqualTo(78));
    }

    @Test
    void theDeterministicProjectionIsStableAcrossCalls() {
        Markdown first = renderer(60);
        Markdown second = renderer(60);

        assertThat(first.document(MarkdownDemo.SOURCE)).isEqualTo(second.document(MarkdownDemo.SOURCE));
        assertThat(first.document(MarkdownDemo.SOURCE).text().plainText())
                .isEqualTo(render(MarkdownDemo.SOURCE, 60).text().plainText());
    }

    private static MarkdownDemo.Scene demo() {
        return new MarkdownDemo.Scene();
    }

    private static MarkdownDemo.Model start() {
        return start(SCREEN_ROWS, SCREEN_COLS);
    }

    private static MarkdownDemo.Model start(int rows, int cols) {
        return demo().init(new InitContext(new Viewport(rows, cols), null)).model();
    }

    private static MarkdownDemo.Model apply(MarkdownDemo.Model state, MarkdownDemo.Msg message) {
        return demo().update(state, message).model();
    }

    private static Optional<MarkdownDemo.Msg> route(MarkdownDemo.Model state, InputEvent event) {
        return io.github.dhoard.braxton.testing.Routing.route(demo(), state, CONTEXT, event).stream()
                .findFirst();
    }

    private static MarkdownDemo.Model send(MarkdownDemo.Model state, String keySpec) {
        return apply(
                state, route(state, key(keySpec)).orElseThrow(() -> new AssertionError("no message for " + keySpec)));
    }

    private static MarkdownDemo.Model type(MarkdownDemo.Model state, String text) {
        MarkdownDemo.Model typed = state;
        for (char character : text.toCharArray()) {
            String value = String.valueOf(character);
            typed = apply(
                    typed, route(typed, typed(value)).orElseThrow(() -> new AssertionError("no message for " + value)));
        }
        return typed;
    }

    private static List<String> demoLines(MarkdownDemo.Model state) {
        Element<MarkdownDemo.Msg> view = demo().view(state, CONTEXT);
        return ElementScreen.render(view, SCREEN_ROWS, SCREEN_COLS).lines();
    }

    private static String demoRow(MarkdownDemo.Model state, int row) {
        return demoLines(state).get(row).stripTrailing();
    }

    @Test
    void theDemoOpensOnTheFirstRowWithTheGutter() {
        MarkdownDemo.Model model = start();
        int width = MarkdownDemo.projectionWidth(model);

        assertThat(width).isEqualTo(SCREEN_COLS - MarkdownDemo.GUTTER_COLUMNS);
        assertThat(demoRow(model, 0))
                .isEqualTo("theme=light mode=wrap numbers=on rows=" + MarkdownDemo.pagerRows(SCREEN_ROWS) + " width="
                        + width + " lines=" + model.document().lineCount() + " limits=default");
        assertThat(demoRow(model, 1)).isEqualTo("diagnostics=none matches=0 active=0 query=- focus=pager last=started");
        assertThat(demoRow(model, 2)).isEqualTo("find:");
        assertThat(demoRow(model, 3)).isEqualTo("  1 \u2502 A field guide to the paper lantern");
        assertThat(demoRow(model, 22)).contains("\u2191/k up");
        assertThat(demoRow(model, 23)).contains("t dark/light");
    }

    @Test
    void theDemoPaintsTheDocumentThroughThePager() {
        MarkdownDemo.Model model = start();
        TextViewport.Config config = MarkdownDemo.pagerConfig(model, 80, 80);

        // A viewport tall enough for the whole projection paints exactly the document's rows.
        TextViewport.Model tall = new TextViewport.Model(
                0,
                0,
                0,
                false,
                true,
                model.document().lineCount() + 4,
                80,
                model.document().content(),
                List.of(),
                -1);
        List<String> painted = TextViewport.visible(tall, MarkdownDemo.pagerConfig(model, 0, 80)).stream()
                .map(row -> row.text().plainText())
                .toList();
        assertThat(painted).contains("public final class Candle {", "[x] Paper bought at the market");
        assertThat(painted).anySatisfy(row -> assertThat(row).startsWith("\u2502 A lantern is a lamp"));
        assertThat(painted).anySatisfy(row -> assertThat(row).startsWith("\u250c"));

        // The demo's own pager is the widget, not a copy: its first row is the document's first.
        List<TextViewport.Row> window = TextViewport.visible(model.pager(), config);
        assertThat(window.getFirst().text().plainText()).isEqualTo("A field guide to the paper lantern");
        assertThat(window.getFirst().line()).isZero();
        assertThat(config.gutter().width()).isEqualTo(MarkdownDemo.GUTTER_COLUMNS);
        assertThat(config.gutter())
                .isNotNull()
                .satisfies(gutter -> assertThat(gutter.renderer().render(new TextViewport.Facts(0, 0, 1, 1)))
                        .isEqualTo(MarkdownDemo.gutter().renderer().render(new TextViewport.Facts(0, 0, 1, 1))));
    }

    @Test
    void theDemoTogglesTheThemeAndReprojects() {
        MarkdownDemo.Model model = start();
        Markdown.Document light = model.document();

        MarkdownDemo.Model dark = send(model, "t");

        assertThat(dark.dark()).isTrue();
        assertThat(dark.document()).isNotSameAs(light);
        assertThat(demoRow(dark, 0)).startsWith("theme=dark");
        assertThat(demoRow(dark, 1)).endsWith("last=theme-dark");
        assertThat(dark.document().lineCount()).isEqualTo(light.lineCount());
        assertThat(styleOf(dark.document(), "A field guide"))
                .isEqualTo(Markdown.Theme.dark().heading(1));
        assertThat(styleOf(light, "A field guide"))
                .isEqualTo(Markdown.Theme.light().heading(1));
    }

    @Test
    void theDemoTogglesTheModeAndReprojectsWide() {
        MarkdownDemo.Model model = start();
        int wrapped = model.document().lineCount();

        MarkdownDemo.Model panned = send(model, "w");

        assertThat(panned.mode()).isEqualTo(TextViewport.Mode.PAN);
        assertThat(MarkdownDemo.projectionWidth(panned)).isEqualTo(MarkdownDemo.PAN_WIDTH);
        assertThat(demoRow(panned, 0)).contains("mode=pan").contains("width=" + MarkdownDemo.PAN_WIDTH);
        assertThat(panned.document().lineCount()).isLessThan(wrapped);

        // Panning has something to pan because the projection is wider than the pane.
        MarkdownDemo.Model scrolled = send(panned, "right");
        assertThat(scrolled.pager().firstColumn()).isPositive();
        assertThat(demoRow(scrolled, 3)).doesNotStartWith("  1 \u2502 A field guide");
    }

    @Test
    void theDemoTogglesTheGutterAndTheProjectionWidth() {
        MarkdownDemo.Model model = start();

        MarkdownDemo.Model bare = send(model, "g");

        assertThat(bare.numbers()).isFalse();
        assertThat(MarkdownDemo.projectionWidth(bare)).isEqualTo(SCREEN_COLS);
        assertThat(demoRow(bare, 0)).contains("numbers=off").contains("width=" + SCREEN_COLS);
        assertThat(demoRow(bare, 3)).isEqualTo("A field guide to the paper lantern");

        MarkdownDemo.Model restored = send(bare, "g");
        assertThat(demoRow(restored, 3)).isEqualTo("  1 \u2502 A field guide to the paper lantern");
    }

    @Test
    void theDemoShowsATypedDiagnosticUnderStrictLimits() {
        MarkdownDemo.Model model = start();
        int lines = model.document().lineCount();

        MarkdownDemo.Model strict = send(model, "p");

        assertThat(strict.strict()).isTrue();
        assertThat(demoRow(strict, 0)).contains("lines=1").contains("limits=strict");
        assertThat(demoRow(strict, 1)).contains("diagnostics=too-many-cells");
        assertThat(strict.document().failed()).isTrue();
        assertThat(strict.document().diagnostics().getFirst())
                .isEqualTo(new Markdown.Diagnostic.TooManyCells(
                        ((Markdown.Diagnostic.TooManyCells)
                                        strict.document().diagnostics().getFirst())
                                .cells(),
                        MarkdownDemo.STRICT_CELLS));
        assertThat(demoRow(strict, 3))
                .startsWith("  1 \u2502 markdown: too many cells: more than " + MarkdownDemo.STRICT_CELLS);

        MarkdownDemo.Model restored = send(strict, "p");
        assertThat(restored.document().failed()).isFalse();
        assertThat(restored.document().lineCount()).isEqualTo(lines);
        assertThat(demoRow(restored, 3)).isEqualTo("  1 \u2502 A field guide to the paper lantern");
    }

    @Test
    void theDemoFindsEveryKeywordAndWalksThem() {
        MarkdownDemo.Model model = start();
        int expected = MarkdownDemo.matches(model.document().lines(), MarkdownDemo.KEYWORD)
                .size();
        assertThat(expected).isPositive();

        MarkdownDemo.Model searched = type(send(model, "/"), MarkdownDemo.KEYWORD);

        assertThat(searched.pager().highlights()).hasSize(expected);
        assertThat(demoRow(searched, 1))
                .isEqualTo("diagnostics=none matches=" + expected + " active=1 query=" + MarkdownDemo.KEYWORD
                        + " focus=field last=typed");
        assertThat(searched.search().focused()).isTrue();
        assertThat(searched.pager().focused()).isFalse();

        MarkdownDemo.Model back = send(searched, "escape");
        assertThat(back.search().focused()).isFalse();
        assertThat(back.pager().active()).isZero();
        MarkdownDemo.Model next = send(back, "n");
        assertThat(next.pager().active()).isEqualTo(1);
        assertThat(send(next, "alt+n").pager().active()).isZero();
        assertThat(send(back, "alt+n").pager().active()).isEqualTo(expected - 1);
    }

    @Test
    void aMatchBelowTheWindowIsScrolledIntoView() {
        MarkdownDemo.Model model = start();
        MarkdownDemo.Model searched = send(type(send(model, "/"), "knot"), "escape");
        assertThat(searched.pager().highlights()).isNotEmpty();
        MarkdownDemo.Model last = searched;
        for (int index = 1; index < searched.pager().highlights().size(); index++) {
            last = send(last, "n");
        }

        TextViewport.Highlight active =
                last.pager().highlights().get(last.pager().active());
        assertThat(TextViewport.visible(last.pager(), MarkdownDemo.pagerConfig(last, 0, 0)))
                .extracting(TextViewport.Row::line)
                .contains(active.line());
        assertThat(last.pager().topLine()).isPositive();
    }

    @Test
    void aResizeReprojectsAtTheNewWidthAndKeepsTheAnchor() {
        MarkdownDemo.Model model = start();
        MarkdownDemo.Model scrolled = model;
        for (int index = 0; index < 12; index++) {
            scrolled = send(scrolled, "down");
        }
        assertThat(scrolled.pager().topLine()).isPositive();

        MarkdownDemo.Model resized = apply(scrolled, new MarkdownDemo.Msg.Resized(16, 60));

        assertThat(resized.pager().rows()).isEqualTo(MarkdownDemo.pagerRows(16));
        assertThat(resized.pager().columns()).isEqualTo(60);
        assertThat(demoRow(resized, 0))
                .as("a narrower projection wraps into more rows")
                .contains("width=" + (60 - MarkdownDemo.GUTTER_COLUMNS));
        assertThat(resized.document().lineCount())
                .isGreaterThan(model.document().lineCount());
        assertThat(resized.pager().topLine())
                .as("the reader keeps their place through a re-projection")
                .isPositive();
        assertThat(demoRow(resized, 1)).endsWith("last=resized");
    }

    @Test
    void aResizeThatChangesNothingIsStillOneProjection() {
        MarkdownDemo.Model model = start();
        MarkdownDemo.Model again = apply(model, new MarkdownDemo.Msg.Resized(SCREEN_ROWS, SCREEN_COLS));

        assertThat(again.document()).isEqualTo(model.document());
        assertThat(again.pager().topLine()).isZero();
    }

    @Test
    void theDemoPublishesTheSameMatchesTheWidgetHighlights() {
        MarkdownDemo.Model model = start();
        MarkdownDemo.Model searched = type(send(model, "/"), MarkdownDemo.KEYWORD);

        assertThat(searched.pager().highlights())
                .isEqualTo(MarkdownDemo.matches(searched.document().lines(), MarkdownDemo.KEYWORD));
        for (TextViewport.Highlight highlight : searched.pager().highlights()) {
            String line = searched.document().lines().get(highlight.line()).plainText();
            assertThat(line.substring(highlight.start(), highlight.end()).toLowerCase(Locale.ROOT))
                    .isEqualTo(MarkdownDemo.KEYWORD);
        }
    }

    // ── R-05: preserved behavior and additive table alignment ──────────────

    @Test
    void tableAlignmentIsAdditiveAndDefaultsToLeft() {
        Table plain = Table.of()
                .row(StyledText.plain("a"), StyledText.plain("bbbb"))
                .row(StyledText.plain("ccc"), StyledText.plain("d"));
        Table left = Table.of()
                .alignments(List.of(Table.Alignment.LEFT, Table.Alignment.LEFT))
                .row(StyledText.plain("a"), StyledText.plain("bbbb"))
                .row(StyledText.plain("ccc"), StyledText.plain("d"));

        assertThat(left.renderLines()).isEqualTo(plain.renderLines());
        assertThat(plain.columnWidths()).isEqualTo(left.columnWidths());
        assertThat(plain.renderLines())
                .allSatisfy(line -> assertThat(line.plainText())
                        .hasSize(plain.renderLines().getFirst().plainText().length()));

        Table right = Table.of()
                .alignments(List.of(Table.Alignment.RIGHT, Table.Alignment.CENTER))
                .row(StyledText.plain("a"), StyledText.plain("bbbb"))
                .row(StyledText.plain("ccc"), StyledText.plain("d"));
        List<String> rows =
                right.renderLines().stream().map(StyledText::plainText).toList();
        assertThat(rows.getFirst().stripTrailing())
                .as("the right column's short cell is centered")
                .isNotEqualTo(left.renderLines().getFirst().plainText().stripTrailing());
        assertThat(rows.getFirst().length())
                .isEqualTo(plain.renderLines().getFirst().plainText().length());
        assertThat(rows.get(1).length()).isEqualTo(rows.getFirst().length());
    }

    @Test
    void aWidthBudgetStillTruncatesAFittedTableWithAlignment() {
        Table table = Table.of()
                .alignments(List.of(Table.Alignment.RIGHT, Table.Alignment.CENTER))
                .buffer(1)
                .width(20)
                .row(StyledText.plain("part"), StyledText.plain("a note that is far too long"));

        assertThat(table.renderLines())
                .allSatisfy(line ->
                        assertThat(TextMeasure.columns(line.plainText())).isLessThanOrEqualTo(20));
    }

    @Test
    void theDemoReportsNoFailureForItsOwnDocument() {
        Markdown.Document document = render(MarkdownDemo.SOURCE, MarkdownDemo.PAN_WIDTH);

        assertThat(document.failed()).isFalse();
        assertThat(document.lineCount()).isPositive();
        assertThat(rows(document).stream().collect(Collectors.joining("\n")))
                .contains("A field guide to the paper lantern")
                .contains("A note on the market")
                .doesNotContain("markdown:");
    }
}
