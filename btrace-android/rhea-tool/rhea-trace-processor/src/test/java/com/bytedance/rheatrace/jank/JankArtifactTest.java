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
package com.bytedance.rheatrace.jank;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class JankArtifactTest {

    @Test
    public void opensValidArtifactAndRejectsMissingEntries() throws Exception {
        File valid = File.createTempFile("rhea-jank", ".zip");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(valid))) {
                put(zip, "manifest.json", "{\"schemaVersion\":1,\"samplingFormatVersion\":5,"
                        + "\"eventId\":\"e\",\"eventStartElapsedRealtimeNanos\":1,"
                        + "\"eventEndElapsedRealtimeNanos\":2}");
                put(zip, "sampling.bin", "sample");
                put(zip, "sampling-mapping.bin", "mapping");
            }
            try (JankArtifact artifact = JankArtifact.open(valid)) {
                Assert.assertEquals("e", artifact.getManifest().getString("eventId"));
            }
        } finally {
            valid.delete();
        }
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsUnexpectedZipEntry() throws Exception {
        File invalid = File.createTempFile("rhea-jank", ".zip");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(invalid))) {
                put(zip, "../escape", "bad");
            }
            JankArtifact.open(invalid);
        } finally {
            invalid.delete();
        }
    }

    private static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
