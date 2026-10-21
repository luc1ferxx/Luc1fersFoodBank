package db.migration;


import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * Checks that every currency of the V12 tables is three upper-case letters, as V11 does for the tables it gave a
 * currency: the order payments, the after-sales cases with their lines and history, the refunds and the merchant's
 * after-sales inbox.
 *
 * <p>A Java migration because the check differs by database: PostgreSQL's {@code ~} operator, H2's
 * {@code REGEXP_LIKE}. The tables are new and empty, so adding the checks scans nothing.
 */
public class V12_1__After_sales_currency_checks extends BaseJavaMigration {

    /**
     * Table to the name of its currency check.
     */
    static final Map<String, String> CURRENCY_CHECKS = currencyChecks();

    private static final Logger LOGGER = LoggerFactory.getLogger(V12_1__After_sales_currency_checks.class);


    private static Map<String, String> currencyChecks() {
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("order_payments", "chk_order_payment_currency");
        checks.put("after_sales_cases", "chk_after_sales_case_currency");
        checks.put("after_sales_case_lines", "chk_after_sales_line_currency");
        checks.put("after_sales_case_history", "chk_after_sales_history_currency");
        checks.put("refunds", "chk_refund_currency");
        checks.put("merchant_after_sales_cases", "chk_merchant_after_sales_currency");
        return checks;
    }


    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        boolean postgres = "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
        String upperCaseCode = postgres ? "currency ~ '^[A-Z]{3}$'" : "REGEXP_LIKE(currency, '^[A-Z]{3}$')";
        for (Map.Entry<String, String> check : CURRENCY_CHECKS.entrySet()) {
            String sql = "ALTER TABLE " + check.getKey() + " ADD CONSTRAINT " + check.getValue() + " CHECK ("
                    + upperCaseCode + ")";
            LOGGER.info("{}", sql);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }
}
