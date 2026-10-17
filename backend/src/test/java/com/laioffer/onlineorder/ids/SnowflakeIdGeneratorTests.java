package com.laioffer.onlineorder.ids;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;


class SnowflakeIdGeneratorTests {

    // 2026-10-16T00:00:00Z
    private static final long T = 1_792_022_400_000L;


    @Test
    void compose_shouldPackTimestampWorkerAndSequenceAndDecodeThemBack() {
        long id = SnowflakeIdGenerator.compose(T - SnowflakeIdGenerator.EPOCH_MILLIS, 517, 4095);

        Assertions.assertTrue(id > 0, "the sign bit stays clear");
        Assertions.assertEquals(T, SnowflakeIdGenerator.timestampMillisOf(id));
        Assertions.assertEquals(517, SnowflakeIdGenerator.workerIdOf(id));
        Assertions.assertEquals(4095, SnowflakeIdGenerator.sequenceOf(id));
        // The largest id still fits a signed 64-bit long: 41 bits of milliseconds last until 2089.
        long last = SnowflakeIdGenerator.compose(SnowflakeIdGenerator.MAX_ELAPSED_MILLIS, 1023, 4095);
        Assertions.assertEquals(Long.MAX_VALUE, last);
        Assertions.assertEquals(2089, java.time.Instant.ofEpochMilli(SnowflakeIdGenerator.timestampMillisOf(last))
                .atZone(java.time.ZoneOffset.UTC).getYear());
    }


    @Test
    void compose_shouldRejectFieldsOutsideTheirBits() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> SnowflakeIdGenerator.compose(-1, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> SnowflakeIdGenerator.compose(SnowflakeIdGenerator.MAX_ELAPSED_MILLIS + 1, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> SnowflakeIdGenerator.compose(0, 1024, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> SnowflakeIdGenerator.compose(0, -1, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> SnowflakeIdGenerator.compose(0, 0, 4096));
        Assertions.assertThrows(IllegalArgumentException.class, () -> SnowflakeIdGenerator.compose(0, 0, -1));
    }


    @Test
    void constructor_shouldOnlyAcceptAssignableWorkerIds() {
        Assertions.assertEquals(0, generator(0, new AtomicLong(T)).workerId());
        Assertions.assertEquals(1022, generator(1022, new AtomicLong(T)).workerId());
        // 1023 belongs to the V10_1 backfill; no running instance may produce those numbers.
        Assertions.assertThrows(IllegalArgumentException.class, () -> generator(1023, new AtomicLong(T)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> generator(1024, new AtomicLong(T)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> generator(-1, new AtomicLong(T)));
    }


    @Test
    void nextId_onTheSystemClock_shouldIncreaseStrictly() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(3, Duration.ofMillis(50), null);
        long previous = 0;
        for (int i = 0; i < 200_000; i++) {
            long id = generator.nextId();
            Assertions.assertTrue(id > previous, "id " + i + " did not increase");
            Assertions.assertEquals(3, SnowflakeIdGenerator.workerIdOf(id));
            previous = id;
        }
        long age = System.currentTimeMillis() - SnowflakeIdGenerator.timestampMillisOf(previous);
        Assertions.assertTrue(age >= 0 && age < 60_000, "the timestamp is the wall clock");
    }


    @Test
    void nextId_fromManyThreads_shouldNeverRepeatAnId() throws Exception {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(7, Duration.ofMillis(50), null);
        int threads = 8;
        int idsPerThread = 50_000;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<long[]>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Callable<long[]> task = () -> {
                    start.await();
                    long[] ids = new long[idsPerThread];
                    for (int i = 0; i < idsPerThread; i++) {
                        ids[i] = generator.nextId();
                    }
                    return ids;
                };
                results.add(executor.submit(task));
            }
            start.countDown();
            Set<Long> unique = new HashSet<>();
            for (Future<long[]> result : results) {
                long previous = 0;
                for (long id : result.get()) {
                    Assertions.assertTrue(id > previous, "ids seen by one thread increase");
                    previous = id;
                    unique.add(id);
                }
            }
            Assertions.assertEquals(threads * idsPerThread, unique.size());
        } finally {
            executor.shutdownNow();
        }
    }


    @Test
    void nextId_onTwoWorkersAtTheSameInstant_shouldNotCollide() {
        AtomicLong clock = new AtomicLong(T);
        SnowflakeIdGenerator first = generator(1, clock);
        SnowflakeIdGenerator second = generator(2, clock);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(first.nextId());
            ids.add(second.nextId());
        }
        Assertions.assertEquals(2000, ids.size());
    }


    @Test
    void nextId_whenAMillisecondsSequenceRunsOut_shouldMoveToTheNextMillisecond() {
        // The clock stands still at T until the generator has polled it 4097 times.
        AtomicLong calls = new AtomicLong();
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(5, 0L, Long.MAX_VALUE, 50,
                () -> calls.incrementAndGet() <= 4097 ? T : T + 1, millis -> {
        }, () -> 0, null);

        long previous = 0;
        for (int sequence = 0; sequence <= SnowflakeIdGenerator.MAX_SEQUENCE; sequence++) {
            long id = generator.nextId();
            Assertions.assertEquals(T, SnowflakeIdGenerator.timestampMillisOf(id));
            Assertions.assertEquals(sequence, SnowflakeIdGenerator.sequenceOf(id));
            Assertions.assertTrue(id > previous);
            previous = id;
        }
        long next = generator.nextId();
        Assertions.assertEquals(T + 1, SnowflakeIdGenerator.timestampMillisOf(next));
        Assertions.assertEquals(0, SnowflakeIdGenerator.sequenceOf(next));
        Assertions.assertTrue(next > previous);
    }


    @Test
    void nextId_shouldStartEachMillisecondAtARandomSequenceBelow2048() {
        AtomicLong clock = new AtomicLong(T);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(5, 0L, Long.MAX_VALUE, 50, clock::get,
                millis -> {
                }, () -> SnowflakeIdGenerator.SEQUENCE_START_BOUND - 1, null);

        long first = generator.nextId();
        long second = generator.nextId();
        clock.incrementAndGet();
        long third = generator.nextId();

        Assertions.assertEquals(2047, SnowflakeIdGenerator.sequenceOf(first));
        Assertions.assertEquals(2048, SnowflakeIdGenerator.sequenceOf(second));
        Assertions.assertEquals(2047, SnowflakeIdGenerator.sequenceOf(third));
        Assertions.assertTrue(third > second);

        // On the system clock: the first id of every millisecond has a sequence below 2048, and not always the same.
        SnowflakeIdGenerator random = new SnowflakeIdGenerator(5, Duration.ofMillis(50), null);
        java.util.Map<Long, Integer> firstSequenceByMillisecond = new java.util.HashMap<>();
        long until = System.currentTimeMillis() + 30;
        while (System.currentTimeMillis() < until || firstSequenceByMillisecond.size() < 5) {
            long id = random.nextId();
            firstSequenceByMillisecond.merge(SnowflakeIdGenerator.timestampMillisOf(id),
                    SnowflakeIdGenerator.sequenceOf(id), Math::min);
        }
        Assertions.assertTrue(firstSequenceByMillisecond.values().stream()
                .allMatch(sequence -> sequence < SnowflakeIdGenerator.SEQUENCE_START_BOUND));
        Assertions.assertTrue(firstSequenceByMillisecond.values().stream().distinct().count() > 1);
    }


    @Test
    void nextId_whenTheClockStepsBackWithinTheTolerance_shouldWaitAndStayMonotonic() {
        AtomicLong clock = new AtomicLong(T);
        List<Long> sleeps = new ArrayList<>();
        List<String> events = new ArrayList<>();
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(9, 0L, Long.MAX_VALUE, 50, clock::get,
                millis -> {
                    sleeps.add(millis);
                    clock.addAndGet(millis);
                }, () -> 0, (millis, waited) -> events.add(millis + ":" + waited));

        long before = generator.nextId();
        clock.set(T - 20);
        long after = generator.nextId();

        Assertions.assertEquals(List.of(20L), sleeps);
        Assertions.assertEquals(List.of("20:true"), events);
        Assertions.assertTrue(after > before);
        Assertions.assertEquals(T, SnowflakeIdGenerator.timestampMillisOf(after));
    }


    @Test
    void nextId_whenTheClockStepsBackBeyondTheTolerance_shouldRefuseUntilItCatchesUp() {
        AtomicLong clock = new AtomicLong(T);
        List<String> events = new ArrayList<>();
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(9, 0L, Long.MAX_VALUE, 50, clock::get,
                millis -> Assertions.fail("must not wait for a large step"), () -> 0,
                (millis, waited) -> events.add(millis + ":" + waited));

        long before = generator.nextId();
        clock.set(T - 5_000);

        ClockMovedBackwardsException refused =
                Assertions.assertThrows(ClockMovedBackwardsException.class, generator::nextId);
        Assertions.assertTrue(refused.getMessage().contains("5000 ms behind"), refused.getMessage());
        Assertions.assertEquals(List.of("5000:false"), events);

        clock.set(T + 1);
        long after = generator.nextId();
        Assertions.assertTrue(after > before);
    }


    @Test
    void nextId_whenTheClockIsStillBehindAfterWaiting_shouldRefuse() {
        AtomicLong clock = new AtomicLong(T);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(9, 0L, Long.MAX_VALUE, 50, clock::get,
                millis -> clock.addAndGet(-100), () -> 0, null);
        generator.nextId();
        clock.set(T - 10);

        Assertions.assertThrows(ClockMovedBackwardsException.class, generator::nextId);
    }


    @Test
    void nextId_whenInterruptedWhileWaitingForTheClock_shouldGiveUpAndKeepTheInterrupt() {
        AtomicLong clock = new AtomicLong(T);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(9, 0L, Long.MAX_VALUE, 50, clock::get,
                millis -> {
                    throw new InterruptedException("stop");
                }, () -> 0, null);
        generator.nextId();
        clock.set(T - 10);

        try {
            Assertions.assertThrows(IdGeneratorUnavailableException.class, generator::nextId);
            Assertions.assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }


    @Test
    void nextId_shouldStartAfterTheFloor() {
        AtomicLong clock = new AtomicLong(T);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(4, T, Long.MAX_VALUE, 50,
                () -> clock.getAndIncrement(), millis -> {
        }, () -> 0, null);

        long id = generator.nextId();

        Assertions.assertTrue(SnowflakeIdGenerator.timestampMillisOf(id) > T, "the floor's millisecond is taken");
    }


    @Test
    void nextId_shouldNotPassTheCeilingUntilItIsRaised() {
        AtomicLong clock = new AtomicLong(T);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(4, 0L, T + 10, 50, clock::get, millis -> {
        }, () -> 0, null);
        generator.nextId();
        clock.set(T + 11);

        Assertions.assertThrows(IdGeneratorUnavailableException.class, generator::nextId);
        generator.raiseCeiling(T + 5);
        Assertions.assertEquals(T + 10, generator.ceilingMillis(), "a lower ceiling is ignored");
        generator.raiseCeiling(T + 100);
        Assertions.assertEquals(T + 11, SnowflakeIdGenerator.timestampMillisOf(generator.nextId()));
    }


    @Test
    void close_shouldStopTheGeneratorAndReportTheLastTimestamp() {
        AtomicLong clock = new AtomicLong(T);
        SnowflakeIdGenerator generator = generator(4, clock);
        generator.nextId();
        clock.set(T + 7);
        generator.nextId();

        Assertions.assertEquals(T + 7, generator.close());
        Assertions.assertEquals(T + 7, generator.lastTimestamp());
        Assertions.assertThrows(IdGeneratorUnavailableException.class, generator::nextId);
    }


    @Test
    void nextId_beforeTheEpoch_shouldFail() {
        SnowflakeIdGenerator generator = generator(4, new AtomicLong(SnowflakeIdGenerator.EPOCH_MILLIS - 1));

        Assertions.assertThrows(IllegalStateException.class, generator::nextId);
    }


    @Test
    void staticWorkerIdGenerator_shouldDelegate() {
        StaticWorkerIdGenerator generator = new StaticWorkerIdGenerator(generator(12, new AtomicLong(T)));

        Assertions.assertEquals(12, generator.workerId());
        Assertions.assertEquals(12, SnowflakeIdGenerator.workerIdOf(generator.nextId()));
    }


    private static SnowflakeIdGenerator generator(int workerId, AtomicLong clock) {
        return new SnowflakeIdGenerator(workerId, 0L, Long.MAX_VALUE, 50, clock::get, millis -> {
        }, () -> 0, SnowflakeIdGenerator.Listener.NONE);
    }
}
