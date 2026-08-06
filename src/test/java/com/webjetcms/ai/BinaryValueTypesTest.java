package com.webjetcms.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class BinaryValueTypesTest {

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
}
