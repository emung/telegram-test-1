package eeu.test;

/**
 * Runtime configuration, read from environment variables.
 * All values are required; missing or malformed ones fail fast at startup.
 */
public record Config(String botToken, String botUsername, Long allowedUserId, String apiBaseUrl) {

    public static Config fromEnv() {
        String token = required("BOT_TOKEN");
        String username = required("BOT_USERNAME");
        String userId = required("ALLOWED_USER_ID");
        String baseUrl = required("API_BASE_URL");

        long allowedUserId;
        try {
            allowedUserId = Long.parseLong(userId.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("ALLOWED_USER_ID must be a numeric Telegram user ID, got: " + userId);
        }

        // Trailing slashes would produce "//api/v1/..." paths against the expense API.
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        return new Config(token, username, allowedUserId, baseUrl);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value.trim();
    }
}
