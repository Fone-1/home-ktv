package com.homektv.dualtrack;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DspVocalSeparationEngineTest {

    @Test
    void testModeAndFilterGraph() {
        DspVocalSeparationEngine engine = new DspVocalSeparationEngine("ffmpeg");
        assertEquals(VocalSeparationMode.DSP, engine.getMode());
        assertNotNull(DspVocalSeparationEngine.DSP_FILTER_GRAPH);
        assertTrue(DspVocalSeparationEngine.DSP_FILTER_GRAPH.contains("lowpass=f=160"));
        assertTrue(DspVocalSeparationEngine.DSP_FILTER_GRAPH.contains("highpass=f=160"));
        assertTrue(DspVocalSeparationEngine.DSP_FILTER_GRAPH.contains("c0=c0-c1|c1=c1-c0"));
        assertTrue(DspVocalSeparationEngine.DSP_FILTER_GRAPH.contains("amix=inputs=2"));
    }
}
