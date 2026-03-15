package io.runcycles.protocol.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.mockito.Mockito.*;

@DisplayName("CyclesProtocolApplication")
class CyclesProtocolApplicationTest {

    @Test
    void mainShouldBootApplication() {
        try (MockedStatic<SpringApplication> mocked = mockStatic(SpringApplication.class)) {
            mocked.when(() -> SpringApplication.run(eq(CyclesProtocolApplication.class), any(String[].class)))
                    .thenReturn(null);

            CyclesProtocolApplication.main(new String[]{});

            mocked.verify(() -> SpringApplication.run(eq(CyclesProtocolApplication.class), any(String[].class)));
        }
    }
}
