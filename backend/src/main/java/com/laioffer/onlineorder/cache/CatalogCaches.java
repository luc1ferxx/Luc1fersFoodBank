package com.laioffer.onlineorder.cache;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.laioffer.onlineorder.model.CatalogMenuItemDto;
import com.laioffer.onlineorder.model.CatalogRestaurantDto;


import java.util.List;


/**
 * The catalog caches and their Redis key versions.
 * <ul>
 *     <li>{@code restaurants::all}: the restaurant list ({@link CatalogRestaurantDto}, no menus). It also
 *     serves as the set of valid restaurant ids, so a lookup of an unknown id is answered from cache.</li>
 *     <li>{@code menu::<restaurantId>}: one restaurant's menu ({@link CatalogMenuItemDto}).</li>
 * </ul>
 * Bump a version whenever the cached DTO's JSON shape changes: Redis keys become
 * {@code onlineorder:cache:<version>:<cache>::<key>}, so instances running the old and new code during a
 * rolling deploy never read each other's entries, and old entries simply expire.
 */
public final class CatalogCaches {

    public static final String RESTAURANTS = "restaurants";
    public static final String MENU = "menu";
    public static final String ALL_RESTAURANTS_KEY = "all";

    static final String RESTAURANTS_KEY_VERSION = "v1";
    static final String MENU_KEY_VERSION = "v1";

    private static final long RESTAURANTS_LOCAL_MAXIMUM_SIZE = 16;


    private CatalogCaches() {
    }


    public static List<TwoLevelCacheSpec> specs(ObjectMapper objectMapper, long menuLocalMaximumSize) {
        TypeFactory types = objectMapper.getTypeFactory();
        return List.of(
                new TwoLevelCacheSpec(
                        RESTAURANTS,
                        RESTAURANTS_KEY_VERSION,
                        types.constructCollectionType(List.class, CatalogRestaurantDto.class),
                        RESTAURANTS_LOCAL_MAXIMUM_SIZE
                ),
                new TwoLevelCacheSpec(
                        MENU,
                        MENU_KEY_VERSION,
                        types.constructCollectionType(List.class, CatalogMenuItemDto.class),
                        menuLocalMaximumSize
                )
        );
    }
}
