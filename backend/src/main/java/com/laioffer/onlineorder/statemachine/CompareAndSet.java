package com.laioffer.onlineorder.statemachine;


/**
 * Checks the row count of a compare-and-set write ({@code UPDATE ... WHERE id = :id AND status = :expected}).
 */
public final class CompareAndSet {

    private CompareAndSet() {
    }


    /**
     * @param rows          the row count the UPDATE returned
     * @param aggregate     the kind of row, for the message, for example "order"
     * @param id            the row's id
     * @param expectedState the state the write expected the row to be in
     * @throws StaleTransitionException when {@code rows} is not 1
     */
    public static void expectOneRow(int rows, String aggregate, Object id, Object expectedState) {
        if (rows != 1) {
            throw new StaleTransitionException(
                    "Compare-and-set of " + aggregate + " " + id + " from " + expectedState + " changed " + rows
                            + " rows instead of 1"
            );
        }
    }
}
