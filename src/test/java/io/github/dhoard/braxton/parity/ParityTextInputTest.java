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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import examples.parity.TextInputDemo;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.internal.input.InputDecoder;
import io.github.dhoard.braxton.internal.input.InputRouter;
import io.github.dhoard.braxton.internal.input.TestReaders;
import io.github.dhoard.braxton.internal.terminal.TimedReader;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.testing.ComponentDriver;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.TextInput;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Phase 05 acceptance: a single-line text input - one row that never breaks, horizontal scrolling,
 * validation, completion and a three-field form.
 *
 * <p>Every assertion is against public behaviour: the model a message produces, the outputs it
 * emits, the row the field paints, and what a routed key does to the demo scene.</p>
 */
class ParityTextInputTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    // ── helpers ──────────────────────────────────────────────────────────────

    private static TextInput.Config plain() {
        return TextInput.Config.defaults();
    }

    private static TextInput.Config completing(String... candidates) {
        return plain().withCompletion(TextInput.Completion.prefix());
    }

    private static Update<TextInput.Model, TextInput.Msg, TextInput.Output> sent(
            TextInput.Model state, TextInput.Config config, TextInput.Msg message) {
        return new TextInput(config).update(state, message);
    }

    private static TextInput.Model after(TextInput.Model state, TextInput.Config config, TextInput.Msg message) {
        return sent(state, config, message).model();
    }

    private static TextInput.Model at(String text, int caret) {
        TextInput.Model base = TextInput.Model.of(text);
        return new TextInput.Model(
                base.draft().withCaret(caret), base.validation(), base.suggestions(), base.revision());
    }

    private static TextInput.Model withCandidates(TextInput.Model state, String... candidates) {
        return new TextInput.Model(
                state.draft(),
                state.validation(),
                new TextInput.Suggestions(state.revision(), List.of(candidates), 0),
                state.revision());
    }

    private static String row(TextInput.Model state, TextInput.Config config, boolean focused) {
        return TextInput.render(state, config, focused).plainText();
    }

    private static String row(TextInput.Model state, TextInput.Config config, boolean focused, int cols) {
        return TextInput.render(state, config, focused, cols).plainText();
    }

    /** The row a real layout paints, which is what a user sees. */
    private static List<String> painted(TextInput.Model state, TextInput.Config config, boolean focused, int cols) {
        return new ScreenRenderer()
                .plainLines(
                        new TextInput(config)
                                .view(state.withFocus(focused), new ViewContext(new Viewport(1, cols)))
                                .map(message -> message),
                        new Viewport(1, cols));
    }

    /** The expanded help of a key map: one line per group, holding every action in the map. */
    private static List<String> help(io.github.dhoard.braxton.input.KeyMap<TextInput.Msg> keys) {
        return new ScreenRenderer()
                .plainLines(Help.view(keys, true, 400), new Viewport(12, 400)).stream()
                        .filter(line -> !line.isBlank())
                        .toList();
    }

    /** A validator for the ISBN fixtures the reference example uses. */
    private static TextInput.Validation isbn13(String text) {
        String digits = text.replace("-", "");
        if (digits.length() != 13) {
            return TextInput.Validation.invalid("ISBN is of wrong length");
        }
        int sum = 0;
        for (int index = 0; index < digits.length(); index++) {
            char digit = digits.charAt(index);
            if (digit < '0' || digit > '9') {
                return TextInput.Validation.invalid("ISBN contains invalid characters");
            }
            sum += (digit - '0') * (index % 2 == 0 ? 1 : 3);
        }
        String prefix = digits.substring(0, 3);
        if (!prefix.equals("978") && !prefix.equals("979")) {
            return TextInput.Validation.invalid("ISBN has invalid GS1 prefix");
        }
        return sum % 10 == 0 ? TextInput.Validation.ok() : TextInput.Validation.invalid("ISBN has invalid check digit");
    }

    private static TextInput.Config isbn() {
        return plain().withMaxLength(17).withValidator(ParityTextInputTest::isbn13);
    }

    // ── R-01: one row, and the width it scrolls for ──────────────────────────

    @Test
    void everyLineBreakAnInsertionCarriesBecomesOneSpace() {
        TextInput.Config config = plain();
        TextInput.Model state = after(TextInput.Model.of(""), config, new TextInput.Msg.Insert("a\r\nb"));
        assertThat(state.text()).as("CRLF is one break, so one space").isEqualTo("a b");
        state = after(state, config, new TextInput.Msg.Insert("c\rd"));
        assertThat(state.text()).as("a lone CR is one break").isEqualTo("a bc d");
        state = after(state, config, new TextInput.Msg.Insert("e\nf"));
        assertThat(state.text()).as("a lone LF is one break").isEqualTo("a bc de f");
        assertThat(state.text()).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void aStoredDraftIsFlattenedToOneRow() {
        assertThat(TextInput.Model.of("one\r\ntwo\rthree\nfour").text()).isEqualTo("one two three four");
        assertThat(TextInput.Model.of("a\r\nb").text()).isEqualTo("a b");
        assertThat(TextInput.render(TextInput.Model.of("a\r\nb"), plain(), true).lines())
                .as("a flattened draft is one row")
                .hasSize(1);
    }

    @Test
    void aPasteIsSanitizedAndFlattened() {
        TextInput.Model state =
                after(TextInput.Model.of(""), plain(), new TextInput.Msg.Paste("line one\r\nline two\n\nline three"));
        assertThat(state.text()).isEqualTo("line one line two  line three");
        assertThat(state.text()).doesNotContain("\n");
    }

    @Test
    void theFieldPaintsExactlyOneRowHoweverLongTheDraftIs() {
        TextInput.Config config = plain().withWidth(20);
        TextInput.Model state = TextInput.Model.of("alpha beta gamma delta");
        assertThat(TextInput.render(state, config, true).lines()).hasSize(1);
        assertThat(painted(state, config, true, 20)).hasSize(1);
    }

    @Test
    void aNarrowFieldScrollsHorizontallyToKeepTheCaretVisible() {
        TextInput.Config config = plain().withWidth(8);
        TextInput.Model state = TextInput.Model.of("abcdefghijklmnop");
        assertThat(TextInput.scrollOffset(state, config, 8)).isEqualTo(9);
        String painted = row(state, config, true);
        assertThat(painted).as("eight cells of row, caret included").hasSize(8);
        assertThat(painted).isEqualTo("jklmnop\u2588");
        assertThat(painted).doesNotContain("a").doesNotContain("i");
    }

    @Test
    void aCaretInTheMiddleOfALongDraftScrollsTheRowToIt() {
        TextInput.Config config = plain().withWidth(6);
        TextInput.Model state = at("0123456789", 8);
        String painted = row(state, config, true);
        assertThat(painted).hasSize(6);
        // The caret replaces the cluster it sits on, so the eighth character is the block.
        assertThat(painted).isEqualTo("34567\u2588");
        assertThat(TextInput.scrollOffset(state, config, 6)).isEqualTo(3);
    }

    @Test
    void theAllocatedWidthWinsWhenItIsNarrowerThanTheDeclaredOne() {
        TextInput.Config config = plain().withWidth(40);
        TextInput.Model state = TextInput.Model.of("abcdefghijklmnopqrstuvwxyz");
        assertThat(TextInput.scrollOffset(state, config, 40)).isZero();
        assertThat(TextInput.scrollOffset(state, config, 10)).isEqualTo(17);
        assertThat(row(state, config, true, 10)).hasSize(10);
    }

    @Test
    void aOneCellFieldPaintsTheCaretAndNothingElse() {
        TextInput.Config config = plain().withWidth(1);
        TextInput.Model state = TextInput.Model.of("abc");
        assertThat(row(state, config, true)).isEqualTo("\u2588");
        assertThat(row(state, config, false))
                .as("one cell of the row, with no caret to show")
                .isEqualTo("c");
    }

    @Test
    void aFieldThatIsGivenNoWidthPaintsItsWholeDraft() {
        TextInput.Config config = plain();
        TextInput.Model state = TextInput.Model.of("abc");
        assertThat(row(state, config, true)).isEqualTo("abc\u2588");
        assertThat(TextInput.scrollOffset(state, config, 0)).isZero();
    }

    @Test
    void wideGlyphsScrollWholeAndKeepTheCaretVisible() {
        TextInput.Config config = plain().withWidth(4);
        TextInput.Model state = TextInput.Model.of("\u65e5\u672c\u8a9e");
        String painted = row(state, config, true);
        assertThat(painted)
                .as("the trailing wide glyph and the caret, never half a glyph")
                .isEqualTo("\u8a9e\u2588");
        assertThat(painted).doesNotContain("\u65e5");
    }

    @Test
    void aTabInADraftIsPaintedAsTheSpacesOfItsStop() {
        TextInput.Config config = plain().withWidth(12);
        TextInput.Model state = at("a\tb", 3);
        String painted = row(state, config, true);
        assertThat(painted).isEqualTo("a   b\u2588");
    }

    @Test
    void aSelectionIsPaintedWithTheSelectionStyleAndReplacedByTyping() {
        TextInput.Config config = plain().withStyles(Style.NONE, Style.NONE.withUnderline(), Style.NONE);
        TextInput.Model selected = after(TextInput.Model.of("hello world"), config, new TextInput.Msg.SelectAll());
        assertThat(selected.hasSelection()).isTrue();
        assertThat(selected.selection()).isEqualTo("hello world");
        List<StyledText.Span> spans = TextInput.render(selected, config, false).spans();
        assertThat(spans).anySatisfy(span -> assertThat(span.style()).isEqualTo(Style.NONE.withUnderline()));
        assertThat(after(selected, config, new TextInput.Msg.Insert("bye")).text())
                .isEqualTo("bye");
    }

    @Test
    void theLengthCapCountsUtf16UnitsWithoutSplittingACluster() {
        TextInput.Config config = plain().withMaxLength(3);
        String thumbsUp = "\uD83D\uDC4D";
        TextInput.Model state = after(TextInput.Model.of(""), config, new TextInput.Msg.Insert(thumbsUp + thumbsUp));
        assertThat(state.text())
                .as("one two-unit cluster fits, the second does not")
                .isEqualTo(thumbsUp);
        state = after(state, config, new TextInput.Msg.Insert("a"));
        assertThat(state.text()).isEqualTo(thumbsUp + "a");
        // A cluster longer than the whole limit never lands half-inserted.
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
        assertThat(after(TextInput.Model.of(""), config, new TextInput.Msg.Insert(family))
                        .text())
                .isEmpty();
    }

    @Test
    void theLengthCapAppliesToAPasteAsWell() {
        TextInput.Config config = plain().withMaxLength(5);
        assertThat(after(TextInput.Model.of(""), config, new TextInput.Msg.Paste("abcdefghij"))
                        .text())
                .isEqualTo("abcde");
    }

    // ── R-02: validation ────────────────────────────────────────────────────

    @Test
    void anEmptyDraftIsValidatedByThePolicyItIsGiven() {
        TextInput field = new TextInput(isbn());
        TextInput.Model initialized = field.init(new InitContext(null, null)).model();
        assertThat(initialized.valid()).isFalse();
        assertThat(initialized.validation().message()).isEqualTo("ISBN is of wrong length");
    }

    @Test
    void aValidDraftTransitionsFromInvalidToValid() {
        TextInput.Config config = isbn();
        TextInput field = new TextInput(config);
        TextInput.Model state = field.init(new InitContext(null, null)).model();
        Update<TextInput.Model, TextInput.Msg, TextInput.Output> typed =
                sent(state, config, new TextInput.Msg.Insert("9783548372570"));
        assertThat(typed.model().valid()).isTrue();
        assertThat(typed.outputs()).containsExactly(new TextInput.Output.ValidationChanged(TextInput.Validation.ok()));
        // A failing draft keeps a message, and the message describes why.
        TextInput.Model wrongCheckDigit = field.validated(TextInput.Model.of("9783548372571"));
        assertThat(wrongCheckDigit.validation().message()).isEqualTo("ISBN has invalid check digit");
        assertThat(field.validated(TextInput.Model.of("1234567890123"))
                        .validation()
                        .message())
                .isEqualTo("ISBN has invalid GS1 prefix");
        assertThat(field.validated(TextInput.Model.of("978-3-548-37257-0")).valid())
                .isTrue();
    }

    @Test
    void validationIsOnlyReportedWhenItChanges() {
        TextInput.Config config = plain().withValidator(text -> text.isEmpty()
                ? TextInput.Validation.ok()
                : text.length() < 3 ? TextInput.Validation.invalid("needs 3 characters") : TextInput.Validation.ok());
        TextInput field = new TextInput(config);
        TextInput.Model state = field.init(new InitContext(null, null)).model();
        assertThat(state.valid()).isTrue();

        Update<TextInput.Model, TextInput.Msg, TextInput.Output> first =
                sent(state, config, new TextInput.Msg.Insert("a"));
        assertThat(first.outputs())
                .containsExactly(
                        new TextInput.Output.ValidationChanged(TextInput.Validation.invalid("needs 3 characters")));
        Update<TextInput.Model, TextInput.Msg, TextInput.Output> second =
                sent(first.model(), config, new TextInput.Msg.Insert("b"));
        assertThat(second.outputs()).as("the answer did not change").isEmpty();
        Update<TextInput.Model, TextInput.Msg, TextInput.Output> third =
                sent(second.model(), config, new TextInput.Msg.Insert("c"));
        assertThat(third.outputs()).containsExactly(new TextInput.Output.ValidationChanged(TextInput.Validation.ok()));
    }

    @Test
    void anInvalidDraftStaysEditableAndItsSubmitIsRejected() {
        TextInput.Config config = isbn();
        TextInput field = new TextInput(config);
        TextInput.Model state = field.init(new InitContext(null, null)).model();
        state = after(state, config, new TextInput.Msg.Insert("9783"));
        assertThat(state.text()).isEqualTo("9783");
        assertThat(state.valid()).isFalse();
        // Editing an invalid draft keeps working: the refusal is a submit policy, not a lock.
        state = after(state, config, new TextInput.Msg.Insert("548372570"));
        assertThat(state.valid()).isTrue();

        TextInput.Model invalid = field.validated(TextInput.Model.of("9783"));
        Update<TextInput.Model, TextInput.Msg, TextInput.Output> rejected =
                sent(invalid, config, new TextInput.Msg.Submit());
        assertThat(rejected.outputs()).containsExactly(new TextInput.Output.Rejected(isbn13("9783")));
        assertThat(rejected.outputs()).noneMatch(output -> output instanceof TextInput.Output.Submit);
    }

    @Test
    void aValidDraftSubmitsItsText() {
        TextInput.Config config = plain().withValidator(
                        text -> text.isEmpty() ? TextInput.Validation.invalid("empty") : TextInput.Validation.ok());
        TextInput.Model valid = new TextInput(config).validated(TextInput.Model.of("hello"));
        assertThat(sent(valid, config, new TextInput.Msg.Submit()).outputs())
                .containsExactly(new TextInput.Output.Submit("hello"));
    }

    @Test
    void anInvalidDraftSubmitsWhenThePolicyAllowsIt() {
        TextInput.Config config = plain().withValidator(text -> TextInput.Validation.invalid("always"))
                .withSubmitPolicy(TextInput.SubmitPolicy.ALLOW_INVALID);
        TextInput.Model state = new TextInput(config).validated(TextInput.Model.of("draft"));
        assertThat(sent(state, config, new TextInput.Msg.Submit()).outputs())
                .containsExactly(new TextInput.Output.Submit("draft"));
    }

    @Test
    void replacingTheDraftRevalidatesIt() {
        TextInput.Config config = plain().withValidator(
                        text -> text.equals("ok") ? TextInput.Validation.ok() : TextInput.Validation.invalid("not ok"));
        TextInput.Model state = new TextInput(config).validated(TextInput.Model.of("bad"));
        assertThat(state.valid()).isFalse();
        state = after(state, config, new TextInput.Msg.SelectAll());
        state = after(state, config, new TextInput.Msg.Insert("ok"));
        assertThat(state.valid()).isTrue();
        // Clearing is an edit too, so the validation follows the draft back to empty.
        state = after(state, config, new TextInput.Msg.Clear());
        assertThat(state.text()).isEmpty();
        assertThat(state.valid()).isFalse();
    }

    @Test
    void aValidatorThatThrowsIsNotSwallowed() {
        TextInput.Config config = plain().withValidator(text -> {
            throw new IllegalStateException("validator exploded");
        });
        assertThatThrownBy(() -> sent(TextInput.Model.of("a"), config, new TextInput.Msg.Insert("b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validator exploded");
    }

    @Test
    void aValidatorThatAnswersNothingIsAConfigurationFailure() {
        TextInput.Config config = plain().withValidator(text -> null);
        assertThatThrownBy(() -> sent(TextInput.Model.of("a"), config, new TextInput.Msg.Insert("b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validator returned no validation");
    }

    @Test
    void aRestoredDraftIsValidatedOnDemand() {
        TextInput field = new TextInput(isbn());
        TextInput.Model restored = field.validated(TextInput.Model.of("9783"));
        assertThat(restored.valid()).isFalse();
        assertThat(restored.validation().message()).isEqualTo("ISBN is of wrong length");
        assertThat(field.validated(TextInput.Model.of("9783548372570")).valid()).isTrue();
    }

    // ── R-03: completion ────────────────────────────────────────────────────

    @Test
    void candidatesMatchByCaseSensitivePrefixInTheOrderTheyWereSupplied() {
        TextInput.Config config = completing();
        TextInput.Model state = withCandidates(TextInput.Model.of("bra"), "braxton", "Branch", "branch", "gum");
        assertThat(TextInput.matchedCandidates(state, config)).containsExactly("braxton", "branch");
        assertThat(TextInput.selectedCandidate(state, config)).contains("braxton");
        assertThat(TextInput.suggestedSuffix(state, config)).isEqualTo("xton");
        assertThat(TextInput.matchedCandidates(withCandidates(TextInput.Model.of("Bra"), "braxton", "Branch"), config))
                .as("case-sensitive by default")
                .containsExactly("Branch");
        assertThat(TextInput.matchedCandidates(
                        withCandidates(TextInput.Model.of("Bra"), "braxton", "Branch"),
                        completing()
                                .withCompletion(TextInput.Completion.prefix().withIgnoreCase(true))))
                .containsExactly("braxton", "Branch");
    }

    @Test
    void anEmptyDraftHasNothingToComplete() {
        TextInput.Config config = completing();
        TextInput.Model state = withCandidates(TextInput.Model.of(""), "braxton");
        assertThat(TextInput.matchedCandidates(state, config)).isEmpty();
        assertThat(TextInput.selectedCandidate(state, config)).isEmpty();
        assertThat(TextInput.suggestedSuffix(state, config)).isEmpty();
    }

    @Test
    void completingIsOffUnlessItIsAskedFor() {
        TextInput.Config config = plain();
        TextInput.Model state = withCandidates(TextInput.Model.of("br"), "braxton");
        assertThat(TextInput.matchedCandidates(state, config)).isEmpty();
        assertThat(TextInput.suggestedSuffix(state, config)).isEmpty();
    }

    @Test
    void acceptingACandidateReplacesTheWholeDraft() {
        TextInput.Config config = completing();
        TextInput.Model state = withCandidates(at("brax", 2), "braxton");
        TextInput.Model accepted = after(state, config, new TextInput.Msg.AcceptSuggestion());
        assertThat(accepted.text())
                .as("the complete candidate, never a fragment of the draft it replaced")
                .isEqualTo("braxton");
        assertThat(accepted.caret()).isEqualTo("braxton".length());
    }

    @Test
    void cyclingChangesTheSelectedCandidateAndWrapsAtBothEnds() {
        TextInput.Config config = completing();
        TextInput.Model state = withCandidates(TextInput.Model.of("br"), "bravo", "braxton", "branch");
        assertThat(TextInput.selectedCandidate(state, config)).contains("bravo");
        state = after(state, config, new TextInput.Msg.NextSuggestion());
        assertThat(TextInput.selectedCandidate(state, config)).contains("braxton");
        state = after(state, config, new TextInput.Msg.NextSuggestion());
        assertThat(TextInput.selectedCandidate(state, config)).contains("branch");
        state = after(state, config, new TextInput.Msg.NextSuggestion());
        assertThat(TextInput.selectedCandidate(state, config))
                .as("wrapping past the last")
                .contains("bravo");
        state = after(state, config, new TextInput.Msg.PreviousSuggestion());
        assertThat(TextInput.selectedCandidate(state, config)).contains("branch");
        // Accepting the cycled candidate inserts that one.
        assertThat(after(state, config, new TextInput.Msg.AcceptSuggestion()).text())
                .isEqualTo("branch");
    }

    @Test
    void duplicateCandidatesAreCollapsedWhenTheyAreSupplied() {
        TextInput.Config config = plain();
        TextInput.Model state = after(
                TextInput.Model.of(""),
                config,
                new TextInput.Msg.Candidates(0, java.util.Arrays.asList("alpha", "alpha", null, "", "beta")));
        assertThat(state.suggestions().candidates()).containsExactly("alpha", "beta");
        // One match cannot cycle: a duplicate must not make it look like two.
        assertThat(TextInput.matchedCandidates(withCandidates(TextInput.Model.of("a"), "alpha"), completing()))
                .hasSize(1);
    }

    @Test
    void anAnswerForAnOlderDraftIsIgnored() {
        TextInput.Config config = plain();
        TextInput.Model state = after(TextInput.Model.of(""), config, new TextInput.Msg.Insert("b"));
        assertThat(state.revision()).isEqualTo(1);
        TextInput.Model answered = after(state, config, new TextInput.Msg.Candidates(0, List.of("stale")));
        assertThat(answered.suggestions().candidates())
                .as("a stale answer changed nothing")
                .isEmpty();
        assertThat(answered).as("and the model instance is the incoming one").isSameAs(state);
        TextInput.Model fresh = after(state, config, new TextInput.Msg.Candidates(1, List.of("fresh")));
        assertThat(fresh.suggestions().candidates()).containsExactly("fresh");
    }

    @Test
    void editingInvalidatesAnObsoleteCompletion() {
        TextInput.Config config = completing();
        TextInput.Model state = withCandidates(TextInput.Model.of("br"), "braxton");
        assertThat(TextInput.suggestedSuffix(state, config)).isEqualTo("axton");
        state = after(state, config, new TextInput.Msg.Insert("zz"));
        assertThat(TextInput.matchedCandidates(state, config)).isEmpty();
        assertThat(TextInput.suggestedSuffix(state, config)).isEmpty();
        assertThat(TextInput.selectedCandidate(state, config)).isEmpty();
    }

    @Test
    void aUnicodePrefixMatchesAndItsSuffixIsTheRestOfTheCandidate() {
        TextInput.Config config = completing();
        TextInput.Model state = withCandidates(TextInput.Model.of("\u65e5\u672c"), "\u65e5\u672c\u8a9e");
        assertThat(TextInput.matchedCandidates(state, config)).containsExactly("\u65e5\u672c\u8a9e");
        assertThat(TextInput.suggestedSuffix(state, config)).isEqualTo("\u8a9e");
        assertThat(row(state, config, true)).isEqualTo("\u65e5\u672c\u2588\u8a9e");
    }

    @Test
    void aMaskedDraftNeverCompletes() {
        TextInput.Config config =
                plain().withMasking(Editor.Config.Masking.PASSWORD).withCompletion(TextInput.Completion.prefix());
        TextInput.Model state = withCandidates(TextInput.Model.of("hunter"), "hunter2");
        assertThat(TextInput.matchedCandidates(state, config)).isEmpty();
        assertThat(TextInput.suggestedSuffix(state, config)).isEmpty();
        assertThat(row(state, config, true))
                .as("one bullet per cluster, the caret, and no ghost")
                .isEqualTo("\u2022\u2022\u2022\u2022\u2022\u2022\u2588");
        Update<TextInput.Model, TextInput.Msg, TextInput.Output> typed =
                sent(state, config, new TextInput.Msg.Insert("2"));
        assertThat(typed.outputs())
                .as("a masked field never asks for candidates")
                .noneMatch(output -> output instanceof TextInput.Output.SuggestionsRequested);
        // Copying a masked draft produces nothing, whatever the field holds.
        assertThat(sent(state, config, new TextInput.Msg.Copy()).outputs())
                .containsExactly(new TextInput.Output.Copy(""));
    }

    @Test
    void aMaskedClusterPaintsOneGlyphSoTheCaretFollowsTheMaskNotTheDraft() {
        TextInput.Config config = plain().withMasking(Editor.Config.Masking.PASSWORD);
        // A joined cluster of eight UTF-16 units masks as one glyph, so a caret at the draft's end
        // is the second cell of the painted row, not the ninth.
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
        TextInput.Model state = TextInput.Model.of(family);
        assertThat(row(state, config, true)).isEqualTo("\u2022\u2588");
        assertThat(TextInput.scrollOffset(state, config, 4)).isZero();
        // And the mask is what copying refuses, whatever the caret is on.
        assertThat(sent(state, config, new TextInput.Msg.Copy()).outputs())
                .containsExactly(new TextInput.Output.Copy(""));
    }

    @Test
    void aHiddenDraftPaintsOnlyItsCaret() {
        TextInput.Config config = plain().withMasking(Editor.Config.Masking.HIDDEN);
        TextInput.Model state = TextInput.Model.of("secret");
        assertThat(row(state, config, true)).isEqualTo("\u2588");
        assertThat(row(state, config, false))
                .as("a hidden draft has nothing to show without a caret")
                .isEmpty();
        assertThat(row(state, config.withPrompt("key: ", Style.NONE), true)).isEqualTo("key: \u2588");
    }

    @Test
    void aCompletionGestureWithNothingToCompleteFallsThroughToTheParent() {
        TextInput.Config config = plain().withCompletion(TextInput.Completion.prefix());
        // A form that binds the same gestures for itself, with the field as the focused element.
        assertThat(route(withCandidates(TextInput.Model.of("br"), "gum"), config, Key.TAB))
                .as("nothing matches 'br' among the candidates, so the form gets the key")
                .contains("next");
        assertThat(route(withCandidates(TextInput.Model.of("br"), "gum"), config, Key.of("n", Modifiers.CTRL)))
                .contains("cycle");
        // One match is something to accept, so tab is the field's - but there is nothing to cycle to.
        assertThat(route(withCandidates(TextInput.Model.of("br"), "bravo"), config, Key.TAB))
                .contains("field:AcceptSuggestion[]");
        assertThat(route(withCandidates(TextInput.Model.of("br"), "bravo"), config, Key.of("n", Modifiers.CTRL)))
                .contains("cycle");
        // Two matches are a cycle, so the cycling gesture is the field's.
        TextInput.Model two = withCandidates(TextInput.Model.of("br"), "bravo", "braxton");
        assertThat(route(two, config, Key.of("n", Modifiers.CTRL))).contains("field:NextSuggestion[]");
    }

    /**
     * Routes one gesture through the field inside a form that binds the same keys for itself.
     *
     * <p>The form's bindings bubble, so the field sees the key first: what it consumes is its own
     * message and what it leaves alone reaches the form.</p>
     */
    private static Optional<String> route(TextInput.Model state, TextInput.Config config, Key gesture) {
        View<String> view = Views.keyed(
                List.of(),
                new TextInput(config).view(state, CONTEXT).map(message -> "field:" + message),
                List.of(
                        io.github.dhoard.braxton.input.Binding.of("tab", "next"),
                        io.github.dhoard.braxton.input.Binding.of("ctrl+n", "cycle")));
        return InputRouter.route(view, InputEvent.KeyInput.of(gesture, gesture.name()));
    }

    @Test
    void tabAcceptsACandidateWhenOneIsSelected() {
        TextInput.Config config = plain().withCompletion(TextInput.Completion.prefix());
        View<TextInput.Msg> field =
                new TextInput(config).view(withCandidates(TextInput.Model.of("br"), "braxton"), CONTEXT);
        Optional<TextInput.Msg> routed = InputRouter.route(field, InputEvent.KeyInput.of(Key.TAB, "\t"));
        assertThat(routed)
                .as("the field consumes the gesture and asks to accept")
                .contains(new TextInput.Msg.AcceptSuggestion());
    }

    @Test
    void aRemappedAcceptGestureReplacesTheDefault() {
        TextInput.Config config = plain().withCompletion(TextInput.Completion.prefix());
        TextInput field =
                new TextInput(config, TextInput.defaultKeys(config).withAliases("accept-suggestion", "ctrl+space"));
        assertThat(field.keys().actionFor(Key.TAB)).isEmpty();
        assertThat(field.keys().actionFor(Key.parse("ctrl+space")).map(action -> action.message()))
                .contains(new TextInput.Msg.AcceptSuggestion());
    }

    @Test
    void completingIsAnOutputTheApplicationAnswers() {
        TextInput.Config config = plain().withCompletion(TextInput.Completion.prefix());
        Update<TextInput.Model, TextInput.Msg, TextInput.Output> typed =
                sent(TextInput.Model.of(""), config, new TextInput.Msg.Insert("br"));
        assertThat(typed.outputs()).containsExactly(new TextInput.Output.SuggestionsRequested(1, "br"));
    }

    @Test
    void theDefaultMapOffersSubmitCompleteAndEditing() {
        List<String> help = help(TextInput.defaultKeys(plain()));
        assertThat(help).anySatisfy(line -> assertThat(line).contains("enter").contains("submit"));
        assertThat(help).anySatisfy(line -> assertThat(line).contains("tab").contains("complete"));
        assertThat(TextInput.defaultKeys(plain()).actionFor(Key.ENTER).map(action -> action.message()))
                .contains(new TextInput.Msg.Submit());
        assertThat(TextInput.defaultKeys(plain()).actions().stream()
                        .map(action -> action.id())
                        .toList())
                .contains("backspace", "left", "word-left", "select-all", "submit", "copy", "paste");
    }

    // ── R-04: the three-field form ──────────────────────────────────────────

    private static ComponentDriver<TextInputDemo.Model, TextInputDemo.Msg, Never> form() {
        return ComponentDriver.initialize(new TextInputDemo.Scene(), new InitContext(null, null));
    }

    private static List<String> lines(ComponentDriver<TextInputDemo.Model, TextInputDemo.Msg, ?> driver) {
        return new ScreenRenderer().plainLines(driver.view(CONTEXT), new Viewport(24, 80));
    }

    private static void press(ComponentDriver<TextInputDemo.Model, TextInputDemo.Msg, Never> driver, String bytes) {
        List<InputEvent> events;
        try {
            events = decodeAll(bytes);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
        for (InputEvent event : events) {
            InputRouter.route(driver.view(CONTEXT), event).ifPresent(message -> deliver(driver, message));
        }
    }

    private static void deliver(
            ComponentDriver<TextInputDemo.Model, TextInputDemo.Msg, Never> driver, TextInputDemo.Msg message) {
        ArrayDeque<TextInputDemo.Msg> pending = new ArrayDeque<>();
        pending.add(message);
        while (!pending.isEmpty()) {
            inspect(driver.send(pending.removeFirst()).command(), pending);
        }
    }

    private static void inspect(Command<TextInputDemo.Msg> command, ArrayDeque<TextInputDemo.Msg> pending) {
        switch (command) {
            case Command.None<TextInputDemo.Msg> none -> {}
            case Command.Batch<TextInputDemo.Msg> batch -> batch.commands().forEach(item -> inspect(item, pending));
            case Command.Dispatch<TextInputDemo.Msg> dispatch -> pending.add(dispatch.message());
            case Command.Scoped<TextInputDemo.Msg> scoped -> inspect(scoped.command(), pending);
            default -> {}
        }
    }

    private static List<InputEvent> decodeAll(String text) throws IOException {
        TimedReader reader = TestReaders.utf8(new ByteAtATime(text));
        InputDecoder decoder = new InputDecoder(reader);
        List<InputEvent> events = new ArrayList<>();
        Optional<InputEvent> event;
        while ((event = decoder.read()).isPresent()) {
            events.add(event.get());
        }
        return events;
    }

    /** Feeds the decoder one byte per read, so a partial sequence is really partial. */
    private static final class ByteAtATime extends InputStream {

        private final byte[] bytes;
        private int index;

        private ByteAtATime(String text) {
            this.bytes = text.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int read() {
            return index < bytes.length ? bytes[index++] & 0xFF : -1;
        }
    }

    @Test
    void theFormStartsOnTheNicknameFieldAndIsNotReady() {
        var driver = form();
        assertThat(driver.model().focus()).isZero();
        assertThat(driver.model().complete()).isFalse();
        assertThat(lines(driver))
                .anySatisfy(line -> assertThat(line).contains("fields=3 focus=nickname completion=on form=incomplete"));
        assertThat(lines(driver))
                .anySatisfy(line -> assertThat(line).contains("nickname len=0 caret=0 scroll=0 cand=none"));
        assertThat(lines(driver)).anySatisfy(line -> assertThat(line).contains("masked=yes"));
    }

    @Test
    void tabMovesFocusWhenTheFocusedFieldHasNothingToComplete() {
        var driver = form();
        press(driver, "\t");
        assertThat(driver.model().focus())
                .as("an empty field completes nothing")
                .isEqualTo(1);
        press(driver, "\t");
        assertThat(driver.model().focus()).isEqualTo(2);
        press(driver, "\t");
        assertThat(driver.model().focus()).as("focus wraps").isZero();
        press(driver, "\u001b[Z");
        assertThat(driver.model().focus()).as("shift+tab goes back").isEqualTo(2);
    }

    @Test
    void theNicknameCompletesFromTheStaticListAndTabAcceptsIt() {
        var driver = form();
        press(driver, "br");
        assertThat(driver.model().nickname().text()).isEqualTo("br");
        assertThat(TextInput.selectedCandidate(driver.model().nickname(), TextInputDemo.config(0)))
                .contains("braxton");
        assertThat(driver.model().focus()).as("typing does not move focus").isZero();
        assertThat(lines(driver))
                .anySatisfy(line -> assertThat(line).contains("nickname len=2 caret=2 scroll=0 cand=braxton"));
        // The ghost suffix is painted after the caret, so the acceptance target is visible.
        assertThat(paintedRow(driver, 6)).contains("br").contains("xton");

        press(driver, "\t");
        assertThat(driver.model().nickname().text()).isEqualTo("braxton");
        assertThat(driver.model().focus())
                .as("tab completed the field instead of moving on")
                .isZero();
        assertThat(driver.model().nickname().valid()).isTrue();

        // With the draft equal to the candidate there is nothing left to add, so tab moves on.
        press(driver, "\t");
        assertThat(driver.model().focus()).isEqualTo(1);
    }

    @Test
    void aLongNicknameScrollsInsteadOfWrapping() {
        var driver = form();
        press(driver, "abcdefghijklmnopqrst");
        assertThat(driver.model().nickname().text()).hasSize(20);
        assertThat(TextInput.scrollOffset(driver.model().nickname(), TextInputDemo.config(0), 16))
                .isEqualTo(5);
        String painted = paintedRow(driver, 6).substring(10);
        assertThat(painted).as("the field paints its own width, not more").startsWith("fghijklmnopqrst");
        assertThat(painted).doesNotContain("abcde");
        assertThat(lines(driver)).anySatisfy(line -> assertThat(line).contains("nickname len=20 caret=20 scroll=5"));
    }

    @Test
    void thePasswordFieldPaintsNeitherItsDraftNorItsCompletion() {
        var driver = form();
        press(driver, "\t\t");
        assertThat(driver.model().focus()).isEqualTo(2);
        press(driver, "hunter2!");
        assertThat(driver.model().password().text()).isEqualTo("hunter2!");
        assertThat(paintedRow(driver, 8)).doesNotContain("hunter");
        assertThat(paintedRow(driver, 8)).contains("\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022");
        assertThat(lines(driver)).anySatisfy(line -> assertThat(line).contains("password len=8 caret=8"));
        assertThat(lines(driver))
                .anySatisfy(line -> assertThat(line).contains("password").contains("masked=yes"));
    }

    @Test
    void submittingAnInvalidFieldIsRejectedAndAFullFormBecomesReady() {
        var driver = form();
        press(driver, "\r");
        assertThat(driver.model().last())
                .as("an empty nickname refuses its submission")
                .isEqualTo("reject:nickname:empty");
        press(driver, "abcd");
        press(driver, "\t");
        press(driver, "\r");
        assertThat(driver.model().last()).startsWith("reject:email:");
        assertThat(lines(driver)).anySatisfy(line -> assertThat(line).contains("last=reject:email:"));

        press(driver, "a@b.c");
        assertThat(driver.model().email().valid()).isTrue();
        press(driver, "\t");
        press(driver, "hunter2!");
        assertThat(driver.model().complete()).isTrue();
        assertThat(lines(driver)).anySatisfy(line -> assertThat(line).contains("form=ready"));
        press(driver, "\r");
        assertThat(driver.model().last()).isEqualTo("submit:password:8");
    }

    @Test
    void aValidatorMessageReachesTheStatusRow() {
        var driver = form();
        press(driver, "x");
        assertThat(lines(driver))
                .anySatisfy(line -> assertThat(line).contains("nickname").contains("valid=no"));
        assertThat(lines(driver)).anySatisfy(line -> assertThat(line).contains("msg=too-short"));
    }

    /** One painted scene row, as text. */
    private static String paintedRow(ComponentDriver<TextInputDemo.Model, TextInputDemo.Msg, ?> driver, int index) {
        return lines(driver).get(index);
    }

    // ── R-05: preserved contracts, bounds and purity ─────────────────────────

    @Test
    void renderingIsPureAndLeavesTheModelAlone() {
        TextInput.Config config = completing().withWidth(10);
        TextInput.Model state = withCandidates(TextInput.Model.of("bra"), "braxton");
        TextInput.Model before =
                new TextInput.Model(state.draft(), state.validation(), state.suggestions(), state.revision());
        StyledText first = TextInput.render(state, config, true);
        StyledText second = TextInput.render(state, config, true);
        assertThat(first).isEqualTo(second);
        assertThat(state).isEqualTo(before);
        assertThat(new ScreenRenderer().plainLines(new TextInput(config).view(state, CONTEXT), new Viewport(1, 10)))
                .isEqualTo(painted(state, config, true, 10));
    }

    @Test
    void aMessageThatChangesNothingKeepsTheModelInstance() {
        TextInput field = new TextInput(plain());
        TextInput.Model state = TextInput.Model.of("abc");
        // The caret is already at the start, so the gesture cannot move it.
        TextInput.Model head = at("abc", 0);
        assertThat(field.update(head, new TextInput.Msg.MoveLeft()).model()).isSameAs(head);
        assertThat(field.update(state, new TextInput.Msg.AcceptSuggestion()).model())
                .as("no candidates, so nothing to accept")
                .isSameAs(state);
        assertThat(field.update(state, new TextInput.Msg.NextSuggestion()).model())
                .isSameAs(state);
        assertThat(field.update(state, new TextInput.Msg.Clear()).model()).isNotSameAs(state);
    }

    @Test
    void editingNeverMovesFocus() {
        TextInput field = new TextInput(plain());
        TextInput.Model unfocused = TextInput.Model.of("abc").withFocus(false);
        assertThat(field.update(unfocused, new TextInput.Msg.Insert("d"))
                        .model()
                        .focused())
                .isFalse();
        assertThat(field.update(unfocused, new TextInput.Msg.Submit()).model().focused())
                .isFalse();
        // The caret is the model's own, and focus is the application's: bringing the instance back
        // in focus is what a focus change looks like.
        assertThat(unfocused.withFocus(true).focused()).isTrue();
    }

    @Test
    void pasteInputAndUnhandledEventsAreMappedAndLeftAlone() {
        TextInput.Config config = plain();
        View<TextInput.Msg> view = new TextInput(config).view(TextInput.Model.of(""), CONTEXT);
        assertThat(InputRouter.route(view, new InputEvent.PasteInput("a\nb")))
                .contains(new TextInput.Msg.Paste("a\nb"));
        assertThat(InputRouter.route(view, InputEvent.KeyInput.of(Key.ESCAPE)))
                .as("escape is not a field gesture, so it is left for the application")
                .isEmpty();
        assertThat(InputRouter.route(view, InputEvent.KeyInput.of(Key.of("b"), "b")))
                .contains(new TextInput.Msg.Insert("b"));
        assertThat(InputRouter.route(view, InputEvent.KeyInput.of(Key.of("b", Modifiers.CTRL), "")))
                .as("a modified key is not text")
                .isEmpty();
    }

    @Test
    void aFieldThatIsNotFocusedReceivesNoInput() {
        View<TextInput.Msg> view =
                new TextInput(plain()).view(TextInput.Model.of("").withFocus(false), CONTEXT);
        assertThat(InputRouter.route(view, InputEvent.KeyInput.of(Key.of("b"), "b")))
                .isEmpty();
    }

    @Test
    void aFieldWithASelectionCopiesOnlyWhatMayLeaveIt() {
        TextInput.Config config = plain();
        TextInput.Model selected = after(TextInput.Model.of("secret"), config, new TextInput.Msg.SelectAll());
        assertThat(sent(selected, config, new TextInput.Msg.Copy()).outputs())
                .containsExactly(new TextInput.Output.Copy("secret"));
        TextInput.Config refused = config.withCopyEnabled(false);
        assertThat(sent(selected, refused, new TextInput.Msg.Copy()).outputs())
                .containsExactly(new TextInput.Output.Copy(""));
        assertThat(sent(selected, config, new TextInput.Msg.PasteRequest()).outputs())
                .containsExactly(new TextInput.Output.PasteRequest());
    }

    @Test
    void aDeclaredCaretSitsOnTheCellABlockWouldCover() {
        TextInput.Config config = plain().withWidth(8)
                .withCursor(Editor.CursorPolicy.nativeCursor(
                        io.github.dhoard.braxton.view.Cursor.Shape.BAR,
                        io.github.dhoard.braxton.view.Cursor.Mode.STEADY));
        TextInput.Model state = TextInput.Model.of("abcdefghijklmnop");
        // A declared caret is not painted into the text; it is placed on the same cell a block would
        // have covered, at the window's caret column.
        String painted = row(state, config, true);
        assertThat(painted).isEqualTo("jklmnop");
        io.github.dhoard.braxton.view.RenderedScreen screen = new ScreenRenderer()
                .screen(new TextInput(config).view(state, new ViewContext(new Viewport(1, 8))), new Viewport(1, 8));
        assertThat(screen.cursor()).isPresent();
        assertThat(screen.cursor().get().column())
                .as("the last cell of the window, where the block would have been")
                .isEqualTo(7);
        assertThat(screen.cursor().get().row()).isZero();
        assertThat(screen.cursor().get().strategy()).isEqualTo(io.github.dhoard.braxton.view.Cursor.Strategy.NATIVE);
    }

    @Test
    void aWordAndADocumentEdgeAreOneMotionInOneRow() {
        TextInput.Config config = plain();
        TextInput.Model state = at("alpha beta", 10);
        assertThat(after(state, config, new TextInput.Msg.MoveWordLeft()).caret())
                .isEqualTo(6);
        assertThat(after(state, config, new TextInput.Msg.MoveStart()).caret()).isZero();
        assertThat(after(state, config, new TextInput.Msg.MoveEnd()).caret()).isEqualTo(10);
        TextInput.Model extended = after(state, config, new TextInput.Msg.Extend(new TextInput.Msg.MoveStart()));
        assertThat(extended.hasSelection()).isTrue();
        assertThat(extended.selection()).isEqualTo("alpha beta");
        assertThatThrownBy(() -> new TextInput.Msg.Extend(new TextInput.Msg.Insert("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theConfigRejectsAPolicyItCannotHonour() {
        assertThatThrownBy(() -> plain().withMaxLength(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TextInput.Appearance.plain().withWidth(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextInput.Model(
                        Editor.Model.empty(), TextInput.Validation.ok(), TextInput.Suggestions.none(), -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextInput.Suggestions(-1, List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedAndOversizedInputStaysOneBoundedRow() {
        TextInput.Config config = plain().withWidth(10).withMaxLength(16);
        TextInput.Model state = TextInput.Model.of("");
        // An unpaired surrogate and a control character are neither breaks nor limits: they are
        // dead ends at a value boundary, and neither may throw or turn the field into two rows.
        state = after(state, config, new TextInput.Msg.Insert("\uD800\u0001"));
        state = after(state, config, new TextInput.Msg.Insert("0123456789abcdefghij"));
        assertThat(state.text()).hasSize(16);
        assertThat(TextInput.render(state, config, true).lines()).hasSize(1);
        assertThat(row(state, config, true)).hasSize(10);
    }

    @Test
    void aVeryLongDraftIsPaintedOnlyThroughItsWindow() {
        TextInput.Config config = plain().withWidth(12);
        TextInput.Model state = TextInput.Model.of("x".repeat(10_000));
        assertThat(TextInput.render(state, config, true).lines()).hasSize(1);
        assertThat(row(state, config, true)).hasSize(12);
        assertThat(TextInput.scrollOffset(state, config, 12)).isEqualTo(9_989);
    }

    @Test
    void theSameCandidateAnswerTwiceChangesNothingTheSecondTime() {
        TextInput.Config config = plain();
        TextInput.Model once = after(TextInput.Model.of(""), config, new TextInput.Msg.Candidates(0, List.of("alpha")));
        TextInput.Model twice = after(once, config, new TextInput.Msg.Candidates(0, List.of("alpha")));
        assertThat(twice).isSameAs(once);
        // An empty answer clears the list, which is how a provider withdraws its candidates.
        assertThat(after(once, config, new TextInput.Msg.Candidates(0, List.of()))
                        .suggestions()
                        .candidates())
                .isEmpty();
    }

    @Test
    void aPromptIsPaintedOnceAndIsNeverContent() {
        TextInput.Config config = plain().withWidth(12).withPrompt("name: ", Style.NONE);
        TextInput.Model state = TextInput.Model.of("ada");
        assertThat(row(state, config, true)).isEqualTo("name: ada\u2588");
        assertThat(sent(state, config, new TextInput.Msg.Submit()).outputs())
                .containsExactly(new TextInput.Output.Submit("ada"));
        // The prompt's breaks are one space, exactly as a draft's are.
        assertThat(TextInput.Appearance.plain().withPrompt("a\r\nb", Style.NONE).prompt())
                .isEqualTo("a b");
    }

    @Test
    void aPlaceholderStandsInForAnEmptyDraftWithoutBeingContent() {
        TextInput.Config config = plain().withWidth(12).withPlaceholder("Nickname", Style.NONE);
        TextInput.Model empty = TextInput.Model.of("");
        assertThat(row(empty, config, true)).isEqualTo("\u2588ickname");
        assertThat(sent(empty, config, new TextInput.Msg.Submit()).outputs())
                .containsExactly(new TextInput.Output.Submit(""));
        assertThat(row(empty, config, false)).isEqualTo("Nickname");
    }
}
