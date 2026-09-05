package eeu.test.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ExpenseSummary {
    private String currency;
    private Double sum;
    private Double refundSum;
    private Integer count;
}