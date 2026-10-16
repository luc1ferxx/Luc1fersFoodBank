package com.laioffer.onlineorder;


import com.laioffer.onlineorder.cache.CacheInvalidationMessage;
import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.ResolvableType;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestBody;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;


import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


/**
 * Jackson 3 enables FAIL_ON_NULL_FOR_PRIMITIVES: a primitive record component whose value is missing from the JSON,
 * or null, fails to bind (Jackson 2 bound 0 or false). A request body that omits such a field would be rejected
 * with 400, and an event written before such a field existed would be dead-lettered. JSON input types therefore use
 * boxed components (Long, Integer, Boolean) and validate presence with Bean Validation where it is required.
 */
class JsonInputTypesTests {

    private static final String BASE_PACKAGE = "com.laioffer.onlineorder";

    /**
     * Payloads read from Kafka and Redis pub/sub (objectMapper.readValue in the consumers and listeners).
     */
    private static final List<Class<?>> CONSUMED_TYPES = List.of(
            OrderEventEnvelope.class,
            DeliveryEventEnvelope.class,
            CacheInvalidationMessage.class
    );


    @Test
    void springBootMapper_shouldRejectAMissingOrNullPrimitiveComponent() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> {
                    JsonMapper mapper = context.getBean(JsonMapper.class);

                    Assertions.assertThrows(
                            MismatchedInputException.class,
                            () -> mapper.readValue("{\"name\":\"rider\"}", PrimitiveBody.class)
                    );
                    Assertions.assertThrows(
                            MismatchedInputException.class,
                            () -> mapper.readValue("{\"on_duty\":null,\"name\":\"rider\"}", PrimitiveBody.class)
                    );
                    Assertions.assertTrue(mapper.readValue("{\"on_duty\":true}", PrimitiveBody.class).onDuty());
                    Assertions.assertNull(mapper.readValue("{\"name\":\"rider\"}", BoxedBody.class).onDuty());
                });
    }


    @Test
    void requestBodiesAndConsumedPayloads_shouldHaveNoPrimitiveRecordComponents() {
        Set<Class<?>> requestBodies = requestBodyTypes();
        // Guards the scan itself: every controller's @RequestBody type is found.
        Assertions.assertTrue(requestBodies.size() >= 8, () -> "Request body types found: " + requestBodies);

        Set<Class<?>> roots = new LinkedHashSet<>(requestBodies);
        roots.addAll(CONSUMED_TYPES);
        List<String> primitives = new ArrayList<>();
        Set<Class<?>> visited = new HashSet<>();
        roots.forEach(root -> collectPrimitiveComponents(ResolvableType.forClass(root), visited, primitives));

        Assertions.assertEquals(
                List.of(),
                primitives,
                "Jackson 3 rejects a JSON input that omits a primitive record component; use the boxed type"
        );
    }


    @Test
    void componentScan_shouldReportPrimitivesInNestedRecordsAndCollections() {
        List<String> primitives = new ArrayList<>();

        collectPrimitiveComponents(ResolvableType.forClass(OuterBody.class), new HashSet<>(), primitives);

        Assertions.assertEquals(
                List.of(
                        PrimitiveBody.class.getName() + ".onDuty (boolean)",
                        InnerItem.class.getName() + ".quantity (int)"
                ),
                primitives
        );
    }


    private static Set<Class<?>> requestBodyTypes() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        Set<Class<?>> types = new LinkedHashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> controller = ClassUtils.resolveClassName(definition.getBeanClassName(), null);
            for (Method method : controller.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    if (parameter.isAnnotationPresent(RequestBody.class)) {
                        types.add(parameter.getType());
                    }
                }
            }
        }
        return types;
    }


    private static void collectPrimitiveComponents(ResolvableType type, Set<Class<?>> visited, List<String> primitives) {
        if (type.isArray()) {
            collectPrimitiveComponents(type.getComponentType(), visited, primitives);
            return;
        }
        for (ResolvableType generic : type.getGenerics()) {
            collectPrimitiveComponents(generic, visited, primitives);
        }
        Class<?> raw = type.resolve();
        if (raw == null || !raw.isRecord() || !raw.getName().startsWith(BASE_PACKAGE) || !visited.add(raw)) {
            return;
        }
        for (RecordComponent component : raw.getRecordComponents()) {
            if (component.getType().isPrimitive()) {
                primitives.add(raw.getName() + "." + component.getName() + " (" + component.getType() + ")");
            } else {
                collectPrimitiveComponents(ResolvableType.forType(component.getGenericType()), visited, primitives);
            }
        }
    }


    record PrimitiveBody(boolean onDuty, String name) {
    }


    record BoxedBody(Boolean onDuty, String name) {
    }


    record InnerItem(Long id, int quantity) {
    }


    record OuterBody(PrimitiveBody rider, List<InnerItem> items, BoxedBody[] others) {
    }
}
