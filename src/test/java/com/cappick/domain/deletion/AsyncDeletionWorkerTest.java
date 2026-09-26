package com.cappick.domain.deletion;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AsyncDeletionWorkerTest {

    @Mock
    private DeletionStateTransitions transitions;

    private AsyncDeletionWorker worker;

    @BeforeEach
    void setUp() {
        worker = new AsyncDeletionWorker(transitions);
    }

    @Test
    void runMarksProcessingThenDeletesContent() {
        worker.run("d1");

        verify(transitions).markProcessing("d1");
        verify(transitions).deleteContent("d1");
        verify(transitions, never()).markFailed(anyString(), anyString());
    }

    @Test
    void runMarksFailedWhenDeleteContentThrows() {
        doThrow(new RuntimeException("boom")).when(transitions).deleteContent("d1");

        worker.run("d1");

        verify(transitions).markProcessing("d1");
        verify(transitions).markFailed("d1", "DELETION_FAILED");
    }
}
