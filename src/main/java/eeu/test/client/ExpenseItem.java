package eeu.test.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ExpenseItem {
    private Long id;
    private Double amount;
    private String date;
    private String description;
    private String category;
    private String recipient;
    private String currency;

    @JsonProperty("isRefund")
    private boolean isRefund;
}