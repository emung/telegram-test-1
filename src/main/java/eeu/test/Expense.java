package eeu.test;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Expense {
    private Integer amount;
    private String currency;
    private String description;
    private String category;
    private String recipient;
    private String date;
}