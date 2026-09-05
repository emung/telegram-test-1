package eeu.test;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) throws TelegramApiException {
        Config config;
        try {
            config = Config.fromEnv();
        } catch (IllegalStateException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.err.println("Required: BOT_TOKEN, BOT_USERNAME, ALLOWED_USER_ID, API_BASE_URL");
            System.exit(1);
            return;
        }

        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(new TelegramBot(config));

        System.out.println("Bot @" + config.botUsername() + " started, using API at " + config.apiBaseUrl());
    }
}
