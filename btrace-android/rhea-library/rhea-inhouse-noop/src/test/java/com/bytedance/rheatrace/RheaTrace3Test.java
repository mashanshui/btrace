/*
 * Copyright (C) 2021 ByteDance Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.bytedance.rheatrace;

import org.junit.Assert;
import org.junit.Test;

public class RheaTrace3Test {

    @Test
    public void noopKeepsOnlineExportApiContract() {
        RheaTrace3.OnlineTraceConfig config = RheaTrace3.OnlineTraceConfig.builder().build();
        Assert.assertEquals(5 * 1024 * 1024, config.getBufferSizeBytes());
        Assert.assertEquals(10_000_000L, config.getMinSampleIntervalNs());
        Assert.assertFalse(config.isEnableJniHook());
        Assert.assertFalse(config.isEnableStackCaptureStats());
        Assert.assertTrue(RheaTrace3.OnlineTraceConfig.builder()
                .setEnableStackCaptureStats(true)
                .build()
                .isEnableStackCaptureStats());
        RheaTrace3.OnlineTraceConfig jankConfig = RheaTrace3.OnlineTraceConfig.builder()
                .setAnonymousDeviceId("device-anonymous")
                .setBuildId("release-1")
                .setEnvironment("production")
                .setChannel("official")
                .build();
        Assert.assertEquals("device-anonymous", jankConfig.getAnonymousDeviceId());
        Assert.assertEquals("release-1", jankConfig.getBuildId());
        Assert.assertEquals("production", jankConfig.getEnvironment());
        Assert.assertEquals("official", jankConfig.getChannel());
        Assert.assertEquals(RheaTrace3.ExportRequestResult.INVALID_RANGE,
                RheaTrace3.exportStackData(10, 10, null));
        Assert.assertEquals(RheaTrace3.ExportRequestResult.DISABLED,
                RheaTrace3.exportStackData(10, 20, null));
        Assert.assertEquals(RheaTrace3.ExportRequestResult.DISABLED,
                RheaTrace3.exportAllStackData(null));
        Assert.assertTrue(RheaTrace3.getAvailableStackTimeRange().isEmpty());
        Assert.assertTrue(RheaTrace3.getPendingJankFiles().isEmpty());
    }

    @Test
    public void jankEventKeepsValidatedMetadata() {
        RheaTrace3.JankEvent event = validJankEvent().build();
        Assert.assertEquals("jank-1", event.getEventId());
        Assert.assertEquals(1000, event.getOccurredAt());
        Assert.assertEquals("session-1", event.getSessionId());
        Assert.assertEquals("home", event.getScene());
        Assert.assertEquals(100, event.getMessageStartNs());
        Assert.assertEquals(400, event.getMessageEndNs());
        Assert.assertEquals(200, event.getThresholdNs());
        Assert.assertEquals(3, event.getAttemptedSampleCount());
        Assert.assertEquals(RheaTrace3.ExportRequestResult.DISABLED,
                RheaTrace3.exportJankTrace(event, null));
        Assert.assertEquals(RheaTrace3.ExportRequestResult.INVALID_JANK_METADATA,
                RheaTrace3.exportJankTrace(null, null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsafeJankEventId() {
        validJankEvent().setEventId("../jank").build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsJankThresholdLongerThanMessage() {
        validJankEvent().setThresholdNs(301).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeAttemptedSampleCount() {
        validJankEvent().setAttemptedSampleCount(-1).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTooSmallBuffer() {
        RheaTrace3.OnlineTraceConfig.builder().setBufferSizeBytes(1024).build();
    }

    private static RheaTrace3.JankEvent.Builder validJankEvent() {
        return RheaTrace3.JankEvent.builder()
                .setEventId("jank-1")
                .setOccurredAt(1000)
                .setSessionId("session-1")
                .setScene("home")
                .setMessageStartNs(100)
                .setMessageEndNs(400)
                .setThresholdNs(200)
                .setAttemptedSampleCount(3);
    }
}
