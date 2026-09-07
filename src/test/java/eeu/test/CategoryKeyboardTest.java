package eeu.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the /categories reply: the listed text and the one-"Show expenses"-button-per-category
 * keyboard, including the round trip from a button's callback data back to a category name.
 */
class CategoryKeyboardTest {

    private static List<InlineKeyboardButton> buttons(List<String> categories) {
        InlineKeyboardMarkup markup = TelegramBot.categoryKeyboard(categories);
        return markup.getKeyboard().stream().flatMap(List::stream).toList();
    }

    /** The category a tap resolves to, i.e. what handleCallback strips the prefix down to. */
    private static String categoryFromCallback(InlineKeyboardButton button) {
        return button.getCallbackData().substring(TelegramBot.SHOW_CATEGORY_PREFIX.length());
    }

    private static String ofBytes(int byteCount) {
        return "x".repeat(byteCount);
    }

    @Nested
    @DisplayName("keyboard")
    class Keyboard {

        @Test
        @DisplayName("gives every category its own row with a Show expenses button")
        void oneRowPerCategory() {
            InlineKeyboardMarkup markup = TelegramBot.categoryKeyboard(List.of("Food", "Travel", "Home"));

            assertAll(
                    () -> assertEquals(3, markup.getKeyboard().size(), "expected one row per category"),
                    () -> assertTrue(markup.getKeyboard().stream().allMatch(row -> row.size() == 1),
                            "expected a single button per row"),
                    () -> assertEquals(
                            List.of("Show expenses · Food", "Show expenses · Travel", "Show expenses · Home"),
                            buttons(List.of("Food", "Travel", "Home")).stream()
                                    .map(InlineKeyboardButton::getText).toList()));
        }

        @Test
        @DisplayName("labels every button with the words Show expenses")
        void everyButtonSaysShowExpenses() {
            assertTrue(buttons(List.of("Food", "Travel")).stream()
                            .allMatch(button -> button.getText().startsWith("Show expenses")),
                    "a button is not labelled Show expenses");
        }

        @Test
        @DisplayName("carries the category in the callback data behind a routable prefix")
        void callbackDataIsPrefixed() {
            InlineKeyboardButton button = buttons(List.of("Food")).getFirst();

            assertAll(
                    () -> assertEquals("SHOW_CAT_Food", button.getCallbackData()),
                    () -> assertTrue(button.getCallbackData().startsWith(TelegramBot.SHOW_CATEGORY_PREFIX)));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "Food", "Food & Drink", "Café Zürich", "Haus/Garten", "100% Fun",
                "Food_and_more", "a b  c", "Ärzte", "受け取り", "SHOW_CAT_weird"
        })
        @DisplayName("a tapped button resolves back to the exact category name")
        void callbackDataRoundTripsExactly(String category) {
            InlineKeyboardButton button = buttons(List.of(category)).getFirst();

            assertAll(
                    () -> assertEquals(category, categoryFromCallback(button)),
                    // The label is plain text, so it must NOT be HTML-escaped.
                    () -> assertEquals("Show expenses · " + category, button.getText()));
        }

        @Test
        @DisplayName("leaves out categories whose callback data would breach Telegram's 64-byte cap")
        void skipsOversizedCategories() {
            String tooLong = ofBytes(TelegramBot.MAX_CALLBACK_DATA_BYTES); // + prefix = over the cap

            List<InlineKeyboardButton> rendered = buttons(List.of("Food", tooLong, "Travel"));

            assertEquals(List.of("Show expenses · Food", "Show expenses · Travel"),
                    rendered.stream().map(InlineKeyboardButton::getText).toList());
        }

        @Test
        @DisplayName("keeps every button's callback data within the byte cap")
        void allCallbackDataFitsTheCap() {
            List<String> categories = List.of("Food", "Café Zürich", ofBytes(54), "受け取り");

            assertTrue(buttons(categories).stream()
                            .allMatch(b -> b.getCallbackData().getBytes(StandardCharsets.UTF_8).length
                                    <= TelegramBot.MAX_CALLBACK_DATA_BYTES),
                    "callback data over the byte cap");
        }

        @Test
        @DisplayName("caps the keyboard size so the client can still render it")
        void capsNumberOfButtons() {
            List<String> many = new ArrayList<>();
            for (int i = 0; i < TelegramBot.MAX_CATEGORY_BUTTONS + 25; i++) {
                many.add("Category " + i);
            }

            assertEquals(TelegramBot.MAX_CATEGORY_BUTTONS, buttons(many).size());
        }

        @Test
        @DisplayName("skips blank and null category names")
        void skipsBlankCategories() {
            List<String> categories = Arrays.asList("Food", "", "   ", null, "Travel");

            assertEquals(List.of("Show expenses · Food", "Show expenses · Travel"),
                    buttons(categories).stream().map(InlineKeyboardButton::getText).toList());
        }

        @Test
        @DisplayName("produces an empty keyboard when nothing can be given a button")
        void producesEmptyKeyboardWhenNothingFits() {
            assertTrue(TelegramBot.categoryKeyboard(List.of(ofBytes(200))).getKeyboard().isEmpty());
        }
    }

    @Nested
    @DisplayName("byte cap")
    class ByteCap {

        @Test
        @DisplayName("accepts callback data of exactly the cap and rejects one byte more")
        void boundaryIsExact() {
            int room = TelegramBot.MAX_CALLBACK_DATA_BYTES - TelegramBot.SHOW_CATEGORY_PREFIX.length();

            assertAll(
                    () -> assertTrue(TelegramBot.fitsCallbackData(ofBytes(room))),
                    () -> assertFalse(TelegramBot.fitsCallbackData(ofBytes(room + 1))));
        }

        @Test
        @DisplayName("measures multi-byte characters in bytes, not characters")
        void countsUtf8Bytes() {
            int room = TelegramBot.MAX_CALLBACK_DATA_BYTES - TelegramBot.SHOW_CATEGORY_PREFIX.length();
            String threeByteChars = "受".repeat(room / 3); // fits exactly

            assertAll(
                    () -> assertTrue(TelegramBot.fitsCallbackData(threeByteChars)),
                    () -> assertFalse(TelegramBot.fitsCallbackData(threeByteChars + "受受")));
        }
    }

    @Nested
    @DisplayName("message text")
    class MessageText {

        @Test
        @DisplayName("lists every category and points at the buttons")
        void listsCategoriesWithHint() {
            assertEquals("""
                            📁 <b>Categories</b>

                            • Food
                            • Travel

                            Tap a <b>Show expenses</b> button below to list a category.""",
                    TelegramBot.formatCategoryList(List.of("Food", "Travel")).stripTrailing());
        }

        @Test
        @DisplayName("escapes category names, which are free text from the user's expenses")
        void escapesCategoryNames() {
            assertTrue(TelegramBot.formatCategoryList(List.of("Home & Garden <old>"))
                            .contains("• Home &amp; Garden &lt;old&gt;"),
                    "category name was not escaped");
        }

        @Test
        @DisplayName("tells the user how to reach a category that has no button")
        void explainsMissingButton() {
            String tooLong = ofBytes(TelegramBot.MAX_CALLBACK_DATA_BYTES);

            String text = TelegramBot.formatCategoryList(List.of("Food", tooLong));

            assertAll(
                    () -> assertTrue(text.contains("• " + tooLong + " — no button, use <code>/category "
                            + tooLong + "</code>"), () -> "no fallback hint: " + text),
                    () -> assertTrue(text.contains("• Food\n"), "button-backed category lost its plain line"));
        }

        @Test
        @DisplayName("still lists categories beyond the button cap")
        void listsCategoriesBeyondTheButtonCap() {
            List<String> many = new ArrayList<>();
            for (int i = 0; i < TelegramBot.MAX_CATEGORY_BUTTONS + 5; i++) {
                many.add("Category " + i);
            }

            String text = TelegramBot.formatCategoryList(many);

            assertAll(
                    () -> assertTrue(text.contains("• Category " + (TelegramBot.MAX_CATEGORY_BUTTONS + 4)),
                            "a category past the cap is missing from the list"),
                    () -> assertTrue(text.contains("• Category " + (TelegramBot.MAX_CATEGORY_BUTTONS + 4)
                            + " — no button"), "a category past the cap was not marked"));
        }

        @Test
        @DisplayName("omits the button hint when no category got a button")
        void omitsHintWithoutButtons() {
            assertFalse(TelegramBot.formatCategoryList(List.of(ofBytes(200))).contains("Tap a"),
                    "hint shown without any buttons");
        }

        @Test
        @DisplayName("balances the HTML tags it opens")
        void producesBalancedHtml() {
            String text = TelegramBot.formatCategoryList(List.of("Food", ofBytes(200)));

            assertAll(
                    () -> assertEquals(ExpenseFormattingTest.count(text, "<b>"),
                            ExpenseFormattingTest.count(text, "</b>"), "unbalanced <b>"),
                    () -> assertEquals(ExpenseFormattingTest.count(text, "<code>"),
                            ExpenseFormattingTest.count(text, "</code>"), "unbalanced <code>"));
        }
    }
}
