package com.homektv.web.dto;

public record MvDownloadSubmitRequest(
        String provider,
        String externalId,
        String title,
        String artist,
       String coverUrl,
       String resolution,
        Boolean autoEnqueue,
        Boolean autoConvertDualTrack
) {}
