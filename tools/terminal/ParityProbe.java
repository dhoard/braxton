/* Development-only child: runs one parity phase scenario under an owned PTY with pyte. */
package io.github.dhoard.braxton.tools;

import examples.parity.ApplicationControlDemo;
import examples.parity.ClipboardDemo;
import examples.parity.CountdownDemo;
import examples.parity.DebugOverlayDemo;
import examples.parity.CursorDemo;
import examples.parity.EditorDemo;
import examples.parity.ElementKernelDemo;
import examples.parity.EngineDemo;
import examples.parity.FilePickerDemo;
import examples.parity.InlineIoDemo;
import examples.parity.KeyboardFocusDemo;
import examples.parity.KeymapHelpDemo;
import examples.parity.LinePromptsDemo;
import examples.parity.MarkdownDemo;
import examples.parity.MouseDemo;
import examples.parity.PaginatorDemo;
import examples.parity.ProcessHandoffDemo;
import examples.parity.ProgressDemo;
import examples.parity.RichOutputDemo;
import examples.parity.RichListDemo;
import examples.parity.Showcase;
import examples.parity.SpinnerDemo;
import examples.parity.StopwatchDemo;
import examples.parity.TableDemo;
import examples.parity.TerminalMatrixDemo;
import examples.parity.TabsDemo;
import examples.parity.TerminalPresentationDemo;
import examples.parity.TerminalQueryDemo;
import examples.parity.TextInputDemo;
import examples.parity.ViewportDemo;
import examples.parity.WidgetGalleryDemo;
import io.github.dhoard.braxton.app.ExitResult;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Dispatches one implemented parity phase to the application that demonstrates it.
 *
 * <p>{@code tools/terminal/verify_terminal.py --parity-phase NN} compiles this class into its own
 * temporary directory and runs it as {@code ParityProbe NN}, so the verified scenario is the
 * packaged demo itself rather than a second copy of it. A phase whose real behavior needs a short
 * run - a countdown that would otherwise take a minute and a half - passes its own configuration
 * instead of a second copy of the application. A phase stays out of this table until its plan is
 * implemented and its verifier assertions exist; an unknown phase fails instead of running
 * nothing.</p>
 */
public final class ParityProbe {

    private ParityProbe() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: ParityProbe <two-digit phase>");
            System.exit(2);
        }
        String phase = args[0].toLowerCase(Locale.ROOT);
        Supplier<ExitResult> scenario =
                switch (phase) {
                    case "01" -> KeymapHelpDemo::run;
                    case "02" -> KeyboardFocusDemo::run;
                    case "03" -> CursorDemo::run;
                    case "04" -> EditorDemo::run;
                    case "05" -> TextInputDemo::run;
                    case "06" -> PaginatorDemo::run;
                    case "07" -> SpinnerDemo::run;
                    case "08" -> ProgressDemo::run;
                    case "09" -> () -> CountdownDemo.run(CountdownDemo.TEST_DURATION);
                    case "10" -> StopwatchDemo::run;
                    case "11" -> RichListDemo::run;
                    case "12" -> ViewportDemo::run;
                    case "13" -> TableDemo::run;
                    case "14" -> FilePickerDemo::run;
                    case "15" -> MarkdownDemo::run;
                    case "16" -> TabsDemo::run;
                    case "17" -> MouseDemo::run;
                    case "18" -> TerminalQueryDemo::run;
                    case "19" -> ClipboardDemo::run;
                    case "20" -> TerminalPresentationDemo::run;
                    case "21" -> ProcessHandoffDemo::run;
                    case "22" -> ApplicationControlDemo::run;
                    case "23" -> InlineIoDemo::run;
                    case "24" -> Showcase::run;
                    case "25" -> TerminalMatrixDemo::run;
                    case "26" -> EngineDemo::run;
                    case "27" -> ElementKernelDemo::run;
                    case "28" -> WidgetGalleryDemo::run;
                    case "29" -> LinePromptsDemo::run;
                    case "30" -> RichOutputDemo::run;
                    case "31" -> DebugOverlayDemo::run;
                    default -> null;
                };
        if (scenario == null) {
            System.err.println("no parity scenario for phase " + args[0]);
            System.exit(2);
        }
        ExitResult result = scenario.get();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }
}
