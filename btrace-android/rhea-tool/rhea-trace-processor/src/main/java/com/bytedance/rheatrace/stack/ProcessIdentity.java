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
package com.bytedance.rheatrace.stack;

import java.util.UUID;
import java.util.regex.Pattern;

/** 线上堆栈产物的进程身份校验工具。 */
public final class ProcessIdentity {

    /** RFC 4122 canonical UUID v4 的文本格式。 */
    private static final Pattern UUID_V4 = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-"
                    + "[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");

    /** 禁止实例化工具类。 */
    private ProcessIdentity() {
    }

    /** 判断值是否为 canonical RFC 4122 UUID v4 字符串。 */
    public static boolean isUuidV4(Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String text = (String) value;
        if (!UUID_V4.matcher(text).matches()) {
            return false;
        }
        try {
            UUID uuid = UUID.fromString(text);
            return uuid.version() == 4 && uuid.variant() == 2
                    && uuid.toString().equals(text);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
