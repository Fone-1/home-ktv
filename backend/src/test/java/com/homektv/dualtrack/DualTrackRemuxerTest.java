package com.homektv.dualtrack;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DualTrackRemuxerTest {

    @Test
    void testInitializationAndConfig() {
        DualTrackRemuxer remuxer = new DualTrackRemuxer("ffmpeg");
        assertEquals("ffmpeg", remuxer.getFfmpegPath());
    }
}
