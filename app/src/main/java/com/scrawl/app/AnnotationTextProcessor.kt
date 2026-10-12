package com.scrawl.app

/**
 * Pure text-processing functions for annotation wrapping.
 * Extracted from ScrawlIMEService for testability.
 */
object AnnotationTextProcessor {

    fun wrapText(mode: AnnotationMode, text: String): String {
        if (mode == AnnotationMode.None) return text
        val leading = text.takeWhile { !it.isLetter() }
        val body = text.removePrefix(leading)
        return when (mode) {
            AnnotationMode.WikiLink -> "${leading}[[${body}]]"
            AnnotationMode.Tag -> "${leading}#${body.replace(" ", "")}"
            AnnotationMode.Header1 -> "${leading}# ${body}"
            AnnotationMode.Header2 -> "${leading}## ${body}"
            AnnotationMode.Header3 -> "${leading}### ${body}"
            AnnotationMode.Header4 -> "${leading}#### ${body}"
            AnnotationMode.Screenshot -> text
            AnnotationMode.None -> text
        }
    }

    fun findBestMatch(annotText: String, fullText: String): String? {
        if (fullText.contains(annotText)) return annotText
        val words = annotText.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (word in words.sortedByDescending { it.length }) {
            if (word.length >= 2 && fullText.contains(word)) return word
        }
        return null
    }

    /**
     * Word-level LCS diff: finds contiguous word segments in [fullText]
     * that are absent from [baseText]. Returns segments in text order.
     */
    fun diffWords(baseText: String, fullText: String): List<String> {
        val baseWords = baseText.split(Regex("\\s+")).filter { it.isNotBlank() }
        val fullWords = fullText.split(Regex("\\s+")).filter { it.isNotBlank() }

        val m = baseWords.size
        val n = fullWords.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (baseWords[i - 1].equals(fullWords[j - 1], ignoreCase = true)) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        val matched = BooleanArray(n)
        var i = m; var j = n
        while (i > 0 && j > 0) {
            if (baseWords[i - 1].equals(fullWords[j - 1], ignoreCase = true)) {
                matched[j - 1] = true; i--; j--
            } else if (dp[i - 1][j] > dp[i][j - 1]) {
                i--
            } else {
                j--
            }
        }

        val segments = mutableListOf<String>()
        var current = mutableListOf<String>()
        for (k in fullWords.indices) {
            if (!matched[k]) {
                current.add(fullWords[k])
            } else if (current.isNotEmpty()) {
                segments.add(current.joinToString(" "))
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) segments.add(current.joinToString(" "))
        return segments
    }

    /**
     * Given fullText, a list of diff segments, and their annotation modes,
     * apply wrapping to each segment in the full text.
     * Strips trailing punctuation so it stays outside the markup.
     */
    fun applyAnnotations(
        fullText: String,
        segments: List<String>,
        modes: List<AnnotationMode>
    ): String {
        var result = fullText
        for ((i, annotText) in segments.withIndex()) {
            if (annotText.isBlank() || i >= modes.size) continue
            val cleaned = annotText.trimEnd('.', ',', ';', ':', '!', '?')
            val trailing = annotText.removePrefix(cleaned)
            val wrapped = wrapText(modes[i], cleaned) + trailing
            result = result.replaceFirst(annotText, wrapped)
        }
        return result
    }
}
