package io.github.dhoard.braxton.widget;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;

/** Every rendered table row shares one width, bordered or not. */
class TablePropertyTest {

    @Test
    void rowsShareOneWidthForRandomCellsAndBorders() {
        Random random = new Random(23L);
        String[] cells = {"a", "\u65E5", "ccc", "", "e\u0301"};
        for (int iteration = 0; iteration < 200; iteration++) {
            Table table = Table.of().buffer(random.nextInt(4))
                    .border(random.nextBoolean() ? Border.SINGLE : Border.NONE);
            int columns = 1 + random.nextInt(3);
            int rows = 1 + random.nextInt(3);
            for (int row = 0; row < rows; row++) {
                java.util.List<StyledText> values = new java.util.ArrayList<>();
                for (int column = 0; column < columns; column++) {
                    values.add(StyledText.plain(cells[random.nextInt(cells.length)]));
                }
                table.row(values);
            }
            java.util.List<Integer> widths = table.renderLines().stream()
                    .map(line -> TextMeasure.columns(line.plainText())).distinct().toList();
            assertThat(widths).as("iteration %d", iteration).hasSize(1);
            java.util.List<Integer> renderedWidths = table.render().plainText().lines()
                    .map(TextMeasure::columns).distinct().toList();
            assertThat(renderedWidths).as("framed iteration %d", iteration).hasSize(1);
        }
    }
}
