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
        Assert.assertEquals(RheaTrace3.ExportRequestResult.INVALID_RANGE,
                RheaTrace3.exportStackData(10, 10, null));
        Assert.assertEquals(RheaTrace3.ExportRequestResult.DISABLED,
                RheaTrace3.exportStackData(10, 20, null));
        Assert.assertEquals(RheaTrace3.ExportRequestResult.DISABLED,
                RheaTrace3.exportAllStackData(null));
        Assert.assertTrue(RheaTrace3.getAvailableStackTimeRange().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTooSmallBuffer() {
        RheaTrace3.OnlineTraceConfig.builder().setBufferSizeBytes(1024).build();
    }
}
