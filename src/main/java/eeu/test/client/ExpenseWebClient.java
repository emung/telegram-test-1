package eeu.test.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eeu.test.Expense;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

public class ExpenseWebClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public ExpenseWebClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // POST /expenses - Create Expense
    public ExpenseItem createExpense(Expense expense) throws Exception {
        ExpenseItem payload = mapExpenseToItem(expense);
        String jsonBody = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/expenses"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response);

        return objectMapper.readValue(response.body(), ExpenseItem.class);
    }

    // PUT /expenses/{id} - Update Expense
    public ExpenseItem updateExpenseById(Long id, Expense expense) throws Exception {
        ExpenseItem payload = mapExpenseToItem(expense);
        payload.setId(id);
        String jsonBody = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/expenses/" + id))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response);

        return objectMapper.readValue(response.body(), ExpenseItem.class);
    }

    // GET /expenses - Get all expenses with total sums
    public ExpenseResponse getAllExpenses() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/expenses"))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response);

        return objectMapper.readValue(response.body(), ExpenseResponse.class);
    }

    // GET /expenses/categories - Get distinct categories
    public List<String> getAllDistinctCategories() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/expenses/categories"))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response);

        return objectMapper.readValue(response.body(), new TypeReference<List<String>>() {});
    }

    // GET /expenses/by-category?category={category}
    public ExpenseResponse getExpensesByCategory(String category) throws Exception {
        String encodedCategory = URLEncoder.encode(category, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/expenses/by-category?category=" + encodedCategory))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response);

        return objectMapper.readValue(response.body(), ExpenseResponse.class);
    }

    // GET /expenses/by-description?description={description}
    public ExpenseResponse getExpensesByDescription(String description) throws Exception {
        String encodedDescription = URLEncoder.encode(description, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/expenses/by-description?description=" + encodedDescription))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response);

        return objectMapper.readValue(response.body(), ExpenseResponse.class);
    }

    // GET /expenses/search?q={query}
    public ExpenseResponse searchExpenses(String query) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/expenses/search?q=" + encodedQuery))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response);

        return objectMapper.readValue(response.body(), ExpenseResponse.class);
    }

    // GET /expenses/{id}
    public ExpenseItem getExpenseById(Long id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/expenses/" + id))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response);

        return objectMapper.readValue(response.body(), ExpenseItem.class);
    }

    // DELETE /expenses/{id}
    public void deleteExpenseById(Long id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/expenses/" + id))
                .header("Accept", "application/json")
                .DELETE()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response);
    }

    private ExpenseItem mapExpenseToItem(Expense expense) {
        ExpenseItem payload = new ExpenseItem();
        payload.setAmount(expense.getAmount().doubleValue());
        payload.setDescription(expense.getDescription());
        payload.setCategory(expense.getCategory());
        payload.setRecipient(expense.getRecipient());
        payload.setCurrency(expense.getCurrency());
        payload.setDate(expense.getDate());
        payload.setRefund(false);
        return payload;
    }

    private void ensureSuccess(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("API call failed with status: " + response.statusCode() + " - " + response.body());
        }
    }
}