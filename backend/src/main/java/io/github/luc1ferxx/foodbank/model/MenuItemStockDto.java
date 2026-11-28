package io.github.luc1ferxx.foodbank.model;


/**
 * The stock limit of a menu item, for admins (GET, PUT and DELETE /admin/menu-items/{menuItemId}/stock). Not part of
 * the catalog: the cached menu never shows stock.
 *
 * @param remaining how many can still be ordered; null, and so left out of the JSON, when the item is unlimited
 */
public record MenuItemStockDto(Long menuItemId, Integer remaining) {
}
