package eeu.test.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ExpenseResponse {
    private List<ExpenseSummary> sums;
    private Integer amount;
    private List<ExpenseItem> expenses;
}