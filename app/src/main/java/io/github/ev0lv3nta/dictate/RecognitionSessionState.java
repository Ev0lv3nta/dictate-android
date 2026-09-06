package io.github.ev0lv3nta.dictate;

final class RecognitionSessionState {

    private boolean stopRequested;
    private boolean cancelled;
    private boolean completed;

    synchronized boolean requestStop() {
        if (cancelled || completed) {
            return false;
        }
        stopRequested = true;
        return true;
    }

    synchronized boolean cancel() {
        if (cancelled || completed) {
            return false;
        }
        cancelled = true;
        return true;
    }

    synchronized boolean complete() {
        if (cancelled || completed) {
            return false;
        }
        completed = true;
        return true;
    }

    synchronized boolean isStopRequested() {
        return stopRequested;
    }

    synchronized boolean isCancelled() {
        return cancelled;
    }

    synchronized boolean isCompleted() {
        return completed;
    }
}
