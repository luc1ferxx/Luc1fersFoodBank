package com.laioffer.onlineorder;


import java.util.UUID;


/**
 * The money migration on H2 in PostgreSQL mode, with the settings of the h2 profile's URL.
 */
class MoneyCurrencyMigrationH2Tests extends AbstractMoneyCurrencyMigrationTests {

    @Override
    String newDatabase() {
        return "jdbc:h2:mem:money-currency-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }


    @Override
    String username() {
        return "sa";
    }


    @Override
    String password() {
        return "";
    }
}
