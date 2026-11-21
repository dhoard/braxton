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

package io.github.dhoard.braxton.widget;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.internal.markdown.CodeHighlighter;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The image hook and the bundled highlighters beyond Java, JSON and shell.
 */
class MarkdownImageAndHighlightTest {

    private static List<String> styled(StyledText text, Style style) {
        List<String> values = new ArrayList<>();
        for (StyledText.Span span : text.spans()) {
            if (span.style().equals(style)) {
                values.add(span.text());
            }
        }
        return values;
    }

    private static StyledText highlight(String language, String code) {
        return CodeHighlighter.highlight(code, language, CodeHighlighter.Styles.light());
    }

    @Test
    void pythonRubyJavaScriptGoRustCAndKotlinAreHighlighted() {
        CodeHighlighter.Styles styles = CodeHighlighter.Styles.light();

        assertThat(styled(highlight("python", "# note\ndef f(): pass"), styles.comment()))
                .containsExactly("# note");
        assertThat(styled(highlight("python", "def f(): pass"), styles.keyword()))
                .containsExactly("def", "pass");
        assertThat(styled(highlight("py", "value = None"), styles.literal())).containsExactly("None");

        assertThat(styled(highlight("ruby", "# note\nmodule M; end"), styles.comment()))
                .containsExactly("# note");
        assertThat(styled(highlight("rb", "module M; end"), styles.keyword())).containsExactly("module", "end");

        assertThat(styled(highlight("javascript", "// note\nconst x = `t`"), styles.comment()))
                .containsExactly("// note");
        assertThat(styled(highlight("typescript", "const x = `t`"), styles.keyword()))
                .containsExactly("const");
        assertThat(styled(highlight("ts", "const x = `t`"), styles.string())).containsExactly("`t`");

        assertThat(styled(highlight("go", "// note\nfunc f() {}"), styles.comment()))
                .containsExactly("// note");
        assertThat(styled(highlight("golang", "func f() {}"), styles.keyword())).containsExactly("func");

        assertThat(styled(highlight("rust", "// note\nfn f() {}"), styles.comment()))
                .containsExactly("// note");
        assertThat(styled(highlight("rs", "let x = 1;"), styles.keyword())).containsExactly("let");

        assertThat(styled(highlight("c", "/* note */\nint x = NULL;"), styles.comment()))
                .containsExactly("/* note */");
        assertThat(styled(highlight("cpp", "int x = NULL;"), styles.keyword())).containsExactly("int");
        assertThat(styled(highlight("h", "int x = NULL;"), styles.literal())).containsExactly("NULL");

        assertThat(styled(highlight("kotlin", "// note\nval x = null"), styles.comment()))
                .containsExactly("// note");
        assertThat(styled(highlight("kt", "val x = null"), styles.keyword())).containsExactly("val");
    }

    @Test
    void anUnknownLanguageStaysPlain() {
        assertThat(highlight("brainfuck", "++[>+<-]").plainText()).isEqualTo("++[>+<-]");
        assertThat(highlight("", "let x = 1").spans())
                .allSatisfy(span -> assertThat(span.style()).isEqualTo(Style.NONE));
    }

    @Test
    void aFencedBlockUsesItsDialect() {
        Markdown markdown = Markdown.of();
        Markdown.Document document = markdown.document("```python\ndef f():\n    return True\n```");

        List<Style> styles = new ArrayList<>();
        for (StyledText line : document.lines()) {
            for (StyledText.Span span : line.spans()) {
                styles.add(span.style());
            }
        }
        assertThat(styles).anySatisfy(style -> assertThat(style).isNotEqualTo(Style.NONE));
        assertThat(document.lines()).extracting(StyledText::plainText).contains("def f():");
    }

    @Test
    void anImageAloneInAParagraphIsPaintedByTheHook() {
        List<Markdown.ImageRef> asked = new ArrayList<>();
        MarkdownConfig config = MarkdownConfig.defaults().withImageHook((image, width) -> {
            asked.add(image);
            return List.of(StyledText.plain("[picture " + width + "]"));
        });

        Markdown.Document document = new Markdown(config).document("![a cat](cat.png \"Cat\")");

        assertThat(asked).containsExactly(new Markdown.ImageRef("cat.png", "a cat", "Cat"));
        assertThat(document.lines()).extracting(StyledText::plainText).containsExactly("[picture 80]");
    }

    @Test
    void aHookThatDeclinesKeepsTheAltText() {
        Markdown byDefault = Markdown.of();
        Markdown withHook = new Markdown(MarkdownConfig.defaults().withImageHook((image, width) -> null));

        assertThat(byDefault.document("![a cat](cat.png)").lines())
                .extracting(StyledText::plainText)
                .containsExactly("a cat");
        assertThat(withHook.document("![a cat](cat.png)").lines())
                .extracting(StyledText::plainText)
                .containsExactly("a cat");
    }

    @Test
    void aHookThatAnswersNothingPaintsNothing() {
        Markdown markdown = new Markdown(MarkdownConfig.defaults().withImageHook((image, width) -> List.of()));

        assertThat(markdown.document("![a cat](cat.png)").lines()).isEmpty();
    }

    @Test
    void anImageInsideRunningTextIsItsAltTextUnlessTheHookAnswersInline() {
        List<Markdown.ImageRef> asked = new ArrayList<>();
        Markdown byBlockOnly = new Markdown(MarkdownConfig.defaults().withImageHook((image, width) -> {
            asked.add(image);
            return List.of(StyledText.plain("picture"));
        }));

        Markdown.Document document = byBlockOnly.document("before ![a cat](cat.png) after");

        assertThat(asked).isEmpty();
        assertThat(document.lines()).extracting(StyledText::plainText).containsExactly("before a cat after");

        Markdown.ImageHook inline = new Markdown.ImageHook() {
            @Override
            public List<StyledText> rows(Markdown.ImageRef image, int width) {
                return null;
            }

            @Override
            public StyledText inline(Markdown.ImageRef image, Style style) {
                asked.add(image);
                return StyledText.of("[img]", style);
            }
        };
        asked.clear();
        Markdown.Document replaced = new Markdown(MarkdownConfig.defaults().withImageHook(inline))
                .document("before ![a cat](cat.png) after");

        assertThat(asked).containsExactly(new Markdown.ImageRef("cat.png", "a cat", ""));
        assertThat(replaced.lines()).extracting(StyledText::plainText).containsExactly("before [img] after");
    }

    @Test
    void aHookRowIsBrokenToTheProjectionWidth() {
        Markdown markdown = new Markdown(MarkdownConfig.defaults()
                .withWidth(6)
                .withImageHook((image, width) -> List.of(StyledText.plain("0123456789"), StyledText.plain("second"))));

        Markdown.Document document = markdown.document("![x](x.png)");

        assertThat(document.lines()).extracting(StyledText::plainText).containsExactly("012345", "6789", "second");
        assertThat(document.lines())
                .allSatisfy(line ->
                        assertThat(TextMeasure.columns(line.plainText())).isLessThanOrEqualTo(6));
    }
}
