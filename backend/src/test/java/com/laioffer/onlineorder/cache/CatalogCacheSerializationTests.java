package com.laioffer.onlineorder.cache;


import com.laioffer.onlineorder.model.CatalogMenuItemDto;
import com.laioffer.onlineorder.model.CatalogRestaurantDto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import tools.jackson.databind.ObjectMapper;


import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;


/**
 * The catalog cache's Redis values and invalidation messages keep the exact JSON that the Spring Boot 3 (Jackson 2)
 * instances wrote, so the {@code v1} keys stay shared across the Spring Boot 4 upgrade. The expected strings below
 * were produced by Jackson 2.21 with the previous {@code JsonMapper.builder().build()} mapper.
 */
class CatalogCacheSerializationTests {

    private static final String JACKSON2_MENU = "[{\"id\":10,\"restaurantId\":5,\"name\":\"Whopper\","
            + "\"description\":\"Flame grilled\",\"price\":6.39,\"imageUrl\":\"w.png\"},"
            + "{\"id\":11,\"restaurantId\":5,\"name\":\"Fries\",\"description\":null,\"price\":2.10,\"imageUrl\":null}]";
    private static final String JACKSON2_RESTAURANTS =
            "[{\"id\":1,\"name\":\"Burger King\",\"address\":\"Main St\",\"phone\":\"123\",\"imageUrl\":\"bk.png\"}]";
    private static final String JACKSON2_INVALIDATION = "{\"origin\":\"instance-a\",\"cache\":\"menu\",\"key\":\"5\"}";

    private final ObjectMapper objectMapper = TwoLevelCaches.objectMapper();


    @Test
    void keyVersions_shouldStayAtV1BecauseTheStoredShapeIsUnchanged() {
        Assertions.assertEquals("v1", CatalogCaches.MENU_KEY_VERSION);
        Assertions.assertEquals("v1", CatalogCaches.RESTAURANTS_KEY_VERSION);
    }


    @Test
    void menuEntries_shouldBeWrittenAndReadExactlyAsJackson2DidIt() {
        JacksonJsonRedisSerializer<Object> serializer = serializer(CatalogCaches.MENU);
        List<CatalogMenuItemDto> menu = List.of(
                new CatalogMenuItemDto(10L, 5L, "Whopper", "Flame grilled", new BigDecimal("6.39"), "w.png"),
                new CatalogMenuItemDto(11L, 5L, "Fries", null, new BigDecimal("2.10"), null)
        );

        Assertions.assertEquals(JACKSON2_MENU, new String(serializer.serialize(menu), StandardCharsets.UTF_8));
        Object read = serializer.deserialize(JACKSON2_MENU.getBytes(StandardCharsets.UTF_8));
        Assertions.assertEquals(menu, read);
        Assertions.assertEquals(new BigDecimal("2.10"), ((CatalogMenuItemDto) ((List<?>) read).get(1)).price());
    }


    @Test
    void restaurantEntries_shouldBeWrittenAndReadExactlyAsJackson2DidIt() {
        JacksonJsonRedisSerializer<Object> serializer = serializer(CatalogCaches.RESTAURANTS);
        List<CatalogRestaurantDto> restaurants = List.of(new CatalogRestaurantDto(1L, "Burger King", "Main St", "123", "bk.png"));

        Assertions.assertEquals(JACKSON2_RESTAURANTS, new String(serializer.serialize(restaurants), StandardCharsets.UTF_8));
        Assertions.assertEquals(restaurants, serializer.deserialize(JACKSON2_RESTAURANTS.getBytes(StandardCharsets.UTF_8)));
    }


    @Test
    void anEntryWithAnUnknownProperty_shouldNotBeReadAsAPartialDto() {
        JacksonJsonRedisSerializer<Object> serializer = serializer(CatalogCaches.MENU);

        Assertions.assertThrows(
                SerializationException.class,
                () -> serializer.deserialize("[{\"id\":10,\"unexpected\":true}]".getBytes(StandardCharsets.UTF_8))
        );
    }


    @Test
    void invalidationMessages_shouldKeepTheirJsonAndIgnoreUnknownProperties() {
        CacheInvalidationMessage message = new CacheInvalidationMessage("instance-a", "menu", "5");

        Assertions.assertEquals(JACKSON2_INVALIDATION, objectMapper.writeValueAsString(message));
        Assertions.assertEquals(
                message,
                objectMapper.readValue(
                        "{\"origin\":\"instance-a\",\"cache\":\"menu\",\"key\":\"5\",\"added_later\":1}",
                        CacheInvalidationMessage.class
                )
        );
    }


    private JacksonJsonRedisSerializer<Object> serializer(String cacheName) {
        TwoLevelCacheSpec spec = CatalogCaches.specs(objectMapper, 100).stream()
                .filter(candidate -> candidate.name().equals(cacheName))
                .findFirst()
                .orElseThrow();
        return new JacksonJsonRedisSerializer<>(objectMapper, spec.valueType());
    }
}
