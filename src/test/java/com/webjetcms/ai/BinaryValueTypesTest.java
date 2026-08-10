package com.webjetcms.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinaryValueTypesTest {

    @TempDir
    Path tempDirectory;

    @Test
    void binaryContentIsImmutableAndUsesContentBasedEquality() {
        byte[] source = {1, 2, 3};
        BinaryContent first = new BinaryContent(source, "image/png", "source.png");
        BinaryContent same = new BinaryContent(new byte[] {1, 2, 3}, "image/png", "source.png");
        source[0] = 9;
        byte[] exposed = first.data();
        exposed[1] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, first.data());
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, new BinaryContent(new byte[] {1, 2, 4}, "image/png", "source.png"));
    }

    @Test
    void generatedMediaIsImmutableAndUsesContentBasedEquality() {
        byte[] source = {4, 5, 6};
        GeneratedMedia first = new GeneratedMedia(source, "image/webp");
        GeneratedMedia same = new GeneratedMedia(new byte[] {4, 5, 6}, "image/webp");
        source[0] = 9;
        byte[] exposed = first.data();
        exposed[1] = 9;

        assertArrayEquals(new byte[] {4, 5, 6}, first.data());
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, new GeneratedMedia(new byte[] {4, 5, 7}, "image/webp"));
    }

    @Test
    void readsBinaryContentFromAPath() throws Exception {
        Path source = Files.write(tempDirectory.resolve("input.png"), new byte[] {7, 8, 9});

        BinaryContent content = BinaryContent.from(source, "image/png");

        assertArrayEquals(new byte[] {7, 8, 9}, content.data());
        assertEquals("image/png", content.mediaType());
        assertEquals("input.png", content.fileName());
    }

    @Test
    void interpretsSupportedGeneratedImageMediaTypes() {
        GeneratedMedia jpeg = new GeneratedMedia(new byte[0], " IMAGE/JPEG; charset=binary ");
        GeneratedMedia webp = new GeneratedMedia(new byte[0], "image/webp");
        GeneratedMedia binary = new GeneratedMedia(new byte[0], null);
        GeneratedMedia unsupportedImage = new GeneratedMedia(new byte[0], "image/svg+xml");

        assertTrue(jpeg.isImage());
        assertEquals("jpg", jpeg.suggestedFileExtension().orElseThrow());
        assertEquals("webp", webp.suggestedFileExtension().orElseThrow());
        assertFalse(binary.isImage());
        assertTrue(binary.suggestedFileExtension().isEmpty());
        assertTrue(unsupportedImage.isImage());
        assertTrue(unsupportedImage.suggestedFileExtension().isEmpty());
    }
}
