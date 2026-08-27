/*
 * Copyright (C) 2021 ByteDance Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytedance.rheatrace.trace.sampling;

import org.junit.Assert;
import org.junit.Test;

public class SamplingConfigTest {

    @Test
    public void deflateAppendsStackCaptureStatsFlag() {
        SamplingConfig config = new SamplingConfig(new SamplingConfigCreator());

        Assert.assertEquals(14, config.deflate().length);
        Assert.assertEquals(0, config.deflate()[13]);

        config.setEnableStackCaptureStats(true);
        long[] values = config.deflate();
        Assert.assertEquals(14, values.length);
        Assert.assertEquals(1, values[13]);
    }
}
