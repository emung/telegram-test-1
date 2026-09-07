package eeu.test;

import eeu.test.client.ExpenseItem;
import eeu.test.client.ExpenseResponse;
import eeu.test.client.ExpenseSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static eeu.test.ExpenseFormattingTest.count;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers splitForTelegram, which keeps replies under Telegram's 4096-character
 * message limit. /list exceeds that limit at roughly 45 expenses.
 */
class MessageSplittingTest {

    private static String listOf(int expenseCount) {
        List<ExpenseItem> items = new ArrayList<>();
        for (int i = 0; i < expenseCount; i++) {
            items.add(new ExpenseItem((long) i, i * 1.5, "2026-09-01", "Item " + i, "Food", "Shop", "EUR", false));
        }
        ExpenseResponse response = new ExpenseResponse();
        response.setExpenses(items);
        response.setSums(List.of(new ExpenseSummary("EUR", 1.5 * expenseCount, 0.0, expenseCount)));
        return TelegramBot.formatExpenseResponse("All expenses", response);
    }

    @Test
    @DisplayName("returns text that already fits as a single, unmodified chunk")
    void shortTextIsNotSplit() {
        String text = "📋 <b>All expenses</b>\n\n<b>100.00 EUR</b> — Groceries";

        assertLinesMatch(List.of(text), TelegramBot.splitForTelegram(text));
    }

    @Test
    @DisplayName("keeps text of exactly the limit in one chunk")
    void textAtExactlyTheLimitIsNotSplit() {
        String text = "x".repeat(TelegramBot.MAX_MESSAGE_LENGTH);

        List<String> chunks = TelegramBot.splitForTelegram(text);

        assertAll(
                () -> assertEquals(1, chunks.size()),
                () -> assertSame(text, chunks.getFirst()));
    }

    @Test
    @DisplayName("splits text one character over the limit")
    void textOverTheLimitIsSplit() {
        List<String> chunks = TelegramBot.splitForTelegram("x".repeat(TelegramBot.MAX_MESSAGE_LENGTH + 1));

        assertEquals(2, chunks.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 45, 46, 200, 1000})
    @DisplayName("every chunk of a real expense list fits the limit and has balanced HTML tags")
    void chunksAreSendableForAnyListSize(int expenseCount) {
        List<String> chunks = TelegramBot.splitForTelegram(listOf(expenseCount));

        assertAll(chunks.stream().map(chunk -> () -> assertAll(
                () -> assertTrue(chunk.length() <= TelegramBot.MAX_MESSAGE_LENGTH,
                        () -> "chunk too long: " + chunk.length()),
                // A chunk cut mid-tag would make Telegram reject the whole message.
                () -> assertEquals(count(chunk, "<b>"), count(chunk, "</b>"), "unbalanced <b>"),
                () -> assertEquals(count(chunk, "<code>"), count(chunk, "</code>"), "unbalanced <code>"),
                () -> assertEquals(chunk.strip(), chunk, "chunk has leading/trailing whitespace"))));
    }

    @ParameterizedTest
    @ValueSource(ints = {45, 46, 200, 1000})
    @DisplayName("splits between expense blocks, so no chunk starts mid-block")
    void splitsOnBlockBoundaries(int expenseCount) {
        List<String> chunks = TelegramBot.splitForTelegram(listOf(expenseCount));

        // Every block begins with a bold amount; a chunk starting anywhere else means a
        // block (and its metadata line) was cut in half.
        assertAll(chunks.stream().skip(1).map(chunk -> () -> assertTrue(chunk.startsWith("<b>"),
                () -> "chunk starts mid-block: " + chunk.lines().findFirst().orElse(""))));
    }

    @ParameterizedTest
    @ValueSource(ints = {45, 200, 1000})
    @DisplayName("loses no content: the chunks rejoin to the original text")
    void losesNoContent(int expenseCount) {
        String text = listOf(expenseCount);

        String rejoined = String.join("\n\n", TelegramBot.splitForTelegram(text));

        assertEquals(normalise(text), normalise(rejoined));
    }

    @Test
    @DisplayName("hard-cuts a single oversized line rather than emitting an unsendable chunk")
    void hardCutsTextWithNoLineBreaks() {
        String text = "y".repeat(TelegramBot.MAX_MESSAGE_LENGTH * 2 + 17);

        List<String> chunks = TelegramBot.splitForTelegram(text);

        assertAll(
                () -> assertEquals(3, chunks.size()),
                () -> assertTrue(chunks.stream().allMatch(c -> c.length() <= TelegramBot.MAX_MESSAGE_LENGTH),
                        "chunk over the limit"),
                () -> assertEquals(text.length(), chunks.stream().mapToInt(String::length).sum(),
                        "characters lost in the hard cut"));
    }

    @Test
    @DisplayName("falls back to a single-newline boundary when there is no blank line")
    void splitsOnSingleNewlineWhenNoBlankLine() {
        String line = "z".repeat(99) + "\n";
        String text = line.repeat(60); // 6000 chars, newline-separated, no blank lines

        List<String> chunks = TelegramBot.splitForTelegram(text);

        assertAll(
                () -> assertEquals(2, chunks.size()),
                () -> assertTrue(chunks.stream().allMatch(c -> c.length() <= TelegramBot.MAX_MESSAGE_LENGTH),
                        "chunk over the limit"),
                // A newline boundary means no line is broken across chunks.
                () -> assertTrue(chunks.stream().flatMap(String::lines).allMatch(l -> l.length() == 99),
                        "a line was cut mid-way"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n\n"})
    @DisplayName("produces nothing for blank text, so the bot sends no empty message")
    void blankTextProducesNoChunks(String blank) {
        assertEquals(List.of(), TelegramBot.splitForTelegram(blank));
    }

    /** Ignores the whitespace that chunk boundaries legitimately absorb. */
    private static String normalise(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }
}
