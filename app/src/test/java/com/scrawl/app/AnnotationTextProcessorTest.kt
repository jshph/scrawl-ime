package com.scrawl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnnotationTextProcessorTest {

    // --- wrapText ---

    @Test
    fun `wrapText wikilink wraps with double brackets`() {
        assertEquals("[[hello]]", AnnotationTextProcessor.wrapText(AnnotationMode.WikiLink, "hello"))
    }

    @Test
    fun `wrapText wikilink preserves spaces`() {
        assertEquals("[[new note]]", AnnotationTextProcessor.wrapText(AnnotationMode.WikiLink, "new note"))
    }

    @Test
    fun `wrapText tag prepends hash`() {
        assertEquals("#hello", AnnotationTextProcessor.wrapText(AnnotationMode.Tag, "hello"))
    }

    @Test
    fun `wrapText tag strips spaces`() {
        assertEquals("#mytag", AnnotationTextProcessor.wrapText(AnnotationMode.Tag, "my tag"))
    }

    @Test
    fun `wrapText tag strips leading space prefix`() {
        assertEquals(" #hello", AnnotationTextProcessor.wrapText(AnnotationMode.Tag, " hello"))
    }

    @Test
    fun `wrapText tag strips leading non-alpha prefix`() {
        assertEquals("123#hello", AnnotationTextProcessor.wrapText(AnnotationMode.Tag, "123hello"))
    }

    @Test
    fun `wrapText wikilink strips leading space prefix`() {
        assertEquals(" [[hello]]", AnnotationTextProcessor.wrapText(AnnotationMode.WikiLink, " hello"))
    }

    @Test
    fun `wrapText wikilink strips leading non-alpha prefix`() {
        assertEquals("- [[hello world]]", AnnotationTextProcessor.wrapText(AnnotationMode.WikiLink, "- hello world"))
    }

    @Test
    fun `wrapText header1 prepends single hash`() {
        assertEquals("# hello", AnnotationTextProcessor.wrapText(AnnotationMode.Header1, "hello"))
    }

    @Test
    fun `wrapText header2 prepends double hash`() {
        assertEquals("## hello world", AnnotationTextProcessor.wrapText(AnnotationMode.Header2, "hello world"))
    }

    @Test
    fun `wrapText header3 prepends triple hash`() {
        assertEquals("### hello", AnnotationTextProcessor.wrapText(AnnotationMode.Header3, "hello"))
    }

    @Test
    fun `wrapText header4 prepends quadruple hash`() {
        assertEquals("#### hello", AnnotationTextProcessor.wrapText(AnnotationMode.Header4, "hello"))
    }

    @Test
    fun `wrapText header preserves leading whitespace`() {
        assertEquals(" # hello", AnnotationTextProcessor.wrapText(AnnotationMode.Header1, " hello"))
    }

    @Test
    fun `wrapText none returns text unchanged`() {
        assertEquals("hello", AnnotationTextProcessor.wrapText(AnnotationMode.None, "hello"))
    }

    // --- diffWords ---

    @Test
    fun `diffWords single insertion at end`() {
        val segments = AnnotationTextProcessor.diffWords("writing and", "writing and tags")
        assertEquals(listOf("tags"), segments)
    }

    @Test
    fun `diffWords single insertion at start`() {
        val segments = AnnotationTextProcessor.diffWords("and tags", "writing and tags")
        assertEquals(listOf("writing"), segments)
    }

    @Test
    fun `diffWords single insertion in middle`() {
        val segments = AnnotationTextProcessor.diffWords("writing tags", "writing and tags")
        assertEquals(listOf("and"), segments)
    }

    @Test
    fun `diffWords two separate insertions`() {
        val segments = AnnotationTextProcessor.diffWords("and", "writing and tags")
        assertEquals(listOf("writing", "tags"), segments)
    }

    @Test
    fun `diffWords multi-word insertion`() {
        val segments = AnnotationTextProcessor.diffWords("writing it", "writing a new note it")
        assertEquals(listOf("a new note"), segments)
    }

    @Test
    fun `diffWords two multi-word insertions`() {
        val segments = AnnotationTextProcessor.diffWords(
            "writing a and it but a new should",
            "writing a new note and tagging it but a new line should not be tagged"
        )
        assertEquals(listOf("new note", "tagging", "line", "not be tagged"), segments)
    }

    @Test
    fun `diffWords case insensitive matching`() {
        val segments = AnnotationTextProcessor.diffWords("Writing And", "Writing new And")
        assertEquals(listOf("new"), segments)
    }

    @Test
    fun `diffWords identical texts returns empty`() {
        val segments = AnnotationTextProcessor.diffWords("hello world", "hello world")
        assertEquals(emptyList<String>(), segments)
    }

    @Test
    fun `diffWords empty base returns all words as one segment`() {
        val segments = AnnotationTextProcessor.diffWords("", "hello world")
        assertEquals(listOf("hello world"), segments)
    }

    @Test
    fun `diffWords empty full returns empty`() {
        val segments = AnnotationTextProcessor.diffWords("hello world", "")
        assertEquals(emptyList<String>(), segments)
    }

    // --- findBestMatch ---

    @Test
    fun `findBestMatch exact match`() {
        assertEquals("new note", AnnotationTextProcessor.findBestMatch("new note", "writing a new note here"))
    }

    @Test
    fun `findBestMatch falls back to longest word`() {
        assertEquals("tagging", AnnotationTextProcessor.findBestMatch("tagging stuff", "writing and tagging it"))
    }

    @Test
    fun `findBestMatch exact match even single char`() {
        // Exact substring match takes priority over word-level fallback
        assertEquals("a", AnnotationTextProcessor.findBestMatch("a", "writing a note"))
    }

    @Test
    fun `findBestMatch word fallback skips short words`() {
        // "x z" is not in fullText, and individual words "x","z" are too short for fallback
        assertNull(AnnotationTextProcessor.findBestMatch("x z", "writing a note"))
    }

    @Test
    fun `findBestMatch returns null when no match`() {
        assertNull(AnnotationTextProcessor.findBestMatch("xyz", "writing a note"))
    }

    // --- applyAnnotations ---

    @Test
    fun `applyAnnotations single wikilink`() {
        val result = AnnotationTextProcessor.applyAnnotations(
            "writing and tags",
            listOf("writing"),
            listOf(AnnotationMode.WikiLink)
        )
        assertEquals("[[writing]] and tags", result)
    }

    @Test
    fun `applyAnnotations single tag`() {
        val result = AnnotationTextProcessor.applyAnnotations(
            "writing and tags",
            listOf("tags"),
            listOf(AnnotationMode.Tag)
        )
        assertEquals("writing and #tags", result)
    }

    @Test
    fun `applyAnnotations wikilink and tag`() {
        val result = AnnotationTextProcessor.applyAnnotations(
            "writing and tags",
            listOf("writing", "tags"),
            listOf(AnnotationMode.WikiLink, AnnotationMode.Tag)
        )
        assertEquals("[[writing]] and #tags", result)
    }

    @Test
    fun `applyAnnotations multiline with four annotations`() {
        val fullText = "writing a new note and tagging it but a new line should not be tagged"
        val segments = listOf("new note", "tagging", "line", "not be tagged")
        val modes = listOf(AnnotationMode.WikiLink, AnnotationMode.Tag, AnnotationMode.WikiLink, AnnotationMode.Tag)
        val result = AnnotationTextProcessor.applyAnnotations(fullText, segments, modes)
        assertEquals("writing a [[new note]] and #tagging it but a new [[line]] should #notbetagged", result)
    }

    @Test
    fun `applyAnnotations strips trailing punctuation`() {
        val result = AnnotationTextProcessor.applyAnnotations(
            "see notes, and tags.",
            listOf("notes,", "tags."),
            listOf(AnnotationMode.WikiLink, AnnotationMode.Tag)
        )
        assertEquals("see [[notes]], and #tags.", result)
    }

    @Test
    fun `applyAnnotations with empty segment skips it`() {
        val result = AnnotationTextProcessor.applyAnnotations(
            "hello world",
            listOf("", "world"),
            listOf(AnnotationMode.WikiLink, AnnotationMode.Tag)
        )
        assertEquals("hello #world", result)
    }
}
