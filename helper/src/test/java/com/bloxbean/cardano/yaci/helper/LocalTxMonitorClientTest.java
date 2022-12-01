package com.bloxbean.cardano.yaci.helper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.MockitoAnnotations.openMocks;

@ExtendWith(MockitoExtension.class)
public class LocalTxMonitorClientTest extends BaseTest {

    @Mock
    private LocalTxMonitorClient localTxMonitorClient;

    @BeforeEach
    public void setup() {
        openMocks(this);
    }


}
