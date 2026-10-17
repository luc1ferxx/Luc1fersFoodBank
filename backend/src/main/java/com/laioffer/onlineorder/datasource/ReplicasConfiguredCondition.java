package com.laioffer.onlineorder.datasource;


import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;


import java.util.List;


/**
 * Matches when {@code app.datasource.replica.urls} holds at least one non-blank URL. {@code @ConditionalOnProperty}
 * cannot express this: application.yml always defines the property, empty unless {@code DATABASE_REPLICA_URLS} is set.
 */
class ReplicasConfiguredCondition extends SpringBootCondition {

    static final String URLS_PROPERTY = "app.datasource.replica.urls";


    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        BindResult<List<String>> urls = Binder.get(context.getEnvironment())
                .bind(URLS_PROPERTY, Bindable.listOf(String.class));
        boolean configured = urls.isBound() && urls.get().stream().anyMatch(url -> url != null && !url.isBlank());
        return configured
                ? ConditionOutcome.match(URLS_PROPERTY + " lists a read replica")
                : ConditionOutcome.noMatch(URLS_PROPERTY + " is empty; every statement uses the primary");
    }
}
