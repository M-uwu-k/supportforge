package com.supportforge.tool;

import java.time.Duration;


public class CircuitBreaker {


    private final int failureThreshold;


    private final long recoveryNanos;


    private CircuitState state =
            CircuitState.CLOSED;


    private int consecutiveFailures;


    private long openedAtNanos;


    private boolean halfOpenProbeRunning;


    public CircuitBreaker(
            int failureThreshold,
            Duration recoveryDuration
    ) {

        this.failureThreshold =
                Math.max(1, failureThreshold);


        Duration safeRecovery =
                recoveryDuration == null
                        ? Duration.ofSeconds(30)
                        : recoveryDuration;


        this.recoveryNanos = Math.max(
                1,
                safeRecovery.toNanos()
        );
    }


    public synchronized boolean allowCall() {

        if (state == CircuitState.CLOSED) {
            return true;
        }


        if (state == CircuitState.OPEN) {
            long elapsed =
                    System.nanoTime()
                            - openedAtNanos;


            if (elapsed < recoveryNanos) {
                return false;
            }


            state = CircuitState.HALF_OPEN;
            halfOpenProbeRunning = true;
            return true;
        }


        if (halfOpenProbeRunning) {
            return false;
        }


        halfOpenProbeRunning = true;
        return true;
    }


    public synchronized void recordSuccess() {

        state = CircuitState.CLOSED;


        consecutiveFailures = 0;


        halfOpenProbeRunning = false;
    }


    public synchronized void recordFailure() {

        halfOpenProbeRunning = false;


        if (state == CircuitState.HALF_OPEN) {
            open();

            return;
        }


        consecutiveFailures++;


        if (consecutiveFailures
                >= failureThreshold) {
            open();
        }
    }


    public synchronized CircuitState state() {
        return state;
    }


    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }


    private void open() {
        state = CircuitState.OPEN;


        openedAtNanos = System.nanoTime();


        consecutiveFailures =
                Math.max(
                        consecutiveFailures,
                        failureThreshold
                );
    }
}
