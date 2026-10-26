package app.memo.vault

import app.memo.core.codec.SynCodec
import app.memo.core.registry.Workspace

/** Durable workspace snapshot codec boundary (SYN envelope, strict parse). */
object MemoVault {
    fun save(workspace: Workspace, out: java.io.OutputStream) {
        val json = SynCodec.serialize(workspace, System.currentTimeMillis())
        out.write(json.toByteArray(Charsets.UTF_8))
    }

    fun load(input: java.io.InputStream): Workspace? {
        return when (val result = SynCodec.parse(String(input.readBytes(), Charsets.UTF_8))) {
            is SynCodec.ParseOutcome.Ok -> result.workspace
            is SynCodec.ParseOutcome.Rejected -> null
        }
    }
}
