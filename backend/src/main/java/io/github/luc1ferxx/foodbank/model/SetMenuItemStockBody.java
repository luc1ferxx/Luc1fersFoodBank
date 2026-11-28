package io.github.luc1ferxx.foodbank.model;


import io.github.luc1ferxx.foodbank.service.StockService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;


/**
 * PUT /admin/menu-items/{menuItemId}/stock. Required, so a missing or misspelled field is a 400 and never removes the
 * limit; DELETE does that.
 *
 * @param remaining how many can be ordered from now on, an absolute value
 */
public record SetMenuItemStockBody(
        @NotNull
        @Min(0)
        @Max(StockService.MAX_STOCK)
        Integer remaining
) {
}
