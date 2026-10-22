package com.laioffer.onlineorder.push;


import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.fulfilment.dispatch.RiderEntity;
import com.laioffer.onlineorder.fulfilment.dispatch.RiderService;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantAccountEntity;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantAccountService;
import com.laioffer.onlineorder.service.CustomerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


/**
 * Opens the push stream of a login (GET /events/stream). The recipients are resolved once, at connect, with the lookups
 * the merchant and rider endpoints use: every login gets {@code customer:{customerId}}, a merchant login
 * (ROLE_MERCHANT) also {@code restaurant:{restaurantId}} of its active account, a rider login (ROLE_RIDER) also
 * {@code rider:{riderId}}.
 * A merchant login without an active account, or a rider login without an active rider, is refused with 403 like the
 * merchant and rider endpoints. The lookups run outside any transaction, so they read the primary.
 */
public class PushStreamService {

    static final String PUSH_STREAM_LIMIT = "PUSH_STREAM_LIMIT";

    private static final String ROLE_MERCHANT = "ROLE_MERCHANT";
    private static final String ROLE_RIDER = "ROLE_RIDER";
    private static final MediaType FRAME_TYPE = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    private final CustomerService customerService;
    private final MerchantAccountService merchantAccountService;
    private final RiderService riderService;
    private final PushStreamRegistry registry;
    private final Duration streamTimeout;


    public PushStreamService(
            CustomerService customerService,
            MerchantAccountService merchantAccountService,
            RiderService riderService,
            PushStreamRegistry registry,
            Duration streamTimeout
    ) {
        this.customerService = customerService;
        this.merchantAccountService = merchantAccountService;
        this.riderService = riderService;
        this.registry = registry;
        this.streamTimeout = streamTimeout;
    }


    /**
     * The emitter of the new stream, with {@code retry: 5000} already queued as its first frame. It completes itself
     * after {@code app.push.stream-timeout} (its async timeout, so spring.mvc.async.request-timeout does not apply).
     *
     * @throws PushStreamRefusedException when the stream is not opened
     */
    public SseEmitter open(Authentication authentication) {
        CustomerEntity customer = customerService.getCustomerByEmail(authentication.getName());
        long customerId = customer.id();
        Set<String> roles = roles(authentication);
        Set<PushRecipient> recipients = new LinkedHashSet<>();
        recipients.add(PushRecipient.customer(customerId));
        List<PushStream.AccessCheck> checks = new ArrayList<>();
        if (roles.contains(ROLE_MERCHANT)) {
            long restaurantId = activeRestaurantId(customerId);
            recipients.add(PushRecipient.restaurant(restaurantId));
            checks.add(() -> merchantAccountService.findByCustomerId(customerId)
                    .filter(account -> account.active() && Long.valueOf(restaurantId).equals(account.restaurantId()))
                    .isPresent());
        }
        if (roles.contains(ROLE_RIDER)) {
            long riderId = activeRiderId(customerId);
            recipients.add(PushRecipient.rider(riderId));
            checks.add(() -> riderService.findByCustomerId(customerId)
                    .filter(rider -> rider.active() && Long.valueOf(riderId).equals(rider.id()))
                    .isPresent());
        }

        SseEmitter emitter = new SseEmitter(streamTimeout.toMillis());
        PushStream stream = new PushStream(customerId, recipients, allOf(checks), new EmitterChannel(emitter));
        // Queued until the response starts, which flushes it with the headers.
        stream.send(SseFrames.OPEN);
        PushStreamRegistry.Registration registration = registry.register(stream);
        if (registration == PushStreamRegistry.Registration.USER_LIMIT) {
            throw new PushStreamRefusedException(
                    HttpStatus.TOO_MANY_REQUESTS, PUSH_STREAM_LIMIT, "Too many open streams for this login");
        }
        if (registration != PushStreamRegistry.Registration.REGISTERED) {
            // At app.push.max-streams, or shutting down: another instance can take it.
            throw new PushStreamRefusedException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "Service temporarily unavailable, please retry");
        }
        emitter.onCompletion(stream::ended);
        emitter.onError(error -> stream.ended());
        emitter.onTimeout(() -> {
            stream.ended();
            // Completing it here ends the request normally instead of with an AsyncRequestTimeoutException.
            emitter.complete();
        });
        return emitter;
    }


    private long activeRestaurantId(long customerId) {
        MerchantAccountEntity account = merchantAccountService.findByCustomerId(customerId).orElse(null);
        if (account == null || !account.active()) {
            throw forbidden();
        }
        return account.restaurantId();
    }


    private long activeRiderId(long customerId) {
        RiderEntity rider = riderService.findByCustomerId(customerId).orElse(null);
        if (rider == null || !rider.active()) {
            throw forbidden();
        }
        return rider.id();
    }


    private static PushStreamRefusedException forbidden() {
        return new PushStreamRefusedException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
    }


    /**
     * Only the ROLE_* authorities: Spring Security 7 adds FACTOR_PASSWORD to every password login.
     */
    private static Set<String> roles(Authentication authentication) {
        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith("ROLE_")) {
                roles.add(name);
            }
        }
        return roles;
    }


    private static PushStream.AccessCheck allOf(List<PushStream.AccessCheck> checks) {
        if (checks.isEmpty()) {
            return PushStream.AccessCheck.ALWAYS;
        }
        List<PushStream.AccessCheck> all = List.copyOf(checks);
        return () -> all.stream().allMatch(PushStream.AccessCheck::stillAllowed);
    }


    /**
     * Writes frames as they are, bypassing SseEmitter's own event builder, so the wire format is exactly
     * {@link SseFrames}.
     */
    private record EmitterChannel(SseEmitter emitter) implements PushStream.Channel {

        @Override
        public void send(String frame) throws IOException {
            emitter.send(Set.of(new ResponseBodyEmitter.DataWithMediaType(frame, FRAME_TYPE)));
        }


        @Override
        public void complete() {
            emitter.complete();
        }
    }
}
