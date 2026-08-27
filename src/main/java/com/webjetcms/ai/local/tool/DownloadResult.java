package com.webjetcms.ai.local.tool;

import java.nio.file.Path;

record DownloadResult(Path path, long size, String sha256, long crc32) { }
