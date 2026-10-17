package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.audit.AuditActions;
import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.audit.AuditLogService;
import com.laioffer.onlineorder.cache.CatalogCacheInvalidator;
import com.laioffer.onlineorder.cache.CatalogCaches;
import com.laioffer.onlineorder.datasource.ReadOnlyTransactions;
import com.laioffer.onlineorder.entity.RestaurantEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.CatalogRestaurantDto;
import com.laioffer.onlineorder.model.UpdateRestaurantBody;
import com.laioffer.onlineorder.repository.RestaurantRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class RestaurantService {


    private final RestaurantRepository restaurantRepository;
    private final CatalogCacheInvalidator cacheInvalidator;
    private final AuditLogService auditLogService;
    private final ReadOnlyTransactions readOnlyTransactions;


    public RestaurantService(
            RestaurantRepository restaurantRepository,
            CatalogCacheInvalidator cacheInvalidator,
            AuditLogService auditLogService,
            ReadOnlyTransactions readOnlyTransactions) {
        this.restaurantRepository = restaurantRepository;
        this.cacheInvalidator = cacheInvalidator;
        this.auditLogService = auditLogService;
        this.readOnlyTransactions = readOnlyTransactions;
    }


    /**
     * The restaurant list without menus. The returned list is shared by every caller that hits the cache and
     * must not be modified. A cache miss loads it in a read-only transaction (a replica read with replicas
     * configured); a hit opens none.
     */
    @Cacheable(cacheNames = CatalogCaches.RESTAURANTS, key = "'" + CatalogCaches.ALL_RESTAURANTS_KEY + "'", sync = true)
    public List<CatalogRestaurantDto> getRestaurantList() {
        return readOnlyTransactions.read("RestaurantService.getRestaurantList", () ->
                restaurantRepository.findAllOrderById().stream()
                        .map(CatalogRestaurantDto::new)
                        .toList());
    }


    /**
     * Uncached primary-key lookup, for ids the cached restaurant list does not know.
     */
    @Transactional(readOnly = true)
    public boolean restaurantExists(long restaurantId) {
        return restaurantRepository.existsById(restaurantId);
    }


    /**
     * Uncached names of the given restaurants, for the staff and rider views; ids without a restaurant are absent.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> findNames(Collection<Long> restaurantIds) {
        if (restaurantIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (RestaurantEntity restaurant : restaurantRepository.findAllById(new HashSet<>(restaurantIds))) {
            names.put(restaurant.id(), restaurant.name());
        }
        return names;
    }


    /**
     * Drops the cached restaurant list on every instance, for example when it is missing a restaurant that
     * was created outside the admin API.
     */
    public void evictRestaurantList() {
        cacheInvalidator.evictRestaurantsAfterCommit();
    }


    /**
     * Updates the restaurant's basic fields and evicts the cached restaurant list after the commit.
     */
    @Transactional
    public CatalogRestaurantDto updateRestaurant(long restaurantId, UpdateRestaurantBody body) {
        return updateRestaurant(restaurantId, body, AuditActor.system());
    }


    /**
     * {@link #updateRestaurant(long, UpdateRestaurantBody)} recorded in the audit log as done by {@code actor}, in the
     * same transaction.
     */
    @Transactional
    public CatalogRestaurantDto updateRestaurant(long restaurantId, UpdateRestaurantBody body, AuditActor actor) {
        if (body.hasNoChanges()) {
            throw new BadRequestException("At least one of name, address, phone or image_url is required");
        }
        int updatedRows = restaurantRepository.updateBasicFields(
                restaurantId,
                body.name(),
                body.address(),
                body.phone(),
                body.imageUrl()
        );
        if (updatedRows == 0) {
            throw new ResourceNotFoundException("Restaurant not found");
        }
        cacheInvalidator.evictRestaurantsAfterCommit();

        // The fields the request set (omitted fields were left unchanged).
        Map<String, Object> changes = new LinkedHashMap<>();
        putIfPresent(changes, "name", body.name());
        putIfPresent(changes, "address", body.address());
        putIfPresent(changes, "phone", body.phone());
        putIfPresent(changes, "image_url", body.imageUrl());
        auditLogService.record(
                actor,
                AuditActions.RESTAURANT_UPDATE,
                AuditActions.TARGET_RESTAURANT,
                restaurantId,
                Map.of("changes", changes)
        );
        return restaurantRepository.findById(restaurantId)
                .map(CatalogRestaurantDto::new)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
    }


    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
