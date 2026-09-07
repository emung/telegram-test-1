package eeu.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers parseExpense, which validates the five-line chat payload. Offset 0 is a plain
 * message (a new expense); offset 1 skips the "/update &lt;id&gt;" header line.
 */
class ExpenseParsingTest {

    private static final int NEW_EXPENSE = 0;
    private static final int UPDATE = 1;

    private static final String VALID = """
            250 EUR
            Train ticket
            Travel
            CFR
            2026-09-05""";

    @Nested
    @DisplayName("valid payloads")
    class Valid {

        @Test
        @DisplayName("maps the five lines onto the expense fields")
        void parsesAllFields() {
            Result result = TelegramBot.parseExpense(VALID, NEW_EXPENSE);

            assertTrue(result.isSuccess(), result::getMessage);
            Expense expense = result.getExpense();
            assertAll(
                    () -> assertEquals(250, expense.getAmount()),
                    () -> assertEquals("EUR", expense.getCurrency()),
                    () -> assertEquals("Train ticket", expense.getDescription()),
                    () -> assertEquals("Travel", expense.getCategory()),
                    () -> assertEquals("CFR", expense.getRecipient()),
                    () -> assertEquals("2026-09-05", expense.getDate()));
        }

        @Test
        @DisplayName("upper-cases the currency")
        void upperCasesCurrency() {
            Result result = TelegramBot.parseExpense(VALID.replace("250 EUR", "250 ron"), NEW_EXPENSE);

            assertEquals("RON", result.getExpense().getCurrency());
        }

        @Test
        @DisplayName("trims surrounding whitespace on every field")
        void trimsFields() {
            Result result = TelegramBot.parseExpense("""
                      250   EUR  \s
                      Train ticket  \s
                     \tTravel
                      CFR
                      2026-09-05  \s""", NEW_EXPENSE);

            assertTrue(result.isSuccess(), result::getMessage);
            Expense expense = result.getExpense();
            assertAll(
                    () -> assertEquals(250, expense.getAmount()),
                    () -> assertEquals("EUR", expense.getCurrency()),
                    () -> assertEquals("Train ticket", expense.getDescription()),
                    () -> assertEquals("Travel", expense.getCategory()),
                    () -> assertEquals("CFR", expense.getRecipient()),
                    () -> assertEquals("2026-09-05", expense.getDate()));
        }

        @Test
        @DisplayName("accepts Windows line endings")
        void acceptsCrLf() {
            Result result = TelegramBot.parseExpense(VALID.replace("\n", "\r\n"), NEW_EXPENSE);

            assertTrue(result.isSuccess(), result::getMessage);
            assertEquals("Train ticket", result.getExpense().getDescription());
        }

        @Test
        @DisplayName("skips the command header when parsing an /update payload")
        void skipsUpdateHeader() {
            Result result = TelegramBot.parseExpense("/update 12\n" + VALID, UPDATE);

            assertTrue(result.isSuccess(), result::getMessage);
            assertAll(
                    () -> assertEquals(250, result.getExpense().getAmount()),
                    () -> assertEquals("Train ticket", result.getExpense().getDescription()));
        }

        @Test
        @DisplayName("accepts a negative amount, which is how a refund is entered")
        void acceptsNegativeAmount() {
            Result result = TelegramBot.parseExpense(VALID.replace("250 EUR", "-250 EUR"), NEW_EXPENSE);

            assertTrue(result.isSuccess(), result::getMessage);
            assertEquals(-250, result.getExpense().getAmount());
        }

        @Test
        @DisplayName("ignores lines after the fifth")
        void ignoresExtraLines() {
            Result result = TelegramBot.parseExpense(VALID + "\nleftover\nmore leftover", NEW_EXPENSE);

            assertTrue(result.isSuccess(), result::getMessage);
            assertEquals("2026-09-05", result.getExpense().getDate());
        }

        @Test
        @DisplayName("takes the whole remainder of line 1 as the currency")
        void currencyIsTheRestOfTheFirstLine() {
            // Documents current behaviour: the amount is split off at the first run of
            // whitespace and everything after it is treated as the currency.
            Result result = TelegramBot.parseExpense(VALID.replace("250 EUR", "250 euro cents"), NEW_EXPENSE);

            assertTrue(result.isSuccess(), result::getMessage);
            assertEquals("EURO CENTS", result.getExpense().getCurrency());
        }
    }

    @Nested
    @DisplayName("rejected payloads")
    class Rejected {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\n", "\t\n  "})
        @DisplayName("rejects blank input")
        void rejectsBlankInput(String blank) {
            Result result = TelegramBot.parseExpense(blank, NEW_EXPENSE);

            assertAll(
                    () -> assertFalse(result.isSuccess()),
                    () -> assertNull(result.getExpense()),
                    () -> assertEquals("Message cannot be empty.", result.getMessage()));
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("rejects fewer than five lines and says which fields are needed")
        void rejectsTooFewLines(int lineCount) {
            String text = VALID.lines().limit(lineCount).reduce((a, b) -> a + "\n" + b).orElseThrow();

            Result result = TelegramBot.parseExpense(text, NEW_EXPENSE);

            assertAll(
                    () -> assertFalse(result.isSuccess()),
                    () -> assertTrue(result.getMessage().startsWith("Please provide 5 fields:"),
                            result::getMessage));
        }

        @Test
        @DisplayName("rejects an /update payload that has five lines only counting the header")
        void rejectsUpdateWithoutFiveFieldLines() {
            String text = "/update 12\n" + VALID.lines().limit(4).reduce((a, b) -> a + "\n" + b).orElseThrow();

            Result result = TelegramBot.parseExpense(text, UPDATE);

            assertAll(
                    () -> assertFalse(result.isSuccess()),
                    () -> assertTrue(result.getMessage().startsWith("Please provide 5 fields:"),
                            result::getMessage));
        }

        @Test
        @DisplayName("rejects a first line with no currency")
        void rejectsMissingCurrency() {
            Result result = TelegramBot.parseExpense(VALID.replace("250 EUR", "250"), NEW_EXPENSE);

            assertAll(
                    () -> assertFalse(result.isSuccess()),
                    () -> assertEquals("Amount line must contain both amount and currency (e.g., '1000 RON').",
                            result.getMessage()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"12.50 RON", "abc RON", "1,000 RON", "€250 EUR"})
        @DisplayName("rejects an amount that is not a whole number")
        void rejectsNonIntegerAmount(String amountLine) {
            Result result = TelegramBot.parseExpense(VALID.replace("250 EUR", amountLine), NEW_EXPENSE);

            assertAll(
                    () -> assertFalse(result.isSuccess(), () -> "accepted " + amountLine),
                    () -> assertEquals("Amount must be a valid integer.", result.getMessage()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"Train ticket", "Travel", "CFR"})
        @DisplayName("rejects a payload where one of the middle text fields is blank")
        void rejectsBlankField(String fieldToBlank) {
            Result result = TelegramBot.parseExpense(VALID.replace(fieldToBlank, "   "), NEW_EXPENSE);

            assertAll(
                    () -> assertFalse(result.isSuccess(), () -> "accepted a blank " + fieldToBlank),
                    () -> assertEquals("All 5 expense fields are required and cannot be empty.",
                            result.getMessage()));
        }

        @Test
        @DisplayName("rejects a blank date line as a missing fifth line, since trailing whitespace is trimmed")
        void rejectsBlankDate() {
            Result result = TelegramBot.parseExpense(VALID.replace("2026-09-05", "   "), NEW_EXPENSE);

            assertAll(
                    () -> assertFalse(result.isSuccess()),
                    // The blank last line is trimmed off the message entirely, so this trips
                    // the line-count check rather than the empty-field check.
                    () -> assertTrue(result.getMessage().startsWith("Please provide 5 fields:"),
                            result::getMessage));
        }
    }
}
