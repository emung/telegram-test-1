package eeu.test;

import eeu.test.client.ExpenseItem;
import eeu.test.client.ExpenseResponse;
import eeu.test.client.ExpenseSummary;
import eeu.test.client.ExpenseWebClient;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;

public class TelegramBot extends TelegramLongPollingBot {

    private static final String DELETE_CONFIRM_PREFIX = "CONFIRM_DELETE_";
    private static final String DELETE_CANCEL_PREFIX = "CANCEL_DELETE_";

    private final Config config;
    private final ExpenseWebClient webClient;

    public TelegramBot(Config config) {
        this.config = config;
        this.webClient = new ExpenseWebClient(config.apiBaseUrl());
    }

    @Override
    public String getBotUsername() {
        return config.botUsername();
    }

    @Override
    public String getBotToken() {
        return config.botToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        // Handle Callback Query (Button Click)
        if (update.hasCallbackQuery()) {
            CallbackQuery callbackQuery = update.getCallbackQuery();
            Long userId = callbackQuery.getFrom().getId();

            if (!config.allowedUserId().equals(userId)) {
                return;
            }

            handleDeleteCallback(callbackQuery);
            return;
        }

        // Handle Text Messages
        if (update.hasMessage() && update.getMessage().hasText()) {
            Long userId = update.getMessage().getFrom().getId();

            if (!config.allowedUserId().equals(userId)) {
                sendText(userId, "⛔ Access denied: You are not authorized to use this bot.");
                return;
            }

            String text = update.getMessage().getText().trim();

            // 1. Command Routing
            if (text.equalsIgnoreCase("/list")) {
                handleListExpenses(userId);
                return;
            }

            if (text.equalsIgnoreCase("/categories")) {
                handleGetCategories(userId);
                return;
            }

            if (text.startsWith("/search ")) {
                String query = text.substring(8).trim();
                handleSearchExpenses(userId, query);
                return;
            }

            if (text.startsWith("/category ")) {
                String category = text.substring(10).trim();
                handleExpensesByCategory(userId, category);
                return;
            }

            if (text.startsWith("/delete ")) {
                String idStr = text.substring(8).trim();
                handleDeleteCommand(userId, idStr);
                return;
            }

            if (text.startsWith("/update ")) {
                handleUpdateExpense(userId, text);
                return;
            }

            // 2. Default: Parsing 5-line string payload
            Result result = parseExpense(text, 0);

            if (result.isSuccess()) {
                Expense expense = result.getExpense();
                try {
                    ExpenseItem savedExpense = webClient.createExpense(expense);
                    sendText(userId, "✅ Saved successfully (ID: " + savedExpense.getId() + ")!\n" +
                            "Spent " + savedExpense.getAmount() + " " + savedExpense.getCurrency() +
                            " for " + savedExpense.getDescription());
                } catch (Exception e) {
                    sendText(userId, "Error sending expense to API: " + e.getMessage());
                }
            } else {
                sendText(userId, "Error: " + result.getMessage());
            }
        }
    }

    private void handleDeleteCommand(Long userId, String idStr) {
        try {
            Long id = Long.parseLong(idStr);

            // Fetch expense first to show context in confirmation message
            ExpenseItem item = webClient.getExpenseById(id);

            String promptText = String.format("⚠️ **Are you sure you want to delete expense ID %d?**\n\n• **Amount:** %s %s\n• **Description:** %s\n• **Category:** %s",
                    item.getId(), item.getAmount(), item.getCurrency(), item.getDescription(), item.getCategory());

            // Build Inline Keyboard
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();

            InlineKeyboardButton confirmBtn = InlineKeyboardButton.builder()
                    .text("✅ Yes, Delete")
                    .callbackData(DELETE_CONFIRM_PREFIX + id)
                    .build();

            InlineKeyboardButton cancelBtn = InlineKeyboardButton.builder()
                    .text("❌ Cancel")
                    .callbackData(DELETE_CANCEL_PREFIX + id)
                    .build();

            rows.add(List.of(confirmBtn, cancelBtn));
            markup.setKeyboard(rows);

            SendMessage sm = SendMessage.builder()
                    .chatId(userId.toString())
                    .text(promptText)
                    .parseMode("Markdown")
                    .replyMarkup(markup)
                    .build();

            execute(sm);
        } catch (NumberFormatException e) {
            sendText(userId, "Invalid ID format. Usage: `/delete <id>` (e.g. `/delete 12`)");
        } catch (Exception e) {
            sendText(userId, "Error fetching expense ID " + idStr + ": " + e.getMessage());
        }
    }

    private void handleDeleteCallback(CallbackQuery callbackQuery) {
        String data = callbackQuery.getData();
        Long chatId = callbackQuery.getMessage().getChatId();
        Integer messageId = callbackQuery.getMessage().getMessageId();

        if (data.startsWith(DELETE_CONFIRM_PREFIX)) {
            Long id = Long.parseLong(data.substring(DELETE_CONFIRM_PREFIX.length()));
            try {
                webClient.deleteExpenseById(id);
                editMessageText(chatId, messageId, "🗑️ **Expense ID " + id + " has been deleted successfully.**");
            } catch (Exception e) {
                editMessageText(chatId, messageId, "❌ Error deleting expense ID " + id + ": " + e.getMessage());
            }
        } else if (data.startsWith(DELETE_CANCEL_PREFIX)) {
            Long id = Long.parseLong(data.substring(DELETE_CANCEL_PREFIX.length()));
            editMessageText(chatId, messageId, "🚫 Deletion of expense ID " + id + " was cancelled.");
        }
    }

    private void handleUpdateExpense(Long userId, String rawText) {
        String[] lines = rawText.trim().split("\\r?\\n");
        String firstLineHeader = lines[0].trim();
        String idStr = firstLineHeader.substring(8).trim();

        Long id;
        try {
            id = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            sendText(userId, "Invalid ID in `/update` command. Usage:\n`/update <id>`\n<100 RON>\n<Description>\n<Category>\n<Recipient>\n<Date>");
            return;
        }

        Result result = parseExpense(rawText, 1);

        if (!result.isSuccess()) {
            sendText(userId, "Error parsing update format:\n" + result.getMessage());
            return;
        }

        try {
            ExpenseItem updatedExpense = webClient.updateExpenseById(id, result.getExpense());
            sendText(userId, "✏️ Expense ID " + updatedExpense.getId() + " updated successfully!\n" +
                    "New values: " + updatedExpense.getAmount() + " " + updatedExpense.getCurrency() +
                    " - " + updatedExpense.getDescription());
        } catch (Exception e) {
            sendText(userId, "Error updating expense: " + e.getMessage());
        }
    }

    private void handleListExpenses(Long userId) {
        try {
            ExpenseResponse response = webClient.getAllExpenses();
            sendText(userId, formatExpenseResponse("All Expenses", response));
        } catch (Exception e) {
            sendText(userId, "Error fetching expenses: " + e.getMessage());
        }
    }

    private void handleGetCategories(Long userId) {
        try {
            List<String> categories = webClient.getAllDistinctCategories();
            if (categories.isEmpty()) {
                sendText(userId, "No categories found.");
                return;
            }

            StringBuilder sb = new StringBuilder("📁 **Categories:**\n\n");
            for (String cat : categories) {
                sb.append("• ").append(cat).append("\n");
            }
            sendText(userId, sb.toString());
        } catch (Exception e) {
            sendText(userId, "Error fetching categories: " + e.getMessage());
        }
    }

    private void handleSearchExpenses(Long userId, String query) {
        if (query.isEmpty()) {
            sendText(userId, "Please provide a search term. Example: `/search grocery`");
            return;
        }
        try {
            ExpenseResponse response = webClient.searchExpenses(query);
            sendText(userId, formatExpenseResponse("Search Results for '" + query + "'", response));
        } catch (Exception e) {
            sendText(userId, "Error searching expenses: " + e.getMessage());
        }
    }

    private void handleExpensesByCategory(Long userId, String category) {
        if (category.isEmpty()) {
            sendText(userId, "Please provide a category. Example: `/category Food`");
            return;
        }
        try {
            ExpenseResponse response = webClient.getExpensesByCategory(category);
            sendText(userId, formatExpenseResponse("Expenses for Category '" + category + "'", response));
        } catch (Exception e) {
            sendText(userId, "Error fetching category expenses: " + e.getMessage());
        }
    }

    private String formatExpenseResponse(String title, ExpenseResponse response) {
        List<ExpenseItem> expenses = response.getExpenses();

        if (expenses == null || expenses.isEmpty()) {
            return "No expenses found for: " + title;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📋 **").append(title).append("**\n\n");

        if (response.getSums() != null && !response.getSums().isEmpty()) {
            sb.append("📊 **Totals:**\n");
            for (ExpenseSummary sum : response.getSums()) {
                sb.append("• ").append(sum.getSum()).append(" ").append(sum.getCurrency());
                if (sum.getRefundSum() != null && sum.getRefundSum() > 0) {
                    sb.append(" (Refunds: ").append(sum.getRefundSum()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("📝 **Items:**\n");
        for (ExpenseItem item : expenses) {
            sb.append("• [ID: ").append(item.getId()).append("] ")
                    .append(item.getAmount()).append(" ").append(item.getCurrency())
                    .append(" - ").append(item.getDescription())
                    .append(" (").append(item.getCategory()).append(")");
            if (item.getRecipient() != null && !item.getRecipient().isEmpty()) {
                sb.append(" → ").append(item.getRecipient());
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    public void editMessageText(Long chatId, Integer messageId, String newText) {
        EditMessageText em = EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text(newText)
                .parseMode("Markdown")
                .build();
        try {
            execute(em);
        } catch (TelegramApiException e) {
            em.setParseMode(null);
            try {
                execute(em);
            } catch (TelegramApiException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    public void sendText(Long who, String what) {
        SendMessage sm = SendMessage.builder()
                .chatId(who.toString())
                .text(what)
                .parseMode("Markdown")
                .build();
        try {
            execute(sm);
        } catch (TelegramApiException e) {
            sm.setParseMode(null);
            try {
                execute(sm);
            } catch (TelegramApiException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    private Result parseExpense(String text, int lineOffset) {
        if (text == null || text.trim().isEmpty()) {
            return Result.error("Message cannot be empty.");
        }

        String[] rawLines = text.trim().split("\\r?\\n");
        if (rawLines.length < (5 + lineOffset)) {
            return Result.error("Please provide 5 fields:\nLine 1: Amount & Currency (e.g., 1000 RON)\nLine 2: Description\nLine 3: Category\nLine 4: Recipient\nLine 5: Date");
        }

        String line1 = rawLines[lineOffset].trim();
        String[] amountParts = line1.split("\\s+", 2);

        if (amountParts.length < 2) {
            return Result.error("Amount line must contain both amount and currency (e.g., '1000 RON').");
        }

        Integer amount;
        try {
            amount = Integer.parseInt(amountParts[0].trim());
        } catch (NumberFormatException e) {
            return Result.error("Amount must be a valid integer.");
        }

        String currency = amountParts[1].trim().toUpperCase();
        String description = rawLines[lineOffset + 1].trim();
        String category = rawLines[lineOffset + 2].trim();
        String recipient = rawLines[lineOffset + 3].trim();
        String date = rawLines[lineOffset + 4].trim();

        if (description.isEmpty() || category.isEmpty() || recipient.isEmpty() || date.isEmpty()) {
            return Result.error("All 5 expense fields are required and cannot be empty.");
        }

        Expense expense = new Expense(amount, currency, description, category, recipient, date);
        return Result.ok(expense);
    }
}