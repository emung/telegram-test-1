package eeu.test;

import eeu.test.client.ExpenseItem;
import eeu.test.client.ExpenseResponse;
import eeu.test.client.ExpenseSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the reply-rendering helpers: HTML escaping, amount formatting and the
 * two-line expense block used by /list, /search, /category, the delete prompt and
 * the create/update confirmations.
 */
class ExpenseFormattingTest {

    private static ExpenseItem item(Long id, Double amount, String date, String description,
                                    String category, String recipient, String currency, boolean isRefund) {
        return new ExpenseItem(id, amount, date, description, category, recipient, currency, isRefund);
    }

    private static ExpenseItem groceries() {
        return item(106L, 100.0, "2026-09-01", "Groceries", "Food", "Lidl", "EUR", false);
    }

    private static ExpenseResponse response(List<ExpenseItem> expenses, List<ExpenseSummary> sums) {
        ExpenseResponse response = new ExpenseResponse();
        response.setExpenses(expenses);
        response.setSums(sums);
        return response;
    }

    @Nested
    @DisplayName("escape")
    class Escape {

        @Test
        @DisplayName("escapes the three characters Telegram's HTML parser treats as markup")
        void escapesHtmlSpecialCharacters() {
            assertEquals("&lt;b&gt;not bold&lt;/b&gt;", TelegramBot.escape("<b>not bold</b>"));
            assertEquals("Tom &amp; Jerry", TelegramBot.escape("Tom & Jerry"));
        }

        @Test
        @DisplayName("escapes the ampersand first so entities are not double-escaped away")
        void escapesAmpersandBeforeAngleBrackets() {
            // If '<' were replaced first, the '&' it introduces would be escaped again and
            // the reader would see a literal "&lt;" instead of "<".
            assertEquals("&amp;lt;", TelegramBot.escape("&lt;"));
            assertEquals("&amp;amp;", TelegramBot.escape("&amp;"));
        }

        @Test
        @DisplayName("leaves Markdown-significant characters alone, since HTML mode ignores them")
        void leavesMarkdownCharactersUntouched() {
            // The bug this replaced: legacy Markdown ate '[' ... ']' as link syntax.
            assertEquals("[ID: 106] *not bold* _not italic_ `not code`",
                    TelegramBot.escape("[ID: 106] *not bold* _not italic_ `not code`"));
        }

        @Test
        @DisplayName("renders null as an empty string rather than the text \"null\"")
        void nullBecomesEmptyString() {
            assertEquals("", TelegramBot.escape(null));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "Groceries", "Café Zürich", "100 RON", "a/b-c_d"})
        @DisplayName("passes through text with no HTML-significant characters unchanged")
        void passesThroughSafeText(String safe) {
            assertEquals(safe, TelegramBot.escape(safe));
        }
    }

    @Nested
    @DisplayName("formatAmount")
    class FormatAmount {

        @ParameterizedTest
        @CsvSource({
                "100.0, 100.00",
                "42.5, 42.50",
                "7, 7.00",
                "0, 0.00",
                "1234.5, 1234.50",
                "-50.0, -50.00"
        })
        @DisplayName("always shows two decimals")
        void showsTwoDecimals(double amount, String expected) {
            assertEquals(expected, TelegramBot.formatAmount(amount));
        }

        @Test
        @DisplayName("uses a dot as the decimal separator regardless of the default locale")
        void isLocaleIndependent() {
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.GERMANY); // would otherwise render "100,00"
                assertEquals("100.00", TelegramBot.formatAmount(100.0));
            } finally {
                Locale.setDefault(original);
            }
        }

        @Test
        @DisplayName("renders a missing amount as ? instead of throwing")
        void nullAmountBecomesQuestionMark() {
            assertEquals("?", TelegramBot.formatAmount(null));
        }
    }

    @Nested
    @DisplayName("itemCount")
    class ItemCount {

        @ParameterizedTest
        @CsvSource({"0, 0 items", "1, 1 item", "2, 2 items", "45, 45 items"})
        @DisplayName("pluralises the noun")
        void pluralises(int count, String expected) {
            assertEquals(expected, TelegramBot.itemCount(count));
        }
    }

    @Nested
    @DisplayName("formatExpenseItem")
    class FormatItem {

        @Test
        @DisplayName("renders a complete expense as two lines: amount/description, then metadata")
        void rendersFullItem() {
            assertEquals("""
                            <b>100.00 EUR</b> — Groceries
                            🏷 Food · 👤 Lidl · 📅 2026-09-01 · 🆔 <code>106</code>""",
                    TelegramBot.formatExpenseItem(groceries()));
        }

        @Test
        @DisplayName("escapes HTML in every field that comes from the user or the API")
        void escapesAllInterpolatedFields() {
            ExpenseItem hostile = item(1L, 5.0, "2026-09-01",
                    "Beans <dark roast> & filters", "Home & Garden", "Shop <Main>", "EUR", false);

            assertEquals("""
                            <b>5.00 EUR</b> — Beans &lt;dark roast&gt; &amp; filters
                            🏷 Home &amp; Garden · 👤 Shop &lt;Main&gt; · 📅 2026-09-01 · 🆔 <code>1</code>""",
                    TelegramBot.formatExpenseItem(hostile));
        }

        @Test
        @DisplayName("keeps square brackets in a description literal (the legacy-Markdown bug)")
        void keepsSquareBracketsLiteral() {
            ExpenseItem bracketed = item(106L, 100.0, "2026-09-01",
                    "[ID: 106] refund *test*", "Food", "Lidl", "EUR", false);

            String rendered = TelegramBot.formatExpenseItem(bracketed);

            assertAll(
                    () -> assertTrue(rendered.contains("[ID: 106] refund *test*"),
                            () -> "brackets were altered: " + rendered),
                    () -> assertTrue(rendered.contains("🆔 <code>106</code>"),
                            () -> "id block missing: " + rendered));
        }

        @Test
        @DisplayName("omits empty metadata fields without leaving dangling separators")
        void omitsEmptyFields() {
            ExpenseItem sparse = item(109L, 7.0, null, "", "Misc", "  ", "EUR", false);

            assertEquals("""
                            <b>7.00 EUR</b>
                            🏷 Misc · 🆔 <code>109</code>""",
                    TelegramBot.formatExpenseItem(sparse));
        }

        @Test
        @DisplayName("renders an item with no metadata at all as a single line")
        void rendersBareItem() {
            ExpenseItem bare = item(null, 3.0, null, null, null, null, "EUR", false);

            String rendered = TelegramBot.formatExpenseItem(bare);

            assertAll(
                    () -> assertEquals("<b>3.00 EUR</b>", rendered),
                    () -> assertFalse(rendered.contains("·"), "separator left behind"),
                    () -> assertFalse(rendered.endsWith("\n"), "trailing newline left behind"));
        }

        @Test
        @DisplayName("marks refunds with an arrow")
        void marksRefunds() {
            ExpenseItem refund = item(108L, 12.0, "2026-09-03", "Returned mug", "Home", "", "EUR", true);

            assertEquals("""
                            <b>12.00 EUR</b> — Returned mug ↩️
                            🏷 Home · 📅 2026-09-03 · 🆔 <code>108</code>""",
                    TelegramBot.formatExpenseItem(refund));
        }

        @Test
        @DisplayName("survives a missing amount and a missing currency")
        void survivesMissingAmountAndCurrency() {
            ExpenseItem broken = item(5L, null, "2026-09-01", "Unknown", "Misc", null, null, false);

            assertEquals("""
                            <b>?</b> — Unknown
                            🏷 Misc · 📅 2026-09-01 · 🆔 <code>5</code>""",
                    TelegramBot.formatExpenseItem(broken));
        }
    }

    @Nested
    @DisplayName("formatExpenseResponse")
    class FormatResponse {

        @Test
        @DisplayName("renders title, totals and one blank-line-separated block per expense")
        void rendersFullResponse() {
            ExpenseResponse response = response(
                    List.of(groceries(),
                            item(107L, 42.5, "2026-09-02", "Coffee beans", "Food", "Roastery", "EUR", false)),
                    List.of(new ExpenseSummary("EUR", 142.5, 0.0, 2)));

            assertEquals("""
                            📋 <b>Search results for “test”</b>

                            📊 <b>142.50 EUR</b> · 2 items

                            <b>100.00 EUR</b> — Groceries
                            🏷 Food · 👤 Lidl · 📅 2026-09-01 · 🆔 <code>106</code>

                            <b>42.50 EUR</b> — Coffee beans
                            🏷 Food · 👤 Roastery · 📅 2026-09-02 · 🆔 <code>107</code>""",
                    TelegramBot.formatExpenseResponse("Search results for “test”", response));
        }

        @Test
        @DisplayName("escapes the title, which carries the user's search term")
        void escapesTitle() {
            ExpenseResponse response = response(List.of(groceries()), null);

            assertTrue(TelegramBot.formatExpenseResponse("Category “A & B <x>”", response)
                            .startsWith("📋 <b>Category “A &amp; B &lt;x&gt;”</b>"),
                    "title was not escaped");
        }

        @Test
        @DisplayName("appends refunds to the totals line only when there are any")
        void showsRefundTotalWhenPresent() {
            ExpenseResponse withRefund = response(List.of(groceries()),
                    List.of(new ExpenseSummary("EUR", 161.5, 12.0, 4)));

            assertTrue(TelegramBot.formatExpenseResponse("All expenses", withRefund)
                            .contains("📊 <b>161.50 EUR</b> · 4 items · ↩️ 12.00 refunded"),
                    "refund total missing");
        }

        @ParameterizedTest
        @CsvSource(nullValues = "null", value = {"null", "0.0"})
        @DisplayName("leaves the refund segment off when the refund total is null or zero")
        void hidesEmptyRefundTotal(Double refundSum) {
            ExpenseResponse response = response(List.of(groceries()),
                    List.of(new ExpenseSummary("EUR", 100.0, refundSum, 1)));

            assertEquals("📊 <b>100.00 EUR</b> · 1 item",
                    TelegramBot.formatExpenseResponse("All expenses", response).lines().toList().get(2));
        }

        @Test
        @DisplayName("falls back to a plain item count when the API returns no sums")
        void fallsBackToItemCountWithoutSums() {
            ExpenseResponse noSums = response(List.of(groceries()), List.of());

            assertTrue(TelegramBot.formatExpenseResponse("All expenses", noSums).contains("📊 1 item"),
                    "item-count fallback missing");
        }

        @Test
        @DisplayName("renders one totals line per currency")
        void rendersOneTotalPerCurrency() {
            ExpenseResponse multi = response(
                    List.of(groceries(), item(110L, 50.0, "2026-09-04", "Taxi", "Travel", "Bolt", "RON", false)),
                    List.of(new ExpenseSummary("EUR", 100.0, 0.0, 1), new ExpenseSummary("RON", 50.0, 0.0, 1)));

            List<String> lines = TelegramBot.formatExpenseResponse("All expenses", multi).lines().toList();

            assertAll(
                    () -> assertEquals("📊 <b>100.00 EUR</b> · 1 item", lines.get(2)),
                    () -> assertEquals("📊 <b>50.00 RON</b> · 1 item", lines.get(3)));
        }

        @Test
        @DisplayName("reports an empty result instead of a bare header")
        void reportsEmptyResult() {
            assertEquals("🔍 No expenses found — Search results for “nope”",
                    TelegramBot.formatExpenseResponse("Search results for “nope”",
                            response(List.of(), List.of())));
        }

        @Test
        @DisplayName("treats a null expense list like an empty one")
        void handlesNullExpenseList() {
            assertEquals("🔍 No expenses found — All expenses",
                    TelegramBot.formatExpenseResponse("All expenses", response(null, null)));
        }

        @Test
        @DisplayName("leaves no trailing blank line, and balances every HTML tag it opens")
        void producesWellFormedOutput() {
            ExpenseResponse response = response(
                    List.of(groceries(),
                            item(107L, 42.5, "2026-09-02", "Coffee", "Food", "Roastery", "EUR", true)),
                    List.of(new ExpenseSummary("EUR", 142.5, 12.0, 2)));

            String rendered = TelegramBot.formatExpenseResponse("All expenses", response);

            assertAll(
                    () -> assertEquals(rendered.stripTrailing(), rendered, "trailing whitespace"),
                    () -> assertEquals(count(rendered, "<b>"), count(rendered, "</b>"), "unbalanced <b>"),
                    () -> assertEquals(count(rendered, "<code>"), count(rendered, "</code>"), "unbalanced <code>"));
        }
    }

    static int count(String text, String needle) {
        return text.split(needle, -1).length - 1;
    }
}
