package eeu.test;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Result {
    private String status;
    private String message;
    private boolean success;
    private Expense expense;

    public static Result ok(Expense expense) {
        return new Result("SUCCESS", "Expense parsed successfully", true, expense);
    }

    public static Result error(String message) {
        return new Result("ERROR", message, false, null);
    }
}