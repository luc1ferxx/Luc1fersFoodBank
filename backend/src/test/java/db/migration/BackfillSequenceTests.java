package db.migration;


import com.laioffer.onlineorder.ids.SnowflakeIdGenerator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


/**
 * The order numbers V10_1 gives existing orders: the Snowflake layout with the reserved worker id 1023, strictly
 * increasing in id order whatever the creation times.
 */
class BackfillSequenceTests {

    // 2026-01-02T03:04:05Z
    private static final long CREATED_AT = 1_767_323_045_000L;


    @Test
    void layout_shouldMatchTheGenerator() {
        Assertions.assertEquals(SnowflakeIdGenerator.EPOCH_MILLIS, V10_1__Add_order_numbers.EPOCH_MILLIS);
        Assertions.assertEquals(SnowflakeIdGenerator.WORKER_ID_BITS, V10_1__Add_order_numbers.WORKER_ID_BITS);
        Assertions.assertEquals(SnowflakeIdGenerator.SEQUENCE_BITS, V10_1__Add_order_numbers.SEQUENCE_BITS);
        Assertions.assertEquals(SnowflakeIdGenerator.BACKFILL_WORKER_ID, V10_1__Add_order_numbers.BACKFILL_WORKER_ID);

        long number = new V10_1__Add_order_numbers.BackfillSequence(null).next(CREATED_AT);

        Assertions.assertEquals(CREATED_AT, SnowflakeIdGenerator.timestampMillisOf(number));
        Assertions.assertEquals(1023, SnowflakeIdGenerator.workerIdOf(number));
        Assertions.assertEquals(0, SnowflakeIdGenerator.sequenceOf(number));
    }


    @Test
    void next_withManyOrdersInOneMillisecond_shouldMoveOnWhenTheSequenceRunsOut() {
        V10_1__Add_order_numbers.BackfillSequence numbers = new V10_1__Add_order_numbers.BackfillSequence(null);
        long previous = 0;
        for (int i = 0; i <= SnowflakeIdGenerator.MAX_SEQUENCE; i++) {
            long number = numbers.next(CREATED_AT);
            Assertions.assertTrue(number > previous);
            Assertions.assertEquals(CREATED_AT, SnowflakeIdGenerator.timestampMillisOf(number));
            previous = number;
        }

        long overflow = numbers.next(CREATED_AT);

        Assertions.assertEquals(CREATED_AT + 1, SnowflakeIdGenerator.timestampMillisOf(overflow));
        Assertions.assertEquals(0, SnowflakeIdGenerator.sequenceOf(overflow));
        // A later order that was created earlier still gets a higher number.
        long earlier = numbers.next(CREATED_AT - 60_000);
        Assertions.assertTrue(earlier > overflow);
        Assertions.assertEquals(CREATED_AT + 1, SnowflakeIdGenerator.timestampMillisOf(earlier));
    }


    @Test
    void next_shouldStartAfterAnExistingNumberAndClampTimesBeforeTheEpoch() {
        long existing = SnowflakeIdGenerator.compose(CREATED_AT - SnowflakeIdGenerator.EPOCH_MILLIS, 1023, 7);
        V10_1__Add_order_numbers.BackfillSequence resumed = new V10_1__Add_order_numbers.BackfillSequence(existing);

        long next = resumed.next(CREATED_AT);

        Assertions.assertTrue(next > existing);
        Assertions.assertEquals(CREATED_AT + 1, SnowflakeIdGenerator.timestampMillisOf(next));

        long beforeEpoch = new V10_1__Add_order_numbers.BackfillSequence(null).next(0L);
        Assertions.assertEquals(SnowflakeIdGenerator.EPOCH_MILLIS, SnowflakeIdGenerator.timestampMillisOf(beforeEpoch));
        Assertions.assertTrue(beforeEpoch > 0);
    }
}
