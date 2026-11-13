package com.opensource.svgaplayer.utils

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/** Construct JSON incrementally: reject excessive containers/values before allocating the tree. */
internal object SVGAJsonDecoder {
    fun decode(input: InputStream): JSONObject {
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        SVGADecodeBudget.enter()
        try {
            val result = readValue(reader) as? JSONObject ?: throw IOException("SVGA JSON must be an object")
            if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing SVGA JSON data")
            return result
        } finally {
            // The caller owns and closes the input stream.
            SVGADecodeBudget.exit()
        }
    }

    private fun readValue(reader: JsonReader): Any {
        SVGADecodeBudget.value()
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> container {
                JSONObject().apply {
                    reader.beginObject()
                    while (reader.hasNext()) put(reader.nextName(), readValue(reader))
                    reader.endObject()
                }
            }
            JsonToken.BEGIN_ARRAY -> container {
                JSONArray().apply {
                    reader.beginArray()
                    while (reader.hasNext()) put(readValue(reader))
                    reader.endArray()
                }
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: it.toDouble() }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            else -> throw IOException("Invalid SVGA JSON value")
        }
    }

    /** Also protect callers that supply an already-built JSON tree to SVGAVideoEntity. */
    fun validate(value: Any?) {
        SVGADecodeBudget.enter()
        try {
            visit(value)
        } finally {
            SVGADecodeBudget.exit()
        }
    }

    private fun visit(value: Any?) {
        SVGADecodeBudget.value()
        when (value) {
            is JSONObject -> container { value.keys().forEach { visit(value.opt(it)) } }
            is JSONArray -> container { for (index in 0 until value.length()) visit(value.opt(index)) }
        }
    }

    private inline fun <T> container(block: () -> T): T {
        SVGADecodeBudget.enter()
        try { return block() } finally { SVGADecodeBudget.exit() }
    }
}
